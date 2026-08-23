package com.payment.ledger.domain;

/**
 * 手续费取整规则。
 *
 * <p>取整规则必须显式配置、可复现。同一笔交易在任何时候、任何机器上
 * 算出来的手续费必须完全一致，否则对账永远对不平。
 */
public enum FeeRounding {

    /** 四舍五入。最常见 */
    HALF_UP,

    /** 向上取整（进一法）。对平台有利，银行代付类计费常用 */
    UP,

    /** 截断（舍去小数）。对商户有利 */
    DOWN
}
