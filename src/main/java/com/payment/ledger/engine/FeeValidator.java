package com.payment.ledger.engine;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.FeeRule;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.FeeRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 费率复核 —— 补上借贷平衡校验的盲区。
 *
 * <p><b>为什么需要这道独立防线：</b>
 * 借贷平衡只能保证"分录内部自洽"，无法保证"金额来源正确"。
 * 上游把 0.6% 算成 6%，传来 {@code amount=20000, fee=1200}，
 * 生成的分录是 {@code 20000 = 18800 + 1200}——完美平衡，
 * 平衡校验放行、试算平衡通过、日切正常、报表照出，
 * <b>账天天是平的，商户被多收了十倍手续费</b>，直到商户自己算出来投诉。
 *
 * <p>所以账务侧必须<b>按签约费率独立试算一遍</b>，与上游传入的值比对，
 * 不一致就拒绝记账。这是唯一能拦住"账是平的但钱是错的"那类事故的防线。
 */
@Slf4j
@Component
public class FeeValidator {

    /** 基点分母：1 bp = 万分之一 */
    private static final long BP_DENOMINATOR = 10_000L;

    private static final long BP_HALF_DENOMINATOR = BP_DENOMINATOR / 2;

    private final FeeRuleRepository ruleRepo;

    public FeeValidator(FeeRuleRepository ruleRepo) {
        this.ruleRepo = ruleRepo;
    }

    /**
     * 按签约费率独立算出应收手续费。
     *
     * @param bizType    业务类型
     * @param merchantId 商户号（用于匹配专属协议价，可为 null）
     * @param amount     交易金额（分）
     * @param date       会计日期（费率有生效期，必须按业务发生日取规则）
     */
    public long calculate(BizType bizType, String merchantId, long amount, LocalDate date) {
        FeeRule rule = ruleRepo.findRule(bizType.name(), merchantId, date);
        if (rule == null) {
            throw new LedgerException("FEE_RULE_NOT_FOUND",
                    "未配置计费规则: bizType=" + bizType + ", merchantId=" + merchantId);
        }
        return calculateByRule(rule, amount);
    }

    /**
     * 复核上游传入的手续费。不一致直接拒绝记账。
     *
     * @param claimedFee 上游声称的手续费（分）
     */
    public void verify(BizType bizType, String merchantId, long amount,
                       long claimedFee, LocalDate date) {
        long expected = calculate(bizType, merchantId, amount, date);
        if (expected != claimedFee) {
            log.error("费率复核不通过: bizType={}, merchantId={}, amount={}, 上游传入={}, 账务试算={}",
                    bizType, merchantId, amount, claimedFee, expected);
            throw new LedgerException("FEE_MISMATCH", String.format(
                    "手续费复核不通过：上游传入 %d 分，账务按签约费率试算 %d 分，差额 %d 分",
                    claimedFee, expected, claimedFee - expected));
        }
    }

    /**
     * 按规则计算手续费。
     *
     * <p>计算链：{@code 基点试算 → 取整 → 保底 → 封顶}
     */
    long calculateByRule(FeeRule rule, long amount) {
        int rateBp = rule.getRateBp();
        long numerator = Math.multiplyExact(amount, rateBp);

        long fee =  switch (rule.getRoundingMode()) {
            case DOWN -> numerator / BP_DENOMINATOR;

            case UP -> {
                long quotient = numerator / BP_DENOMINATOR;
                long remainder = numerator % BP_DENOMINATOR;
                yield remainder == 0 ? quotient : quotient + 1;
            }

            case HALF_UP -> (numerator + BP_HALF_DENOMINATOR) / BP_DENOMINATOR;

        };

        if(fee < rule.getMinFee()) {
            fee =  rule.getMinFee();
        }
        if(rule.getMaxFee() != null && fee > rule.getMaxFee()) {
            fee = rule.getMaxFee();
        }

        return fee;
    }
}
