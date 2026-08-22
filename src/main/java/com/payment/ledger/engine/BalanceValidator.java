package com.payment.ledger.engine;

import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 借贷平衡校验器。
 *
 * <p>两条铁律的代码化：
 * <ol>
 *   <li>有借必有贷 —— 一组分录里借贷双方都必须出现</li>
 *   <li>借贷必相等 —— 借方合计必须等于贷方合计</li>
 * </ol>
 *
 * <p><b>但要清醒认识它的盲区</b>：平衡校验只能保证"分录内部自洽"，
 * 无法保证"金额来源正确"。费率算错十倍时，分录照样是平的，校验照样放行。
 * 所以生产系统还需要一道独立的费率复核防线（见 {@code FeeValidator}，阶段 2 加）。
 */
@Component
public class BalanceValidator {

    /**
     * 校验一组分录是否借贷平衡。
     *
     * @param entries 待落库的分录组
     * @return 凭证金额（= 借方合计 = 贷方合计），单位：分
     * @throws LedgerException 不平衡、金额非正、分录不足两条时抛出
     */
    public long validate(List<EntryCommand> entries) {
        // ══════════════════════════════════════════════════════════════
        //  TODO 1 —— 由你实现（验收：BalanceValidatorTest，8 个用例）
        //
        //  要求：
        //   a) 分录组为空或少于 2 条 → 抛 LedgerException.invalidRequest
        //      提示信息需包含"有借必有贷"以外的说明即可
        //   b) 任何一条分录金额 <= 0 → 抛 invalidRequest，
        //      提示信息必须包含"必须为正数"
        //      （金额永远为正，方向由 direction 表达）
        //   c) 只有借方或只有贷方 → 抛 invalidRequest，
        //      提示信息必须包含"有借必有贷"
        //   d) 借方合计 != 贷方合计 → 抛 LedgerException.unbalanced(debit, credit)
        //   e) 校验通过 → 返回借方合计（即凭证金额）
        //
        //  跑测试：mvn test -Dtest=BalanceValidatorTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 1: 实现借贷平衡校验");
    }
}
