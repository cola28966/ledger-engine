package com.payment.ledger.repository;

import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.domain.ChannelTradeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道对账单明细的读写。
 *
 * <p><b>只有 insert，没有 update。</b>对账单是外部事实的快照，
 * 落库之后就该被当成只读数据——一旦允许修改，"昨天对平了、今天数据变了"
 * 这种事根本查不清楚，对账结论也就失去了公信力。
 */
@Repository
public class ChannelStatementRepository {

    private final JdbcTemplate jdbc;

    public ChannelStatementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChannelStatement> MAPPER = (ResultSet rs, int i) -> {
        Timestamp tradeTime = rs.getTimestamp("trade_time");
        long balanceAfter = rs.getLong("balance_after");
        return ChannelStatement.builder()
                .balanceAfter(rs.wasNull() ? null : balanceAfter)
                .id(rs.getLong("id"))
                .channelCode(rs.getString("channel_code"))
                .channelTradeNo(rs.getString("channel_trade_no"))
                .bizOrderNo(rs.getString("biz_order_no"))
                .bizType(com.payment.ledger.domain.BizType.valueOf(rs.getString("biz_type")))
                .amount(rs.getLong("amount"))
                .fee(rs.getLong("fee"))
                .tradeStatus(ChannelTradeStatus.valueOf(rs.getString("trade_status")))
                .statementDate(rs.getDate("statement_date").toLocalDate())
                .ourAccountNo(rs.getString("our_account_no"))
                .tradeTime(tradeTime == null ? null : tradeTime.toLocalDateTime())
                .build();
    };

    /**
     * 逐行落库。
     * <p>{@code (channel_code, channel_trade_no)} 上有唯一索引——
     * 渠道重发对账单、或运维手滑跑了两遍导入，都会在这里被挡住。
     * 对账单重复导入的后果是全量重复差异，比漏导更难排查。
     */
    public void insert(ChannelStatement s) {
        jdbc.update("""
                INSERT INTO channel_statement
                    (channel_code, channel_trade_no, biz_order_no, biz_type, amount, fee,
                     trade_status, statement_date, our_account_no, balance_after,
                     trade_time, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                s.getChannelCode(), s.getChannelTradeNo(), s.getBizOrderNo(),
                s.getBizType().name(),
                s.getAmount(), s.getFee(), s.getTradeStatus().name(),
                java.sql.Date.valueOf(s.getStatementDate()), s.getOurAccountNo(),
                s.getBalanceAfter(),
                s.getTradeTime(), LocalDateTime.now());
    }

    /** 取某日某渠道的全部对账单明细 */
    public List<ChannelStatement> findByDate(java.time.LocalDate statementDate, String channelCode) {
        return jdbc.query("""
                SELECT * FROM channel_statement
                 WHERE statement_date = ? AND channel_code = ?
                """, MAPPER, java.sql.Date.valueOf(statementDate), channelCode);
    }

    /**
     * 取某日某渠道的明细，<b>按入库顺序返回</b>（{@code ORDER BY id}）。
     *
     * <p>逐笔余额连续性必须用这个，不能用 {@link #findByDate}，也<b>绝不能按交易时间排序</b>。
     *
     * <p>渠道账单是按余额变动顺序输出的，而"交易时间"通常只精确到秒——
     * 同一秒里可能有几十笔。按时间重排会把渠道原本正确的顺序打乱，
     * 真实数据实测过：原始顺序 1 处断裂，按时间排序后变成 12012 处。
     *
     * <p>所以解析入库时必须<b>逐行顺序插入</b>，让自增 id 承载文件的原始行序。
     * 并发解析、多线程入库都会毁掉这个前提。
     */
    public List<ChannelStatement> findByDateInFileOrder(java.time.LocalDate statementDate,
                                                        String channelCode) {
        return jdbc.query("""
                SELECT * FROM channel_statement
                 WHERE statement_date = ? AND channel_code = ?
                 ORDER BY id
                """, MAPPER, java.sql.Date.valueOf(statementDate), channelCode);
    }

    public ChannelStatement findByTradeNo(String channelCode, String channelTradeNo) {
        List<ChannelStatement> list = jdbc.query("""
                SELECT * FROM channel_statement
                 WHERE channel_code = ? AND channel_trade_no = ?
                """, MAPPER, channelCode, channelTradeNo);
        return list.isEmpty() ? null : list.get(0);
    }
}
