package com.payment.ledger.repository;

import com.payment.ledger.domain.BalanceSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BalanceSnapshot> MAPPER = (ResultSet rs, int i) ->
            new BalanceSnapshot(
                    rs.getDate("accounting_date").toLocalDate(),
                    rs.getString("account_no"),
                    rs.getLong("opening_balance"),
                    rs.getLong("debit_amount"),
                    rs.getLong("credit_amount"),
                    rs.getLong("closing_balance"));

    public void insert(BalanceSnapshot s) {
        jdbc.update("""
                INSERT INTO balance_snapshot
                    (accounting_date, account_no, opening_balance,
                     debit_amount, credit_amount, closing_balance, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                Date.valueOf(s.getAccountingDate()), s.getAccountNo(),
                s.getOpeningBalance(), s.getDebitAmount(),
                s.getCreditAmount(), s.getClosingBalance(), LocalDateTime.now());
    }

    public BalanceSnapshot find(LocalDate date, String accountNo) {
        List<BalanceSnapshot> list = jdbc.query(
                "SELECT * FROM balance_snapshot WHERE accounting_date = ? AND account_no = ?",
                MAPPER, Date.valueOf(date), accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<BalanceSnapshot> findByDate(LocalDate date) {
        return jdbc.query("SELECT * FROM balance_snapshot WHERE accounting_date = ?",
                MAPPER, Date.valueOf(date));
    }

    /** 取上一个已有快照的期末余额，作为本期期初余额。没有历史快照时为 0 */
    public long findLatestClosingBefore(LocalDate date, String accountNo) {
        List<Long> list = jdbc.queryForList("""
                SELECT closing_balance FROM balance_snapshot
                 WHERE account_no = ? AND accounting_date < ?
                 ORDER BY accounting_date DESC
                 LIMIT 1
                """, Long.class, accountNo, Date.valueOf(date));
        return list.isEmpty() ? 0L : list.get(0);
    }
}
