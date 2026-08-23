package com.payment.ledger.repository;

import com.payment.ledger.domain.CalendarStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class CalendarRepository {

    private final JdbcTemplate jdbc;

    public CalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 该会计日的状态；日历中没有该日期时返回 null */
    public CalendarStatus findStatus(LocalDate date) {
        List<String> list = jdbc.queryForList(
                "SELECT status FROM accounting_calendar WHERE accounting_date = ?",
                String.class, Date.valueOf(date));
        return list.isEmpty() ? null : CalendarStatus.valueOf(list.get(0));
    }

    /**
     * 当前会计日 = 最早的一个 OPEN 日期。
     * <p>日切把当前会计日置为 CLOSED 后，下一个 OPEN 自然成为新的当前会计日。
     */
    public LocalDate findCurrentOpenDate() {
        List<Date> list = jdbc.queryForList("""
                SELECT accounting_date FROM accounting_calendar
                 WHERE status = 'OPEN'
                 ORDER BY accounting_date
                 LIMIT 1
                """, Date.class);
        return list.isEmpty() ? null : list.get(0).toLocalDate();
    }

    public int updateStatus(LocalDate date, CalendarStatus from, CalendarStatus to) {
        return jdbc.update("""
                UPDATE accounting_calendar
                   SET status = ?, closed_at = ?
                 WHERE accounting_date = ? AND status = ?
                """, to.name(),
                to == CalendarStatus.CLOSED ? LocalDateTime.now() : null,
                Date.valueOf(date), from.name());
    }

    /** 确保某个会计日存在于日历中（幂等） */
    public void ensureExists(LocalDate date, CalendarStatus status) {
        if (findStatus(date) == null) {
            jdbc.update("INSERT INTO accounting_calendar VALUES (?,?,?,?)",
                    Date.valueOf(date), status.name(), LocalDateTime.now(), null);
        }
    }
}
