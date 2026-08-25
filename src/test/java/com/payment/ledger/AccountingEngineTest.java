package com.payment.ledger;

import com.payment.ledger.domain.*;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.BookingResult;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.AccountRepository;
import com.payment.ledger.repository.EntryRepository;
import com.payment.ledger.repository.SerialRepository;
import com.payment.ledger.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记账引擎集成测试。
 */
@SpringBootTest
class AccountingEngineTest {

    @Autowired AccountingEngine engine;
    @Autowired AccountRepository accountRepo;
    @Autowired VoucherRepository voucherRepo;
    @Autowired EntryRepository entryRepo;
    @Autowired SerialRepository serialRepo;
    @Autowired com.payment.ledger.engine.HotAccountRouter router;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ACC_DATE = LocalDate.of(2026, 8, 22);

    @BeforeEach
    void reset() {
        LedgerTestSupport.resetAll(jdbc, router);
    }

    /** 给账户造余额：走一笔充值 */
    private void recharge(String account, long amount) {
        engine.book(BookingRequest.builder()
                .requestId("RECHARGE_" + account + "_" + System.nanoTime())
                .bizType(BizType.RECHARGE)
                .bizOrderNo("INIT")
                .accountingDate(ACC_DATE)
                .payeeAccount(account)
                .amount(amount)
                .build());
    }

    private long balanceOf(String account) {
        return accountRepo.findByNo(account).getBalance();
    }

    // ================================================================

    @Test
    @DisplayName("充值 100 元：用户余额 +100，备付金存管户 +100（资产负债同增）")
    void recharge_shouldIncreaseBothSides() {
        recharge("U0001", 10000);

        assertThat(balanceOf("U0001")).isEqualTo(10000);
        assertThat(balanceOf(EntryGenerator.BANK_RESERVE)).isEqualTo(10000);
    }

