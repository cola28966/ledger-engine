package com.payment.ledger.domain;

/**
 * 会计科目类型。决定了该科目的余额方向。
 *
 * <p>记忆锚点：
 * <ul>
 *   <li><b>资产 / 费用</b> → 借方增加（"我有的东西"和"我花的钱"在左边）</li>
 *   <li><b>负债 / 权益 / 收入</b> → 贷方增加（"我欠的"和"我赚的"在右边）</li>
 * </ul>
 */
public enum SubjectType {

    /** 资产：银行存款、应收账款。余额方向：借 */
    ASSET(Direction.DR),

    /** 负债：客户备付金（用户余额、商户待结算）、应付账款。余额方向：贷 */
    LIABILITY(Direction.CR),

    /** 所有者权益。余额方向：贷 */
    EQUITY(Direction.CR),

    /** 收入：手续费收入。余额方向：贷 */
    INCOME(Direction.CR),

    /** 费用/成本：渠道成本、营销补贴。余额方向：借 */
    EXPENSE(Direction.DR);

    private final Direction balanceDirection;

    SubjectType(Direction balanceDirection) {
        this.balanceDirection = balanceDirection;
    }

    public Direction getBalanceDirection() {
        return balanceDirection;
    }
}
