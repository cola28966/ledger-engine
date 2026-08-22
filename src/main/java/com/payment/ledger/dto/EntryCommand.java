package com.payment.ledger.dto;

import com.payment.ledger.domain.Direction;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一条待记分录（尚未落库）。
 *
 * <p>配合静态工厂 {@link #debit} / {@link #credit}，生成分录的代码读起来就是分录本身：
 * <pre>{@code
 * // 借：客户备付金-用户A     100.00
 * //     贷：客户备付金-商户M      99.40
 * //     贷：手续费收入             0.60
 * List.of(
 *     debit (payerAccount, amount),
 *     credit(payeeAccount, amount - fee),
 *     credit(FEE_INCOME,   fee)
 * );
 * }</pre>
 */
@Data
@AllArgsConstructor
public class EntryCommand {

    private String accountNo;
    private Direction direction;

    /** 单位：分。永远为正数，方向由 direction 表达 */
    private long amount;

    /** 借方 */
    public static EntryCommand debit(String accountNo, long amount) {
        return new EntryCommand(accountNo, Direction.DR, amount);
    }

    /** 贷方 */
    public static EntryCommand credit(String accountNo, long amount) {
        return new EntryCommand(accountNo, Direction.CR, amount);
    }
}
