package com.payment.ledger;

import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.engine.BalanceValidator;
import com.payment.ledger.exception.LedgerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payment.ledger.dto.EntryCommand.credit;
import static com.payment.ledger.dto.EntryCommand.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 借贷平衡校验。纯单测，不依赖 Spring。
 */
class BalanceValidatorTest {

    private final BalanceValidator validator = new BalanceValidator();

    @Test
    @DisplayName("一借一贷平衡 → 通过，返回凭证金额")
    void simpleBalanced() {
        long total = validator.validate(List.of(
                debit ("U0001", 10000),
                credit("M0001", 10000)));
        assertThat(total).isEqualTo(10000);
    }

    @Test
    @DisplayName("一借多贷平衡 → 通过（余额消费：用户100 = 商户99.4 + 手续费0.6）")
    void oneDebitMultiCredit() {
        long total = validator.validate(List.of(
                debit ("U0001",      10000),
                credit("M0001",       9940),
                credit("FEE_INCOME",    60)));
        assertThat(total).isEqualTo(10000);
    }

    @Test
    @DisplayName("多借一贷平衡 → 通过（退款：商户298.2 + 保证金9.94 + 垫付288.26 ... ）")
    void multiDebitOneCredit() {
        long total = validator.validate(List.of(
                debit ("M0001_DEPOSIT",  994),
                debit ("MERCHANT_RECV", 28826),
                debit ("FEE_INCOME",      180),
                credit("U0001",         30000)));
        assertThat(total).isEqualTo(30000);
    }

    @Test
    @DisplayName("借贷不平衡 → 拒绝落库，异常里带出差额")
    void unbalanced() {
        assertThatThrownBy(() -> validator.validate(List.of(
                debit ("U0001", 10000),
                credit("M0001",  9940))))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("借贷不平衡")
                .hasMessageContaining("差额=60");
    }

    @Test
    @DisplayName("只有借方没有贷方 → 拒绝（有借必有贷）")
    void debitOnly() {
        assertThatThrownBy(() -> validator.validate(List.of(
                debit("U0001", 10000),
                debit("M0001", 10000))))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("有借必有贷");
    }

    @Test
    @DisplayName("分录金额为 0 或负数 → 拒绝（金额永远为正，方向由 direction 表达）")
    void nonPositiveAmount() {
        assertThatThrownBy(() -> validator.validate(List.of(
                debit ("U0001", 10000),
                credit("M0001", 10000),
                credit("FEE_INCOME", 0))))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("必须为正数");
    }

    @Test
    @DisplayName("少于两条分录 → 拒绝")
    void tooFewEntries() {
        assertThatThrownBy(() -> validator.validate(List.of(debit("U0001", 10000))))
                .isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> validator.validate(List.of()))
                .isInstanceOf(LedgerException.class);
    }

    @Test
    @DisplayName("大额交易：单笔 3000 万元（超 int 上限）时借方合计不能溢出")
    void largeAmountMustNotOverflow() {
        // int 上限 2,147,483,647 分 = 2147.48 万元
        // 这里用 3000 万元 = 3,000,000,000 分，已经越界
        long amount = 3_000_000_000L;

        long total = validator.validate(List.of(
                debit ("CORP_PAYER", amount),
                credit("CORP_PAYEE", amount)));

        assertThat(total).isEqualTo(amount);
    }

    @Test
    @DisplayName("大额累加：多条分录累加后超过 int 上限，同样不能溢出")
    void accumulatedAmountMustNotOverflow() {
        // 每条 8 亿分（800万元），三条累加 24 亿分，超过 int 上限
        long each = 800_000_000L;

        long total = validator.validate(List.of(
                debit ("M0001", each),
                debit ("M0002", each),
                debit ("M0003", each),
                credit("BANK_RESERVE", each * 3)));

        assertThat(total).isEqualTo(each * 3);
    }

    @Test
    @DisplayName("警示用例：金额算错十倍，但借贷依然是平的 —— 平衡校验的盲区")
    void balancedButWrong() {
        // 200 元订单，费率 0.6%，正确手续费应该是 1.20 元（120分）
        // 这里算成了 12 元（1200分）—— 错了十倍
        long total = validator.validate(List.of(
                debit ("U0001",      20000),
                credit("M0001",      18800),
                credit("FEE_INCOME",  1200)));

        // 校验器顺利放行：20000 == 18800 + 1200
        assertThat(total).isEqualTo(20000);

        // 结论：借贷平衡只能保证"分录内部自洽"，无法保证"金额来源正确"。
        // 生产系统必须再加一道独立的费率复核防线（阶段 2）。
    }
}
