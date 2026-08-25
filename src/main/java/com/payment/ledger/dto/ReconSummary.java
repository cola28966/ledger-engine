package com.payment.ledger.dto;

import com.payment.ledger.domain.DiffType;
import com.payment.ledger.domain.ReconDiff;

import java.time.LocalDate;
import java.util.List;

/**
 * 一个对账批次的结论。
 *
 * <p>对账跑完必须给出一个明确的结论，而不是"日志里自己找"。
 * 两侧笔数并列给出，是为了让"对账程序自己漏读了半个文件"这种事一眼可见——
 * 差异为 0 但渠道侧 0 笔，那不叫对平，那叫没对。
 *
 * @param diffs 本批次新产生的差异（不含此前已进入终态的历史差异）
 */
public record ReconSummary(LocalDate reconDate,
                           String channelCode,
                           int ourCount,
                           int channelCount,
                           int matchedCount,
                           int inTransitCount,
                           List<ReconDiff> diffs) {

    /** 是否完全对平 */
    public boolean balanced() {
        return diffs.isEmpty();
    }

    public int diffCount() {
        return diffs.size();
    }

    public List<ReconDiff> of(DiffType type) {
        return diffs.stream().filter(d -> d.getDiffType() == type).toList();
    }

    public int countOf(DiffType type) {
        return of(type).size();
    }

    /**
     * 一行式结论，直接可发告警。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(String.format(
                "对账 %s/%s：我方 %d 笔，渠道 %d 笔，平账 %d 笔，在途 %d 笔，差异 %d 笔",
                reconDate, channelCode, ourCount, channelCount,
                matchedCount, inTransitCount, diffs.size()));
        for (DiffType t : DiffType.values()) {
            int c = countOf(t);
            if (c > 0) {
                sb.append(String.format("；%s %d 笔", t, c));
            }
        }
        return sb.toString();
    }
}
