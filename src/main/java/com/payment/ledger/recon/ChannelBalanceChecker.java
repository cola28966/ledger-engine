package com.payment.ledger.recon;

import com.payment.ledger.batch.DayEndResult.CheckResult;
import com.payment.ledger.domain.ChannelBalance;
import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.repository.ChannelBalanceRepository;
import com.payment.ledger.repository.ChannelStatementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 渠道余额连续性校验。
 *
 * <p>阶段五做的逐笔对账有一个盲区：<b>它只能证明「我看到的这些是对的」，
 * 证明不了「我该看到的都看到了」。</b>
 *
 * <pre>
 *   08-19 的对账单整天没下载成功：
 *     逐笔对账跑 08-18  →  完美对平 ✓
 *     逐笔对账跑 08-20  →  完美对平 ✓
 *   因为缺失的那批数据两边都不在比对范围里，比对逻辑根本看不见它。
 * </pre>
 *
 * <p>余额连续性是唯一能抓到这类问题的检查——它比的不是"数据内部对不对"，
 * 而是<b>"数据之间接不接得上"</b>：
 *
 * <pre>
 *   T 日期末 != T+1 日期初   →   中间一定丢了什么
 * </pre>
 *
 * <h3>三层检查，越往下定位越精确</h3>
 * <ol>
 *   <li><b>跨日连续</b>——发现"整天缺了"，但只知道缺在哪一天</li>
 *   <li><b>单日自洽</b>——发现"这一天内部对不上"，定位到日</li>
 *   <li><b>逐笔连续</b>——定位到<b>具体哪一笔</b>开始断的</li>
 * </ol>
 *
 * <p>这和 {@code DayEndService} 的日终快照是同一条勾稽
 * （{@code 期末 = 期初 + 本期发生额}），区别只在数据源：
 * 那个校验<b>内部账本</b>，这个校验<b>渠道账单</b>。
 */
@Slf4j
@Service
public class ChannelBalanceChecker {

    public static final String CHK_DAILY_BALANCE = "渠道单日余额自洽";
    public static final String CHK_ROW_CONTINUITY = "渠道逐笔余额连续";
    public static final String CHK_CROSS_DAY = "渠道跨日余额连续";

    private final ChannelBalanceRepository balanceRepo;
    private final ChannelStatementRepository statementRepo;

    public ChannelBalanceChecker(ChannelBalanceRepository balanceRepo,
                                 ChannelStatementRepository statementRepo) {
        this.balanceRepo = balanceRepo;
        this.statementRepo = statementRepo;
    }

    // ================================================================
    //  TODO 18：单日余额自洽
    // ================================================================

    /**
     * 校验某一天的渠道余额记录自洽，且与明细对得上。
     *
     * <p><b>TODO 18：实现这个方法。</b>
     *
     * <p>两件事，都要查：
     * <ol>
     *   <li><b>余额等式</b>：{@code 期初 + 收入 − 支出 == 期末}</li>
     *   <li><b>明细 vs 汇总</b>：把当日 {@code channel_statement} 逐行累加，
     *       收入合计必须等于 {@code incomeAmount}，支出合计必须等于 {@code expenseAmount}</li>
     * </ol>
     *
     * <p>明细累加的口径（{@link ChannelStatement} 的 {@code amount} 恒为正，
     * 方向由 {@code bizType} 表达）：
     * <ul>
     *   <li>{@code RECHARGE} —— 计入收入</li>
     *   <li>{@code WITHDRAW_SUCCESS} —— 计入支出</li>
     *   <li>{@code tradeStatus != SUCCESS} 的行不计入（失败交易不产生资金变动）</li>
     * </ul>
     *
     * <p>找不到当天的余额记录时，直接判失败——<b>"没有数据"和"数据没问题"是两回事</b>，
     * 返回通过等于把缺失伪装成正常。
     *
     * <p><b>第 2 项才是重点。</b>它抓的是「对账单文件下载到一半断了」：
     * 少了 3000 行明细，余额等式照样成立（因为汇总数是渠道给的，不受截断影响），
     * 但明细累加会对不上。少了这一步，那 3000 笔会在逐笔对账里
     * <b>伪装成 3000 笔渠道单边账</b>——告警炸了，值班的人去查记账服务，
     * 真实原因是网络抖了一下。
     *
     * @return 用 {@link CheckResult#pass}/{@link CheckResult#fail} 返回，
     *         名称用 {@link #CHK_DAILY_BALANCE}；失败时 detail 要写清两侧数字
     */
    public CheckResult checkDailyBalance(String channelCode, LocalDate date) {
        // TODO 18: 余额等式 + 明细累加 vs 汇总
        throw new UnsupportedOperationException("TODO 18: 实现 checkDailyBalance");
    }

    // ================================================================
    //  TODO 19：逐笔余额连续
    // ================================================================

