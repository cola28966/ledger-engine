package com.payment.ledger.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账户。余额的载体。
 *
 * <p><b>金额一律用 long 存"分"，永不使用 float/double。</b>
 *
 * <p>{@link #balance} 存的是"余额方向上的正数"：
 * <ul>
 *   <li>资产户 balance=1000 表示借方余额 1000 分</li>
 *   <li>负债户 balance=1000 表示贷方余额 1000 分</li>
 * </ul>
 * 这样"余额不能为负"的校验对所有科目类型都是统一的。
 *
 * <p>恒等式约束（日终必查）：{@code balance == available_balance + frozen_balance}
 */
@Data
public class Account {

    private String accountNo;
    private String accountName;
    private String subjectCode;
    private String ownerId;

    /** USER 用户户 / MERCHANT 商户户 / INTERNAL 内部户 */
    private String accountType;

    private String currency;

    /** 该账户的余额方向，与所属科目一致 */
    private Direction balanceDirection;

    private long balance;
    private long availableBalance;
    private long frozenBalance;

    /** NORMAL 正常 / FROZEN 冻结 / IN_ONLY 只收不付 / CLOSED 销户 */
    private String status;

    /**
     * 是否允许余额为负。
     * <p>用户户、商户户永远为 false——需要"透支"的场景请显式设计垫资户/应收户，
     * 而不是让余额变成负数。
     */
    private boolean allowNegative;

    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 判断一笔分录会让本账户余额增加还是减少。
     *
     * @param entryDirection 分录方向
     * @return true 表示余额增加
     */
    public boolean isIncrease(Direction entryDirection) {
        // ══════════════════════════════════════════════════════════════
        //  TODO 3 —— 由你实现（很短，但它是整个引擎的方向判断中枢）
        //
        //  借(DR)和贷(CR)本身不表示增减，一笔分录到底让余额增加还是减少，
        //  取决于这个账户自己的余额方向 balanceDirection。
        //
        //  想清楚这四种组合：
        //    资产户(DR) 收到一条 DR 分录 → ?    负债户(CR) 收到一条 DR 分录 → ?
        //    资产户(DR) 收到一条 CR 分录 → ?    负债户(CR) 收到一条 CR 分录 → ?
        //
        //  举例自检：用户余额户是负债户(CR)。
        //    充值时分录是 "贷：用户余额"（CR）→ 余额应该增加
        //    消费时分录是 "借：用户余额"（DR）→ 余额应该减少
        // ══════════════════════════════════════════════════════════════
        throw new UnsupportedOperationException("TODO 3: 判断该分录让余额增加还是减少");
    }
}
