package com.payment.ledger.domain;

/**
 * 借贷方向。
 *
 * <p>注意：借(DR)和贷(CR)本身不表示增加或减少，它们只是"左边"和"右边"。
 * 一笔分录到底让账户余额增加还是减少，取决于该账户的余额方向：
 * <ul>
 *   <li>分录方向 == 账户余额方向 → 余额增加</li>
 *   <li>分录方向 != 账户余额方向 → 余额减少</li>
 * </ul>
 */
public enum Direction {

    /** 借方 Debit（左）。资产、费用类科目的余额方向 */
    DR,

    /** 贷方 Credit（右）。负债、权益、收入类科目的余额方向 */
    CR;

    /** 取相反方向。冲正时把原分录的方向整体翻转即可 */
    public Direction opposite() {
        return this == DR ? CR : DR;
    }
}
