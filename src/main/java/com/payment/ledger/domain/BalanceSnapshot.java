package com.payment.ledger.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 日终余额快照。
 *
 * <p>勾稽等式（借方科目）：
 * <pre>期末余额 = 期初余额 + 本期借方发生额 - 本期贷方发生额</pre>
 * 贷方科目方向相反。
 *
 * <p>两个作用：
 * <ol>
 *   <li>日终试算的校验基准——等式不成立就说明账错了</li>
 *   <li>让"查询任意历史日期余额"变成 O(1)，不必回放全部流水</li>
 * </ol>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceSnapshot {

    private LocalDate accountingDate;
    private String accountNo;

    private long openingBalance;
    private long debitAmount;
    private long creditAmount;
    private long closingBalance;
}
