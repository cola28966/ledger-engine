package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.engine.TemplateEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板渲染。<b>这些用例就是你手写过的那些分录题。</b>
 */
@SpringBootTest
class TemplateEngineTest {

    @Autowired TemplateEngine templateEngine;

    private BookingRequest req(BizType type, String payer, String payee, long amount, long fee) {
        return BookingRequest.builder()
                .requestId("REQ_" + System.nanoTime())
                .bizType(type)
                .bizOrderNo("ORDER001")
                .accountingDate(LocalDate.of(2026, 8, 22))
                .payerAccount(payer)
                .payeeAccount(payee)
                .amount(amount)
                .fee(fee)
                .build();
    }

    /** 断言存在某条分录 */
    private void assertEntry(List<EntryCommand> entries, String account, Direction dir, long amount) {
        assertThat(entries)
                .as("期望存在分录: %s %s %d", dir, account, amount)
                .anySatisfy(e -> {
                    assertThat(e.getAccountNo()).isEqualTo(account);
                    assertThat(e.getDirection()).isEqualTo(dir);
                    assertThat(e.getAmount()).isEqualTo(amount);
                });
    }

    @Test
    @DisplayName("充值 100 元：借 银行存款 / 贷 用户余额（负债增加记贷方）")
    void recharge() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.RECHARGE, null, "U0001", 10000, 0));

        assertThat(entries).hasSize(2);
        assertEntry(entries, EntryGenerator.BANK_RESERVE, Direction.DR, 10000);
        assertEntry(entries, "U0001", Direction.CR, 10000);
    }

    @Test
    @DisplayName("余额消费 100 元 费率 0.6%：一借两贷，商户 99.40 手续费 0.60")
    void consume() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.CONSUME, "U0001", "M0001", 10000, 60));

        assertThat(entries).hasSize(3);
        assertEntry(entries, "U0001", Direction.DR, 10000);
        assertEntry(entries, "M0001", Direction.CR, 9940);
        assertEntry(entries, EntryGenerator.FEE_INCOME, Direction.CR, 60);
    }

    @Test
    @DisplayName("用户转账：负债内部转移，平台资产负债总额均不变")
    void transfer() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.TRANSFER, "U0001", "U0002", 5000, 0));

        assertThat(entries).hasSize(2);
        assertEntry(entries, "U0001", Direction.DR, 5000);
        assertEntry(entries, "U0002", Direction.CR, 5000);
    }

    @Test
    @DisplayName("担保下单：钱进中间户，既不属于用户也不属于商户")
    void escrowPay() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.ESCROW_PAY, "U0001", null, 100000, 0));

        assertEntry(entries, "U0001", Direction.DR, 100000);
        assertEntry(entries, EntryGenerator.ESCROW, Direction.CR, 100000);
    }

    @Test
    @DisplayName("确认收货：中间户 → 商户 + 手续费")
    void escrowConfirm() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.ESCROW_CONFIRM, null, "M0001", 100000, 600));

        assertThat(entries).hasSize(3);
        assertEntry(entries, EntryGenerator.ESCROW, Direction.DR, 100000);
        assertEntry(entries, "M0001", Direction.CR, 99400);
        assertEntry(entries, EntryGenerator.FEE_INCOME, Direction.CR, 600);
    }

    @Test
    @DisplayName("担保退款：中间户 → 用户，原路退回")
    void escrowRefund() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.ESCROW_REFUND, null, "U0001", 100000, 0));

        assertEntry(entries, EntryGenerator.ESCROW, Direction.DR, 100000);
        assertEntry(entries, "U0001", Direction.CR, 100000);
    }

    @Test
    @DisplayName("提现提交：商户户 → 提现在途户，此时平台资产还没减少")
    void withdrawSubmit() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.WITHDRAW_SUBMIT, "M0001", null, 100000, 200));

        assertThat(entries).hasSize(3);
        assertEntry(entries, "M0001", Direction.DR, 100000);
        assertEntry(entries, EntryGenerator.WD_TRANSIT, Direction.CR, 99800);
        assertEntry(entries, EntryGenerator.WD_FEE_INCOME, Direction.CR, 200);

        // 关键：银行存款科目没有出现 —— 钱还在备付金账户里
        assertThat(entries).noneMatch(e -> e.getAccountNo().equals(EntryGenerator.BANK_RESERVE));
    }

    @Test
    @DisplayName("提现成功：提现在途 → 银行存款，全流程唯一「平台资产真正减少」的时刻")
    void withdrawSuccess() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.WITHDRAW_SUCCESS, null, null, 99800, 0));

        assertThat(entries).hasSize(2);
        assertEntry(entries, EntryGenerator.WD_TRANSIT, Direction.DR, 99800);
        assertEntry(entries, EntryGenerator.BANK_RESERVE, Direction.CR, 99800);
    }

    @Test
    @DisplayName("退款到余额 300 元 退还手续费 1.80：两借一贷，手续费收入记借方")
    void refundToBalance() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.REFUND_TO_BALANCE, "M0001", "U0001", 30000, 180));

        assertThat(entries).hasSize(3);
        assertEntry(entries, "M0001", Direction.DR, 29820);
        // 收入减少记借方
        assertEntry(entries, EntryGenerator.FEE_INCOME, Direction.DR, 180);
        assertEntry(entries, "U0001", Direction.CR, 30000);
    }

    @Test
    @DisplayName("手续费为 0 时不产生 0 元分录（账上不留没有业务含义的记录）")
    void zeroFeeProducesNoFeeEntry() {
        List<EntryCommand> entries = templateEngine.render(
                req(BizType.CONSUME, "U0001", "M0001", 10000, 0));

        assertThat(entries).hasSize(2);
        assertThat(entries).noneMatch(e -> e.getAmount() == 0);
        assertThat(entries).noneMatch(e -> e.getAccountNo().equals(EntryGenerator.FEE_INCOME));
    }

    @Test
    @DisplayName("生成的每一组分录都必须自平衡")
    void allGeneratedEntriesAreBalanced() {
        List<BookingRequest> cases = List.of(
                req(BizType.RECHARGE,          null,    "U0001", 10000,  0),
                req(BizType.CONSUME,           "U0001", "M0001", 10000, 60),
                req(BizType.TRANSFER,          "U0001", "U0002",  5000,  0),
                req(BizType.ESCROW_PAY,        "U0001", null,   100000,  0),
                req(BizType.ESCROW_CONFIRM,    null,    "M0001",100000,600),
                req(BizType.ESCROW_REFUND,     null,    "U0001",100000,  0),
                req(BizType.WITHDRAW_SUBMIT,   "M0001", null,   100000,200),
                req(BizType.WITHDRAW_SUCCESS,  null,    null,    99800,  0),
                req(BizType.REFUND_TO_BALANCE, "M0001", "U0001", 30000,180));

        for (BookingRequest r : cases) {
            List<EntryCommand> entries = templateEngine.render(r);
            long dr = entries.stream().filter(e -> e.getDirection() == Direction.DR)
                    .mapToLong(EntryCommand::getAmount).sum();
            long cr = entries.stream().filter(e -> e.getDirection() == Direction.CR)
                    .mapToLong(EntryCommand::getAmount).sum();
            assertThat(dr).as("业务类型 %s 借贷不平", r.getBizType()).isEqualTo(cr);
        }
    }
}
