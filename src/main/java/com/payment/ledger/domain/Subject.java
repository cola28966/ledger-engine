package com.payment.ledger.domain;

import lombok.Data;

/**
 * 会计科目。账户的分类模板——科目是"类"，账户是"实例"。
 *
 * <p>新增科目时的三步判断法：
 * <ol>
 *   <li><b>站在平台自己的账上，这是我的什么？</b>
 *       别人欠我=资产，我欠别人=负债，我赚的=收入，我花的=费用。
 *       视角只能是平台——"商户欠我钱"是我的<i>资产</i>，不是"商户的负债"。</li>
 *   <li><b>这笔钱物理上在哪个银行账户里？</b>
 *       在备付金存管户 → 必须挂 2241 客户备付金下；
 *       不在任何账户里（是债权、是未来的钱）→ 绝对不能挂客户备付金。</li>
 *   <li><b>子科目的余额方向和父科目一致吗？</b> 不一致就是挂错了。</li>
 * </ol>
 */
@Data
public class Subject {

    private String subjectCode;
    private String subjectName;
    private String parentCode;
    private SubjectType subjectType;
    private Direction balanceDirection;

    /** 只有末级科目才能挂账户 */
    private boolean leaf;
}
