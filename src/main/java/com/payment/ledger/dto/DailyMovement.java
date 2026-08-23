package com.payment.ledger.dto;

/**
 * 某账户在某个会计日的借贷发生额汇总。
 *
 * @param accountNo    账户号
 * @param debitAmount  本期借方发生额（分）
 * @param creditAmount 本期贷方发生额（分）
 */
public record DailyMovement(String accountNo, long debitAmount, long creditAmount) {
}
