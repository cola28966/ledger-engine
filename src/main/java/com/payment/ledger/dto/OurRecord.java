package com.payment.ledger.dto;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.VoucherStatus;

import java.time.LocalDate;

/**
 * 我方账务侧的一笔待对账记录。
 *
 * <p>由 voucher + accounting_entry 聚合而来，把散在两张表里的
 * "金额"和"手续费"拼成一行，与渠道对账单的一行结构对齐——
 * <b>对账的前提是先把两侧拉到同一个数据形状上</b>，
 * 否则比较逻辑里会混进大量结构转换，错误就藏在那里面。
 *
 * @param bizOrderNo     平台订单号，匹配键
 * @param voucherNo      我方凭证号
 * @param bizType        业务类型
 * @param accountingDate 我方会计日期。可能与渠道对账单日期差一天
 * @param amount         交易金额（分）
 * @param fee            我方账上的手续费/成本（分）
 * @param status         凭证状态。PROCESSING 表示尚未到终态，对账时应判为在途
 */
public record OurRecord(String bizOrderNo,
                        String voucherNo,
                        BizType bizType,
                        LocalDate accountingDate,
                        long amount,
                        long fee,
                        VoucherStatus status) {
}
