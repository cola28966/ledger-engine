package com.payment.ledger.domain;

/** 会计日状态 */
public enum CalendarStatus {

    /** 开放记账 */
    OPEN,

    /** 日切中。暂停记账，等待在途凭证落地 */
    CUTTING,

    /**
     * 已关账。
     * <p>该日的日终快照已生成、报表已出、对账文件已推送给渠道和商户，
     * <b>禁止任何追溯记账</b>——错账只能在当前会计日红冲。
     */
    CLOSED
}
