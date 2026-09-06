package com.payment.ledger;

import com.payment.ledger.batch.DayEndResult.CheckResult;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.ChannelBalance;
import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.domain.ChannelTradeStatus;
import com.payment.ledger.engine.HotAccountRouter;
import com.payment.ledger.recon.ChannelBalanceChecker;
import com.payment.ledger.repository.ChannelBalanceRepository;
import com.payment.ledger.repository.ChannelStatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道余额连续性。
 *
 * <p>逐笔对账只能证明「我看到的这些是对的」，证明不了「我该看到的都看到了」。
 * 这个测试类里最该看的是 {@code missingDayWithZeroNetChangeIsStillCaught}：
 * 整天数据缺失、而当天净变动恰好为 0 时，余额链完美接得上——
 * <b>余额对得上，不代表没缺天。</b>
 */
@SpringBootTest
class ChannelBalanceCheckerTest {

    @Autowired ChannelBalanceChecker checker;
    @Autowired ChannelBalanceRepository balanceRepo;
    @Autowired ChannelStatementRepository statementRepo;
    @Autowired HotAccountRouter router;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;      // 2026-08-22
    static final String CH = "UNIONPAY";

    @BeforeEach
    void reset() {
        LedgerTestSupport.resetAll(jdbc, router);
    }

    // ---------------- 造数 ----------------

    private void balance(LocalDate date, long opening, long income, long expense, long closing) {
        balanceRepo.insert(ChannelBalance.builder()
                .channelCode(CH).balanceDate(date)
                .openingBalance(opening).incomeAmount(income)
                .expenseAmount(expense).closingBalance(closing)
                .build());
    }

    private void stmt(LocalDate date, String tradeNo, BizType type, long amount, Long balanceAfter) {
        stmt(date, tradeNo, type, amount, balanceAfter, ChannelTradeStatus.SUCCESS, LocalDateTime.now());
    }

    private void stmt(LocalDate date, String tradeNo, BizType type, long amount, Long balanceAfter,
                      ChannelTradeStatus status, LocalDateTime tradeTime) {
        statementRepo.insert(ChannelStatement.builder()
                .channelCode(CH).channelTradeNo(tradeNo).bizOrderNo("ORD_" + tradeNo)
                .bizType(type).amount(amount).fee(0)
                .tradeStatus(status).statementDate(date)
                .ourAccountNo("U0001").balanceAfter(balanceAfter)
                .tradeTime(tradeTime).build());
    }

    /** 标准的一天：期初 100000，充值 30000 + 20000，提现 50000，期末 100000 */
    private void normalDay(LocalDate date) {
        balance(date, 100000, 50000, 50000, 100000);
        stmt(date, "T1_" + date, BizType.RECHARGE, 30000, 130000L);
        stmt(date, "T2_" + date, BizType.RECHARGE, 20000, 150000L);
        stmt(date, "T3_" + date, BizType.WITHDRAW_SUCCESS, 50000, 100000L);
    }

    // ================================================================
    //  TODO 18 · 单日余额自洽
    // ================================================================

