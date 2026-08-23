package com.payment.ledger;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

/**
 * 测试数据重置。
 *
 * <p>日终结账会把会计日置为 CLOSED，若不重置会污染后续测试
 * （被 {@code AccountingCalendar} 拒绝记账）。所有集成测试的
 * {@code @BeforeEach} 都应调用 {@link #resetAll}。
 */
public final class LedgerTestSupport {

    /** 测试统一使用的会计日 */
    public static final LocalDate ACC_DATE = LocalDate.of(2026, 8, 22);

    private LedgerTestSupport() {
    }

    public static void resetAll(JdbcTemplate jdbc) {
        jdbc.execute("DELETE FROM balance_snapshot");
        jdbc.execute("DELETE FROM account_serial");
        jdbc.execute("DELETE FROM accounting_entry");
        jdbc.execute("DELETE FROM voucher");
        jdbc.execute("UPDATE account SET balance = 0, available_balance = 0, "
                + "frozen_balance = 0, version = 0");

        // 会计日历恢复到初始状态：08-21 已关账，08-22 起开放
        jdbc.execute("DELETE FROM accounting_calendar");
        jdbc.update("INSERT INTO accounting_calendar VALUES (?,?,?,?)",
                java.sql.Date.valueOf(LocalDate.of(2026, 8, 21)), "CLOSED",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        for (int i = 22; i <= 26; i++) {
            jdbc.update("INSERT INTO accounting_calendar VALUES (?,?,?,?)",
                    java.sql.Date.valueOf(LocalDate.of(2026, 8, i)), "OPEN",
                    java.time.LocalDateTime.now(), null);
        }
    }
}
