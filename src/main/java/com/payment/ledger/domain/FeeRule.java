package com.payment.ledger.domain;

import lombok.Data;

import java.time.LocalDate;

/**
 * 计费规则。
 *
 * <p><b>费率用「基点 bp」存整数</b>：万分之一为 1 bp，0.6% = 60 bp。
 * 绝不用 double 存 0.006——浮点表示 0.006 本身就是不精确的，
 * 乘出来的手续费会带上误差，日积月累对账必然出现分位差。
 */
@Data
public class FeeRule {

    private Long ruleId;
    private String bizType;

    /** null 表示该业务类型的默认规则；有值表示商户专属协议价 */
    private String merchantId;

    /** 基点，万分之一。60 = 0.6% */
    private int rateBp;

    /** 保底手续费（分）。小额交易按费率算不足此值时取此值 */
    private long minFee;

    /** 封顶手续费（分）。null 表示不封顶 */
    private Long maxFee;

    private FeeRounding roundingMode;

    private LocalDate effectiveDate;
    private LocalDate expireDate;
    private String status;
}
