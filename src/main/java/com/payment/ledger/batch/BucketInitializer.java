package com.payment.ledger.batch;

import com.payment.ledger.domain.Account;
import com.payment.ledger.engine.HotAccountRouter;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 分桶账户初始化。
 *
 * <p>应用启动时，为每个配置了 {@code bucket_count > 0} 的逻辑主户
 * 补齐它的子桶账户，并加载路由配置。
 */
@Slf4j
@Component
public class BucketInitializer implements ApplicationRunner {

    private final AccountRepository accountRepo;
    private final HotAccountRouter router;

    public BucketInitializer(AccountRepository accountRepo, HotAccountRouter router) {
        this.accountRepo = accountRepo;
        this.router = router;
    }

    @Override
    public void run(ApplicationArguments args) {
        initAll();
    }

    /** 为所有已配置分桶的主户补齐子桶，并刷新路由缓存 */
    public void initAll() {
        for (Account parent : accountRepo.findBucketedAccounts()) {
            createBuckets(parent);
        }
        router.reload();
    }

    /**
     * 运行时开启某个账户的分桶。
     *
     * <p><b>只能在该账户余额为 0 时开启。</b>否则主户上已有的余额会被"锁死"——
     * 新记账全部走桶，主户余额再也不会变动，而逻辑余额查询虽然能算对
     * （主户 + 所有桶），但对账、快照、迁移都会变得含糊不清。
     * 生产环境给存量热点账户开分桶，必须先做一次余额迁移。
     *
     * @param logicalAccountNo 逻辑主户账号
     * @param bucketCount      桶数
     */
    public void enableBucketing(String logicalAccountNo, int bucketCount) {
        if (bucketCount <= 1) {
            throw LedgerException.invalidRequest("桶数必须大于 1：" + bucketCount);
        }
        Account parent = accountRepo.findByNo(logicalAccountNo);
        if (parent == null) {
            throw LedgerException.accountNotFound(logicalAccountNo);
        }
        if (parent.getBalance() != 0) {
            throw new LedgerException("BUCKETING_NONZERO_BALANCE",
                    "账户 " + logicalAccountNo + " 余额非 0，开启分桶前必须先迁移余额");
        }
        accountRepo.updateBucketCount(logicalAccountNo, bucketCount);

        parent.setBucketCount(bucketCount);
        createBuckets(parent);
        router.reload();
        log.info("已为 {} 开启分桶，桶数 {}", logicalAccountNo, bucketCount);
    }

    /** 关闭分桶（仅供测试与回滚使用） */
    public void disableBucketing(String logicalAccountNo) {
        accountRepo.updateBucketCount(logicalAccountNo, 0);
        router.reload();
    }

    private void createBuckets(Account parent) {
        for (int i = 0; i < parent.getBucketCount(); i++) {
            accountRepo.createBucketIfAbsent(parent, router.bucketNo(parent.getAccountNo(), i));
        }
    }
}
