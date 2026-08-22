package com.payment.ledger.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 记账结果。
 *
 * <p>{@link #idempotent} 为 true 表示这次调用命中了幂等——
 * 请求被识别为重复，直接返回了原凭证号，没有产生新的记账。
 * 上游重试时拿到的仍是成功，且账只记了一次。
 */
@Data
@AllArgsConstructor
public class BookingResult {

    private String voucherNo;

    /** 是否幂等命中（true = 本次没有真正记账，返回的是历史结果） */
    private boolean idempotent;

    public static BookingResult success(String voucherNo) {
        return new BookingResult(voucherNo, false);
    }

    public static BookingResult idempotentHit(String voucherNo) {
        return new BookingResult(voucherNo, true);
    }
}
