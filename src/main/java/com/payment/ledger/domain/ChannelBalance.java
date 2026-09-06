package com.payment.ledger.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 渠道某一天的余额与发生额。
 *
 * <p>数据来自渠道账单的汇总部分（或余额查询接口）。它和逐笔明细是<b>两条独立的信息</b>：
 * 明细回答"每一笔是什么"，余额回答"这一天整体对不对"。
 *
 * <p><b>收入和支出都存正数</b>，方向由字段名表达——和"分录金额永远为正、
 * 方向由 {@link Direction} 表达"是同一条规矩。渠道原始文件里支出常带负号
 * （支付宝就是 {@code -746688.21}），入库时统一取绝对值，
 * 否则"支出是正是负"这个约定会散落在每一处计算里。
 */
@Data
@Builder
public class ChannelBalance {

    private Long id;

    private String channelCode;

    private LocalDate balanceDate;

    /** 期初余额，单位：分 */
    private long openingBalance;

    /** 当日收入合计（正数），单位：分 */
    private long incomeAmount;

    /** 当日支出合计（正数），单位：分 */
    private long expenseAmount;

    /** 期末余额，单位：分 */
    private long closingBalance;

    /** 当日净变动 = 收入 − 支出 */
    public long netChange() {
        return incomeAmount - expenseAmount;
    }
}
