package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.domain.ChannelTradeStatus;
import com.payment.ledger.domain.DiffType;
import com.payment.ledger.domain.VoucherStatus;
import com.payment.ledger.dto.OurRecord;
import com.payment.ledger.recon.ReconService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对账定性 —— 单笔的判断规则。
 *
 * <p>这里全是纯函数测试，没有数据库。定性规则是对账的语义核心，
 * 值得从批处理流程里单独拎出来测：批处理跑错了还能重跑，
 * <b>定性判错了会把补救动作导向反方向</b>。
 */
@SpringBootTest
class ReconClassifyTest {

    @Autowired ReconService recon;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;

    private OurRecord our(long amount, long fee, VoucherStatus status) {
        return new OurRecord("ORD001", "V001", BizType.RECHARGE, D, amount, fee, status);
    }

    private ChannelStatement channel(long amount, long fee, ChannelTradeStatus status) {
        return ChannelStatement.builder()
                .channelCode("UNIONPAY").channelTradeNo("CH001").bizOrderNo("ORD001")
                .bizType(BizType.RECHARGE).amount(amount).fee(fee)
                .tradeStatus(status).statementDate(D).ourAccountNo("U0001")
                .build();
    }

    // ================================================================
    //  平账与在途
    // ================================================================

    @Test
    @DisplayName("两侧完全一致 → 平账")
    void bothSidesIdentical() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(10000, 0, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.MATCHED);
    }

    @Test
    @DisplayName("两侧都为空 → 调用方传错了，直接抛异常")
    void bothSidesNull() {
        assertThatThrownBy(() -> recon.classify(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("我方在途、渠道无 → 在途，不是我方单边账")
    void ourProcessingWithoutChannel() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.PROCESSING), null))
                .isEqualTo(DiffType.IN_TRANSIT);
    }

    @Test
    @DisplayName("★ 我方在途且金额还对不上 → 仍然是在途，在途优先于一切")
    void inTransitBeatsEverything() {
        // 我方还没记完，此刻拿它和渠道比金额毫无意义。
        // 如果这里返回 AMOUNT_MISMATCH，每天几千笔在途订单会全部变成假告警，
        // 真正的金额问题就淹没在里面了。
        assertThat(recon.classify(our(9999, 0, VoucherStatus.PROCESSING),
                channel(10000, 0, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.IN_TRANSIT);
    }

    @Test
    @DisplayName("★ 渠道失败 + 我方无记录 → 平账，两边说的是同一件事")
    void channelFailedAndWeHaveNothing() {
        // 对账单里会包含失败交易。渠道说没成、我方也没记账，
        // 这是完全正常的一笔，报成差异就是纯噪音。
        assertThat(recon.classify(null, channel(10000, 0, ChannelTradeStatus.FAIL)))
                .isEqualTo(DiffType.MATCHED);
    }

    // ================================================================
    //  单边账
    // ================================================================

    @Test
    @DisplayName("★ 我方有、渠道无 → 我方单边账（平台资损方向）")
    void ourSideOnly() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS), null))
                .isEqualTo(DiffType.OUR_MORE);
    }

    @Test
    @DisplayName("★ 渠道成功、我方无 → 渠道单边账（用户资损方向）")
    void channelSideOnly() {
        assertThat(recon.classify(null, channel(10000, 0, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.CHANNEL_MORE);
    }

    // ================================================================
    //  金额 / 手续费 / 状态
    // ================================================================

    @Test
    @DisplayName("金额不符")
    void amountMismatch() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(9900, 0, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("金额一致但渠道收了通道费、我方没记成本 → 手续费不符")
    void feeMismatch() {
        // 资金安全没问题，但成本漏记会让利润虚高——财报失真的常见来源
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(10000, 25, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.FEE_MISMATCH);
    }

    @Test
    @DisplayName("★ 我方成功、渠道失败 → 状态不符")
    void statusMismatch() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(10000, 0, ChannelTradeStatus.FAIL)))
                .isEqualTo(DiffType.STATUS_MISMATCH);
    }

    // ================================================================
    //  优先级：一笔只报一种，且必须是最严重的那种
    // ================================================================

    @Test
    @DisplayName("★ 既状态不符又金额不符 → 只报状态不符")
    void statusBeatsAmount() {
        // 顺序写反了，这笔会被报成「金额不符」，
        // 值班的人就会去查金额计算逻辑——查一整天也查不出问题，
        // 因为真正的问题是双方对「这笔到底成没成」都没达成一致。
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(8888, 0, ChannelTradeStatus.FAIL)))
                .isEqualTo(DiffType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("既金额不符又手续费不符 → 只报金额不符")
    void amountBeatsFee() {
        assertThat(recon.classify(our(10000, 0, VoucherStatus.SUCCESS),
                channel(9900, 25, ChannelTradeStatus.SUCCESS)))
                .isEqualTo(DiffType.AMOUNT_MISMATCH);
    }

    // ================================================================
    //  needsHandling 的语义
    // ================================================================

    @Test
    @DisplayName("平账与在途不进差异表，其余全部要处理")
    void needsHandlingSemantics() {
        assertThat(DiffType.MATCHED.needsHandling()).isFalse();
        assertThat(DiffType.IN_TRANSIT.needsHandling()).isFalse();
        assertThat(DiffType.OUR_MORE.needsHandling()).isTrue();
        assertThat(DiffType.CHANNEL_MORE.needsHandling()).isTrue();
        assertThat(DiffType.AMOUNT_MISMATCH.needsHandling()).isTrue();
        assertThat(DiffType.FEE_MISMATCH.needsHandling()).isTrue();
        assertThat(DiffType.STATUS_MISMATCH.needsHandling()).isTrue();
    }
}