    @Test
    @DisplayName("余额消费 100 元 费率 0.6%：用户 -100，商户 +99.40，手续费 +0.60")
    void consume_shouldSplitCorrectly() {
        recharge("U0001", 10000);

        engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER001_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER001")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")
                .payeeAccount("M0001")
                .amount(10000)
                .fee(60)
                .build());

        assertThat(balanceOf("U0001")).isZero();
        assertThat(balanceOf("M0001")).isEqualTo(9940);
        assertThat(balanceOf(EntryGenerator.FEE_INCOME)).isEqualTo(60);

        // 平台资产（银行存款）没有任何变化 —— 第三方支付的本质是"账上搬钱，实物不动"
        assertThat(balanceOf(EntryGenerator.BANK_RESERVE)).isEqualTo(10000);
    }

    @Test
    @DisplayName("幂等：同一 requestId 记两次，账只记一次")
    void idempotent_sameRequestIdBooksOnce() {
        recharge("U0001", 10000);

        BookingRequest req = BookingRequest.builder()
                .requestId("CONSUME_ORDER001_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER001")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")
                .payeeAccount("M0001")
                .amount(5000)
                .fee(30)
                .build();

        BookingResult first  = engine.book(req);
        BookingResult second = engine.book(req);

        assertThat(first.isIdempotent()).isFalse();
        assertThat(second.isIdempotent()).isTrue();
        // 幂等返回的必须是同一张凭证
        assertThat(second.getVoucherNo()).isEqualTo(first.getVoucherNo());

        // 余额只变了一次
        assertThat(balanceOf("U0001")).isEqualTo(5000);
        assertThat(balanceOf("M0001")).isEqualTo(4970);
    }

    @Test
    @DisplayName("余额不足：整笔拒绝，事务回滚，所有账户余额不变")
    void insufficientBalance_shouldRollbackEverything() {
        recharge("U0001", 5000);
        long bankBefore = balanceOf(EntryGenerator.BANK_RESERVE);

        assertThatThrownBy(() -> engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER002_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER002")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")
                .payeeAccount("M0001")
                .amount(10000)   // 超过余额 5000
                .fee(60)
                .build()))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("余额不足");

        // 事务完整回滚：用户余额没动，商户没收到钱，凭证也没留下
        assertThat(balanceOf("U0001")).isEqualTo(5000);
        assertThat(balanceOf("M0001")).isZero();
        assertThat(balanceOf(EntryGenerator.BANK_RESERVE)).isEqualTo(bankBefore);
        assertThat(voucherRepo.findByRequestId("CONSUME_ORDER002_1")).isNull();
    }

    @Test
    @DisplayName("余额永远不允许为负")
    void balanceNeverGoesNegative() {
        assertThatThrownBy(() -> engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER003_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER003")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")   // 余额为 0
                .payeeAccount("M0001")
                .amount(100)
                .fee(1)                  // 100 × 60bp = 0.6 分，四舍五入为 1 分
                .build()))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("余额不足");

        assertThat(balanceOf("U0001")).isZero();
    }

    @Test
    @DisplayName("账户流水的 balance_after 必须准确记录每笔之后的余额")
    void serialRecordsBalanceAfter() {
        recharge("U0001", 10000);

        engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER004_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER004")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")
                .payeeAccount("M0001")
                .amount(3000)
                .fee(18)
                .build());

        List<AccountSerial> serials = serialRepo.findByAccountNo("U0001");
        assertThat(serials).hasSize(2);

        // 第一笔：充值后余额 10000
        assertThat(serials.get(0).getBalanceBefore()).isZero();
        assertThat(serials.get(0).getBalanceAfter()).isEqualTo(10000);

        // 第二笔：消费后余额 7000
        assertThat(serials.get(1).getBalanceBefore()).isEqualTo(10000);
        assertThat(serials.get(1).getBalanceAfter()).isEqualTo(7000);

        // 链式校验：上一笔的 balanceAfter == 本笔的 balanceBefore
        assertThat(serials.get(1).getBalanceBefore()).isEqualTo(serials.get(0).getBalanceAfter());
    }

    // ================================================================
    //  冲正
    // ================================================================

    @Test
    @DisplayName("冲正：全额镜像反向，余额恢复原状")
    void reverse_shouldRestoreBalance() {
        recharge("U0001", 10000);

        BookingResult origin = engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER005_1")
                .bizType(BizType.CONSUME)
                .bizOrderNo("ORDER005")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001")
                .payeeAccount("M0001")
                .amount(10000)
                .fee(60)
                .build());

        assertThat(balanceOf("U0001")).isZero();

        engine.reverse(origin.getVoucherNo(), "REVERSE_ORDER005_1", ACC_DATE.plusDays(1));

        // 三方余额全部回到记账前
        assertThat(balanceOf("U0001")).isEqualTo(10000);
        assertThat(balanceOf("M0001")).isZero();
        assertThat(balanceOf(EntryGenerator.FEE_INCOME)).isZero();
    }

    @Test
    @DisplayName("冲正：原凭证只打标记不修改，形成可追溯的审计链")
    void reverse_shouldKeepAuditTrail() {
        recharge("U0001", 10000);
        BookingResult origin = engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER006_1")
                .bizType(BizType.CONSUME).bizOrderNo("ORDER006")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(10000).fee(60).build());

        BookingResult rev = engine.reverse(
                origin.getVoucherNo(), "REVERSE_ORDER006_1", ACC_DATE.plusDays(1));

        Voucher originVoucher  = voucherRepo.findByNo(origin.getVoucherNo());
        Voucher reverseVoucher = voucherRepo.findByNo(rev.getVoucherNo());

        // 原凭证：状态改为已冲正，金额纹丝不动
        assertThat(originVoucher.getStatus()).isEqualTo(VoucherStatus.REVERSED);
        assertThat(originVoucher.getTotalAmount()).isEqualTo(10000);
        assertThat(originVoucher.getReversedBy()).isEqualTo(rev.getVoucherNo());

        // 冲正凭证：反向指回原凭证
        assertThat(reverseVoucher.getReverseOf()).isEqualTo(origin.getVoucherNo());

        // 冲正的会计日期是新的会计日，不能记回原凭证的日期
        // （已关账的会计期间禁止追溯修改）
        assertThat(reverseVoucher.getAccountingDate()).isEqualTo(ACC_DATE.plusDays(1));

        // 原分录一条没少、一条没改
        assertThat(entryRepo.findByVoucherNo(origin.getVoucherNo())).hasSize(3);
    }

    @Test
    @DisplayName("冲正是全额镜像，不是差额调整：分录条数与原凭证完全一致，方向全部取反")
    void reverse_isFullMirrorNotDelta() {
        recharge("U0001", 10000);
        BookingResult origin = engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER007_1")
                .bizType(BizType.CONSUME).bizOrderNo("ORDER007")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(10000).fee(60).build());

        BookingResult rev = engine.reverse(
                origin.getVoucherNo(), "REVERSE_ORDER007_1", ACC_DATE);

        List<AccountingEntry> originEntries  = entryRepo.findByVoucherNo(origin.getVoucherNo());
        List<AccountingEntry> reverseEntries = entryRepo.findByVoucherNo(rev.getVoucherNo());

        assertThat(reverseEntries).hasSameSizeAs(originEntries);

        for (int i = 0; i < originEntries.size(); i++) {
            AccountingEntry o = originEntries.get(i);
            AccountingEntry r = reverseEntries.get(i);
            assertThat(r.getAccountNo()).isEqualTo(o.getAccountNo());
            assertThat(r.getAmount()).isEqualTo(o.getAmount());          // 全额，不是差额
            assertThat(r.getDirection()).isEqualTo(o.getDirection().opposite());
        }
    }

    @Test
    @DisplayName("同一张凭证不允许重复冲正")
    void reverse_cannotReverseTwice() {
        recharge("U0001", 10000);
        BookingResult origin = engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER008_1")
                .bizType(BizType.CONSUME).bizOrderNo("ORDER008")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(10000).fee(60).build());

        engine.reverse(origin.getVoucherNo(), "REVERSE_ORDER008_1", ACC_DATE);

        assertThatThrownBy(() -> engine.reverse(
                origin.getVoucherNo(), "REVERSE_ORDER008_2", ACC_DATE))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("已被冲正");
    }

    @Test
    @DisplayName("冲正本身也必须幂等")
    void reverse_isIdempotent() {
        recharge("U0001", 10000);
        BookingResult origin = engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER009_1")
                .bizType(BizType.CONSUME).bizOrderNo("ORDER009")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(10000).fee(60).build());

        BookingResult r1 = engine.reverse(origin.getVoucherNo(), "REVERSE_ORDER009_1", ACC_DATE);
        BookingResult r2 = engine.reverse(origin.getVoucherNo(), "REVERSE_ORDER009_1", ACC_DATE);

        assertThat(r2.isIdempotent()).isTrue();
        assertThat(r2.getVoucherNo()).isEqualTo(r1.getVoucherNo());
        // 只冲正了一次，余额没有被冲两遍
        assertThat(balanceOf("U0001")).isEqualTo(10000);
    }

    // ================================================================
    //  冻结 / 解冻
    // ================================================================

    @Test
    @DisplayName("冻结：balance 不变，available→frozen；只产生流水，不产生会计分录")
    void freeze_noEntryButHasSerial() {
        recharge("U0001", 10000);
        int entriesBefore = countEntries();

        engine.freeze("U0001", 3000, ACC_DATE);

        Account acc = accountRepo.findByNo("U0001");
        assertThat(acc.getBalance()).isEqualTo(10000);            // balance 不变
        assertThat(acc.getAvailableBalance()).isEqualTo(7000);
        assertThat(acc.getFrozenBalance()).isEqualTo(3000);
        assertThat(acc.getBalance())
                .isEqualTo(acc.getAvailableBalance() + acc.getFrozenBalance());

        // 没有新增会计分录
        assertThat(countEntries()).isEqualTo(entriesBefore);

        // 但有账户流水
        List<AccountSerial> serials = serialRepo.findByAccountNo("U0001");
        assertThat(serials).anyMatch(s -> s.getSerialType() == SerialType.FREEZE
                && s.getAmount() == 3000);
    }

    @Test
    @DisplayName("解冻：frozen→available，balance 依然不变")
    void unfreeze() {
        recharge("U0001", 10000);
        engine.freeze("U0001", 3000, ACC_DATE);
        engine.unfreeze("U0001", 3000, ACC_DATE);

        Account acc = accountRepo.findByNo("U0001");
        assertThat(acc.getBalance()).isEqualTo(10000);
        assertThat(acc.getAvailableBalance()).isEqualTo(10000);
        assertThat(acc.getFrozenBalance()).isZero();
    }

    @Test
    @DisplayName("冻结后可用余额不足，无法支付")
    void frozenAmountCannotBeSpent() {
        recharge("U0001", 10000);
        engine.freeze("U0001", 8000, ACC_DATE);

        assertThatThrownBy(() -> engine.book(BookingRequest.builder()
                .requestId("CONSUME_ORDER010_1")
                .bizType(BizType.CONSUME).bizOrderNo("ORDER010")
                .accountingDate(ACC_DATE)
                .payerAccount("U0001").payeeAccount("M0001")
                .amount(5000).fee(30).build()))   // 可用只剩 2000
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("余额不足");
    }

    private int countEntries() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM accounting_entry", Integer.class);
        return c == null ? 0 : c;
    }
}
