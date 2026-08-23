package com.payment.ledger;

import com.payment.ledger.batch.BucketInitializer;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.repository.AccountRepository;
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
 * 热点账户压测。
 *
 * <p>三组对照，记账量完全相同：
 * <ol>
 *   <li><b>无热点</b>（TRANSFER）—— 账户天然分散，性能上限参考值</li>
 *   <li><b>有热点·不分桶</b>（CONSUME）—— 每笔都抢 M0001 和 FEE_INCOME 两行</li>
 *   <li><b>有热点·分 16 桶</b>（CONSUME）—— 争抢摊开到 32 行</li>
 * </ol>
 *
 * <p>H2 内存库上看不出差别（锁持有时间是微秒级）。
 * 用真实 MySQL 跑：{@code SPRING_PROFILES_ACTIVE=perf mvn test -Dtest=HotAccountBenchmarkTest}
 */
@SpringBootTest
class HotAccountBenchmarkTest {

    @Autowired AccountingEngine engine;
    @Autowired AccountRepository accountRepo;
    @Autowired BucketInitializer bucketInitializer;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate D = LedgerTestSupport.ACC_DATE;

    static final int THREADS = 16;
    static final int PER_THREAD = 60;
    static final int USERS = 200;
    static final int BUCKETS = 16;

    /** 每笔消费金额与手续费（1000 分 × 60bp = 6 分） */
    static final long AMOUNT = 1000;
    static final long FEE = 6;

    @BeforeEach
    void setUp() {
        bucketInitializer.disableBucketing(EntryGenerator.FEE_INCOME);
        bucketInitializer.disableBucketing("M0001");
        LedgerTestSupport.resetAll(jdbc);
        jdbc.execute("DELETE FROM account WHERE parent_account_no IS NOT NULL");
        jdbc.execute("DELETE FROM account WHERE account_no LIKE 'P%'");
        prepareAccounts();
    }

    private void prepareAccounts() {
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            String no = String.format("P%05d", i);
            batch.add(new Object[]{no, "压测用户" + i, "224101", no, "USER", "CNY", "CR",
                    1_000_000L, 1_000_000L, 0L, "NORMAL", false,
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

    private String user(int i) {
        return String.format("P%05d", i % USERS);
    }

    private record Result(String label, long elapsedMs, int ok, int failed) {
        double tps() {
            return ok * 1000.0 / Math.max(elapsedMs, 1);
        }
    }

    private Result runConcurrent(String label, BizType bizType) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        int seq = threadNo * PER_THREAD + i;
                        try {
                            engine.book(BookingRequest.builder()
                                    .requestId(label + "_" + seq)
                                    .bizType(bizType)
                                    .bizOrderNo("ORD_" + seq)
                                    .accountingDate(D)
                                    .payerAccount(user(seq))
                                    .payeeAccount(bizType == BizType.TRANSFER
                                            ? user(seq + USERS / 2) : "M0001")
                                    .amount(AMOUNT)
                                    .fee(bizType == BizType.CONSUME ? FEE : 0)
                                    .build());
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            errors.computeIfAbsent(root.getClass().getSimpleName() + " : "
                                    + String.valueOf(root.getMessage()).lines().findFirst().orElse(""),
                                    k -> new AtomicInteger()).incrementAndGet();
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
        long t0 = System.nanoTime();
        start.countDown();
        done.await(300, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        errors.forEach((msg, cnt) -> System.out.printf("      %d 次 <- %s%n", cnt.get(), msg));
        return new Result(label, elapsedMs, ok.get(), failed.get());
    }

    @Test
    @DisplayName("热点账户三组对照：无热点 / 有热点不分桶 / 有热点分16桶")
    void hotspotBenchmark() throws Exception {
        List<Result> results = new ArrayList<>();

        // ① 无热点基准
        setUp();
        results.add(runConcurrent("无热点-TRANSFER", BizType.TRANSFER));

        // ② 有热点，不分桶
        setUp();
        results.add(runConcurrent("有热点-不分桶", BizType.CONSUME));
        long feeNoBucket = accountRepo.findByNo(EntryGenerator.FEE_INCOME).getBalance();

        // ③ 有热点，开 16 个桶
        setUp();
        bucketInitializer.enableBucketing(EntryGenerator.FEE_INCOME, BUCKETS);
        bucketInitializer.enableBucketing("M0001", BUCKETS);
        results.add(runConcurrent("有热点-分" + BUCKETS + "桶", BizType.CONSUME));

        // 分桶后主户余额为 0，真实余额分散在各桶里
        long feeMain = accountRepo.findByNo(EntryGenerator.FEE_INCOME).getBalance();
        long feeLogical = accountRepo.sumLogicalBalance(EntryGenerator.FEE_INCOME);

        // ---------- 报告 ----------
        System.out.printf("%n%n══════════ 热点账户压测报告 ══════════%n");
        System.out.printf("并发线程 %d，每线程 %d 笔，合计 %d 笔%n%n",
                THREADS, PER_THREAD, THREADS * PER_THREAD);
        System.out.printf("%-20s %10s %8s %8s %10s%n", "场景", "耗时(ms)", "成功", "失败", "TPS");
        System.out.println("─".repeat(62));
        for (Result r : results) {
            System.out.printf("%-20s %10d %8d %8d %10.0f%n",
                    r.label(), r.elapsedMs(), r.ok(), r.failed(), r.tps());
        }
        System.out.println("─".repeat(62));

        Result noBucket = results.get(1);
        Result bucketed = results.get(2);
        System.out.printf("%n分桶带来的提升: %.2fx  (TPS %.0f → %.0f)%n",
                bucketed.tps() / Math.max(noBucket.tps(), 0.001), noBucket.tps(), bucketed.tps());
        System.out.printf("热点相对无热点的差距: 不分桶 %.2fx，分桶后 %.2fx%n",
                results.get(0).tps() / Math.max(noBucket.tps(), 0.001),
                results.get(0).tps() / Math.max(bucketed.tps(), 0.001));
        System.out.printf("%n手续费收入 —— 不分桶时主户余额: %d 分%n", feeNoBucket);
        System.out.printf("手续费收入 —— 分桶后主户余额: %d 分（钱不在主户上）%n", feeMain);
        System.out.printf("手续费收入 —— 分桶后逻辑余额: %d 分（SUM 所有桶）%n", feeLogical);
        System.out.println("═".repeat(38));

        // ---------- 正确性 ----------
        long expectedFee = (long) THREADS * PER_THREAD * FEE;
        assertThat(feeNoBucket).as("不分桶时一分钱都不能丢").isEqualTo(expectedFee);
        assertThat(feeLogical).as("分桶后逻辑余额必须与不分桶时完全一致").isEqualTo(expectedFee);
        assertThat(feeMain).as("分桶后主户不再参与记账").isZero();
    }
}
