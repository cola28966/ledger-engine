package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.domain.ChannelTradeStatus;
import com.payment.ledger.domain.DiffStatus;
import com.payment.ledger.domain.DiffType;
import com.payment.ledger.domain.ReconDiff;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.BookingResult;
import com.payment.ledger.dto.ReconSummary;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.engine.HotAccountRouter;
import com.payment.ledger.recon.ReconService;
import com.payment.ledger.repository.AccountRepository;
import com.payment.ledger.repository.ChannelStatementRepository;
import com.payment.ledger.repository.ReconRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道对账。
 *
 * <p>前四个阶段的所有防线——借贷平衡、费率复核、日终五项勾稽——
 * 都只看我方账本内部。它们能证明账本自洽，
 * <b>证明不了账本描述的事情真的发生过</b>。
 *
 * <p>这个测试类里最该看的是 {@code channelSideOnlyIsFound}：
 * 一笔充值的回调丢了、我方完全没记账，五项勾稽会全部通过，
 * 因为压根没有这笔记录，账本内部当然是自洽的。
 * 而用户的钱已经从银行卡扣走了。
 */
@SpringBootTest
class ReconServiceTest {

    @Autowired ReconService recon;
    @Autowired AccountingEngine engine;
    @Autowired ChannelStatementRepository statementRepo;
    @Autowired ReconRepository reconRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired HotAccountRouter router;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;   // 2026-08-22
    static final String CH = "UNIONPAY";

    @BeforeEach
    void reset() {
        LedgerTestSupport.resetAll(jdbc, router);
    }

    // ---------------- 造数 ----------------

    private String bookRecharge(String orderNo, String payee, long amount) {
        return bookRecharge(orderNo, payee, amount, D);
    }

    private String bookRecharge(String orderNo, String payee, long amount, LocalDate date) {
        BookingResult r = engine.book(BookingRequest.builder()
                .requestId("REQ_" + orderNo).bizType(BizType.RECHARGE).bizOrderNo(orderNo)
                .accountingDate(date).payeeAccount(payee).amount(amount).fee(0).build());
        return r.getVoucherNo();
    }

    private void channelRow(String tradeNo, String orderNo, long amount) {
        channelRow(tradeNo, orderNo, amount, 0, ChannelTradeStatus.SUCCESS, D, "U0001",
                BizType.RECHARGE);
    }

    private void channelRow(String tradeNo, String orderNo, long amount, long fee,
                            ChannelTradeStatus status, LocalDate stmtDate,
                            String ourAccountNo, BizType bizType) {
        statementRepo.insert(ChannelStatement.builder()
                .channelCode(CH).channelTradeNo(tradeNo).bizOrderNo(orderNo)
                .bizType(bizType).amount(amount).fee(fee)
                .tradeStatus(status).statementDate(stmtDate)
                .ourAccountNo(ourAccountNo).tradeTime(LocalDateTime.now())
                .build());
    }

    // ================================================================
    //  基本核对
    // ================================================================

    @Test
    @DisplayName("两侧完全一致 → 对平，零差异")
    void allMatched() {
        bookRecharge("ORD1", "U0001", 100000);
        bookRecharge("ORD2", "U0002", 50000);
        channelRow("CH1", "ORD1", 100000);
        channelRow("CH2", "ORD2", 50000);

        ReconSummary s = recon.reconcile(D, CH);

        assertThat(s.balanced()).isTrue();
        assertThat(s.ourCount()).isEqualTo(2);
        assertThat(s.channelCount()).isEqualTo(2);
        assertThat(s.matchedCount()).isEqualTo(2);
        assertThat(reconRepo.findDiffs(D, CH)).isEmpty();
    }

    @Test
    @DisplayName("★★ 渠道有、我方无 —— 五项勾稽全过，只有对账能发现")
    void channelSideOnlyIsFound() {
        bookRecharge("ORD1", "U0001", 100000);
        channelRow("CH1", "ORD1", 100000);
        // 这一笔的回调丢了，我方完全没记账。
        // 用户的钱已经扣走，我方账上没有任何痕迹——
        // 借贷平衡 ✓ 余额与流水一致 ✓ 备付金勾稽 ✓，因为压根没有这条记录。
        channelRow("CH_LOST", "ORD_LOST", 88888);

        ReconSummary s = recon.reconcile(D, CH);

        assertThat(s.balanced()).isFalse();
        assertThat(s.countOf(DiffType.CHANNEL_MORE)).isEqualTo(1);

        ReconDiff d = s.of(DiffType.CHANNEL_MORE).get(0);
        assertThat(d.getBizOrderNo()).isEqualTo("ORD_LOST");
        assertThat(d.getChannelAmount()).isEqualTo(88888);
        assertThat(d.getOurAmount()).isZero();
        assertThat(d.getStatus()).isEqualTo(DiffStatus.PENDING);
    }

