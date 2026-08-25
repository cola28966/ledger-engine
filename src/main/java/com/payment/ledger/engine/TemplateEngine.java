package com.payment.ledger.engine;

import com.payment.ledger.domain.AccountingTemplate;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.TemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记账模板引擎：按配置把业务请求渲染成分录组。
 *
 * <p>取代原先 {@code EntryGenerator} 里那个 switch。业务类型会无限增长
 * （新支付方式、新营销玩法、新分账模式），每加一种就改代码发版是灾难。
 * 配置化之后，新增业务只需往 {@code accounting_template} 插几行。
 *
 * <h3>关于表达式求值的安全性</h3>
 * 这里用的是 {@link SimpleEvaluationContext#forReadOnlyDataBinding()}，
 * <b>而不是 StandardEvaluationContext</b>。这个选择是刻意的：
 * <pre>
 *   StandardEvaluationContext 允许 SpEL 的完整能力，包括类型引用与方法调用。
 *   一旦模板配置被篡改（拿到数据库写权限、或配置管理后台越权），
 *   一行 T(java.lang.Runtime).getRuntime().exec('...') 就是远程代码执行。
 * </pre>
 * SimpleEvaluationContext 只开放属性读取与基本运算，关掉了 {@code T()}、
 * bean 引用和方法调用——足够表达 {@code amount - fee}，但拿不到任何危险入口。
 *
 * <p><b>配置化把"改代码"的成本降下来了，同时把配置变成了新的攻击面。</b>
 * 凡是把可执行表达式放进数据库的设计，都必须先想清楚这一层。
 */
@Slf4j
@Component
public class TemplateEngine {

    private final TemplateRepository templateRepo;

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 表达式编译缓存。
     * <p>SpEL 解析是字符串处理，开销远大于求值本身。记账链路上每笔都要渲染，
     * 必须缓存编译结果，否则配置化换来的灵活性会以吞吐为代价。
     */
    private final Map<String, Expression> compiled = new ConcurrentHashMap<>();

    /** 只读求值上下文：不允许方法调用与类型引用 */
    private final EvaluationContext readOnlyContext =
            SimpleEvaluationContext.forReadOnlyDataBinding().build();

    public TemplateEngine(TemplateRepository templateRepo) {
        this.templateRepo = templateRepo;
    }

    /**
     * 按模板渲染出分录组。
     *
     * @param req 记账请求，同时作为表达式求值的根对象
     * @return 分录组，已滤掉金额为 0 的条目
     */
    public List<EntryCommand> render(BookingRequest req) {
        if (req.getAmount() <= 0) {
            throw LedgerException.invalidRequest("交易金额必须大于 0");
        }
        if (req.getFee() < 0 || req.getFee() > req.getAmount()) {
            throw LedgerException.invalidRequest("手续费必须在 [0, amount] 区间内");
        }

        List<AccountingTemplate> accountingTemplateList = templateRepo.findByBizType(req.getBizType().name());
        if (CollectionUtils.isEmpty(accountingTemplateList)) {
            throw LedgerException.templateNotFound("未配置记账模板: " + req.getBizType());
        }

        List<EntryCommand> entryCommandList = new ArrayList<>(accountingTemplateList.size());
        for (AccountingTemplate accountingTemplate : accountingTemplateList) {
            String accNo = evalString(accountingTemplate.getAccountRule(), req);
            long amount = evalAmount(accountingTemplate.getAmountRule(), req);
            Direction direction = accountingTemplate.getDirection();
            entryCommandList.add(new EntryCommand(accNo, direction, amount));
        }

        entryCommandList = entryCommandList.stream().filter(entryCommand -> entryCommand.getAmount() > 0).collect(Collectors.toList());

        if (entryCommandList.isEmpty()) {
            throw LedgerException.templateRenderEmpty("模板渲染结果为空（模板已配置，但所有分录金额均为 0）: " + req.getBizType());
        }
        return entryCommandList;
    }

    /** 求值出账号 */
    String evalString(String rule, BookingRequest req) {
        try {
            String value = compile(rule).getValue(readOnlyContext, req, String.class);
            if (value == null || value.isBlank()) {
                throw new LedgerException("TEMPLATE_EVAL_NULL",
                        "账户规则求值为空: " + rule);
            }
            return value;
        } catch (LedgerException e) {
            throw e;
        } catch (Exception e) {
            throw new LedgerException("TEMPLATE_EVAL_ERROR",
                    "账户规则求值失败: " + rule + " —— " + e.getMessage());
        }
    }

    /** 求值出金额（分） */
    long evalAmount(String rule, BookingRequest req) {
        try {
            Long value = compile(rule).getValue(readOnlyContext, req, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            throw new LedgerException("TEMPLATE_EVAL_ERROR",
                    "金额规则求值失败: " + rule + " —— " + e.getMessage());
        }
    }

    private Expression compile(String rule) {
        return compiled.computeIfAbsent(rule, parser::parseExpression);
    }

    /** 配置变更后清空编译缓存 */
    public void clearCache() {
        compiled.clear();
    }

    /** 供模板自检使用 */
    public List<AccountingTemplate> templatesOf(String bizType) {
        return templateRepo.findByBizType(bizType);
    }

    /** 渲染但不过滤零金额，自检时用于观察完整的模板输出 */
    List<EntryCommand> renderRaw(BookingRequest req) {
        List<EntryCommand> out = new ArrayList<>();
        for (AccountingTemplate t : templateRepo.findByBizType(req.getBizType().name())) {
            out.add(new EntryCommand(evalString(t.getAccountRule(), req),
                    t.getDirection(), evalAmount(t.getAmountRule(), req)));
        }
        return out;
    }
}
