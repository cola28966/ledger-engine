package com.payment.ledger.domain;

/**
 * 业务类型。记账模板按此维度配置。
 *
 * <p>第一版用硬编码的 {@code EntryGenerator} 生成分录；
 * 阶段 3 会改造成配置化的记账模板，新增业务只需插一行配置，不改代码。
 */
public enum BizType {

    /** 充值：银行卡 → 平台余额 */
    RECHARGE,

    /** 余额消费：用户余额 → 商户待结算（含手续费） */
    CONSUME,

    /** 用户间转账（免费） */
    TRANSFER,

    /** 担保下单：用户余额 → 担保中间户 */
    ESCROW_PAY,

    /** 确认收货：担保中间户 → 商户待结算 + 手续费 */
    ESCROW_CONFIRM,

    /** 担保退款：担保中间户 → 用户余额 */
    ESCROW_REFUND,

    /** 提现提交：商户待结算 → 提现在途 + 提现手续费 */
    WITHDRAW_SUBMIT,

    /** 提现成功：提现在途 → 银行存款（资产真正减少的时刻） */
    WITHDRAW_SUCCESS,

    /** 退款到余额：商户待结算 + 手续费退还 → 用户余额 */
    REFUND_TO_BALANCE
}
