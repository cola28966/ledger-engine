package com.payment.ledger.repository;

import com.payment.ledger.domain.AccountSerial;
import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.Direction;
import com.payment.ledger.domain.SerialType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SerialRepository {

    private final JdbcTemplate jdbc;

    public SerialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccountSerial> MAPPER = (ResultSet rs, int i) -> {
        AccountSerial s = new AccountSerial();
        s.setSerialNo(rs.getLong("serial_no"));
        s.setAccountNo(rs.getString("account_no"));
        s.setVoucherNo(rs.getString("voucher_no"));
        s.setSerialType(SerialType.valueOf(rs.getString("serial_type")));
        String dir = rs.getString("direction");
        s.setDirection(dir == null ? null : Direction.valueOf(dir));
        s.setAmount(rs.getLong("amount"));
        s.setBalanceBefore(rs.getLong("balance_before"));
        s.setBalanceAfter(rs.getLong("balance_after"));
        String biz = rs.getString("biz_type");
        s.setBizType(biz == null ? null : BizType.valueOf(biz));
        s.setAccountingDate(rs.getDate("accounting_date").toLocalDate());
        return s;
    };

    public void insert(AccountSerial s) {
        jdbc.update("""
                INSERT INTO account_serial (account_no, voucher_no, serial_type, direction,
                                            amount, balance_before, balance_after,
                                            biz_type, accounting_date, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                s.getAccountNo(), s.getVoucherNo(), s.getSerialType().name(),
                s.getDirection() == null ? null : s.getDirection().name(),
                s.getAmount(), s.getBalanceBefore(), s.getBalanceAfter(),
                s.getBizType() == null ? null : s.getBizType().name(),
                java.sql.Date.valueOf(s.getAccountingDate()), LocalDateTime.now());
    }

    public List<AccountSerial> findByAccountNo(String accountNo) {
        return jdbc.query(
                "SELECT * FROM account_serial WHERE account_no = ? ORDER BY serial_no",
                MAPPER, accountNo);
    }

    /**
     * 内部对账用：按流水累计推算账户余额。
     * <p>结果必须等于 account.balance，不等就说明余额被脏改过、或流水漏记/重记。
     * 这是最强的账内自检之一——外部对账查不出的问题，它能查出来。
     */
    public long calcBalanceBySerial(String accountNo, Direction balanceDirection) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = ? THEN amount ELSE -amount END), 0)
                  FROM account_serial
                 WHERE account_no = ? AND serial_type = 'BOOKING'
                """, Long.class, balanceDirection.name(), accountNo);
        return sum == null ? 0L : sum;
    }
}
