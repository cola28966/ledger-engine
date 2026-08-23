package com.payment.ledger.batch;

import com.payment.ledger.batch.DayEndResult.CheckResult;
import com.payment.ledger.domain.*;
import com.payment.ledger.dto.DailyMovement;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日终结账。
 *
 * <p>流程：
 * <pre>
 *   置日切中 → 检查在途凭证 → 四项勾稽校验 → 生成日终快照 → 关账 → 开新会计日
 * </pre>
 *
 * <p><b>任何一项校验不通过，必须阻断日切并告警。</b>
 * 绝不"先跑着明天再说"——带病日切会让错误持续放大，第二天更难定位。
 */
@Slf4j
@Service
public class DayEndService {

    // 各项检查的名称，测试和告警按名称引用
    public static final String CHK_NO_PROCESSING = "在途凭证清零";
    public static final String CHK_TRIAL_BALANCE = "试算平衡";
    public static final String CHK_ACCOUNT_CONSISTENCY = "账户内部一致";
    public static final String CHK_BALANCE_VS_SERIAL = "余额与流水核对";
    public static final String CHK_RESERVE_INVARIANT = "备付金勾稽";

    private final AccountRepository accountRepo;
    private final VoucherRepository voucherRepo;
    private final EntryRepository entryRepo;
    private final SerialRepository serialRepo;
    private final SnapshotRepository snapshotRepo;
    private final CalendarRepository calendarRepo;

    public DayEndService(AccountRepository accountRepo, VoucherRepository voucherRepo,
                         EntryRepository entryRepo, SerialRepository serialRepo,
                         SnapshotRepository snapshotRepo, CalendarRepository calendarRepo) {
        this.accountRepo = accountRepo;
        this.voucherRepo = voucherRepo;
        this.entryRepo = entryRepo;
        this.serialRepo = serialRepo;
        this.snapshotRepo = snapshotRepo;
        this.calendarRepo = calendarRepo;
    }

    // ================================================================
    //  主流程
    // ================================================================

    public DayEndResult run(LocalDate date) {
        log.info("=== 开始日终结账: {} ===", date);

        // 进入日切状态，暂停新的记账落到本会计日
        calendarRepo.updateStatus(date, CalendarStatus.OPEN, CalendarStatus.CUTTING);

        List<CheckResult> checks = new ArrayList<>();
        checks.add(checkNoProcessingVouchers(date));
        checks.add(checkTrialBalance(date));
        checks.add(checkAccountConsistency());
        checks.add(checkBalanceVsSerial());
        checks.add(checkReserveInvariant());

        boolean success = checks.stream().allMatch(CheckResult::passed);

        if (success) {
            generateSnapshots(date);
            calendarRepo.updateStatus(date, CalendarStatus.CUTTING, CalendarStatus.CLOSED);
            calendarRepo.ensureExists(date.plusDays(1), CalendarStatus.OPEN);
            log.info("=== 日终结账完成，会计日推进到 {} ===", date.plusDays(1));
        } else {
            // 阻断日切，回到 OPEN 状态等待人工处理
            calendarRepo.updateStatus(date, CalendarStatus.CUTTING, CalendarStatus.OPEN);
            log.error("=== 日终结账失败，已阻断日切。失败项: {} ===",
                    checks.stream().filter(c -> !c.passed()).map(CheckResult::name).toList());
        }
        return new DayEndResult(date, success, checks);
    }

    // ================================================================
    //  五项校验
    // ================================================================

    /**
     * ① 在途凭证清零。
     * <p>状态为 PROCESSING 的凭证是"记账崩在中间"的残留——
     * 只写了一半的分录会直接导致试算不平。日切前必须为 0。
     */
    CheckResult checkNoProcessingVouchers(LocalDate date) {
        int count = voucherRepo.countProcessing(date);
        return count == 0
                ? CheckResult.pass(CHK_NO_PROCESSING, "在途凭证 0 笔")
                : CheckResult.fail(CHK_NO_PROCESSING, "存在 " + count + " 笔记账中的凭证，需先处理");
    }

    /**
     * ② 试算平衡：当日全部分录的借方合计必须等于贷方合计。
     */
    CheckResult checkTrialBalance(LocalDate date) {
        long debit = entryRepo.sumByDirection(date, Direction.DR);
        long credit = entryRepo.sumByDirection(date, Direction.CR);
        if(debit == credit) {
            return CheckResult.pass(CHK_TRIAL_BALANCE, "当日全部分录的借方合计等于贷方合计，双方合计 " + debit);
        }else {
            List<String> unbalancedVouchers = entryRepo.findUnbalancedVouchers(date);
            return CheckResult.fail(CHK_TRIAL_BALANCE, String.format(
                    "借方 %d != 贷方 %d，差额 %d；组内不平的凭证: %s",
                    debit, credit, debit - credit,
                    unbalancedVouchers.isEmpty() ? "无（疑为分录整行丢失）" : unbalancedVouchers));
        }
    }

