package com.payment.ledger.repository;

import com.payment.ledger.domain.AccountingTemplate;
import com.payment.ledger.domain.Direction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

@Repository
public class TemplateRepository {

    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccountingTemplate> MAPPER = (ResultSet rs, int i) -> {
        AccountingTemplate t = new AccountingTemplate();
        t.setTemplateId(rs.getLong("template_id"));
        t.setBizType(rs.getString("biz_type"));
        t.setEntrySeq(rs.getInt("entry_seq"));
        t.setDirection(Direction.valueOf(rs.getString("direction")));
        t.setAccountRule(rs.getString("account_rule"));
        t.setAmountRule(rs.getString("amount_rule"));
        t.setRemark(rs.getString("remark"));
        t.setStatus(rs.getString("status"));
        return t;
    };

    /** 按业务类型取模板，按 entry_seq 排序 */
    public List<AccountingTemplate> findByBizType(String bizType) {
        return jdbc.query("""
                SELECT * FROM accounting_template
                 WHERE biz_type = ? AND status = 'ACTIVE'
                 ORDER BY entry_seq
                """, MAPPER, bizType);
    }

    public List<AccountingTemplate> findAll() {
        return jdbc.query(
                "SELECT * FROM accounting_template WHERE status = 'ACTIVE' ORDER BY biz_type, entry_seq",
                MAPPER);
    }
}
