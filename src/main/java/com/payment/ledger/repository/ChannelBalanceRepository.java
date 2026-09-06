package com.payment.ledger.repository;

import com.payment.ledger.domain.ChannelBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ChannelBalanceRepository {

    private final JdbcTemplate jdbc;

    public ChannelBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChannelBalance> MAPPER = (ResultSet rs, int i) ->
            ChannelBalance.builder()
                    .id(rs.getLong("id"))
                    .channelCode(rs.getString("channel_code"))
                    .balanceDate(rs.getDate("balance_date").toLocalDate())
                    .openingBalance(rs.getLong("opening_balance"))
                    .incomeAmount(rs.getLong("income_amount"))
                    .expenseAmount(rs.getLong("expense_amount"))
                    .closingBalance(rs.getLong("closing_balance"))
                    .build();

    public void insert(ChannelBalance b) {
        jdbc.update("""
                INSERT INTO channel_balance
                    (channel_code, balance_date, opening_balance,
                     income_amount, expense_amount, closing_balance, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                b.getChannelCode(), java.sql.Date.valueOf(b.getBalanceDate()),
                b.getOpeningBalance(), b.getIncomeAmount(), b.getExpenseAmount(),
                b.getClosingBalance(), LocalDateTime.now());
    }

    public ChannelBalance find(String channelCode, LocalDate balanceDate) {
        List<ChannelBalance> list = jdbc.query("""
                SELECT * FROM channel_balance
                 WHERE channel_code = ? AND balance_date = ?
                """, MAPPER, channelCode, java.sql.Date.valueOf(balanceDate));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 取一段日期内的余额记录，<b>按日期升序</b>。
     *
     * <p>注意返回的是"实际存在的记录"，缺失的日期不会补空——
     * 判断有没有缺天是调用方的事，这里不替它决定。
     */
    public List<ChannelBalance> findRange(String channelCode, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM channel_balance
                 WHERE channel_code = ? AND balance_date BETWEEN ? AND ?
                 ORDER BY balance_date
                """, MAPPER, channelCode,
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }
}
