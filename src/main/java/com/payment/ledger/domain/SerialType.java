package com.payment.ledger.domain;

/** 账户流水类型 */
public enum SerialType {

    /** 记账：balance 发生变化，同时产生会计分录 */
    BOOKING,

    /** 冻结：balance 不变，available→frozen。只有流水，没有会计分录 */
    FREEZE,

    /** 解冻：balance 不变，frozen→available。只有流水，没有会计分录 */
    UNFREEZE
}
