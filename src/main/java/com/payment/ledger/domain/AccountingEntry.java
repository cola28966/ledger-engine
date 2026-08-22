package com.payment.ledger.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会计分录。面向财务/总账。
 *
 * <p>同一凭证下的所有分录，<b>借方合计必须等于贷方合计</b>。
 * 这不是形式主义，而是内建的自检机制。
 *
 * <p>与 {@link AccountSerial} 的区别：
 * <table border="1">
 *   <tr><th></th><th>会计分录</th><th>账户流水</th></tr>
 *   <tr><td>面向</td><td>财务/总账</td><td>用户/商户/客服</td></tr>
 *   <tr><td>必须平衡</td><td>是</td><td>否</td></tr>
 *   <tr><td>冻结解冻</td><td>不产生</td><td>产生</td></tr>
 * </table>
 */
@Data
public class AccountingEntry {

    private Long entryId;
    private String voucherNo;

    /** 组内序号，从 1 开始 */
    private int entrySeq;

    private String accountNo;
    private String subjectCode;
    private Direction direction;

    /** 金额，单位：分。永远为正数，方向由 direction 表达 */
    private long amount;

    private LocalDate accountingDate;
    private LocalDateTime createdAt;
}
