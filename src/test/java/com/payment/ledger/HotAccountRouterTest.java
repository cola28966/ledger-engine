package com.payment.ledger;

import com.payment.ledger.batch.BucketInitializer;
import com.payment.ledger.engine.EntryGenerator;
import com.payment.ledger.engine.HotAccountRouter;
import com.payment.ledger.exception.LedgerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 热点账户路由。
 */
@SpringBootTest
class HotAccountRouterTest {

    @Autowired HotAccountRouter router;
    @Autowired BucketInitializer bucketInitializer;
    @Autowired JdbcTemplate jdbc;

    static final String FEE = EntryGenerator.FEE_INCOME;

    @BeforeEach
    void setUp() {
        LedgerTestSupport.resetAll(jdbc, router);
        jdbc.execute("DELETE FROM account WHERE parent_account_no IS NOT NULL");
        bucketInitializer.disableBucketing(FEE);
    }

    @AfterEach
    void tearDown() {
        bucketInitializer.disableBucketing(FEE);
        jdbc.execute("DELETE FROM account WHERE parent_account_no IS NOT NULL");
    }

    @Test
    @DisplayName("未配置分桶的账户，路由后原样返回")
    void notBucketedReturnsAsIs() {
        assertThat(router.route("U0001", "REQ_1")).isEqualTo("U0001");
        assertThat(router.route(FEE, "REQ_1")).isEqualTo(FEE);
    }

    @Test
    @DisplayName("配置分桶后，路由到形如 FEE_INCOME_B07 的桶账号")
    void routesToBucket() {
        bucketInitializer.enableBucketing(FEE, 16);

        String routed = router.route(FEE, "REQ_1");
        assertThat(routed).startsWith(FEE + "_B");
        assertThat(routed).matches(FEE + "_B\\d{2}");
    }

    @Test
    @DisplayName("★ 路由是纯函数：同一 requestId 永远落到同一个桶（幂等重试的前提）")
    void routingIsDeterministic() {
        bucketInitializer.enableBucketing(FEE, 16);

        String first = router.route(FEE, "CONSUME_ORDER001_1");
        for (int i = 0; i < 100; i++) {
            assertThat(router.route(FEE, "CONSUME_ORDER001_1")).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("★ 路由键哈希为负数时，不能拼出 _B-5 这种非法账号")
    void negativeHashCodeHandled() {
        bucketInitializer.enableBucketing(FEE, 16);

        // 找几个 hashCode 为负数的字符串
        int negatives = 0;
        for (int i = 0; i < 500 && negatives < 20; i++) {
            String key = UUID.randomUUID().toString();
            if (key.hashCode() >= 0) {
                continue;
            }
            negatives++;
            assertThat(router.route(FEE, key))
                    .as("负哈希路由键 %s (hash=%d) 不能产生非法桶号", key, key.hashCode())
                    .matches(FEE + "_B\\d{2}");
        }
        assertThat(negatives).as("应当采样到负哈希的键").isPositive();
    }

    @Test
    @DisplayName("分桶应当分散：1000 个随机键应覆盖到多个桶")
    void routingSpreadsAcrossBuckets() {
        bucketInitializer.enableBucketing(FEE, 16);

        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            hit.add(router.route(FEE, "REQ_" + UUID.randomUUID()));
        }
        // 不要求完美均匀，但至少要用上大部分桶
        assertThat(hit).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("开启分桶会自动建出全部子桶账户")
    void bucketsAreCreated() {
        bucketInitializer.enableBucketing(FEE, 16);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE parent_account_no = ?", Integer.class, FEE);
        assertThat(count).isEqualTo(16);
    }

    @Test
    @DisplayName("★ 余额非 0 的账户禁止开启分桶（必须先迁移余额）")
    void cannotEnableBucketingWithNonZeroBalance() {
        jdbc.update("UPDATE account SET balance = 500 WHERE account_no = ?", FEE);

        assertThatThrownBy(() -> bucketInitializer.enableBucketing(FEE, 16))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("余额非 0");
    }
}