    /**
     * ③ 账户内部一致：balance == available + frozen。
     */
    CheckResult checkAccountConsistency() {
        List<String> bad = accountRepo.findInconsistentAccounts();
        return bad.isEmpty()
                ? CheckResult.pass(CHK_ACCOUNT_CONSISTENCY, "全部账户满足 balance = available + frozen")
                : CheckResult.fail(CHK_ACCOUNT_CONSISTENCY, "不一致账户: " + bad);
    }

    /**
     * ④ 账账核对：每个账户的余额必须等于其流水的累计推算值。
     * <p>能抓出"余额被脏改"和"流水漏记/重记"——这类问题试算平衡查不出来。
     */
    CheckResult checkBalanceVsSerial() {
        List<String> bad = new ArrayList<>();
        for (Account acc : accountRepo.findAll()) {
            long fromSerial = serialRepo.calcBalanceBySerial(
                    acc.getAccountNo(), acc.getBalanceDirection());
            if (acc.getBalance() != fromSerial) {
                bad.add(String.format("%s(余额=%d, 流水推算=%d)",
                        acc.getAccountNo(), acc.getBalance(), fromSerial));
            }
        }
        return bad.isEmpty()
                ? CheckResult.pass(CHK_BALANCE_VS_SERIAL, "余额与流水累计一致")
                : CheckResult.fail(CHK_BALANCE_VS_SERIAL, "不一致: " + bad);
    }

    /**
     * ⑤ 备付金勾稽（监管红线）。
     *
     * <pre>
     *   SUM(客户备付金类负债)  ==  备付金存管账户余额 - 已确认未划转的自有收入
     * </pre>
     *
     * <p>为什么要减去收入：平台每收一笔手续费，就把钱从"客户备付金"（负债）
     * 转成了"手续费收入"（权益），但那笔钱还实实在在躺在备付金存管账户里。
     * 客户备付金侧少了，银行侧没少，等式就失衡了。
     * 实务中靠定期"手续费划转"把自有收入划到自有资金账户来恢复平衡。
     */
    CheckResult checkReserveInvariant() {
        long totalCustomerReserveFundsBalance = accountRepo.sumBalanceBySubjectPrefix("2241");
        long bankReserveBalance = accountRepo.findByNo(EntryGenerator.BANK_RESERVE).getBalance();
        long subjectTyBalance = accountRepo.sumBalanceBySubjectType(SubjectType.INCOME.name());

        if(totalCustomerReserveFundsBalance == bankReserveBalance - subjectTyBalance) {
            return CheckResult.pass(CHK_RESERVE_INVARIANT, String.format(
                    "客户备付金 %d = 存管户 %d - 未划转收入 %d",
                    totalCustomerReserveFundsBalance, bankReserveBalance, subjectTyBalance));
        }else {
            return CheckResult.fail(CHK_RESERVE_INVARIANT, String.format(
                    "客户备付金 %d = 存管户 %d - 未划转收入 %d 备付金勾稽失衡 差额 %d",
                    totalCustomerReserveFundsBalance, bankReserveBalance, subjectTyBalance, totalCustomerReserveFundsBalance -bankReserveBalance + subjectTyBalance));

        }
    }

    // ================================================================
    //  日终快照
    // ================================================================

    /**
     * 生成日终余额快照。
     *
     * <pre>
     *   借方科目：期末 = 期初 + 本期借方 - 本期贷方
     *   贷方科目：期末 = 期初 + 本期贷方 - 本期借方
     * </pre>
     *
     * <p>算出来的期末余额必须等于账户当前余额——这本身就是一道勾稽。
     */
    void generateSnapshots(LocalDate date) {
        Map<String, DailyMovement> movements = new HashMap<>();
        for (DailyMovement m : entryRepo.aggregateByAccount(date)) {
            movements.put(m.accountNo(), m);
        }

        for (Account acc : accountRepo.findAll()) {
            String no = acc.getAccountNo();
            long opening = snapshotRepo.findLatestClosingBefore(date, no);
            DailyMovement m = movements.getOrDefault(no, new DailyMovement(no, 0L, 0L));

            long closing = acc.getBalanceDirection() == Direction.DR
                    ? opening + m.debitAmount() - m.creditAmount()
                    : opening + m.creditAmount() - m.debitAmount();

            if (closing != acc.getBalance()) {
                throw new IllegalStateException(String.format(
                        "账户 %s 快照勾稽失败：期初 %d + 发生额 = %d，但账户实际余额为 %d",
                        no, opening, closing, acc.getBalance()));
            }

            snapshotRepo.insert(new BalanceSnapshot(
                    date, no, opening, m.debitAmount(), m.creditAmount(), closing));
        }
        log.info("日终快照已生成: {} 个账户", accountRepo.findAll().size());
    }
}
