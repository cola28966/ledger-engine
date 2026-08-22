package com.payment.ledger.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 记账凭证。一次记账请求 = 一张凭证 = 一组分录。
 *
 * <p><b>{@link #requestId} 上的唯一索引是幂等的核心防线。</b>
 *
 * <p>易错点：requestId 必须由上游生成且业务唯一。用 orderNo 做幂等键是错的——
 * 一笔订单可能有支付、退款、分账多次记账，会互相幂等掉。
 * 正确形态：{@code requestId = bizType + "_" + orderNo + "_" + seq}
 */
@Data
public class Voucher {

    private String voucherNo;

    /** 上游唯一请求号。唯一索引，幂等锚点 */
    private String requestId;

    private BizType bizType;
    private String bizOrderNo;

    /**
     * 会计日期。
     * <p><b>绝不等于 now()</b>——23:59:59 发起的请求可能 00:00:01 才处理完，
     * 用 now() 会导致跨日串账，对账文件直接对不上。
     */
    private LocalDate accountingDate;

    /** 凭证金额（借方合计，等于贷方合计） */
    private long totalAmount;

    private VoucherStatus status;

    /** 本凭证是哪张凭证的冲正凭证 */
    private String reverseOf;

    /** 本凭证被哪张凭证冲正了 */
    private String reversedBy;

    private String remark;
    private LocalDateTime createdAt;
}
