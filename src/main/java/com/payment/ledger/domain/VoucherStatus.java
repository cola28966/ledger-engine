package com.payment.ledger.domain;

/** 记账凭证状态 */
public enum VoucherStatus {

    /** 记账中。用于识别"记账崩在中间"的凭证——日终试算不平时的第一排查对象 */
    PROCESSING,

    /** 记账成功 */
    SUCCESS,

    /** 已被冲正。原凭证不删除、不修改，只打标记 */
    REVERSED
}
