package com.payment.ledger.engine;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static com.payment.ledger.dto.EntryCommand.credit;
import static com.payment.ledger.dto.EntryCommand.debit;

/**
 * 分录生成器：把一个业务请求翻译成一组会计分录。
 *
 * <p><b>这个类就是你手写过的那些分录，变成了代码。</b>
 *
 * <p>第一版用 switch 硬编码，目的是让分录规则一目了然。
 * 阶段 3 会改造成配置化的记账模板（模板表 + 表达式引擎），
 * 届时新增业务类型只需插一行配置，不改一行代码——
 * 因为业务类型会无限增长（新支付方式、新营销玩法、新分账模式），
 * 每次都改代码是灾难。
 */
@Component
public class EntryGenerator {

    // ---------- 内部户账号常量 ----------
    /** 备付金存管户（资产） */
    public static final String BANK_RESERVE  = "BANK_RESERVE";
    /** 担保交易中间户（负债） */
    public static final String ESCROW        = "ESCROW";
    /** 提现在途户（负债） */
    public static final String WD_TRANSIT    = "WD_TRANSIT";
    /** 支付手续费收入户（收入） */
    public static final String FEE_INCOME    = "FEE_INCOME";
    /** 提现手续费收入户（收入） */
    public static final String WD_FEE_INCOME = "WD_FEE_INCOME";

    public List<EntryCommand> generate(BookingRequest req) {
        long amount = req.getAmount();
        long fee    = req.getFee();
        String payer = req.getPayerAccount();
        String payee = req.getPayeeAccount();

        if (amount <= 0) {
            throw LedgerException.invalidRequest("交易金额必须大于 0");
        }
        if (fee < 0 || fee > amount) {
            throw LedgerException.invalidRequest("手续费必须在 [0, amount] 区间内");
        }

        BizType bizType = req.getBizType();

        List<EntryCommand> entryCommands = switch (bizType) {
            case RECHARGE -> List.of(
                    EntryCommand.debit(BANK_RESERVE, amount),
                    EntryCommand.credit(payee, amount)
            );

            case CONSUME -> List.of(
                    EntryCommand.debit(payer, amount),
                    EntryCommand.credit(payee, amount - fee),
                    EntryCommand.credit(FEE_INCOME, fee)
            );

            case TRANSFER -> List.of(
                    EntryCommand.debit(payer, amount),
                    EntryCommand.credit(payee, amount)
            );

            case ESCROW_PAY -> List.of(
                    EntryCommand.debit(payer, amount),
                    EntryCommand.credit(ESCROW, amount)
            );

            case ESCROW_CONFIRM -> List.of(
                    EntryCommand.debit(ESCROW, amount),
                    EntryCommand.credit(payee, amount - fee),
                    EntryCommand.credit(FEE_INCOME, fee)
            );

            case ESCROW_REFUND -> List.of(
                    EntryCommand.debit(ESCROW, amount),
                    EntryCommand.credit(payee, amount)
            );

            case WITHDRAW_SUBMIT -> List.of(
                    EntryCommand.debit(payer, amount),
                    EntryCommand.credit(WD_FEE_INCOME, fee),
                    EntryCommand.credit(WD_TRANSIT, amount - fee)
            );

            case WITHDRAW_SUCCESS -> List.of(
                    EntryCommand.debit(WD_TRANSIT, amount),
                    EntryCommand.credit(BANK_RESERVE, amount)
            );

            case REFUND_TO_BALANCE -> List.of(
                    EntryCommand.debit(payer, amount - fee),
                    EntryCommand.debit(FEE_INCOME, fee),
                    EntryCommand.credit(payee, amount)
            );
        };

        entryCommands = entryCommands.stream().filter(entryCommand -> entryCommand.getAmount() > 0).collect(Collectors.toList());
        return entryCommands;
    }
}
