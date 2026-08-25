package com.payment.ledger.batch;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.engine.TemplateEngine;
import com.payment.ledger.exception.LedgerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 记账模板自检。
 *
 * <p><b>配置化把"改代码"的成本降下来了，代价是把错误从编译期挪到了运行期。</b>
 * 原先漏写一个 case，编译不过；现在漏配一行模板，要等到第一笔真实交易才发现。
 *
 * <p>所以必须补一道自检：启动时用样本数据把每种业务类型都渲染一遍，
 * 校验模板存在、表达式可求值、借贷自平衡。<b>任何一项不过就阻止应用启动</b>——
 * 带着错误的模板上线，等于把配置错误直接兑现成资损。
 */
@Slf4j
@Component
@Order(100)   // 在 BucketInitializer 之后执行
public class TemplateValidator implements ApplicationRunner {

    /** 自检用的样本金额与手续费 */
    private static final long SAMPLE_AMOUNT = 10_000L;
    private static final long SAMPLE_FEE = 60L;

    private final TemplateEngine templateEngine;

    public TemplateValidator(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems;
        try {
            problems = validateAll();
        } catch (UnsupportedOperationException todoNotDone) {
            // TODO 14 尚未实现时跳过自检，以免应用无法启动、其余测试全部受阻。
            // 实现完成后这个分支就不会再走到。
            log.warn("模板自检尚未实现（TODO 14），本次跳过");
            return;
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "记账模板自检未通过，拒绝启动：\n  " + String.join("\n  ", problems));
        }
        log.info("记账模板自检通过：{} 种业务类型", BizType.values().length);
    }

    /**
     * 逐个业务类型校验模板。
     *
     * @return 问题描述列表；全部通过时为空
     */
    public List<String> validateAll() {
        // ══════════════════════════════════════════════════════════════
        //  TODO 14 —— 由你实现（验收：TemplateValidatorTest）
        //
        //  遍历 BizType.values()，对每一种：
        //
        //   a) 用 sampleRequest(type) 造一个样本请求
        //
        //   b) 调 templateEngine.render(sample) 渲染。
        //      抛异常就记一条问题（用 e.getMessage()），continue 到下一个，
        //      不要让一个坏模板中断整轮检查 —— 一次性报出全部问题，
        //      比修一个发现一个高效得多
        //
        //   c) 渲染结果为空 → 记问题（该业务类型没有可用模板）
        //
        //   d) 校验借贷平衡：分别累加 DR 与 CR 的金额，不等则记一条问题，
        //      带上业务类型、借方合计、贷方合计
        //
        //  问题描述建议格式（测试断言里会检查业务类型名出现在文案中）：
        //      "CONSUME: 借贷不平衡，借方 10000 != 贷方 9940"
        //
        //  ── 为什么值得单独做这一层 ───────────────────────────
        //   BalanceValidator 已经会在记账时拦下不平衡的分录了，
        //   但那时错误已经发生在一笔真实交易上：用户看到失败、
        //   客服接到投诉、值班的人半夜被叫起来。
        //   模板自检把同一个错误提前到"启动那一刻"，代价是零。
        //
        //   这就是配置化必须配套的东西：
        //   <b>你把校验从编译器手里拿走了，就得自己把它建回来。</b>
        //
        //  跑测试：mvn test -Dtest=TemplateValidatorTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 14: 实现模板自检");
    }

    /** 造一个用于自检的样本请求 */
    public BookingRequest sampleRequest(BizType type) {
        return BookingRequest.builder()
                .requestId("TEMPLATE_SELF_CHECK")
                .bizType(type)
                .bizOrderNo("SELF_CHECK")
                .accountingDate(LocalDate.now())
                .payerAccount("__SAMPLE_PAYER__")
                .payeeAccount("__SAMPLE_PAYEE__")
                .amount(SAMPLE_AMOUNT)
                .fee(SAMPLE_FEE)
                .build();
    }

    /** 累加某一方向的金额 */
    static long sumOf(List<EntryCommand> entries, Direction direction) {
        long sum = 0L;
        for (EntryCommand e : entries) {
            if (e.getDirection() == direction) {
                sum += e.getAmount();
            }
        }
        return sum;
    }
}
