package com.payment.ledger.engine;

import com.payment.ledger.domain.CalendarStatus;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.CalendarRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 会计日历 —— 会计日期的唯一权威来源。
 *
 * <p><b>为什么不能各模块自己 {@code LocalDate.now()}：</b>
 * 23:59:59 发起的请求，账务在 00:00:01 才处理完，会计日就串了，
 * 对账文件直接对不上。会计日期必须由一处统一裁定。
 *
 * <p><b>会计日期 ≠ 系统日期。</b>银行和渠道有自己的清算日切时点
 * （通常 23:00~次日 2:00），系统日期已经跨天了，会计日可能还没切。
 */
@Slf4j
@Component
public class AccountingCalendar {

    private final CalendarRepository repo;

    public AccountingCalendar(CalendarRepository repo) {
        this.repo = repo;
    }

    /** 当前会计日 = 日历中最早的一个 OPEN 日期 */
    public LocalDate current() {
        LocalDate date = repo.findCurrentOpenDate();
        if (date == null) {
            throw new LedgerException("NO_OPEN_ACCOUNTING_DATE",
                    "没有处于 OPEN 状态的会计日，系统无法记账");
        }
        return date;
    }

    /**
     * 裁定一笔记账应当归属的会计日期。
     *
     * @param requested 上游指定的会计日期，可为 null（表示交给日历裁定）
     * @return 最终采用的会计日期
     */
    public LocalDate resolve(LocalDate requested) {
        // ══════════════════════════════════════════════════════════════
        //  TODO 8 —— 由你实现（验收：AccountingCalendarTest，7 个用例）
        //
        //  a) requested 为 null → 返回 current()，即当前会计日
        //
        //  b) requested 有值 → 用 repo.findStatus(requested) 查状态：
        //       CLOSED  → 抛 LedgerException("ACCOUNTING_DATE_CLOSED", ...)
        //                 提示信息需包含"已关账"和"红冲"
        //                 （已关账的会计期间，日终快照已生成、报表已出、
        //                   对账文件已推送给渠道，禁止任何追溯记账；
        //                   错账只能在当前会计日红冲）
        //       CUTTING → 抛 LedgerException("ACCOUNTING_DATE_CUTTING", ...)
        //                 提示信息需包含"正在日切"
        //       OPEN    → 原样返回 requested
        //       null    → 日历中还没生成该日期。这里按宽松模式放行并打 warn，
        //                 生产环境应改为严格拒绝、由日历预生成任务补齐。
        //
        //  ── 这个方法存在的全部理由 ────────────────────────────
        //   23:59:59 发起的请求，账务在 00:00:01 才处理完。如果各模块
        //   各自 LocalDate.now()，会计日当场就串了，对账文件直接对不上。
        //   会计日期必须由一处统一裁定 —— 就是这里。
        //
        //  跑测试：mvn test -Dtest=AccountingCalendarTest
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 8: 实现会计日期裁定");
    }
}
