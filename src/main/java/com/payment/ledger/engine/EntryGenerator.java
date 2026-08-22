package com.payment.ledger.engine;

import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import org.springframework.stereotype.Component;

import java.util.List;

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

        // ══════════════════════════════════════════════════════════════
        //  TODO 2 —— 由你实现（验收：EntryGeneratorTest，11 个用例）
        //
        //  把 9 种业务翻译成分录组。这就是你手写过的那些题，现在写成代码。
        //  可用的写法（读起来就是分录本身）：
        //      debit (账号, 金额)   借方
        //      credit(账号, 金额)   贷方
        //  可用变量：amount 交易金额、fee 手续费、payer 付款方、payee 收款方
        //  可用内部户常量：BANK_RESERVE、ESCROW、WD_TRANSIT、FEE_INCOME、WD_FEE_INCOME
        //
        //  ── 九种业务的资金流向（借贷方向自己判断）──────────────
        //   RECHARGE          充值：银行卡的钱进了备付金账户，平台开始欠用户
        //   CONSUME           余额消费：用户→商户，平台抽 fee
        //   TRANSFER          用户转账：免费，负债内部转移
        //   ESCROW_PAY        担保下单：用户→担保中间户
        //   ESCROW_CONFIRM    确认收货：中间户→商户，平台抽 fee
        //   ESCROW_REFUND     担保退款：中间户→用户
        //   WITHDRAW_SUBMIT   提现提交：商户→提现在途户，平台抽 fee（走 WD_FEE_INCOME）
        //                     注意：此刻平台资产还没减少，钱仍在备付金账户里
        //   WITHDRAW_SUCCESS  提现成功：提现在途户→银行存款（资产真正减少的时刻）
        //   REFUND_TO_BALANCE 退款到余额：商户承担本金，平台退还 fee
        //                     注意：这是"两借一贷"，退还的手续费记哪一方？
        //
        //  ── 两个容易漏的点 ──────────────────────────────────
        //   ① fee = 0 时不能产生金额为 0 的分录（账上不留没有业务含义的记录），
        //      最后统一过滤掉 amount <= 0 的即可
        //   ② 每一种业务生成的分录组都必须自平衡，
        //      测试 allGeneratedEntriesAreBalanced 会逐个检查
        //
        //  跑测试：mvn test -Dtest=EntryGeneratorTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 2: 实现分录生成规则");
    }
}
