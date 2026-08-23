package com.payment.ledger.repository;

import com.payment.ledger.domain.Account;
import com.payment.ledger.domain.Direction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Account> MAPPER = (ResultSet rs, int i) -> {
        Account a = new Account();
        a.setAccountNo(rs.getString("account_no"));
        a.setAccountName(rs.getString("account_name"));
        a.setSubjectCode(rs.getString("subject_code"));
        a.setOwnerId(rs.getString("owner_id"));
        a.setAccountType(rs.getString("account_type"));
        a.setCurrency(rs.getString("currency"));
        a.setBalanceDirection(Direction.valueOf(rs.getString("balance_direction")));
        a.setBalance(rs.getLong("balance"));
        a.setAvailableBalance(rs.getLong("available_balance"));
        a.setFrozenBalance(rs.getLong("frozen_balance"));
        a.setStatus(rs.getString("status"));
        a.setAllowNegative(rs.getBoolean("allow_negative"));
        a.setVersion(rs.getInt("version"));
        return a;
    };

    public Account findByNo(String accountNo) {
        List<Account> list = jdbc.query(
                "SELECT * FROM account WHERE account_no = ?", MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT * FROM account", MAPPER);
    }

    /**
     * 按科目编码前缀汇总余额。
     * <p>备付金勾稽用：{@code sumBalanceBySubjectPrefix("2241")} 即客户备付金负债合计。
     */
    public long sumBalanceBySubjectPrefix(String subjectCodePrefix) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM account WHERE subject_code LIKE ?",
                Long.class, subjectCodePrefix + "%");
        return v == null ? 0L : v;
    }

    /**
     * 按科目类型汇总余额。
     * <p>备付金勾稽用：{@code sumBalanceBySubjectType("INCOME")} 即已确认的自有收入，
     * 这部分钱还躺在备付金账户里，尚未划转到自有资金账户。
     */
    public long sumBalanceBySubjectType(String subjectType) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.balance), 0)
                  FROM account a
                  JOIN subject s ON a.subject_code = s.subject_code
                 WHERE s.subject_type = ?
                """, Long.class, subjectType);
        return v == null ? 0L : v;
    }

    /** 找出违反 balance = available + frozen 的账户 */
    public List<String> findInconsistentAccounts() {
        return jdbc.queryForList("""
                SELECT account_no FROM account
                 WHERE balance <> available_balance + frozen_balance
                """, String.class);
    }

    /**
     * 按余额方向对账户余额做增减，<b>并在同一条 SQL 内完成余额充足性校验</b>。
     *
     * <p><b>这是整个账务系统最关键的一条 SQL。</b>它把"检查余额是否充足"写进了
     * WHERE 子句，利用数据库行锁天然保证原子性，一次 IO 完成"检查 + 扣减"，
     * 彻底消灭了"先 SELECT 查余额、再 UPDATE 扣减"之间的竞态窗口。
     *
     * <p>判断影响行数即可知道结果：
     * <ul>
     *   <li>1 → 成功</li>
     *   <li>0 → 余额不足，或账户状态异常，或账户不存在</li>
     * </ul>
     *
     * @param accountNo 账户号
     * @param delta     余额变化量（正数增加，负数减少），单位：分
     * @return 影响行数
     */
    public int applyDelta(String accountNo, long delta) {
        return jdbc.update("""
                UPDATE account
                   SET balance = balance + ?,
                       available_balance    = available_balance + ?,
                       version           = version + 1,
                       updated_at        = ?
                 WHERE account_no = ?
                   AND status     = 'NORMAL'
                   AND (
                        (
                        available_balance + ? >= 0
                            AND balance + ? >= 0
                        )
                            OR allow_negative = TRUE
                            )
                """, delta, delta, LocalDateTime.now(), accountNo, delta, delta);
    }

    /** 冻结：balance 不变，available → frozen */
    public int freeze(String accountNo, long amount) {
        return jdbc.update("""
                UPDATE account
                   SET available_balance = available_balance - ?,
                       frozen_balance    = frozen_balance + ?,
                       version           = version + 1,
                       updated_at        = ?
                 WHERE account_no = ?
                   AND status     = 'NORMAL'
                   AND available_balance >= ?
                """, amount, amount, LocalDateTime.now(), accountNo, amount);
    }

    /** 解冻：balance 不变，frozen → available */
    public int unfreeze(String accountNo, long amount) {
        return jdbc.update("""
                UPDATE account
                   SET available_balance = available_balance + ?,
                       frozen_balance    = frozen_balance - ?,
                       version           = version + 1,
                       updated_at        = ?
                 WHERE account_no = ?
                   AND frozen_balance >= ?
                """, amount, amount, LocalDateTime.now(), accountNo, amount);
    }
}
