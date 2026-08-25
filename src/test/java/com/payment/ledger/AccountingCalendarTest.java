package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.engine.AccountingCalendar;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会计日历 —— 会计日期的唯一权威来源。
 */
@SpringBootTest
class AccountingCalendarTest {

    @Autowired AccountingCalendar calendar;
    @Autowired AccountingEngine engine;
    @Autowired VoucherRepository voucherRepo;
    @Autowired com.payment.ledger.engine.HotAccountRouter router;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ACC_DATE = LedgerTestSupport.ACC_DATE;   // 2026-08-22

    @BeforeEach
    void reset() {
        LedgerTestSupport.resetAll(jdbc, router);
    }

    @Test
    @DisplayName("当前会计日 = 日历中最早的 OPEN 日期")
    void currentIsEarliestOpen() {
        assertThat(calendar.current()).isEqualTo(ACC_DATE);
    }

    @Test
    @DisplayName("上游不传会计日期 → 由日历裁定为当前会计日")
    void resolveNullFallsBackToCurrent() {
        assertThat(calendar.resolve(null)).isEqualTo(ACC_DATE);
    }

    @Test
    @DisplayName("传入开放中的会计日 → 原样采用")
    void resolveOpenDate() {
        assertThat(calendar.resolve(ACC_DATE)).isEqualTo(ACC_DATE);
        assertThat(calendar.resolve(ACC_DATE.plusDays(1))).isEqualTo(ACC_DATE.plusDays(1));
    }

    @Test
    @DisplayName("★ 已关账的会计日禁止追溯记账")
    void closedDateIsRejected() {
        assertThatThrownBy(() -> calendar.resolve(LocalDate.of(2026, 8, 21)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("已关账")
                .hasMessageContaining("红冲");
    }

    @Test
    @DisplayName("★ 记账接口拒绝把账记入已关账的会计日")
    void engineRejectsClosedAccountingDate() {
        assertThatThrownBy(() -> engine.book(BookingRequest.builder()
                .requestId("R_CLOSED_1")
                .bizType(BizType.RECHARGE)
                .bizOrderNo("ORDER_CLOSED")
                .accountingDate(LocalDate.of(2026, 8, 21))   // 已关账
                .payeeAccount("U0001")
                .amount(10000)
                .build()))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("已关账");

        // 凭证没有落库
        assertThat(voucherRepo.findByRequestId("R_CLOSED_1")).isNull();
    }

    @Test
    @DisplayName("不传会计日期也能正常记账，凭证上落的是当前会计日")
    void bookWithoutExplicitDate() {
        engine.book(BookingRequest.builder()
                .requestId("R_AUTO_DATE_1")
                .bizType(BizType.RECHARGE)
                .bizOrderNo("ORDER_AUTO")
                .accountingDate(null)          // 交给日历裁定
                .payeeAccount("U0001")
                .amount(10000)
                .build());

        assertThat(voucherRepo.findByRequestId("R_AUTO_DATE_1").getAccountingDate())
                .isEqualTo(ACC_DATE);
    }

    @Test
    @DisplayName("日切中的会计日暂停记账")
    void cuttingDateIsRejected() {
        jdbc.update("UPDATE accounting_calendar SET status = 'CUTTING' WHERE accounting_date = ?",
                java.sql.Date.valueOf(ACC_DATE));

        assertThatThrownBy(() -> calendar.resolve(ACC_DATE))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("正在日切");
    }
}
