package com.payment.ledger.repository;

import com.payment.ledger.domain.AccountingEntry;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.dto.DailyMovement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class EntryRepository {

    private final JdbcTemplate jdbc;

    public EntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccountingEntry> MAPPER = (ResultSet rs, int i) -> {
        AccountingEntry e = new AccountingEntry();
        e.setEntryId(rs.getLong("entry_id"));
        e.setVoucherNo(rs.getString("voucher_no"));
        e.setEntrySeq(rs.getInt("entry_seq"));
        e.setAccountNo(rs.getString("account_no"));
        e.setSubjectCode(rs.getString("subject_code"));
        e.setDirection(Direction.valueOf(rs.getString("direction")));
        e.setAmount(rs.getLong("amount"));
        e.setAccountingDate(rs.getDate("accounting_date").toLocalDate());
        return e;
    };

    public void insert(AccountingEntry e) {
        jdbc.update("""
                INSERT INTO accounting_entry (voucher_no, entry_seq, account_no, subject_code,
                                              direction, amount, accounting_date, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                e.getVoucherNo(), e.getEntrySeq(), e.getAccountNo(), e.getSubjectCode(),
                e.getDirection().name(), e.getAmount(),
                java.sql.Date.valueOf(e.getAccountingDate()), LocalDateTime.now());
    }

    public List<AccountingEntry> findByVoucherNo(String voucherNo) {
        return jdbc.query(
                "SELECT * FROM accounting_entry WHERE voucher_no = ? ORDER BY entry_seq",
                MAPPER, voucherNo);
    }

    /**
     * 试算平衡：某个会计日全部分录的借方合计 / 贷方合计。
     * <p>两者必须相等，否则账错了，必须阻断日切并立即告警。
     */
    public long sumByDirection(LocalDate accountingDate, Direction direction) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM accounting_entry
                 WHERE accounting_date = ? AND direction = ?
                """, Long.class, java.sql.Date.valueOf(accountingDate), direction.name());
        return sum == null ? 0L : sum;
    }

    /**
     * 日终快照用：一次性聚合出当日每个账户的借贷发生额。
     * <p>只返回当日有发生额的账户；当日无交易的账户由调用方按"期初=期末"补齐。
     */
    public List<DailyMovement> aggregateByAccount(LocalDate accountingDate) {
        return jdbc.query("""
                SELECT account_no,
                       COALESCE(SUM(CASE WHEN direction = 'DR' THEN amount ELSE 0 END), 0) AS dr,
                       COALESCE(SUM(CASE WHEN direction = 'CR' THEN amount ELSE 0 END), 0) AS cr
                  FROM accounting_entry
                 WHERE accounting_date = ?
                 GROUP BY account_no
                """,
                (rs, i) -> new DailyMovement(
                        rs.getString("account_no"), rs.getLong("dr"), rs.getLong("cr")),
                java.sql.Date.valueOf(accountingDate));
    }

    /**
     * 找出组内借贷不平的凭证。
     * <p>试算平衡不通过时的第一排查手段——通常一步就能定位到问题凭证。
     */
    public List<String> findUnbalancedVouchers(LocalDate accountingDate) {
        return jdbc.queryForList("""
                SELECT voucher_no FROM accounting_entry
                 WHERE accounting_date = ?
                 GROUP BY voucher_no
                HAVING SUM(CASE WHEN direction = 'DR' THEN amount ELSE 0 END)
                    <> SUM(CASE WHEN direction = 'CR' THEN amount ELSE 0 END)
                """, String.class, java.sql.Date.valueOf(accountingDate));
    }
}
