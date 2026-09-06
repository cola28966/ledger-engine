package com.payment.ledger.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 渠道对账单明细的一行。
 *
 * <p>这是<b>外部事实</b>：钱在银行/渠道那一侧真实发生了什么。
 * 我方账务是内部记录，两者谁对谁错并不天然确定，
 * 但在「资金是否真的发生」这一点上，<b>以渠道为准</b>——
 * 因为钱真实躺在银行账户里，不在我们的数据库里。
 */
@Data
@Builder
public class ChannelStatement {

    private Long id;

    /** 渠道标识：UNIONPAY / ALIPAY / WECHAT ... */
    private String channelCode;

    /** 渠道侧流水号，渠道内唯一 */
    private String channelTradeNo;

    /** 平台订单号 —— 对账的匹配键 */
    private String bizOrderNo;

    /**
     * 渠道交易类型映射到我方的业务类型。
     * <p>解析对账单时完成映射。差异自动修复靠它判断该补记哪种账——
     * 代收（充值）和代付（提现）补反了，窟窿会翻倍。
     */
    private BizType bizType;

    /** 交易金额，单位：分 */
    private long amount;

    /** 渠道向我方收取的通道费，单位：分 */
    @Builder.Default
    private long fee = 0L;

    private ChannelTradeStatus tradeStatus;

    /**
     * 对账单归属日期。
     * <p><b>它不等于我方的会计日期。</b>23:59 发起的交易，渠道很可能记在次日单里，
     * 我方却记在当日账上——跨日临界的错位是对账误判的头号来源。
     */
    private LocalDate statementDate;

    /**
     * 入账账户。
     * <p>渠道对账单原文里没有这一列，是解析入库时反查订单系统补上的。
     * 差异自动补记账离不开它：知道"少了一笔钱"没用，还得知道该记给谁。
     */
    private String ourAccountNo;

    /**
     * 渠道侧「这一笔之后」的账户余额，单位：分。
     * <p>为 null 表示该渠道不提供逐笔余额（不是所有渠道都给）。
     * 有它才能做逐笔余额连续性——那是唯一能定位到「从哪一笔开始错」的检查。
     */
    private Long balanceAfter;

    private LocalDateTime tradeTime;
}
