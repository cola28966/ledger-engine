package com.payment.ledger.engine;

import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

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
        if(CollectionUtils.isEmpty(entries)) {
            throw  LedgerException.invalidRequest("借贷为空");
        }
        if(entries.size() < 2) {
            throw  LedgerException.invalidRequest("借贷缺失");
        }

        boolean matchAmount = entries.stream().anyMatch(entryCommand -> entryCommand.getAmount() <= 0);
        if(matchAmount) {
            throw  LedgerException.invalidRequest("必须为正数");
        }

        int crCnt = 0;
        int drCnt = 0;
        for (EntryCommand entry : entries) {
            if(entry.getDirection() == Direction.CR) {
                crCnt++;
            }else {
                drCnt++;
            }
        }
        if(crCnt == 0 || drCnt == 0){
            throw  LedgerException.invalidRequest("有借必有贷");
        }

        long credit = 0;
        long debit = 0;
        for (EntryCommand entry : entries) {
            if(entry.getDirection() == Direction.CR) {
                credit += entry.getAmount();
            }else {
                debit += entry.getAmount();
            }
        }

        if(credit != debit){
            throw LedgerException.unbalanced(debit, credit);
        }

        return credit;
    }
}
