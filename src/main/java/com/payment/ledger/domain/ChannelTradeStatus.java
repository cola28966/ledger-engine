package com.payment.ledger.domain;

/**
 * 渠道对账单上的交易状态。
 *
 * <p>只有两个终态。渠道对账单里<b>不会</b>出现"处理中"——
 * 对账单是 T 日结束后生成的终态快照，这是它能作为核对基准的前提。
 * 在途状态只可能出现在我方。
 */
public enum ChannelTradeStatus {

    /** 渠道侧交易成功，钱已实际发生 */
    SUCCESS,

    /** 渠道侧交易失败，钱未发生。失败的记录也会出现在对账单里 */
    FAIL
}
