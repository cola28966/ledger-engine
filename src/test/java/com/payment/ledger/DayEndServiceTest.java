package com.payment.ledger;

import com.payment.ledger.batch.DayEndResult;
import com.payment.ledger.batch.DayEndService;
import com.payment.ledger.domain.BalanceSnapshot;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.CalendarRepository;
import com.payment.ledger.repository.SnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.payment.ledger.batch.DayEndService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日终结账 —— 账务系统的每日体检。
 *
 * <p>核心约束：<b>任何一项勾稽不通过，必须阻断日切</b>。
 * 绝不"先跑着明天再说"——带病日切会让错误持续放大。
 */
@SpringBootTest
class DayEndServiceTest {

    @Autowired DayEndService dayEnd;
    @Autowired AccountingEngine engine;
    @Autowired SnapshotRepository snapshotRepo;
    @Autowired CalendarRepository calendarRepo;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;   // 2026-08-22

    @BeforeEach
    void reset() {
        LedgerTestSupport.resetAll(jdbc);
    }

    private void book(String reqId, BizType type, String payer, String payee, long amount, long fee) {
        engine.book(BookingRequest.builder()
                .requestId(reqId).bizType(type).bizOrderNo("ORDER")
                .accountingDate(D)
                .payerAccount(payer).payeeAccount(payee)
                .amount(amount).fee(fee).build());
    }

