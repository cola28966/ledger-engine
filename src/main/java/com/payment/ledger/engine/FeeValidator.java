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
        // ══════════════════════════════════════════════════════════════
        //  TODO 7 —— 由你实现（验收：FeeValidatorTest，14 个用例）
        //
        //  计算链：基点试算 → 取整 → 保底 → 封顶
        //
        //  a) 基点试算：fee = amount × rateBp ÷ 10000
        //     全程整数运算，一个浮点都不许出现。
        //     用 Math.multiplyExact 做乘法 —— 溢出时抛异常，而不是像 `*`
        //     那样静默回绕（还记得 int debit 那个 bug 吗，同一个道理）。
        //
        //  b) 取整，按 rule.getRoundingMode()：
        //       DOWN    截断      → 直接整除
        //       UP      向上取整  → 有余数就进一
        //       HALF_UP 四舍五入  → 想想加上"半个分母"再整除会发生什么
        //     提示：这三种都能用纯整数加减和整除表达，不需要 Math.round，
        //           更不需要转成 double。
        //
        //  c) 保底：算出来低于 rule.getMinFee() 时，取保底额
        //  d) 封顶：rule.getMaxFee() 不为 null 且超过它时，压到封顶额
        //
        //  ── 顺序陷阱 ──────────────────────────────────────────
        //   先保底再封顶，顺序不能反。想想 minFee=100、maxFee=50 这种
        //   矛盾配置下，两种顺序会得到不同结果——虽然是脏配置，但系统
        //   的行为必须是确定的、可复现的。
        //
        //  ── 为什么费率要用基点(bp)存整数 ─────────────────────
        //   0.6% 若用 double 存 0.006，这个值在二进制里本身就不精确，
        //   乘出来的手续费会带上误差，日积月累对账必然出现分位差。
        //   基点是整数：0.6% = 60 bp，全程精确。
        //
        //  跑测试：mvn test -Dtest=FeeValidatorTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 7: 实现手续费计算");
    }
}
