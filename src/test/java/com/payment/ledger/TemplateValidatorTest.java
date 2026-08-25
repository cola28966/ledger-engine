package com.payment.ledger;

import com.payment.ledger.batch.TemplateValidator;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.engine.TemplateEngine;
import com.payment.ledger.exception.LedgerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记账模板自检。
 *
 * <p>配置化把校验从编译器手里拿走了，就得自己把它建回来——
 * 这个自检就是那个"建回来"的东西。
 */
@SpringBootTest
class TemplateValidatorTest {

    @Autowired TemplateValidator validator;
    @Autowired TemplateEngine templateEngine;
    @Autowired JdbcTemplate jdbc;

    /** 每个用例都可能改模板表，跑完必须还原 */
    @AfterEach
    void restoreTemplates() {
        jdbc.execute("UPDATE accounting_template SET status = 'ACTIVE'");
        jdbc.update("UPDATE accounting_template SET amount_rule = 'amount - fee' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 2");
        jdbc.update("UPDATE accounting_template SET account_rule = '''FEE_INCOME''' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 3");
        templateEngine.clearCache();
    }

    @Test
    @DisplayName("现有模板配置全部通过自检")
    void allTemplatesValid() {
        assertThat(validator.validateAll()).isEmpty();
    }

    @Test
    @DisplayName("每种业务类型都必须配了模板")
    void everyBizTypeHasTemplate() {
        for (BizType type : BizType.values()) {
            assertThat(templateEngine.templatesOf(type.name()))
                    .as("业务类型 %s 未配置记账模板", type)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("★ 模板配错导致借贷不平衡 → 自检抓出来")
    void unbalancedTemplateIsCaught() {
        // 把商户那条的金额规则从 amount - fee 改成 amount，
        // 于是贷方比借方多出一个 fee
        jdbc.update("UPDATE accounting_template SET amount_rule = 'amount' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 2");
        templateEngine.clearCache();

        List<String> problems = validator.validateAll();

        assertThat(problems).isNotEmpty();
        assertThat(problems).anySatisfy(p -> {
            assertThat(p).contains("CONSUME");
            assertThat(p).contains("借贷不平衡");
        });
    }

    @Test
    @DisplayName("★ 模板整组被停用 → 自检报出该业务类型没有可用模板")
    void missingTemplateIsCaught() {
        jdbc.update("UPDATE accounting_template SET status = 'DISABLED' WHERE biz_type = 'CONSUME'");
        templateEngine.clearCache();

        List<String> problems = validator.validateAll();

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("CONSUME"));
    }

    @Test
    @DisplayName("★ 表达式写错 → 自检报出求值失败，而不是等到真实交易")
    void brokenExpressionIsCaught() {
        // 引用一个 BookingRequest 上不存在的属性
        jdbc.update("UPDATE accounting_template SET account_rule = 'noSuchProperty' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 3");
        templateEngine.clearCache();

        List<String> problems = validator.validateAll();

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("CONSUME"));
    }

    @Test
    @DisplayName("★ 一次报出全部问题，而不是修一个发现一个")
    void reportsAllProblemsAtOnce() {
        jdbc.update("UPDATE accounting_template SET amount_rule = 'amount' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 2");
        jdbc.update("UPDATE accounting_template SET status = 'DISABLED' "
                + "WHERE biz_type = 'TRANSFER'");
        templateEngine.clearCache();

        List<String> problems = validator.validateAll();

        assertThat(problems).hasSizeGreaterThanOrEqualTo(2);
        assertThat(String.join("\n", problems)).contains("CONSUME").contains("TRANSFER");
    }

    @Test
    @DisplayName("同一个问题只报一次，不能重复计数")
    void eachProblemReportedOnce() {
        jdbc.update("UPDATE accounting_template SET status = 'DISABLED' WHERE biz_type = 'CONSUME'");
        templateEngine.clearCache();

        List<String> problems = validator.validateAll();

        long consumeProblems = problems.stream().filter(p -> p.startsWith("CONSUME")).count();
        assertThat(consumeProblems)
                .as("CONSUME 只有「没配模板」这一个问题，报出来也该只有一条，实际报了：%s",
                        problems.stream().filter(p -> p.startsWith("CONSUME")).toList())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("模板缺失的报错要带上是哪个业务类型")
    void missingTemplateErrorNamesBizType() {
        jdbc.update("UPDATE accounting_template SET status = 'DISABLED' WHERE biz_type = 'CONSUME'");
        templateEngine.clearCache();

        assertThatThrownBy(() -> templateEngine.render(validator.sampleRequest(BizType.CONSUME)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("未配置记账模板")
                .hasMessageContaining("CONSUME");
    }

    @Test
    @DisplayName("★ 未配置模板的业务类型，报「未配置记账模板」而不是「借贷为空」")
    void missingTemplateGivesClearError() {
        jdbc.update("UPDATE accounting_template SET status = 'DISABLED' WHERE biz_type = 'CONSUME'");
        templateEngine.clearCache();

        assertThatThrownBy(() -> templateEngine.render(validator.sampleRequest(BizType.CONSUME)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("未配置记账模板");
    }

    @Test
    @DisplayName("★ 安全：模板里的 SpEL 不允许类型引用与方法调用")
    void spelCannotEscapeIntoCodeExecution() {
        // StandardEvaluationContext 下这行是远程代码执行；
        // SimpleEvaluationContext 下必须求值失败
        jdbc.update("UPDATE accounting_template "
                + "SET account_rule = 'T(java.lang.System).getProperty(''user.name'')' "
                + "WHERE biz_type = 'CONSUME' AND entry_seq = 3");
        templateEngine.clearCache();

        assertThatThrownBy(() -> templateEngine.render(validator.sampleRequest(BizType.CONSUME)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("求值失败");
    }
}