    @Test
    @DisplayName("余额等式成立且明细对得上 → 通过")
    void dailyBalanceHolds() {
        normalDay(D);
        CheckResult r = checker.checkDailyBalance(CH, D);
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    @Test
    @DisplayName("★ 期初 + 收入 − 支出 != 期末 → 失败")
    void dailyBalanceEquationBroken() {
        balance(D, 100000, 50000, 50000, 99999);      // 期末少 1 分
        stmt(D, "T1", BizType.RECHARGE, 50000, 150000L);
        stmt(D, "T2", BizType.WITHDRAW_SUCCESS, 50000, 100000L);

        CheckResult r = checker.checkDailyBalance(CH, D);

        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("99999");
    }

    @Test
    @DisplayName("★★ 对账单被截断：余额等式照样成立，但明细累加对不上")
    void truncatedStatementIsCaught() {
        // 汇总数是渠道给的，不受文件截断影响 —— 余额等式怎么算都成立。
        // 只有把明细逐行累加起来比一遍，才能发现少了行。
        balance(D, 100000, 50000, 50000, 100000);
        stmt(D, "T1", BizType.RECHARGE, 30000, 130000L);
        // 少了 T2 的 20000 充值（模拟文件下载到一半断了）
        stmt(D, "T3", BizType.WITHDRAW_SUCCESS, 50000, 100000L);

        CheckResult r = checker.checkDailyBalance(CH, D);

        // 少了这一步，这 20000 会在逐笔对账里伪装成一笔渠道单边账，
        // 值班的人会去查记账服务，真实原因是网络抖了一下
        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("30000").contains("50000");
    }

    @Test
    @DisplayName("★ 当天压根没有余额记录 → 失败，不能当成没问题")
    void missingBalanceRecordFails() {
        stmt(D, "T1", BizType.RECHARGE, 30000, 130000L);

        CheckResult r = checker.checkDailyBalance(CH, D);

        // 「没有数据」和「数据没问题」是两回事
        assertThat(r.passed()).isFalse();
    }

    @Test
    @DisplayName("失败交易不计入明细累加")
    void failedTradesAreNotCounted() {
        balance(D, 100000, 30000, 0, 130000);
        stmt(D, "T1", BizType.RECHARGE, 30000, 130000L);
        stmt(D, "T_FAIL", BizType.RECHARGE, 99999, null,
                ChannelTradeStatus.FAIL, LocalDateTime.now());

        CheckResult r = checker.checkDailyBalance(CH, D);
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    // ================================================================
    //  TODO 19 · 逐笔余额连续
    // ================================================================

    @Test
    @DisplayName("逐笔余额链完整 → 通过")
    void rowContinuityHolds() {
        normalDay(D);
        CheckResult r = checker.checkRowContinuity(CH, D);
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    @Test
    @DisplayName("★ 中间一笔余额对不上 → 报出是第几笔、哪个流水号")
    void rowContinuityBreakIsLocated() {
        balance(D, 100000, 50000, 0, 150000);
        stmt(D, "T1", BizType.RECHARGE, 30000, 130000L);
        stmt(D, "T2", BizType.RECHARGE, 20000, 149000L);   // 应为 150000，少 1000

        CheckResult r = checker.checkRowContinuity(CH, D);

        assertThat(r.passed()).isFalse();
        assertThat(r.detail())
                .contains("T2")           // 定位到具体哪一笔
                .contains("149000");
    }

    @Test
    @DisplayName("★★ 断点要一次全报出来，不能撞到第一个就返回")
    void allBreaksAreReportedAtOnce() {
        balance(D, 0, 40000, 0, 40000);
        stmt(D, "T1", BizType.RECHARGE, 10000, 10000L);
        stmt(D, "T2", BizType.RECHARGE, 10000, 25000L);   // 断点 1
        stmt(D, "T3", BizType.RECHARGE, 10000, 40000L);   // 断点 2
        stmt(D, "T4", BizType.RECHARGE, 10000, 60000L);   // 断点 3

        CheckResult r = checker.checkRowContinuity(CH, D);

        assertThat(r.passed()).isFalse();
        // 断点数量本身就是诊断信息：断 1 处通常是跨日归集，
        // 断几千处则是排序或解析出了问题，处理方式完全不同
        assertThat(r.detail()).contains("T2").contains("T3").contains("T4");
    }

    @Test
    @DisplayName("★ 渠道不提供逐笔余额 → 通过，且说明是「查不了」而不是「没问题」")
    void channelWithoutRowBalanceIsSkipped() {
        balance(D, 100000, 30000, 0, 130000);
        stmt(D, "T1", BizType.RECHARGE, 30000, null);
        stmt(D, "T2", BizType.RECHARGE, 0, null);

        CheckResult r = checker.checkRowContinuity(CH, D);

        assertThat(r.passed()).isTrue();
        assertThat(r.detail()).contains("不提供");
    }

    @Test
    @DisplayName("★★ 必须按入库顺序核对，按交易时间排序会把正确的链打断")
    void mustUseFileOrderNotTradeTime() {
        // 渠道账单按余额变动顺序输出，而交易时间只精确到秒 —— 同一秒可能几十笔。
        // 这里三笔时间完全相同且倒序，但入库顺序（= 文件行序）是对的。
        balance(D, 0, 60000, 0, 60000);
        LocalDateTime sameSecond = LocalDateTime.of(2026, 8, 22, 0, 0, 0);
        stmt(D, "T1", BizType.RECHARGE, 10000, 10000L, ChannelTradeStatus.SUCCESS, sameSecond);
        stmt(D, "T2", BizType.RECHARGE, 20000, 30000L, ChannelTradeStatus.SUCCESS, sameSecond);
        stmt(D, "T3", BizType.RECHARGE, 30000, 60000L, ChannelTradeStatus.SUCCESS, sameSecond);

        CheckResult r = checker.checkRowContinuity(CH, D);

        // 真实数据实测过：原始顺序 1 处断裂，按交易时间重排后变成 12012 处
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    // ================================================================
    //  TODO 20 · 跨日余额连续
    // ================================================================

    @Test
    @DisplayName("三天首尾相接 → 通过")
    void crossDayContinuityHolds() {
        balance(D,               100000, 50000, 50000, 100000);
        balance(D.plusDays(1),   100000, 30000, 30000, 100000);
        balance(D.plusDays(2),   100000, 10000,     0, 110000);

        CheckResult r = checker.checkCrossDayContinuity(CH, D, D.plusDays(2));
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    @Test
    @DisplayName("★ 次日期初接不上前一日期末 → 失败")
    void crossDayBalanceBreak() {
        balance(D,             100000, 50000, 50000, 100000);
        balance(D.plusDays(1),  95000, 10000,     0, 105000);   // 期初应为 100000

        CheckResult r = checker.checkCrossDayContinuity(CH, D, D.plusDays(1));

        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("100000").contains("95000");
    }

    @Test
    @DisplayName("★★★ 整天缺失、且当天净变动为 0 → 余额链接得上，但必须报出缺日期")
    void missingDayWithZeroNetChangeIsStillCaught() {
        // 这是整个余额连续性里最隐蔽的一种：
        // 08-23 的账单整天没下载，而那天净变动恰好为 0
        //（周末、节假日、或一批充值和一批提现刚好抵消）。
        // 只比「相邻两条记录」的话，比的是 08-22 和 08-24 —— 完美接上。
        balance(D,             100000, 50000, 50000, 100000);
        // D.plusDays(1) 整天缺失，其净变动本应为 0
        balance(D.plusDays(2), 100000, 10000,     0, 110000);

        CheckResult r = checker.checkCrossDayContinuity(CH, D, D.plusDays(2));

        // 余额对得上，不代表没缺天 —— 日期连续性必须单独查
        assertThat(r.passed())
                .as("余额链是连的，但 %s 整天没有数据，必须报出来。detail=%s",
                        D.plusDays(1), r.detail())
                .isFalse();
        assertThat(r.detail()).contains(D.plusDays(1).toString());
    }

    @Test
    @DisplayName("区间内只有一天 → 通过，没有跨日可比")
    void singleDayRangeIsFine() {
        balance(D, 100000, 50000, 50000, 100000);
        CheckResult r = checker.checkCrossDayContinuity(CH, D, D);
        assertThat(r.passed()).as("detail=%s", r.detail()).isTrue();
    }

    // ================================================================
    //  整套
    // ================================================================

    @Test
    @DisplayName("checkAll：区间内全部通过")
    void checkAllPasses() {
        normalDay(D);
        normalDay(D.plusDays(1));

        assertThat(checker.checkAll(CH, D, D.plusDays(1)))
                .allSatisfy(r -> assertThat(r.passed()).as("%s: %s", r.name(), r.detail()).isTrue());
    }
}