    /** 造一批正常业务 */
    private void normalBusiness() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        book("C1", BizType.CONSUME,  "U0001", "M0001",  30000, 180);
        book("T1", BizType.TRANSFER, "U0001", "U0002",  10000, 0);
    }

    // ================================================================
    //  正常日切
    // ================================================================

    @Test
    @DisplayName("五项勾稽全部通过 → 日切成功，会计日推进到次日")
    void successfulDayEnd() {
        normalBusiness();

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.checks()).hasSize(5);

        // 当日关账，次日开放
        assertThat(calendarRepo.findStatus(D).name()).isEqualTo("CLOSED");
        assertThat(calendarRepo.findCurrentOpenDate()).isEqualTo(D.plusDays(1));
    }

    @Test
    @DisplayName("日切后禁止再往该会计日记账")
    void cannotBookIntoClosedDate() {
        normalBusiness();
        dayEnd.run(D);

        assertThatThrownBy(() -> book("R_AFTER", BizType.RECHARGE, null, "U0001", 5000, 0))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("已关账");
    }

    @Test
    @DisplayName("空账日也能正常日切（当日无任何交易）")
    void emptyDayCanStillCut() {
        DayEndResult result = dayEnd.run(D);
        assertThat(result.success()).isTrue();
    }

    // ================================================================
    //  日终快照
    // ================================================================

    @Test
    @DisplayName("快照勾稽：期末 = 期初 + 本期发生额，且等于账户当前余额")
    void snapshotArithmetic() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        book("C1", BizType.CONSUME,  "U0001", "M0001",  30000, 180);

        dayEnd.run(D);

        // 用户户（负债，贷方科目）：期初 0 + 贷方 100000 - 借方 30000 = 70000
        BalanceSnapshot u = snapshotRepo.find(D, "U0001");
        assertThat(u.getOpeningBalance()).isZero();
        assertThat(u.getCreditAmount()).isEqualTo(100000);
        assertThat(u.getDebitAmount()).isEqualTo(30000);
        assertThat(u.getClosingBalance()).isEqualTo(70000);

        // 备付金存管户（资产，借方科目）：期初 0 + 借方 100000 - 贷方 0 = 100000
        BalanceSnapshot bank = snapshotRepo.find(D, EntryGenerator.BANK_RESERVE);
        assertThat(bank.getDebitAmount()).isEqualTo(100000);
        assertThat(bank.getClosingBalance()).isEqualTo(100000);

        // 手续费收入户
        assertThat(snapshotRepo.find(D, EntryGenerator.FEE_INCOME).getClosingBalance())
                .isEqualTo(180);
    }

    @Test
    @DisplayName("跨日快照：次日期初余额 = 前一日期末余额")
    void snapshotCarriesOverToNextDay() {
        book("R1", BizType.RECHARGE, null, "U0001", 100000, 0);
        dayEnd.run(D);

        // 次日再发生一笔
        LocalDate d2 = D.plusDays(1);
        engine.book(BookingRequest.builder()
                .requestId("C_D2").bizType(BizType.CONSUME).bizOrderNo("ORDER_D2")
                .accountingDate(d2)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(30000).fee(180).build());
        dayEnd.run(d2);

        BalanceSnapshot day2 = snapshotRepo.find(d2, "U0001");
        assertThat(day2.getOpeningBalance()).isEqualTo(100000);   // 承接前一日期末
        assertThat(day2.getDebitAmount()).isEqualTo(30000);
        assertThat(day2.getClosingBalance()).isEqualTo(70000);
    }

    // ================================================================
    //  勾稽失败必须阻断日切
    // ================================================================

    @Test
    @DisplayName("★ 试算平衡不通过 → 阻断日切，会计日停在原地")
    void trialBalanceFailureBlocksDayEnd() {
        normalBusiness();

        // 手工塞一条孤立的借方分录，制造借贷不平（模拟记账崩在中间/数据被脏改）
        jdbc.update("""
                INSERT INTO accounting_entry
                    (voucher_no, entry_seq, account_no, subject_code,
                     direction, amount, accounting_date, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, "V_BROKEN", 1, "U0001", "224101", "DR", 500,
                Date.valueOf(D), LocalDateTime.now());

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isFalse();
        assertThat(result.check(CHK_TRIAL_BALANCE).passed()).isFalse();
        assertThat(result.check(CHK_TRIAL_BALANCE).detail())
                .contains("差额 500")
                .contains("V_BROKEN");        // 直接把问题凭证捞出来了

        // 日切被阻断：会计日仍是 D，没有关账
        assertThat(calendarRepo.findStatus(D).name()).isEqualTo("OPEN");
        assertThat(calendarRepo.findCurrentOpenDate()).isEqualTo(D);
        // 快照也没有生成
        assertThat(snapshotRepo.findByDate(D)).isEmpty();
    }

    @Test
    @DisplayName("试算不平时，必须报出真实的问题凭证号和借贷合计")
    void trialBalanceReportsActualVoucherNo() {
        normalBusiness();

        // 换一个凭证号，模拟生产环境里真实的凭证编号
        jdbc.update("""
                INSERT INTO accounting_entry
                    (voucher_no, entry_seq, account_no, subject_code,
                     direction, amount, accounting_date, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, "V20260822000000009527", 1, "U0001", "224101", "DR", 700,
                Date.valueOf(D), LocalDateTime.now());

        DayEndResult result = dayEnd.run(D);
        String detail = result.check(CHK_TRIAL_BALANCE).detail();

        assertThat(result.success()).isFalse();
        // 报出的必须是查出来的那个凭证号
        assertThat(detail).contains("V20260822000000009527");
        assertThat(detail).contains("700");
        // 借贷双方合计都要给出，值班的人才能判断偏在哪一侧
        assertThat(detail).contains(String.valueOf(140700));   // 借方合计
        assertThat(detail).contains(String.valueOf(140000));   // 贷方合计
    }

    @Test
    @DisplayName("★ 存在记账中的凭证 → 阻断日切（试算不平最高频的原因）")
    void processingVoucherBlocksDayEnd() {
        normalBusiness();

        jdbc.update("""
                INSERT INTO voucher
                    (voucher_no, request_id, biz_type, biz_order_no, accounting_date,
                     total_amount, status, reverse_of, reversed_by, remark, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "V_HANGING", "REQ_HANGING", "CONSUME", "ORDER_X",
                Date.valueOf(D), 10000, "PROCESSING", null, null, null, LocalDateTime.now());

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isFalse();
        assertThat(result.check(CHK_NO_PROCESSING).passed()).isFalse();
        assertThat(result.check(CHK_NO_PROCESSING).detail()).contains("1 笔");
        assertThat(calendarRepo.findStatus(D).name()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("★ 账户余额被脏改 → 账账核对抓出来，阻断日切")
    void dirtyBalanceBlocksDayEnd() {
        normalBusiness();

        // 绕过记账引擎直接改余额 —— 现实中可能是人工 SQL、也可能是并发 bug
        jdbc.update("UPDATE account SET balance = balance + 999 WHERE account_no = 'U0001'");

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isFalse();
        assertThat(result.check(CHK_BALANCE_VS_SERIAL).passed()).isFalse();
        assertThat(result.check(CHK_BALANCE_VS_SERIAL).detail()).contains("U0001");

        // 注意：这种脏改试算平衡是查不出来的 —— 分录没动，借贷仍然是平的
        assertThat(result.check(CHK_TRIAL_BALANCE).passed()).isTrue();
    }

    @Test
    @DisplayName("★ balance ≠ available + frozen → 账户内部一致性抓出来")
    void inconsistentAccountBlocksDayEnd() {
        normalBusiness();
        jdbc.update("UPDATE account SET frozen_balance = 100 WHERE account_no = 'U0001'");

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isFalse();
        assertThat(result.check(CHK_ACCOUNT_CONSISTENCY).passed()).isFalse();
        assertThat(result.check(CHK_ACCOUNT_CONSISTENCY).detail()).contains("U0001");
    }

    // ================================================================
    //  备付金勾稽
    // ================================================================

    @Test
    @DisplayName("备付金勾稽：收了手续费也能过 —— 等式已计入未划转的自有收入")
    void reserveInvariantAccountsForFeeIncome() {
        normalBusiness();   // 含 180 分手续费

        DayEndResult result = dayEnd.run(D);

        assertThat(result.check(CHK_RESERVE_INVARIANT).passed()).isTrue();
        assertThat(result.check(CHK_RESERVE_INVARIANT).detail())
                .contains("未划转收入 180");
    }

    @Test
    @DisplayName("★ 备付金被挪用 → 勾稽失衡，阻断日切")
    void reserveMisappropriationBlocksDayEnd() {
        normalBusiness();

        // 模拟：备付金存管户的钱少了 5000，但客户备付金负债没变
        // 现实对应：拿客户的钱去垫付、或对外付款没记账
        jdbc.update("UPDATE account SET balance = balance - 5000, "
                + "available_balance = available_balance - 5000 WHERE account_no = ?",
                EntryGenerator.BANK_RESERVE);

        DayEndResult result = dayEnd.run(D);

        assertThat(result.success()).isFalse();
        assertThat(result.check(CHK_RESERVE_INVARIANT).passed()).isFalse();
        assertThat(result.check(CHK_RESERVE_INVARIANT).detail())
                .contains("备付金勾稽失衡")
                .contains("差额 5000");
    }
}
