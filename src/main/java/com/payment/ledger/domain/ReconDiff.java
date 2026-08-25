package com.payment.ledger.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 一条对账差异。
 *
 * <p>对账的产出不是"对账单"，而是<b>差异清单</b>：对平的部分不留痕，
 * 不平的每一条都必须能追踪到闭环。一条差异记录要能回答三个问题：
 * <ol>
 *   <li>哪一笔？（{@code bizOrderNo} / {@code channelTradeNo}）</li>
 *   <li>差在哪？（{@code diffType} + 两侧金额并列，值班的人一眼看出偏在哪侧）</li>
 *   <li>处理了吗？（{@code status} + {@code repairVoucherNo}）</li>
 * </ol>
 *
 * <p><b>两侧金额必须并列存下来</b>，只存一个差额是不够的——
 * 差额 500 既可能是 "我方 1000 / 渠道 500"，也可能是 "我方 10500 / 渠道 10000"，
 * 排查路径完全不同。
 */
@Data
@Builder
public class ReconDiff {

    private Long diffId;

    /** 对账批次日期 */
    private LocalDate reconDate;

    private String channelCode;

    /** 匹配键 */
    private String bizOrderNo;

    /** 渠道流水号。我方单边账时为 null */
    private String channelTradeNo;

    private DiffType diffType;

    /** 我方金额（分）。渠道单边账时为 0 */
    @Builder.Default
    private long ourAmount = 0L;

    /** 渠道金额（分）。我方单边账时为 0 */
    @Builder.Default
    private long channelAmount = 0L;

    @Builder.Default
    private long ourFee = 0L;

    @Builder.Default
    private long channelFee = 0L;

    /** 我方凭证号。渠道单边账时为 null */
    private String ourVoucherNo;

    @Builder.Default
    private DiffStatus status = DiffStatus.PENDING;

    /** 自动补记账产生的凭证号 —— 差异到处理动作的审计链 */
    private String repairVoucherNo;

    private String remark;

    /** 差额（我方 - 渠道），单位：分 */
    public long amountDiff() {
        return ourAmount - channelAmount;
    }
}