    /**
     * 沿着明细逐笔核对余额链：<b>上一笔余额 + 本笔发生额 == 本笔余额</b>。
     *
     * <p><b>TODO 19：实现这个方法。</b>
     *
     * <p>要求：
     * <ol>
     *   <li>用 {@link ChannelStatementRepository#findByDateInFileOrder} 取数。
     *       <b>不能自己按交易时间排序</b>，理由见那个方法的注释</li>
     *   <li>跳过 {@code balanceAfter == null} 的渠道：不是所有渠道都提供逐笔余额。
     *       没有这列时应当返回<b>通过</b>并在 detail 里说明"该渠道不提供逐笔余额"——
     *       这是"查不了"，不是"查出问题了"，两者不能混为一谈</li>
     *   <li>本笔发生额按 {@code bizType} 定符号：{@code RECHARGE} 为正，
     *       {@code WITHDRAW_SUCCESS} 为负；非 SUCCESS 的行跳过</li>
     *   <li>首行没有"上一笔"，只用来推算期初，不参与比较</li>
     *   <li><b>断点要全部收集完再一次性返回</b>，不能撞到第一个就 return</li>
     * </ol>
     *
     * <p><b>第 5 点是从真实事故里来的。</b>排查对账要的是「一共断了几处、各在哪」——
     * 一次只报一个断点，修一个跑一轮，一天就过去了。而且断点数量本身就是诊断信息：
     * 断 1 处通常是日切边界的跨日归集，断几千处则是排序或解析出了问题，
     * 这两种情况的处理方式完全不同。
     *
     * <p>失败时 detail 里每个断点都要给出：<b>第几笔、渠道流水号、
     * 上一笔余额、本笔发生额、账单上写的余额、缺口多少</b>。
     * 断点多时只列前 10 条，剩下的给个数量——把几千行塞进告警等于没有告警。
     *
     * @return 名称用 {@link #CHK_ROW_CONTINUITY}
     */
    public CheckResult checkRowContinuity(String channelCode, LocalDate date) {
        // TODO 19: 逐笔余额链，收集全部断点
        throw new UnsupportedOperationException("TODO 19: 实现 checkRowContinuity");
    }

    // ================================================================
    //  TODO 20：跨日余额连续
    // ================================================================

    /**
     * 校验一段日期内，每一天的期初都接得上前一天的期末。
     *
     * <p><b>TODO 20：实现这个方法。</b>
     *
     * <p>两件事，<b>都要查，缺一不可</b>：
     * <ol>
     *   <li><b>余额衔接</b>：{@code 本日 openingBalance == 前日 closingBalance}</li>
     *   <li><b>日期不缺</b>：{@code [from, to]} 区间内每一天都必须有余额记录</li>
     * </ol>
     *
     * <p><b>为什么第 2 项不能省——这是这个方法唯一的难点：</b>
     *
     * <pre>
     *   08-19 的账单整天没下载，channel_balance 里没有这一天。
     *   如果只比"相邻两条记录"，比的就是 08-18 和 08-20。
     *   而只要 08-19 当天的净变动恰好为 0（周末、节假日、
     *   或者一批充值和一批提现刚好抵消），余额链就完美接上了。
     * </pre>
     *
     * <p><b>余额对得上，不代表没缺天。</b>所以日期连续性必须<b>单独查</b>，
     * 不能指望余额比对顺带发现。
     *
     * <p>这正是整个余额连续性存在的理由的极端形态：逐笔对账看不见缺失的那批数据，
     * 而"只比相邻记录"的余额检查，看不见缺失的那一天。
     * <b>每加一层检查，都要问一遍：这一层自己的盲区是什么。</b>
     *
     * <p>失败时两类问题都要报出来，且要能区分是"缺日期"还是"余额接不上"。
     *
     * @return 名称用 {@link #CHK_CROSS_DAY}
     */
    public CheckResult checkCrossDayContinuity(String channelCode, LocalDate from, LocalDate to) {
        // TODO 20: 余额衔接 + 日期不缺
        throw new UnsupportedOperationException("TODO 20: 实现 checkCrossDayContinuity");
    }

    // ================================================================
    //  已备好
    // ================================================================

    /**
     * 跑完整套：区间内每天的单日自洽 + 逐笔连续，再加一次跨日连续。
     */
    public List<CheckResult> checkAll(String channelCode, LocalDate from, LocalDate to) {
        List<CheckResult> results = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            results.add(checkDailyBalance(channelCode, d));
            results.add(checkRowContinuity(channelCode, d));
        }
        results.add(checkCrossDayContinuity(channelCode, from, to));
        return results;
    }

    /** 明细里这一行造成的余额变动（分）：收入为正、支出为负，非成功交易为 0 */
    static long deltaOf(ChannelStatement s) {
        if (s.getTradeStatus() != com.payment.ledger.domain.ChannelTradeStatus.SUCCESS) {
            return 0L;
        }
        return switch (s.getBizType()) {
            case RECHARGE -> s.getAmount();
            case WITHDRAW_SUCCESS -> -s.getAmount();
            default -> 0L;
        };
    }

    /** 便于在 detail 里引用余额记录 */
    static String describe(ChannelBalance b) {
        return String.format("%s 期初 %d + 收入 %d − 支出 %d = 期末 %d",
                b.getBalanceDate(), b.getOpeningBalance(),
                b.getIncomeAmount(), b.getExpenseAmount(), b.getClosingBalance());
    }
}
