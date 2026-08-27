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
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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
        List<String> problems = validateAll();;
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
        List<String> errorList = new ArrayList<>();
        for (BizType type : BizType.values()) {
            String errorMsg = null;
            BookingRequest sample = sampleRequest(type);
            List<EntryCommand> entryCommands = null;
            try{
                entryCommands = templateEngine.render(sample);
            }catch (LedgerException ledgerException){
                errorMsg = type + ": " + ledgerException.getMessage();
            }


            if(!StringUtils.hasText(errorMsg)){
                if(CollectionUtils.isEmpty(entryCommands)) {
                    errorMsg = type + ": " + "该业务类型没有可用模板";
                }
                else {
                    long debit = TemplateValidator.sumOf(entryCommands, Direction.DR);
                    long credit = TemplateValidator.sumOf(entryCommands, Direction.CR);
                    if(debit != credit) {
                        errorMsg = String.format(
                                "%s: 借贷不平衡，借方 %d != 贷方 %d",
                                type, debit, credit);
                    }
                }
            }

            if(StringUtils.hasText(errorMsg)){
                errorList.add(errorMsg);
            }
        }
        return errorList;
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
