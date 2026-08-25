package com.payment.ledger.engine;

/**
 * 内部户账号常量。
 *
 * <p><b>历史说明：</b>阶段一时这个类里有一个 switch，把 9 种业务类型硬编码成分录组。
 * 阶段四已把那部分搬进 {@code accounting_template} 表，由 {@link TemplateEngine}
 * 按配置渲染——新增业务类型只需插几行配置，不改代码、不发版。
 *
 * <p>类保留下来只作为内部户账号的唯一出处：这些账号在模板配置、日终勾稽、
 * 测试断言里都要引用，散落成字符串字面量迟早会拼错。
 */
public final class EntryGenerator {

    /** 备付金存管户（资产） */
    public static final String BANK_RESERVE  = "BANK_RESERVE";
    /** 担保交易中间户（负债） */
    public static final String ESCROW        = "ESCROW";
    /** 提现在途户（负债） */
    public static final String WD_TRANSIT    = "WD_TRANSIT";
    /** 支付手续费收入户（收入） */
    public static final String FEE_INCOME    = "FEE_INCOME";
    /** 提现手续费收入户（收入） */
    public static final String WD_FEE_INCOME = "WD_FEE_INCOME";

    private EntryGenerator() {
    }
}
