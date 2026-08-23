package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.engine.FeeValidator;
import com.payment.ledger.exception.LedgerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费率复核 —— 补上借贷平衡校验的盲区。
 */
@SpringBootTest
class FeeValidatorTest {

    @Autowired FeeValidator feeValidator;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;

    private long fee(BizType type, String merchantId, long amount) {
        return feeValidator.calculate(type, merchantId, amount, D);
    }

    // ================================================================
    //  费率计算
    // ================================================================

    @Test
    @DisplayName("基本费率：100 元 × 0.6% = 0.60 元（60bp，整数运算无浮点误差）")
    void basicRate() {
        assertThat(fee(BizType.CONSUME, null, 10000)).isEqualTo(60);
    }

    @Test
    @DisplayName("商户专属协议价优先于默认费率：M0001 享 0.38%，而非默认 0.6%")
    void merchantSpecificRateWins() {
        assertThat(fee(BizType.CONSUME, "M0001", 10000)).isEqualTo(38);   // 0.38%
        assertThat(fee(BizType.CONSUME, "M0002", 10000)).isEqualTo(60);   // 无专属价，走默认
        assertThat(fee(BizType.CONSUME, null,    10000)).isEqualTo(60);
    }

    @Test
    @DisplayName("四舍五入：1 元 × 0.6% = 0.006 元 = 0.6 分 → 进为 1 分")
    void halfUpRounding() {
        assertThat(fee(BizType.CONSUME, null, 100)).isEqualTo(1);
    }

    @Test
    @DisplayName("四舍五入：0.6 元 × 0.6% = 0.36 分 → 舍为 0 分")
    void halfUpRoundingDown() {
        assertThat(fee(BizType.CONSUME, null, 60)).isZero();
    }

    @Test
    @DisplayName("保底：提现 100 元按 0.1% 只有 0.10 元，被保底抬到 1 元")
    void minFeeApplies() {
        // 10000 分 × 10bp = 10 分，低于 minFee=100 分
        assertThat(fee(BizType.WITHDRAW_SUBMIT, null, 10000)).isEqualTo(100);
    }

    @Test
    @DisplayName("封顶：提现 100 万元按 0.1% 是 1000 元，被封顶压到 25 元")
    void maxFeeApplies() {
        // 100_000_000 分 × 10bp = 100000 分，高于 maxFee=2500 分
        assertThat(fee(BizType.WITHDRAW_SUBMIT, null, 100_000_000L)).isEqualTo(2500);
    }

    @Test
    @DisplayName("保底与封顶之间：提现 1 万元按 0.1% = 10 元，不触发保底也不触发封顶")
    void betweenMinAndMax() {
        // 1_000_000 分 × 10bp = 1000 分 = 10 元
        assertThat(fee(BizType.WITHDRAW_SUBMIT, null, 1_000_000L)).isEqualTo(1000);
    }

    @Test
    @DisplayName("免费业务：费率 0bp，任何金额都不收费")
    void zeroRate() {
        assertThat(fee(BizType.RECHARGE,   null, 1_000_000L)).isZero();
        assertThat(fee(BizType.TRANSFER,   null, 1_000_000L)).isZero();
        assertThat(fee(BizType.ESCROW_PAY, null, 1_000_000L)).isZero();
    }

    @Test
    @DisplayName("大额不溢出：1 亿元交易按 0.6% 算出 60 万元")
    void largeAmountNoOverflow() {
        // 10_000_000_000 分 = 1 亿元
        assertThat(fee(BizType.CONSUME, null, 10_000_000_000L)).isEqualTo(60_000_000L);
    }

    @Test
    @DisplayName("未配置计费规则 → 拒绝，绝不默认按 0 处理")
    void missingRuleIsRejected() {
        assertThatThrownBy(() -> fee(BizType.REFUND_TO_BALANCE, null, 10000))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("未配置计费规则");
    }

    // ================================================================
    //  复核
    // ================================================================

    @Test
    @DisplayName("复核通过：上游传入值与签约费率一致")
    void verifyPasses() {
        feeValidator.verify(BizType.CONSUME, null, 10000, 60, D);   // 不抛异常即通过
    }

    @Test
    @DisplayName("★ 拦住十倍错误：借贷平衡放行的那笔，费率复核拦下了")
    void verifyCatchesTenfoldError() {
        // 200 元订单，费率 0.6%，正确手续费 1.20 元（120 分）
        // 上游把 0.6% 当成 6% 算，传来 1200 分
        // 生成的分录 20000 = 18800 + 1200 借贷完美平衡，平衡校验会放行
        assertThatThrownBy(() -> feeValidator.verify(BizType.CONSUME, null, 20000, 1200, D))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("手续费复核不通过")
                .hasMessageContaining("上游传入 1200")
                .hasMessageContaining("账务按签约费率试算 120");
    }

    @Test
    @DisplayName("复核也拦少收：上游少传手续费，同样拒绝")
    void verifyCatchesUnderCharge() {
        assertThatThrownBy(() -> feeValidator.verify(BizType.CONSUME, null, 10000, 0, D))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("手续费复核不通过");
    }

    @Test
    @DisplayName("复核认商户专属价：按默认费率传值的请求会被拒绝")
    void verifyRespectsMerchantRate() {
        // M0001 签的是 0.38%，传默认费率算出的 60 分要被拦下
        assertThatThrownBy(() -> feeValidator.verify(BizType.CONSUME, "M0001", 10000, 60, D))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("试算 38");

        feeValidator.verify(BizType.CONSUME, "M0001", 10000, 38, D);   // 正确值通过
    }
}
