package com.payment.ledger.repository;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.Voucher;
import com.payment.ledger.domain.VoucherStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class VoucherRepository {

    private final JdbcTemplate jdbc;

    public VoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Voucher> MAPPER = (ResultSet rs, int i) -> {
        Voucher v = new Voucher();
        v.setVoucherNo(rs.getString("voucher_no"));
        v.setRequestId(rs.getString("request_id"));
        v.setBizType(BizType.valueOf(rs.getString("biz_type")));
        v.setBizOrderNo(rs.getString("biz_order_no"));
        v.setAccountingDate(rs.getDate("accounting_date").toLocalDate());
        v.setTotalAmount(rs.getLong("total_amount"));
        v.setStatus(VoucherStatus.valueOf(rs.getString("status")));
        v.setReverseOf(rs.getString("reverse_of"));
        v.setReversedBy(rs.getString("reversed_by"));
        v.setRemark(rs.getString("remark"));
        return v;
    };

    /**
     * 插入凭证。{@code request_id} 上有唯一索引——
     * 并发重复请求会在这里抛 DuplicateKeyException，这是幂等的最后一道防线。
     */
    public void insert(Voucher v) {
        jdbc.update("""
                INSERT INTO voucher (voucher_no, request_id, biz_type, biz_order_no,
                                     accounting_date, total_amount, status,
                                     reverse_of, reversed_by, remark, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                v.getVoucherNo(), v.getRequestId(), v.getBizType().name(), v.getBizOrderNo(),
                java.sql.Date.valueOf(v.getAccountingDate()), v.getTotalAmount(), v.getStatus().name(),
                v.getReverseOf(), v.getReversedBy(), v.getRemark(), LocalDateTime.now());
    }

    public Voucher findByRequestId(String requestId) {
        List<Voucher> list = jdbc.query(
                "SELECT * FROM voucher WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Voucher findByNo(String voucherNo) {
        List<Voucher> list = jdbc.query(
                "SELECT * FROM voucher WHERE voucher_no = ?", MAPPER, voucherNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 把原凭证标记为已冲正。
     * <p>注意：只打标记，<b>绝不删除、绝不修改原凭证的金额</b>——
     * 账务数据是法定凭据，可变即失去公信力。
     */
    /**
     * 统计某会计日处于「记账中」的凭证数。
     * <p>日切前必须为 0。这类凭证是"记账崩在中间"的残留，
     * 也是日终试算不平时最高频的原因——一半以上的不平都出在这里。
     */
    public int countProcessing(java.time.LocalDate accountingDate) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM voucher
                 WHERE accounting_date = ? AND status = ?
                """, Integer.class,
                java.sql.Date.valueOf(accountingDate), VoucherStatus.PROCESSING.name());
        return c == null ? 0 : c;
    }

    public int markReversed(String voucherNo, String reverseVoucherNo) {
        return jdbc.update("""
                UPDATE voucher SET status = ?, reversed_by = ?
                 WHERE voucher_no = ? AND status = ?
                """, VoucherStatus.REVERSED.name(), reverseVoucherNo,
                voucherNo, VoucherStatus.SUCCESS.name());
    }
}