    @Test
    @DisplayName("★ 我方有、渠道无 → 我方单边账")
    void ourSideOnlyIsFound() {
        bookRecharge("ORD_GHOST", "U0001", 30000);

        ReconSummary s = recon.reconcile(D, CH);

        assertThat(s.countOf(DiffType.OUR_MORE)).isEqualTo(1);
        ReconDiff d = s.of(DiffType.OUR_MORE).get(0);
        assertThat(d.getOurAmount()).isEqualTo(30000);
        assertThat(d.getChannelAmount()).isZero();
        assertThat(d.getOurVoucherNo()).isNotBlank();     // 能直接追到凭证
    }

    @Test
    @DisplayName("金额不符：两侧金额必须都落库，只存差额是不够的")
    void amountMismatchRecordsBothSides() {
        bookRecharge("ORD1", "U0001", 100000);
        channelRow("CH1", "ORD1", 99900);

        ReconSummary s = recon.reconcile(D, CH);

        ReconDiff d = s.of(DiffType.AMOUNT_MISMATCH).get(0);
        // 差额 100 既可能是「1000 对 900」也可能是「100000 对 99900」，
        // 排查路径完全不同，所以两侧原值都要留下
        assertThat(d.getOurAmount()).isEqualTo(100000);
        assertThat(d.getChannelAmount()).isEqualTo(99900);
        assertThat(d.amountDiff()).isEqualTo(100);
    }

    @Test
    @DisplayName("两侧笔数都要报出：差异为 0 但渠道 0 笔，那不叫对平，叫没对")
    void summaryReportsBothSideCounts() {
        bookRecharge("ORD1", "U0001", 100000);
        bookRecharge("ORD2", "U0002", 50000);
        channelRow("CH1", "ORD1", 100000);

        ReconSummary s = recon.reconcile(D, CH);

        assertThat(s.ourCount()).isEqualTo(2);
        assertThat(s.channelCount()).isEqualTo(1);
        assertThat(s.describe()).contains("我方 2 笔").contains("渠道 1 笔");
    }

    // ================================================================
    //  容易误判的三类
    // ================================================================

