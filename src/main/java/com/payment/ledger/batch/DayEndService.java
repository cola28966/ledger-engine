package com.payment.ledger.batch;

import com.payment.ledger.batch.DayEndResult.CheckResult;
import com.payment.ledger.domain.*;
import com.payment.ledger.dto.DailyMovement;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        // ══════════════════════════════════════════════════════════════
        //  TODO 9 —— 由你实现（验收：DayEndServiceTest.trialBalanceFailureBlocksDayEnd）
        //
        //  a) 用 entryRepo.sumByDirection(date, Direction.DR / CR) 取当日
        //     全部分录的借方合计和贷方合计
        //  b) 相等 → CheckResult.pass(CHK_TRIAL_BALANCE, 描述)
        //  c) 不等 → CheckResult.fail(...)，且 detail 里必须包含：
        //       · 借方合计、贷方合计、差额（测试断言 "差额 500"）
        //       · 用 entryRepo.findUnbalancedVouchers(date) 把组内不平的
        //         凭证号一并列出（测试断言 detail 中出现 "V_BROKEN"）
        //
        //  ── 为什么要顺手把问题凭证捞出来 ─────────────────────
        //   告警只说"借贷差了 500"，值班的人还得自己写 SQL 去找。
        //   直接把凭证号打出来，排查从 30 分钟变成 30 秒。
        //   如果 findUnbalancedVouchers 返回空，说明每张凭证组内都是平的、
        //   但总数不平 —— 那是分录整行丢失，要往另一个方向查。
        //
        //  跑测试：mvn test -Dtest=DayEndServiceTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 9: 实现试算平衡校验");
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
        // ══════════════════════════════════════════════════════════════
        //  TODO 10 —— 由你实现（验收：DayEndServiceTest 的两个备付金用例）
        //
        //  这就是你在第 14 题第 3 问写对的那条等式，现在变成代码。
        //
        //  可用的查询：
        //    accountRepo.sumBalanceBySubjectPrefix("2241")
        //        → 客户备付金类负债合计（科目 2241 开头）
        //    accountRepo.findByNo(EntryGenerator.BANK_RESERVE).getBalance()
        //        → 备付金存管账户余额
        //    accountRepo.sumBalanceBySubjectType(SubjectType.INCOME.name())
        //        → 已确认、尚未划转的自有收入
        //
        //  要校验的等式（想清楚为什么要减那一项）：
        //      SUM(客户备付金类负债) == 存管户余额 - 未划转的自有收入
        //
        //  detail 里请带上三个数值：
        //    通过时 → 测试断言包含 "未划转收入 180"
        //    失败时 → 测试断言包含 "备付金勾稽失衡" 和 "差额 5000"
        //
        //  ── 为什么要减去收入 ──────────────────────────────────
        //   平台每收一笔手续费，就把钱从"客户备付金"（负债）转成了
        //   "手续费收入"（权益），但那笔钱还实实在在躺在备付金存管
        //   账户里。客户备付金侧少了，银行侧没少，等式就失衡了。
        //   实务中靠定期「手续费划转」把自有收入划到自有资金账户来恢复平衡：
        //       借：银行存款-自有资金账户   XX
        //           贷：银行存款-备付金存管户    XX
        //
        //  ── 这条等式的分量 ────────────────────────────────────
        //   《非银行支付机构监督管理条例》：客户备付金不得挪用。
        //   这不是一句口号，而是每天可计算、可校验、可告警的一条等式。
        //   左右差一分钱，就必须查出来。
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 10: 实现备付金勾稽校验");
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
