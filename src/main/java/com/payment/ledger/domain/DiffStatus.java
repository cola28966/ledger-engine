package com.payment.ledger.domain;

/**
 * 对账差异的处理状态。
 *
 * <pre>
 *   PENDING ──自动补记账──→ AUTO_REPAIRED
 *      │
 *      ├──人工冲正/补账──→ MANUAL_RESOLVED
 *      └──确认非问题────→ IGNORED
 * </pre>
 *
 * <p><b>终态是不可逆的。</b>对账批次可以重跑无数次，但重跑只能覆盖 PENDING；
 * 已经进入终态的差异必须原样保留——人工花两小时查清楚的结论，
 * 不能被一次例行重跑抹掉。
 */
public enum DiffStatus {

    /** 待处理 */
    PENDING,

    /** 已由系统自动补记账修复 */
    AUTO_REPAIRED,

    /** 已由人工处理（冲正、补账、与渠道协商挂账） */
    MANUAL_RESOLVED,

    /** 已确认为非问题（例如渠道对账单本身有误，已与渠道确认） */
    IGNORED;

    /** 是否为终态：终态的差异不再被重跑覆盖 */
    public boolean isFinal() {
        return this != PENDING;
    }
}
