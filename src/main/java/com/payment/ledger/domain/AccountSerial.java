package com.payment.ledger.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 账户流水。面向用户/商户/客服（就是 App 里的"账单明细"）。
 *
 * <p>关键字段是 {@link #balanceAfter}（记账后余额）：
 * <ul>
 *   <li>用户查账单时能看到每笔之后的余额</li>
 *   <li>对账时可校验链式关系：
 *       {@code 上一笔.balanceAfter ± 本笔.amount == 本笔.balanceAfter}</li>
 *   <li>这是发现串号、漏记、重复记账的利器</li>
 * </ul>
 *
 * <p>冻结/解冻不改变 balance，因此不产生会计分录，
 * <b>但必须产生账户流水</b>，否则冻结记录无法追溯，
 * 出现"冻结泄漏"（frozen 只增不减）时连查都没法查。
 */
@Data
public class AccountSerial {

    private Long serialNo;
    private String accountNo;

    /** 冻结/解冻类流水没有凭证号 */
    private String voucherNo;

    private SerialType serialType;

    /** 冻结/解冻类流水没有借贷方向 */
    private Direction direction;

    private long amount;
    private long balanceBefore;
    private long balanceAfter;

    private BizType bizType;
    private LocalDate accountingDate;
    private LocalDateTime createdAt;
}
