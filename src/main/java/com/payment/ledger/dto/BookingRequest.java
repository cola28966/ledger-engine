package com.payment.ledger.dto;

import com.payment.ledger.domain.BizType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 记账请求。
 */
@Data
@Builder
public class BookingRequest {

    /**
     * 幂等键。由上游生成，业务唯一。
     * <p>不要直接用 orderNo——一笔订单会有支付、退款、分账多次记账，会互相幂等掉。
     * 正确形态：{@code bizType + "_" + orderNo + "_" + seq}
     */
    private String requestId;

    private BizType bizType;
    private String bizOrderNo;

    /**
     * 会计日期。
     * <p>传 null 表示交给 {@code AccountingCalendar} 裁定为当前会计日；
     * 传了值则必须是未关账的会计日，否则拒绝。
     * <b>引擎内部绝不取 now()。</b>
     */
    private LocalDate accountingDate;

    /**
     * 商户号，用于匹配专属协议费率。
     * <p>为 null 时走该业务类型的默认费率。
     */
    private String merchantId;

    /** 付款方账号（部分业务不需要，如提现成功） */
    private String payerAccount;

    /** 收款方账号 */
    private String payeeAccount;

    /** 交易金额，单位：分 */
    private long amount;

    /** 手续费，单位：分。无手续费的业务传 0 */
    @Builder.Default
    private long fee = 0L;

    private String remark;
}
