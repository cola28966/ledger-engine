package com.payment.ledger.engine;

import com.payment.ledger.domain.Account;
import com.payment.ledger.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热点账户路由。
 *
 * <p><b>问题：</b>手续费收入户、大商户结算户、担保中间户——这些账户是
 * <b>所有交易都要记的</b>。大促时几万 TPS 全打在一行上，InnoDB 行锁把并发
 * 串行化，实测吞吐会掉一个数量级。
 *
 * <p><b>解法：</b>把逻辑主户拆成 N 个子桶，记账时按路由键哈希落到某一桶。
 * 争抢一行变成争抢 N 行，并发度提升 N 倍。
 *
 * <p><b>代价：</b>写变快，读变慢——查逻辑余额要 SUM 所有桶。
 * 所以只对"高频写、低频读"的账户分桶：
 * <ul>
 *   <li>✅ 手续费收入户——没人实时看它</li>
 *   <li>✅ 担保中间户、大商户结算户</li>
 *   <li>❌ 用户余额户——用户要立刻看到余额，绝不能分桶（而且它天然分散）</li>
 * </ul>
 */
@Slf4j
@Component
public class HotAccountRouter {

    /** 桶账号后缀分隔符：FEE_INCOME → FEE_INCOME_B00 */
    public static final String BUCKET_SEPARATOR = "_B";

    private final AccountRepository accountRepo;

    /**
     * 分桶配置缓存：逻辑主户号 → 桶数。
     * <p>记账链路上每条分录都要查一次路由，不能每次都打数据库。
     * 分桶配置变更极少，缓存在内存里，变更时调 {@link #reload()}。
     */
    private final Map<String, Integer> bucketConfig = new ConcurrentHashMap<>();

    public HotAccountRouter(AccountRepository accountRepo) {
        this.accountRepo = accountRepo;
    }

    /** 从数据库重新加载分桶配置 */
    public void reload() {
        Map<String, Integer> fresh = new ConcurrentHashMap<>();
        for (Account a : accountRepo.findBucketedAccounts()) {
            fresh.put(a.getAccountNo(), a.getBucketCount());
        }
        bucketConfig.clear();
        bucketConfig.putAll(fresh);
        log.info("热点账户分桶配置已加载: {}", bucketConfig);
    }

    /** 该账户是否配置了分桶 */
    public boolean isBucketed(String accountNo) {
        Integer n = bucketConfig.get(accountNo);
        return n != null && n > 0;
    }

    public int bucketCountOf(String accountNo) {
        return bucketConfig.getOrDefault(accountNo, 0);
    }

    /** 拼出第 index 个桶的账号，如 (FEE_INCOME, 3) → FEE_INCOME_B03 */
    public String bucketNo(String logicalAccountNo, int index) {
        return logicalAccountNo + BUCKET_SEPARATOR + String.format("%02d", index);
    }

    /**
     * 把逻辑账号路由到具体的桶账号。
     *
     * @param accountNo  逻辑账号。未配置分桶时原样返回
     * @param routingKey 路由键。<b>必须用 requestId</b>——幂等重试时同一请求
     *                   必须落回同一个桶，否则重试会在另一个桶上再记一笔
     * @return 实际参与记账的账号
     */
    public String route(String accountNo, String routingKey) {
        int bucketCount = bucketCountOf(accountNo);
        if(bucketCount <= 0){
            return accountNo;
        }

        return bucketNo(accountNo, Math.floorMod(routingKey.hashCode(), bucketCount));
    }
}
