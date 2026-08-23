package com.payment.ledger.batch;

import java.time.LocalDate;
import java.util.List;

/**
 * 日终结账结果 —— 账务系统的"体检报告"。
 */
public record DayEndResult(LocalDate accountingDate,
                           boolean success,
                           List<CheckResult> checks) {

    /** 单项校验结果 */
    public record CheckResult(String name, boolean passed, String detail) {

        public static CheckResult pass(String name, String detail) {
            return new CheckResult(name, true, detail);
        }

        public static CheckResult fail(String name, String detail) {
            return new CheckResult(name, false, detail);
        }
    }

    public CheckResult check(String name) {
        return checks.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这项检查: " + name));
    }

    public List<CheckResult> failures() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }
}