    @Test
    @DisplayName("★ 跨日：我方记 T 日、渠道记 T+1 日 → 对平，不是单边账")
    void crossDayIsNotADiff() {
        // 23:59 发起的交易，我方记当日账，渠道记进次日对账单。
        // 按会计日期精确匹配，这一笔会在两天里各被报一次差异，而它完全正常。
        bookRecharge("ORD_LATE", "U0001", 66600, D);
        channelRow("CH_LATE", "ORD_LATE", 66600, 0,
                ChannelTradeStatus.SUCCESS, D.plusDays(1), "U0001", BizType.RECHARGE);

        ReconSummary s = recon.reconcile(D.plusDays(1), CH);

        assertThat(s.balanced()).isTrue();
        assertThat(s.matchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 在途凭证不算差异，但要计入在途笔数")
    void processingIsNotADiff() {
        jdbc.update("""
                INSERT INTO voucher
                    (voucher_no, request_id, biz_type, biz_order_no, accounting_date,
                     total_amount, status, reverse_of, reversed_by, remark, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "V_PROC", "REQ_PROC", "RECHARGE", "ORD_PROC",
                Date.valueOf(D), 20000, "PROCESSING", null, null, null, LocalDateTime.now());

        ReconSummary s = recon.reconcile(D, CH);

        // 在途直接报成差异，是对账告警最大的噪音来源：
        // 值班的人被几千条「差异」淹没，真问题反而看不见
        assertThat(s.balanced()).isTrue();
        assertThat(s.inTransitCount()).isEqualTo(1);
        assertThat(reconRepo.findDiffs(D, CH)).isEmpty();
    }

    @Test
    @DisplayName("★ 已冲正的凭证视同我方无记录：渠道说成功 → 渠道单边账")
    void reversedVoucherCountsAsMissing() {
        String v = bookRecharge("ORD_REV", "U0001", 40000);
        engine.reverse(v, "REQ_REV_1", D);
        channelRow("CH_REV", "ORD_REV", 40000);

        ReconSummary s = recon.reconcile(D, CH);

        // 一正一反净额为零，等价于我方没有这笔；
        // 而渠道说钱确实到了 —— 这个结论是对的，必须报出来
        assertThat(s.countOf(DiffType.CHANNEL_MORE)).isEqualTo(1);
        assertThat(s.of(DiffType.CHANNEL_MORE).get(0).getBizOrderNo()).isEqualTo("ORD_REV");
    }

    // ================================================================
    //  重跑
    // ================================================================

    @Test
    @DisplayName("★ 重跑幂等：跑两次，差异不翻倍")
    void rerunIsIdempotent() {
        bookRecharge("ORD1", "U0001", 100000);
        channelRow("CH1", "ORD1", 99900);
        channelRow("CH_LOST", "ORD_LOST", 5000);

        ReconSummary first = recon.reconcile(D, CH);
        ReconSummary second = recon.reconcile(D, CH);

        assertThat(first.diffCount()).isEqualTo(2);
        assertThat(second.diffCount()).isEqualTo(2);
        assertThat(reconRepo.findDiffs(D, CH)).hasSize(2);
    }

    @Test
    @DisplayName("★★ 重跑不能覆盖人工处理的结论")
    void rerunDoesNotOverwriteManualResolution() {
        bookRecharge("ORD1", "U0001", 100000);
        channelRow("CH1", "ORD1", 99900);

        recon.reconcile(D, CH);
        ReconDiff d = reconRepo.findDiffs(D, CH).get(0);
        // 有人花两小时查清楚了，标记为已人工处理
        reconRepo.markHandled(d.getDiffId(), DiffStatus.MANUAL_RESOLVED, null, "已与渠道确认，渠道单有误");

        recon.reconcile(D, CH);

        List<ReconDiff> after = reconRepo.findDiffs(D, CH);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getStatus()).isEqualTo(DiffStatus.MANUAL_RESOLVED);
        assertThat(after.get(0).getRemark()).contains("已与渠道确认");
    }

    @Test
    @DisplayName("★★ 跳过已处理的，不能连带把新差异一起丢掉")
    void handledDiffMustNotSwallowNewOnes() {
        // 上一个用例只有一笔差异，且它恰好就是被人工处理的那笔——
        // 「跳过已处理」的判断写反了也照样能过。必须再加一笔全新的差异才能分辨。
        channelRow("CH_A", "ORD_A", 11111);
        recon.reconcile(D, CH);
        ReconDiff a = reconRepo.findDiffs(D, CH).get(0);
        reconRepo.markHandled(a.getDiffId(), DiffStatus.MANUAL_RESOLVED, null, "已与渠道确认");

        // 渠道对账单里又多出一笔全新的
        channelRow("CH_B", "ORD_B", 22222);
        ReconSummary s = recon.reconcile(D, CH);

        assertThat(s.diffCount())
                .as("本次只该报新出现的 ORD_B，实际报出：%s",
                        s.diffs().stream().map(ReconDiff::getBizOrderNo).toList())
                .isEqualTo(1);
        assertThat(s.diffs().get(0).getBizOrderNo()).isEqualTo("ORD_B");

        // 而且要真的落库，不能只活在内存里
        assertThat(reconRepo.findDiffs(D, CH))
                .extracting(ReconDiff::getBizOrderNo)
                .containsExactlyInAnyOrder("ORD_A", "ORD_B");
    }

    @Test
    @DisplayName("★ 我方同一订单号记了两笔 → 对账不能崩在读数据这一步")
    void duplicateBizOrderDoesNotKillTheBatch() {
        // 幂等键是 requestId，bizOrderNo 不唯一 —— 同一订单被记两次账幂等挡不住。
        // 而这正是对账该发现的「我方重复入账」。
        engine.book(BookingRequest.builder()
                .requestId("REQ_A").bizType(BizType.RECHARGE).bizOrderNo("ORD_DUP")
                .accountingDate(D).payeeAccount("U0001").amount(10000).fee(0).build());
        engine.book(BookingRequest.builder()
                .requestId("REQ_B").bizType(BizType.RECHARGE).bizOrderNo("ORD_DUP")
                .accountingDate(D).payeeAccount("U0001").amount(10000).fee(0).build());
        channelRow("CH_DUP", "ORD_DUP", 10000);

        ReconSummary s = recon.reconcile(D, CH);

        // 批次要跑完，不能抛 IllegalStateException：
        // 崩在这里的话，值班的人第二天看到的是「任务失败」而不是「发现重复入账」，
        // 于是去查程序 bug，真正的资损躺在账上没人管
        assertThat(s.ourCount()).isEqualTo(2);
        assertThat(s.channelCount()).isEqualTo(1);

        // 已知局限：重复的那一笔只进了 error 日志，没有进差异表。
        // 彻底的做法是加一种 DiffType，走差异表 + 人工处理流程。
        assertThat(s.matchedCount()).isEqualTo(1);
        assertThat(s.balanced()).isTrue();
    }

    // ================================================================
    //  自动修复
    // ================================================================

    @Test
    @DisplayName("★★ 渠道单边账自动补记账：账真的补上了，且形成审计链")
    void autoRepairBooksTheMissingRecharge() {
        channelRow("CH_LOST", "ORD_LOST", 88888);
        recon.reconcile(D, CH);

        int repaired = recon.autoRepair(D, CH);

        assertThat(repaired).isEqualTo(1);
        assertThat(accountRepo.findByNo("U0001").getBalance()).isEqualTo(88888);

        ReconDiff d = reconRepo.findDiffs(D, CH).get(0);
        assertThat(d.getStatus()).isEqualTo(DiffStatus.AUTO_REPAIRED);
        assertThat(d.getRepairVoucherNo()).isNotBlank();   // 差异 → 处理动作，可追溯
    }

    @Test
    @DisplayName("★★ 补记账幂等：差异被重置后再修一次，钱不会记两遍")
    void autoRepairDoesNotDoubleBook() {
        channelRow("CH_LOST", "ORD_LOST", 88888);
        recon.reconcile(D, CH);
        recon.autoRepair(D, CH);

        // 模拟运维把差异重置回待处理后重跑补偿任务
        jdbc.update("UPDATE recon_diff SET status = 'PENDING', repair_voucher_no = NULL");
        recon.autoRepair(D, CH);

        // requestId 由渠道流水号派生，第二次会被记账引擎的幂等防线挡回来。
        // 如果用了 UUID 或时间戳，这里会变成 177776 —— 而且不会被任何勾稽发现，
        // 因为两笔各自都是借贷平衡的
        assertThat(accountRepo.findByNo("U0001").getBalance()).isEqualTo(88888);
        Integer vouchers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher WHERE biz_order_no = 'ORD_LOST'", Integer.class);
        assertThat(vouchers).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 金额不符绝不自动修复 —— 会把计算 bug 悄悄抹掉")
    void autoRepairRefusesAmountMismatch() {
        bookRecharge("ORD1", "U0001", 100000);
        channelRow("CH1", "ORD1", 99900);
        recon.reconcile(D, CH);

        long before = accountRepo.findByNo("U0001").getBalance();
        int repaired = recon.autoRepair(D, CH);

        assertThat(repaired).isZero();
        assertThat(accountRepo.findByNo("U0001").getBalance()).isEqualTo(before);
        assertThat(reconRepo.findDiffs(D, CH).get(0).getStatus()).isEqualTo(DiffStatus.PENDING);
    }

    @Test
    @DisplayName("★ 非充值类的渠道单边账不自动修 —— 代收代付补反了窟窿翻倍")
    void autoRepairSkipsNonRecharge() {
        channelRow("CH_WD", "ORD_WD", 50000, 0, ChannelTradeStatus.SUCCESS,
                D, "U0001", BizType.WITHDRAW_SUCCESS);
        recon.reconcile(D, CH);

        assertThat(recon.autoRepair(D, CH)).isZero();
        assertThat(reconRepo.findDiffs(D, CH).get(0).getStatus()).isEqualTo(DiffStatus.PENDING);
        assertThat(accountRepo.findByNo("U0001").getBalance()).isZero();
    }

    @Test
    @DisplayName("★ 不知道该记给谁的差异，只能转人工")
    void autoRepairSkipsUnknownAccount() {
        channelRow("CH_X", "ORD_X", 12345, 0, ChannelTradeStatus.SUCCESS,
                D, null, BizType.RECHARGE);
        recon.reconcile(D, CH);

        assertThat(recon.autoRepair(D, CH)).isZero();
        assertThat(reconRepo.findDiffs(D, CH).get(0).getStatus()).isEqualTo(DiffStatus.PENDING);
    }

    @Test
    @DisplayName("★ 补记账要能被下一次对账认领：补完再对，账是平的")
    void reconcileIsCleanAfterRepair() {
        channelRow("CH_LOST", "ORD_LOST", 88888);
        recon.reconcile(D, CH);
        recon.autoRepair(D, CH);

        ReconSummary s = recon.reconcile(D, CH);

        // 补记的凭证若另起一个订单号，这里会冒出一笔新的我方单边账
        assertThat(s.balanced()).isTrue();
        assertThat(s.matchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 补记账记在当前会计日，不是交易日 —— 已关账的期间禁止追溯")
    void repairIsBookedOnCurrentAccountingDate() {
        // 08-21 已关账（对账通常在 T+1 跑，那时 T 日多半已经关了）
        LocalDate closed = D.minusDays(1);
        channelRow("CH_OLD", "ORD_OLD", 30000, 0, ChannelTradeStatus.SUCCESS,
                closed, "U0001", BizType.RECHARGE);

        recon.reconcile(closed, CH);
        assertThat(recon.autoRepair(closed, CH)).isEqualTo(1);

        String repairVoucher = reconRepo.findDiffs(closed, CH).get(0).getRepairVoucherNo();
        java.sql.Date bookedOn = jdbc.queryForObject(
                "SELECT accounting_date FROM voucher WHERE voucher_no = ?",
                java.sql.Date.class, repairVoucher);

        // 记在发现日，和冲正的规则完全一致
        assertThat(bookedOn.toLocalDate()).isEqualTo(D);
    }
}
