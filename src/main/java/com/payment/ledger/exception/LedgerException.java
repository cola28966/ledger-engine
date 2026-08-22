package com.payment.ledger.exception;

import lombok.Getter;

/**
 * 账务异常。所有记账失败都抛这个，携带明确的错误码。
 */
@Getter
public class LedgerException extends RuntimeException {

    private final String errorCode;

    public LedgerException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    // ---------- 常用错误码 ----------

    /** 借贷不平衡——一组分录内借方合计 != 贷方合计 */
    public static LedgerException unbalanced(long debit, long credit) {
        return new LedgerException("UNBALANCED",
                String.format("借贷不平衡：借方合计=%d, 贷方合计=%d, 差额=%d", debit, credit, debit - credit));
    }

    /** 余额不足 */
    public static LedgerException insufficientBalance(String accountNo) {
        return new LedgerException("INSUFFICIENT_BALANCE",
                "余额不足或账户状态异常：" + accountNo);
    }

    /** 账户不存在 */
    public static LedgerException accountNotFound(String accountNo) {
        return new LedgerException("ACCOUNT_NOT_FOUND", "账户不存在：" + accountNo);
    }

    /** 凭证不存在 */
    public static LedgerException voucherNotFound(String voucherNo) {
        return new LedgerException("VOUCHER_NOT_FOUND", "凭证不存在：" + voucherNo);
    }

    /** 凭证已被冲正，不允许重复冲正 */
    public static LedgerException alreadyReversed(String voucherNo) {
        return new LedgerException("ALREADY_REVERSED", "凭证已被冲正：" + voucherNo);
    }

    /** 非法的记账请求 */
    public static LedgerException invalidRequest(String message) {
        return new LedgerException("INVALID_REQUEST", message);
    }
}
