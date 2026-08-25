package com.payment.ledger.repository;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.DiffStatus;
import com.payment.ledger.domain.DiffType;
import com.payment.ledger.domain.ReconDiff;
import com.payment.ledger.domain.VoucherStatus;
import com.payment.ledger.dto.OurRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对账的数据访问：我方待对账记录 + 差异表读写。
 */
@Repository
public class ReconRepository {

    /**
     * 手续费/通道成本所在的科目。
     *
     * <p><b>这里用科目编码而不是账号，是有意的。</b>热点账户分桶之后，
     * 手续费收入会落到 {@code FEE_INCOME_B07} 这样的桶账号上，
     * 按账号匹配会漏掉全部分桶数据；而桶账户的 {@code subject_code}
     * 是从主户原样复制的，永远不变。
     *
     * <p>凡是"跨账户做汇总"的查询，都应该走科目而不是账号。
     */
    private static final String FEE_SUBJECTS = "'600101','600102'";

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ================================================================
    //  我方侧
    // ================================================================

    /**
     * 取我方待对账记录。
     *
     * <p>三处过滤值得留意：
     * <ul>
     *   <li><b>日期是区间不是等值</b>——跨日临界的交易，我方记 T 日、渠道记 T+1 日，
     *       按等值查必然把它误判成单边账。容差窗口是对账的必需品，不是优化项。</li>
     *   <li><b>排除冲正凭证</b>（{@code reverse_of IS NOT NULL}）与被冲正的原凭证
     *       （{@code status = 'REVERSED'}）。一正一反净额为零，等价于"我方没有这笔"，
     *       此时若渠道说成功，就会被正确地识别为渠道单边账——这正是我们想要的结论。</li>
     *   <li><b>保留 PROCESSING</b>——在途凭证必须进入对账视野，
     *       但要被定性为"在途"而不是"差异"。直接在 SQL 里滤掉，
     *       它就会变成一笔我方单边账，制造假告警。</li>
     * </ul>
     *
     * @param from     会计日期下界（含）
     * @param to       会计日期上界（含）
     * @param bizTypes 参与对账的业务类型：只有与渠道有真实资金往来的才需要对账
     */
    public List<OurRecord> findOurRecords(LocalDate from, LocalDate to, Collection<BizType> bizTypes) {
        if (bizTypes.isEmpty()) {
            return List.of();
        }
        String inClause = bizTypes.stream().map(t -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[bizTypes.size() + 2];
        args[0] = java.sql.Date.valueOf(from);
        args[1] = java.sql.Date.valueOf(to);
        int i = 2;
        for (BizType t : bizTypes) {
            args[i++] = t.name();
        }

        String sql = "SELECT v.biz_order_no, v.voucher_no, v.biz_type, v.accounting_date,"
                + "       v.total_amount, v.status,"
                + "       COALESCE((SELECT SUM(e.amount) FROM accounting_entry e"
                + "                  WHERE e.voucher_no = v.voucher_no"
                + "                    AND e.direction  = 'CR'"
                + "                    AND e.subject_code IN (" + FEE_SUBJECTS + ")), 0) AS fee"
                + "  FROM voucher v"
                + " WHERE v.accounting_date BETWEEN ? AND ?"
                + "   AND v.reverse_of IS NULL"
                + "   AND v.status IN ('SUCCESS','PROCESSING')"
                + "   AND v.biz_type IN (" + inClause + ")";

        return jdbc.query(sql,
                (ResultSet rs, int n) -> new OurRecord(
                        rs.getString("biz_order_no"),
                        rs.getString("voucher_no"),
                        BizType.valueOf(rs.getString("biz_type")),
                        rs.getDate("accounting_date").toLocalDate(),
                        rs.getLong("total_amount"),
                        rs.getLong("fee"),
                        VoucherStatus.valueOf(rs.getString("status"))),
                args);
    }

    // ================================================================
    //  差异表
    // ================================================================

    private static final RowMapper<ReconDiff> MAPPER = (ResultSet rs, int i) ->
            ReconDiff.builder()
                    .diffId(rs.getLong("diff_id"))
                    .reconDate(rs.getDate("recon_date").toLocalDate())
                    .channelCode(rs.getString("channel_code"))
                    .bizOrderNo(rs.getString("biz_order_no"))
                    .channelTradeNo(rs.getString("channel_trade_no"))
                    .diffType(DiffType.valueOf(rs.getString("diff_type")))
                    .ourAmount(rs.getLong("our_amount"))
                    .channelAmount(rs.getLong("channel_amount"))
                    .ourFee(rs.getLong("our_fee"))
                    .channelFee(rs.getLong("channel_fee"))
                    .ourVoucherNo(rs.getString("our_voucher_no"))
                    .status(DiffStatus.valueOf(rs.getString("status")))
                    .repairVoucherNo(rs.getString("repair_voucher_no"))
                    .remark(rs.getString("remark"))
                    .build();

    public void insertDiff(ReconDiff d) {
        jdbc.update("""
                INSERT INTO recon_diff
                    (recon_date, channel_code, biz_order_no, channel_trade_no, diff_type,
                     our_amount, channel_amount, our_fee, channel_fee,
                     our_voucher_no, status, repair_voucher_no, remark, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                java.sql.Date.valueOf(d.getReconDate()), d.getChannelCode(),
                d.getBizOrderNo(), d.getChannelTradeNo(), d.getDiffType().name(),
                d.getOurAmount(), d.getChannelAmount(), d.getOurFee(), d.getChannelFee(),
                d.getOurVoucherNo(), d.getStatus().name(), d.getRepairVoucherNo(),
                d.getRemark(), LocalDateTime.now());
    }

    /**
     * 清理本批次<b>尚未处理</b>的差异。
     *
     * <p>注意 {@code status = 'PENDING'} 这个条件：对账批次可以重跑无数次，
     * 但已进入终态的差异必须原样保留——人工花两小时查清楚的结论，
     * 不能被一次例行重跑抹掉。
     */
    public int deletePendingDiffs(LocalDate reconDate, String channelCode) {
        return jdbc.update("""
                DELETE FROM recon_diff
                 WHERE recon_date = ? AND channel_code = ? AND status = ?
                """, java.sql.Date.valueOf(reconDate), channelCode, DiffStatus.PENDING.name());
    }

    /**
     * 本批次里已经进入终态的订单号。
     * <p>重跑时这些订单要整个跳过：既不重新报差异，也不覆盖既有结论。
     */
    public List<String> findHandledOrderNos(LocalDate reconDate, String channelCode) {
        return jdbc.queryForList("""
                SELECT biz_order_no FROM recon_diff
                 WHERE recon_date = ? AND channel_code = ? AND status <> ?
                """, String.class,
                java.sql.Date.valueOf(reconDate), channelCode, DiffStatus.PENDING.name());
    }

    public List<ReconDiff> findDiffs(LocalDate reconDate, String channelCode) {
        return jdbc.query("""
                SELECT * FROM recon_diff
                 WHERE recon_date = ? AND channel_code = ?
                 ORDER BY biz_order_no
                """, MAPPER, java.sql.Date.valueOf(reconDate), channelCode);
    }

    public List<ReconDiff> findDiffsByTypeAndStatus(LocalDate reconDate, String channelCode,
                                                    DiffType type, DiffStatus status) {
        return jdbc.query("""
                SELECT * FROM recon_diff
                 WHERE recon_date = ? AND channel_code = ?
                   AND diff_type = ? AND status = ?
                 ORDER BY biz_order_no
                """, MAPPER, java.sql.Date.valueOf(reconDate), channelCode,
                type.name(), status.name());
    }

    /**
     * 推进差异状态。
     *
     * <p>{@code AND status = 'PENDING'} 是并发下的乐观锁：
     * 对账补偿任务与人工处理台可能同时操作同一条差异，
     * 靠影响行数判断"是不是我改成功的"，避免把人工结论覆盖掉。
     *
     * @return 影响行数。0 表示这条差异已被别人处理了
     */
    public int markHandled(Long diffId, DiffStatus to, String repairVoucherNo, String remark) {
        return jdbc.update("""
                UPDATE recon_diff
                   SET status = ?, repair_voucher_no = ?, remark = ?
                 WHERE diff_id = ? AND status = ?
                """, to.name(), repairVoucherNo, remark, diffId, DiffStatus.PENDING.name());
    }

    public int countPending(LocalDate reconDate, String channelCode) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM recon_diff
                 WHERE recon_date = ? AND channel_code = ? AND status = ?
                """, Integer.class,
                java.sql.Date.valueOf(reconDate), channelCode, DiffStatus.PENDING.name());
        return c == null ? 0 : c;
    }
}
