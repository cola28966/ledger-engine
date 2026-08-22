package com.payment.ledger;

import com.payment.ledger.domain.Account;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.repository.AccountRepository;
import com.payment.ledger.repository.EntryRepository;
import com.payment.ledger.repository.SerialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 勾稽校验 —— 账务系统的"体检报告"。
 *
 * <p>这些不变式是账务系统能自证正确的全部依据。任何一条不成立，
 * 都必须阻断日切并立即告警——带病日切会让错误持续放大，第二天更难定位。
 */
@SpringBootTest
class LedgerInvariantTest {

    @Autowired AccountingEngine engine;
    @Autowired AccountRepository accountRepo;
    @Autowired EntryRepository entryRepo;
    @Autowired SerialRepository serialRepo;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ACC_DATE = LocalDate.of(2026, 8, 22);

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM account_serial");
        jdbc.execute("DELETE FROM accounting_entry");
        jdbc.execute("DELETE FROM voucher");
        jdbc.execute("UPDATE account SET balance = 0, available_balance = 0, frozen_balance = 0, version = 0");
    }

    private void book(String reqId, BizType type, String payer, String payee, long amount, long fee) {
        engine.book(BookingRequest.builder()
                .requestId(reqId).bizType(type).bizOrderNo("ORDER")
                .accountingDate(ACC_DATE)
                .payerAccount(payer).payeeAccount(payee)
                .amount(amount).fee(fee).build());
    }

    /** 客户备付金类负债合计（科目 2241 开头） */
    private long sumClientReserve() {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM account WHERE subject_code LIKE '2241%'",
                Long.class);
        return v == null ? 0 : v;
    }

    private long bankReserve() {
        return accountRepo.findByNo(EntryGenerator.BANK_RESERVE).getBalance();
    }

    // ================================================================
    //  ① 试算平衡：SUM(借方) == SUM(贷方)
    // ================================================================

    @Test
    @DisplayName("① 试算平衡：跑完一批业务后，全部分录的借方合计必须等于贷方合计")
    void trialBalance() {
        book("R1", BizType.RECHARGE,  null,    "U0001", 100000, 0);
        book("R2", BizType.RECHARGE,  null,    "U0002",  50000, 0);
        book("C1", BizType.CONSUME,   "U0001", "M0001",  30000, 180);
        book("T1", BizType.TRANSFER,  "U0002", "U0001",  10000, 0);
        book("E1", BizType.ESCROW_PAY,"U0001", null,     20000, 0);
        book("E2", BizType.ESCROW_CONFIRM, null, "M0001",20000, 120);
        book("W1", BizType.WITHDRAW_SUBMIT, "M0001", null, 10000, 200);
        book("W2", BizType.WITHDRAW_SUCCESS, null, null,   9800, 0);

        long debit  = entryRepo.sumByDirection(ACC_DATE, Direction.DR);
        long credit = entryRepo.sumByDirection(ACC_DATE, Direction.CR);

        assertThat(debit).as("试算平衡失败：借方 %d != 贷方 %d", debit, credit).isEqualTo(credit);
        assertThat(debit).isPositive();
    }

    // ================================================================
    //  ② 账户余额 == 流水累计
    // ================================================================

    @Test
    @DisplayName("② 账账核对：每个账户的余额，必须等于其流水的累计推算值")
    void balanceMatchesSerials() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        book("C1", BizType.CONSUME,  "U0001", "M0001",  30000, 180);
        book("C2", BizType.CONSUME,  "U0001", "M0001",  20000, 120);

        for (Account acc : accountRepo.findAll()) {
            long fromSerial = serialRepo.calcBalanceBySerial(
                    acc.getAccountNo(), acc.getBalanceDirection());
            assertThat(acc.getBalance())
                    .as("账户 %s 余额(%d) 与流水累计(%d) 不一致",
                            acc.getAccountNo(), acc.getBalance(), fromSerial)
                    .isEqualTo(fromSerial);
        }
    }

    // ================================================================
    //  ③ 账户内部一致性：balance == available + frozen
    // ================================================================

    @Test
    @DisplayName("③ 账户内部一致：balance 必须恒等于 available + frozen")
    void balanceEqualsAvailablePlusFrozen() {
        book("R1", BizType.RECHARGE, null, "U0001", 100000, 0);
        engine.freeze("U0001", 30000, ACC_DATE);
        book("C1", BizType.CONSUME, "U0001", "M0001", 20000, 120);
        engine.unfreeze("U0001", 10000, ACC_DATE);

        for (Account acc : accountRepo.findAll()) {
            assertThat(acc.getBalance())
                    .as("账户 %s: balance=%d, available=%d, frozen=%d",
                            acc.getAccountNo(), acc.getBalance(),
                            acc.getAvailableBalance(), acc.getFrozenBalance())
                    .isEqualTo(acc.getAvailableBalance() + acc.getFrozenBalance());
        }
    }

    // ================================================================
    //  ④ 备付金勾稽（监管红线）
    //     SUM(客户备付金类负债) == 备付金存管账户余额
    // ================================================================

    @Test
    @DisplayName("④-a 用户充值：等式两边同增，勾稽不破")
    void reserveInvariant_recharge() {
        book("R1", BizType.RECHARGE, null, "U0001", 100000, 0);

        assertThat(sumClientReserve()).isEqualTo(bankReserve());
        assertThat(bankReserve()).isEqualTo(100000);
    }

    @Test
    @DisplayName("④-b 用户转账：负债内部转移，净额为 0，勾稽不破")
    void reserveInvariant_transfer() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        book("T1", BizType.TRANSFER, "U0001", "U0002",  40000, 0);

        assertThat(sumClientReserve()).isEqualTo(bankReserve());
        // 结构变了：U0001 60000 + U0002 40000，但总额没变
        assertThat(accountRepo.findByNo("U0001").getBalance()).isEqualTo(60000);
        assertThat(accountRepo.findByNo("U0002").getBalance()).isEqualTo(40000);
    }

    @Test
    @DisplayName("④-c 商户提现出款：等式两边同减，勾稽不破")
    void reserveInvariant_withdraw() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        book("C1", BizType.CONSUME,  "U0001", "M0001", 100000, 0);   // 费率为 0，避免干扰
        book("W1", BizType.WITHDRAW_SUBMIT,  "M0001", null, 100000, 0);
        book("W2", BizType.WITHDRAW_SUCCESS, null,    null, 100000, 0);

        assertThat(sumClientReserve()).isEqualTo(bankReserve());
        assertThat(bankReserve()).isZero();
    }

    @Test
    @DisplayName("④-d 平台收手续费：勾稽【会被破坏】，差额恰好等于手续费 —— 这正是必须做手续费划转的原因")
    void reserveInvariant_feeBreaksIt() {
        book("R1", BizType.RECHARGE, null,    "U0001", 100000, 0);
        assertThat(sumClientReserve()).isEqualTo(bankReserve());   // 此刻还平

        book("C1", BizType.CONSUME, "U0001", "M0001", 100000, 600);

        long clientReserve = sumClientReserve();
        long bank = bankReserve();

        // 客户备付金侧减少了 600（手续费转成了平台收入）
        assertThat(clientReserve).isEqualTo(99400);
        // 但备付金存管账户余额纹丝不动 —— 那 600 还实实在在躺在里面
        assertThat(bank).isEqualTo(100000);

        // 等式被破坏，差额恰好是手续费
        long gap = bank - clientReserve;
        assertThat(gap).isEqualTo(600);
        assertThat(gap).isEqualTo(accountRepo.findByNo(EntryGenerator.FEE_INCOME).getBalance());

        // ────────────────────────────────────────────────────────────
        // 结论：平台每收一笔手续费，就在备付金账户里沉淀一笔属于自己的钱。
        // 实务中必须定期做「手续费划转」，把已确认的自有收入从备付金存管账户
        // 划到自有资金账户：
        //     借：银行存款-自有资金账户   600
        //         贷：银行存款-备付金存管户    600
        // 划转后等式重新平衡。
        //
        // 所以更精确的勾稽等式是：
        //     SUM(客户备付金类负债) == 备付金存管户余额 - 已确认未划转的自有收入
        // ────────────────────────────────────────────────────────────
        assertThat(clientReserve)
                .isEqualTo(bank - accountRepo.findByNo(EntryGenerator.FEE_INCOME).getBalance());
    }

    // ================================================================
    //  ⑤ 中间户勾稽：担保中间户余额 == 已付款未确认收货的订单金额之和
    // ================================================================

    @Test
    @DisplayName("⑤ 中间户勾稽：担保中间户余额 == 已付款未确认收货的订单金额之和")
    void escrowMatchesPendingOrders() {
        book("R1", BizType.RECHARGE, null, "U0001", 100000, 0);
        book("R2", BizType.RECHARGE, null, "U0002", 100000, 0);

        book("E1", BizType.ESCROW_PAY, "U0001", null, 30000, 0);
        book("E2", BizType.ESCROW_PAY, "U0002", null, 20000, 0);
        book("E3", BizType.ESCROW_PAY, "U0001", null, 10000, 0);

        // 此刻三笔都未确认收货，中间户余额 = 30000 + 20000 + 10000
        assertThat(accountRepo.findByNo(EntryGenerator.ESCROW).getBalance()).isEqualTo(60000);

        // E1 确认收货 → 钱流出中间户
        book("E1C", BizType.ESCROW_CONFIRM, null, "M0001", 30000, 180);
        assertThat(accountRepo.findByNo(EntryGenerator.ESCROW).getBalance()).isEqualTo(30000);

        // E2 用户退款 → 钱也流出中间户
        book("E2R", BizType.ESCROW_REFUND, null, "U0002", 20000, 0);
        assertThat(accountRepo.findByNo(EntryGenerator.ESCROW).getBalance()).isEqualTo(10000);

        // 只剩 E3 未完结，中间户余额恰好等于它
        // 生产上这个断言应写成：SELECT SUM(amount) FROM trade_order
        //   WHERE status='PAID' AND confirm_time IS NULL AND refund_status='NONE'
        // 定时比对，差一分钱就告警。日终再加一层账龄分析：
        // 停留超过 30 天的资金 100% 是流程 bug。
        assertThat(accountRepo.findByNo(EntryGenerator.ESCROW).getBalance()).isEqualTo(10000);
    }
}
