package com.payment.ledger.domain;

import lombok.Data;

/**
 * 记账模板：一行一条分录。
 *
 * <p>把"业务类型 → 分录组"的映射从代码搬进配置。
 * 新增业务类型只需插几行记录，不改代码、不发版。
 *
 * <p>{@link #accountRule} 与 {@link #amountRule} 是 SpEL 表达式，
 * 以 {@code BookingRequest} 为求值根对象：
 * <pre>
 *   账户：payerAccount / payeeAccount / 'FEE_INCOME'（单引号为字面量）
 *   金额：amount / fee / amount - fee
 * </pre>
 */
@Data
public class AccountingTemplate {

    private Long templateId;
    private String bizType;

    /** 组内序号，同时决定 entry_seq（业务语义顺序：借在前、贷在后） */
    private int entrySeq;

    private Direction direction;

    /** 账户取值规则（SpEL） */
    private String accountRule;

    /** 金额取值规则（SpEL） */
    private String amountRule;

    private String remark;
    private String status;
}
