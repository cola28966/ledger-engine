package com.payment.ledger.repository;

import com.payment.ledger.domain.FeeRounding;
import com.payment.ledger.domain.FeeRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

@Repository
public class FeeRuleRepository {

    private final JdbcTemplate jdbc;

    public FeeRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FeeRule> MAPPER = (ResultSet rs, int i) -> {
        FeeRule r = new FeeRule();
        r.setRuleId(rs.getLong("rule_id"));
        r.setBizType(rs.getString("biz_type"));
        r.setMerchantId(rs.getString("merchant_id"));
        r.setRateBp(rs.getInt("rate_bp"));
        r.setMinFee(rs.getLong("min_fee"));
        long maxFee = rs.getLong("max_fee");
        r.setMaxFee(rs.wasNull() ? null : maxFee);
        r.setRoundingMode(FeeRounding.valueOf(rs.getString("rounding_mode")));
        r.setEffectiveDate(rs.getDate("effective_date").toLocalDate());
        Date expire = rs.getDate("expire_date");
        r.setExpireDate(expire == null ? null : expire.toLocalDate());
        r.setStatus(rs.getString("status"));
        return r;
    };

    /**
     * 查找适用的计费规则。
     *
     * <p>匹配优先级：<b>商户专属协议价 &gt; 业务类型默认规则</b>。
     * 用 {@code ORDER BY merchant_id DESC} 让非 NULL 的商户规则排在前面，
     * 取第一条即可（H2/MySQL 下 NULL 在 DESC 排序中排最后）。
     *
     * @return 命中的规则；没有配置任何规则时返回 null
     */
    public FeeRule findRule(String bizType, String merchantId, LocalDate accountingDate) {
        List<FeeRule> list = jdbc.query("""
                SELECT * FROM fee_rule
                 WHERE biz_type = ?
                   AND status = 'ACTIVE'
                   AND (merchant_id = ? OR merchant_id IS NULL)
                   AND effective_date <= ?
                   AND (expire_date IS NULL OR expire_date >= ?)
                 ORDER BY merchant_id DESC
                """, MAPPER, bizType, merchantId,
                Date.valueOf(accountingDate), Date.valueOf(accountingDate));
        return list.isEmpty() ? null : list.get(0);
    }
}
