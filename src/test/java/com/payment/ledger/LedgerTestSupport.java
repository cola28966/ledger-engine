package com.payment.ledger;

import com.payment.ledger.engine.HotAccountRouter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 测试数据重置。
 *
 * <p>所有集成测试的 {@code @BeforeEach} 都应调用 {@link #resetAll}。
 *
 * <p><b>要清的不只是数据，还有全局状态：</b>
 * <ul>
 *   <li>日终结账会把会计日置为 CLOSED，不还原会导致后续测试被拒绝记账</li>
 *   <li>分桶配置同时存在于数据库（{@code bucket_count}）和
 *       {@link HotAccountRouter} 的内存缓存里。任何一个测试开了分桶而不还原，
 *       后续测试就会把账记进一个已被删除的桶——而且因为测试执行顺序不固定，
 *       这种失败是间歇性的，最难排查</li>
 * </ul>
 */
public final class LedgerTestSupport {

    /** 测试统一使用的会计日 */
    public static final LocalDate ACC_DATE = LocalDate.of(2026, 8, 22);

    private LedgerTestSupport() {
    }

    public static void resetAll(JdbcTemplate jdbc, HotAccountRouter router) {
        jdbc.execute("DELETE FROM recon_diff");
        jdbc.execute("DELETE FROM channel_balance");
        jdbc.execute("DELETE FROM channel_statement");
        jdbc.execute("DELETE FROM balance_snapshot");
        jdbc.execute("DELETE FROM account_serial");
        jdbc.execute("DELETE FROM accounting_entry");
        jdbc.execute("DELETE FROM voucher");

        // 分桶状态必须彻底还原：先删桶账户，再清配置，最后刷新路由缓存。
        // 三者缺一，后续测试就会路由到不存在的桶上。
        jdbc.execute("DELETE FROM account WHERE parent_account_no IS NOT NULL");
        jdbc.execute("UPDATE account SET bucket_count = 0");
        router.reload();

        jdbc.execute("UPDATE account SET balance = 0, available_balance = 0, "
                + "frozen_balance = 0, version = 0");

        // 会计日历恢复到初始状态：08-21 已关账，08-22 起开放
        jdbc.execute("DELETE FROM accounting_calendar");
        jdbc.update("INSERT INTO accounting_calendar VALUES (?,?,?,?)",
                java.sql.Date.valueOf(LocalDate.of(2026, 8, 21)), "CLOSED",
                LocalDateTime.now(), LocalDateTime.now());
        for (int i = 22; i <= 26; i++) {
            jdbc.update("INSERT INTO accounting_calendar VALUES (?,?,?,?)",
                    java.sql.Date.valueOf(LocalDate.of(2026, 8, i)), "OPEN",
                    LocalDateTime.now(), null);
        }
    }
}
