package com.payment.ledger;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.engine.AccountingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发死锁回归测试。
 *
 * <p>场景：{@code A→B} 与 {@code B→A} 双向转账同时发生。
 * 若记账时按分录出现顺序加锁，两个事务的加锁路径相反，必然成环：
 * <pre>
 *   事务1: 锁住 A，等待 B
 *   事务2: 锁住 B，等待 A     → 死锁
 * </pre>
 *
 * <p>修复前实测失败率：H2 约 5%，MySQL 约 25%。
 * 修复后必须为 <b>0</b>。
 */
@SpringBootTest
class DeadlockTest {

    @Autowired AccountingEngine engine;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;

    /** 参与对撞的账户对数。数量越少，撞上的概率越高 */
    static final int PAIRS = 8;
    static final int THREADS = 16;
    static final int PER_THREAD = 40;

    @BeforeEach
    void setUp() {
        LedgerTestSupport.resetAll(jdbc);
        jdbc.execute("DELETE FROM account WHERE account_no LIKE 'DL%'");

        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < PAIRS * 2; i++) {
            String no = String.format("DL%03d", i);
            batch.add(new Object[]{no, "死锁测试户" + i, "224101", no, "USER", "CNY", "CR",
                    10_000_000L, 10_000_000L, 0L, "NORMAL", false,
                    LocalDateTime.now(), LocalDateTime.now()});
        }
        jdbc.batchUpdate("""
                INSERT INTO account (account_no, account_name, subject_code, owner_id, account_type,
                                     currency, balance_direction, balance, available_balance,
                                     frozen_balance, status, allow_negative,
                                     bucket_count, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,?)
                """, batch);
    }

    @Test
    @DisplayName("★ 双向转账高并发对撞，不允许出现任何死锁")
    void bidirectionalTransfersMustNotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger others = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        int seq = threadNo * PER_THREAD + i;
                        int pair = seq % PAIRS;
                        // 偶数线程 A→B，奇数线程 B→A，方向相反才会成环
                        String a = String.format("DL%03d", pair * 2);
                        String b = String.format("DL%03d", pair * 2 + 1);
                        boolean forward = threadNo % 2 == 0;

                        try {
                            engine.book(BookingRequest.builder()
                                    .requestId("DL_" + seq)
                                    .bizType(BizType.TRANSFER)
                                    .bizOrderNo("DLORD_" + seq)
                                    .accountingDate(D)
                                    .payerAccount(forward ? a : b)
                                    .payeeAccount(forward ? b : a)
                                    .amount(100)
                                    .build());
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            String msg = String.valueOf(root.getMessage()).toLowerCase();
                            if (msg.contains("deadlock")) {
                                deadlocks.incrementAndGet();
                            } else {
                                others.incrementAndGet();
                                System.out.println("非死锁失败: " + root);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(300, TimeUnit.SECONDS);
        pool.shutdownNow();

        int total = THREADS * PER_THREAD;
        System.out.printf("%n[死锁测试] 总笔数=%d 成功=%d 死锁=%d 其他失败=%d%n",
                total, ok.get(), deadlocks.get(), others.get());

        assertThat(deadlocks.get())
                .as("按账号固定顺序加锁后，死锁必须为 0")
                .isZero();
        assertThat(ok.get()).isEqualTo(total);

        // 每一对账户的两侧金额必须守恒：总额始终是初始的 2000 万分
        for (int p = 0; p < PAIRS; p++) {
            Long sum = jdbc.queryForObject(
                    "SELECT SUM(balance) FROM account WHERE account_no IN (?,?)",
                    Long.class, String.format("DL%03d", p * 2), String.format("DL%03d", p * 2 + 1));
            assertThat(sum).as("账户对 %d 资金守恒", p).isEqualTo(20_000_000L);
        }
    }
}
