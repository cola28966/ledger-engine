package com.payment.ledger.recon;

import com.payment.ledger.domain.BizType;
import com.payment.ledger.domain.ChannelStatement;
import com.payment.ledger.domain.DiffStatus;
import com.payment.ledger.domain.DiffType;
import com.payment.ledger.domain.ReconDiff;
import com.payment.ledger.dto.OurRecord;
import com.payment.ledger.dto.ReconSummary;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.repository.ChannelStatementRepository;
import com.payment.ledger.repository.ReconRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 渠道对账。
 *
 * <p>前四个阶段做的都是「让账记对」，对账做的是<b>「证明账记对了」</b>。
 * 这两件事完全不同：日终结账的五项勾稽全是<b>内部自洽</b>校验——
 * 借贷平衡、余额与流水一致、备付金勾稽，它们能证明我方账本自身没有矛盾，
 * <b>但证明不了账本描述的事情真的发生过</b>。
 *
 * <pre>
 *   一笔充值的回调丢了，我方完全没记账：
 *     借贷平衡 ✓   余额与流水一致 ✓   备付金勾稽 ✓   全部通过
 *   因为压根没有这笔记录，账本内部当然自洽。
 *   而用户的钱已经从银行卡扣走了。
 * </pre>
 *
 * <p><b>只有引入外部事实才能发现这类问题。</b>这就是对账存在的理由：
 * 拿渠道对账单（钱的真实去向）与我方账务（我们以为的去向）逐笔核对。
 *
 * <h3>三个必须做对的地方</h3>
 * <ol>
 *   <li><b>双向核对。</b>只查"我方有渠道无"会漏掉最危险的一类——
 *       渠道有我方无，也就是钱到了但没入账。</li>
 *   <li><b>可重跑。</b>对账是批处理，重跑是常态（文件到得晚、程序挂了、参数改了）。
 *       重跑必须幂等，且不能覆盖人工已处理的结论。</li>
 *   <li><b>差异要能闭环。</b>报出差异只完成一半，每条差异都要能推进到终态。</li>
 * </ol>
 */
@Slf4j
@Service
public class ReconService {

    /**
     * 参与渠道对账的业务类型。
     *
     * <p><b>只有与渠道发生真实资金往来的业务才需要对账。</b>
     * 用户间转账、余额消费都只是我方负债的内部转移，银行侧什么都不会发生，
     * 拿去和渠道对账单比对只会得到满屏的"我方单边账"。
     *
     * <p>对应到会计上：需要对账的正是那些让 {@code BANK_RESERVE}（资产）
     * 发生增减的业务——充值让它增加，提现成功让它减少。
     */
    public static final Set<BizType> RECON_BIZ_TYPES =
            EnumSet.of(BizType.RECHARGE, BizType.WITHDRAW_SUCCESS);

    /**
     * 跨日容差窗口（天）。
     *
     * <p>23:59:58 发起的交易，我方记 T 日账，渠道很可能记进 T+1 日的对账单。
     * 按会计日期精确匹配，这一笔在 T 日是"我方单边账"、在 T+1 日是"渠道单边账"，
     * 同一笔钱被报了两次差异，而它其实完全正常。
     *
     * <p>所以我方侧的查询窗口要向前放宽一天。
     * <b>这不是优化项，是对账能不能用的前提。</b>
     */
    public static final int TOLERANCE_DAYS = 1;

    /** 自动补记账的幂等键前缀 */
    public static final String REPAIR_REQUEST_PREFIX = "RECON_REPAIR_";

    private final ReconRepository reconRepo;
    private final ChannelStatementRepository statementRepo;
    private final AccountingEngine engine;

    public ReconService(ReconRepository reconRepo,
                        ChannelStatementRepository statementRepo,
                        AccountingEngine engine) {
        this.reconRepo = reconRepo;
        this.statementRepo = statementRepo;
        this.engine = engine;
    }

    // ================================================================
    //  TODO 15：单笔定性
    // ================================================================

    /**
     * 判断一笔的对账结果。
     *
     * <p><b>TODO 15：实现这个方法。</b>
     *
     * <p>入参两侧都可能为 null，代表"这一侧没有这笔记录"。
     * 返回 {@link DiffType}，<b>一笔只返回一种</b>——
     * 一笔既金额不符又手续费不符时，只报最严重的那个。
     * 把所有异常维度都报出来，只会让告警从"3 条真问题"变成"30 条噪音"。
     *
     * <p>规则（<b>优先级从高到低，命中即返回</b>）：
     * <ol>
     *   <li>两侧都为 null —— 调用方传错了，抛 {@link IllegalArgumentException}</li>
     *   <li>我方凭证处于 {@code PROCESSING} → {@link DiffType#IN_TRANSIT}。
     *       <b>不管渠道侧是什么状态</b>。我方自己都还没到终态，
     *       此刻拿它和渠道比对没有意义，等下一批</li>
     *   <li>只有我方有 → {@link DiffType#OUR_MORE}</li>
     *   <li>只有渠道有：
     *       <ul>
     *         <li>渠道 {@code SUCCESS} → {@link DiffType#CHANNEL_MORE}</li>
     *         <li>渠道 {@code FAIL} → {@link DiffType#MATCHED}
     *             （渠道认为没成、我方也没记账，两边说的是同一件事，这不是差异）</li>
     *       </ul></li>
     *   <li>两侧都有：
     *       <ul>
     *         <li>渠道 {@code FAIL} → {@link DiffType#STATUS_MISMATCH}
     *             （能走到这里说明我方是 SUCCESS）</li>
     *         <li>金额不等 → {@link DiffType#AMOUNT_MISMATCH}</li>
     *         <li>手续费不等 → {@link DiffType#FEE_MISMATCH}</li>
     *         <li>否则 → {@link DiffType#MATCHED}</li>
     *       </ul></li>
     * </ol>
     *
     * <p><b>为什么状态要排在金额前面：</b>状态不符时，双方连"这笔交易到底成没成"
     * 都没达成一致，此时比较金额得出的任何结论都是无意义的。
     * 顺序写反了，一笔"我方成功 / 渠道失败"的交易会被报成金额不符，
     * 值班的人就会去查金额计算逻辑——查一整天也查不出问题。
     *
     * @param our     我方记录，可能为 null
     * @param channel 渠道记录，可能为 null
     */
    public DiffType classify(OurRecord our, ChannelStatement channel) {
        // TODO 15: 按上面的优先级实现单笔定性
        throw new UnsupportedOperationException("TODO 15: 实现 classify");
    }

    // ================================================================
    //  TODO 16：批次对账
    // ================================================================

    /**
     * 执行一个对账批次。
     *
     * <p><b>TODO 16：实现这个方法。</b>
     *
     * <p>步骤：
     * <ol>
     *   <li><b>清理本批次遗留的待处理差异</b>，用
     *       {@link ReconRepository#deletePendingDiffs}。
     *       对账重跑是常态，不清就会撞上 {@code uk_recon_diff} 唯一索引直接崩掉</li>
     *   <li>取我方记录：{@link ReconRepository#findOurRecords}，
     *       日期窗口 {@code [reconDate - TOLERANCE_DAYS, reconDate]}，
     *       业务类型 {@link #RECON_BIZ_TYPES}</li>
     *   <li>取渠道记录：{@link ChannelStatementRepository#findByDate}</li>
     *   <li>两侧各自按 {@code bizOrderNo} 建索引</li>
     *   <li>逐笔定性，调 {@link #classify}</li>
     *   <li>{@link DiffType#needsHandling()} 为 true 的落库，状态 {@code PENDING}，
     *       用 {@link #buildDiff} 组装。<b>但要跳过</b>
     *       {@link ReconRepository#findHandledOrderNos} 返回的订单号——
     *       那些已经人工处理过了，不能重复报</li>
     *   <li>统计并返回 {@link ReconSummary}：
     *       {@code matchedCount} / {@code inTransitCount} 按 {@link #classify} 的结果统计，
     *       {@code ourCount} / {@code channelCount} 为两侧的原始笔数，
     *       {@code diffs} 只装本次新落库的那些</li>
     * </ol>
     *
     * <p><b>这个方法唯一的难点在第 5 步：要遍历什么。</b>
     * 最自然的写法是遍历我方记录、逐笔去渠道那边找——
     * 这样写出来的对账程序能跑、能出差异、看起来完全正常，
     * 但它<b>永远发现不了"渠道有、我方没有"的那一类</b>，
     * 因为那些订单号压根不在我方的遍历集合里。
     *
     * <p>而那恰恰是最该被发现的一类：钱已经从用户卡里扣走、已经到了银行账户，
     * 我方账上却没有。用户打客服电话之前，没有任何人会知道。
     * 前面四个阶段搭的所有防线——借贷平衡、费率复核、五项勾稽——
     * <b>没有任何一道能发现它</b>，因为它们全都只看我方账本内部。
     *
     * @param reconDate   对账批次日期，也就是渠道对账单的归属日期
     * @param channelCode 渠道标识
     */
    public ReconSummary reconcile(LocalDate reconDate, String channelCode) {
        // TODO 16: 实现双向核对
        throw new UnsupportedOperationException("TODO 16: 实现 reconcile");
    }

    // ================================================================
    //  TODO 17：差异自动修复
    // ================================================================

    /**
     * 对可自动修复的差异补记账。
     *
     * <p><b>TODO 17：实现这个方法。</b>
     *
     * <p>报出差异只完成了对账的一半。剩下一半是让每条差异走到终态，
     * 否则差异表会越积越多，最后没人看——和没有对账是一样的。
     *
     * <p>步骤：
     * <ol>
     *   <li>取本批次 {@link DiffType#CHANNEL_MORE} 且 {@link DiffStatus#PENDING} 的差异
     *       （{@link ReconRepository#findDiffsByTypeAndStatus}）</li>
     *   <li>逐条用 {@code channelTradeNo} 取回渠道原始记录
     *       （{@link ChannelStatementRepository#findByTradeNo}）</li>
     *   <li>不满足自动修复条件的<b>原样跳过</b>，保持 PENDING 等人工：
     *       <ul>
     *         <li>渠道记录的 {@code bizType} 不是 {@link BizType#RECHARGE}</li>
     *         <li>{@code ourAccountNo} 为空</li>
     *       </ul></li>
     *   <li>满足条件的调 {@link AccountingEngine#book} 补记一笔充值：
     *       <ul>
     *         <li>{@code requestId} = {@link #repairRequestId}</li>
     *         <li>{@code accountingDate} = <b>null</b></li>
     *         <li>{@code bizOrderNo} = 渠道记录的 {@code bizOrderNo}
     *             （补记的这笔要能在下次对账时和渠道那行匹配上，
     *             另起一个订单号等于制造一笔新的我方单边账）</li>
     *         <li>{@code payeeAccount} = 渠道记录的 {@code ourAccountNo}</li>
     *         <li>{@code amount} = <b>渠道金额</b>，{@code fee} = 0</li>
     *       </ul></li>
     *   <li>回写 {@link ReconRepository#markHandled}
     *       为 {@link DiffStatus#AUTO_REPAIRED}，带上补记的凭证号。
     *       <b>影响行数为 0 说明这条已被人抢先处理，不计入修复数</b></li>
     *   <li>返回成功修复的笔数</li>
     * </ol>
     *
     * <h3>三个不能想当然的点</h3>
     *
     * <p><b>① 幂等键必须由渠道流水号派生。</b>用 UUID 或时间戳，
     * 对账重跑一次就补记一次，一个窟窿补成三笔重复入账。
     * 而这类重复记账<b>不会被任何勾稽发现</b>——每一笔自己都是借贷平衡的。
     * {@link #repairRequestId} 已经写好，用它。
     *
     * <p><b>② 会计日期传 null，不是 reconDate。</b>对账通常在 T+1 跑，
     * 那时 T 日多半已经关账了。补记账要记在<b>发现日</b>，不是交易日——
     * 和冲正的规则完全一致：已关账的会计期间禁止追溯写入。
     * 传 null 会由 {@code AccountingCalendar} 裁定为当前会计日。
     *
     * <p><b>③ 只有 CHANNEL_MORE 可以自动修，这条边界必须写死在代码里。</b>
     * 为什么只有它：{@code CHANNEL_MORE} 意味着"钱确实到了银行账户"——
     * 这是一个由外部事实确定的、不需要猜的结论，补记账只是让账本追上现实。
     * 其余几类都建立在"某一侧算错了"之上，在没搞清楚哪一侧错、错在哪之前，
     * 任何自动动作都是在<b>用一个错误覆盖另一个错误</b>。
     * 尤其是 {@code AMOUNT_MISMATCH}：自动按渠道金额调平，
     * 会把我方计算逻辑的 bug 悄悄抹掉，等到下个月发现时已经错了几十万笔。
     *
     * @return 成功补记账的笔数
     */
    public int autoRepair(LocalDate reconDate, String channelCode) {
        // TODO 17: 实现差异自动修复
        throw new UnsupportedOperationException("TODO 17: 实现 autoRepair");
    }

    // ================================================================
    //  已备好的工具方法
    // ================================================================

    /**
     * 自动补记账的幂等键。
     *
     * <p>由<b>渠道流水号</b>派生：同一笔渠道流水，无论对账重跑多少次，
     * 算出来的 requestId 永远相同，第二次会被记账引擎的幂等防线挡回来。
     *
     * <p>注意这里没有拼 {@code reconDate}——那样 T 日和 T+1 日各补一次就成了两笔。
     * <b>幂等键只能由"这件事本身"决定，不能掺进"什么时候发现的"。</b>
     */
    public static String repairRequestId(String channelCode, String channelTradeNo) {
        return REPAIR_REQUEST_PREFIX + channelCode + "_" + channelTradeNo;
    }

    /**
     * 组装一条差异记录。两侧都可能为 null。
     */
    public ReconDiff buildDiff(LocalDate reconDate, String channelCode, DiffType type,
                               OurRecord our, ChannelStatement channel) {
        return ReconDiff.builder()
                .reconDate(reconDate)
                .channelCode(channelCode)
                .bizOrderNo(our != null ? our.bizOrderNo() : channel.getBizOrderNo())
                .channelTradeNo(channel != null ? channel.getChannelTradeNo() : null)
                .diffType(type)
                .ourAmount(our != null ? our.amount() : 0L)
                .channelAmount(channel != null ? channel.getAmount() : 0L)
                .ourFee(our != null ? our.fee() : 0L)
                .channelFee(channel != null ? channel.getFee() : 0L)
                .ourVoucherNo(our != null ? our.voucherNo() : null)
                .status(DiffStatus.PENDING)
                .remark(describe(type, our, channel))
                .build();
    }

    private static String describe(DiffType type, OurRecord our, ChannelStatement channel) {
        return switch (type) {
            case OUR_MORE -> "我方有记录、渠道对账单无此笔，疑似我方误判成功";
            case CHANNEL_MORE -> "渠道已成功、我方无记录，疑似回调丢失或记账失败";
            case AMOUNT_MISMATCH -> String.format("金额不符：我方 %d，渠道 %d",
                    our.amount(), channel.getAmount());
            case FEE_MISMATCH -> String.format("手续费不符：我方 %d，渠道 %d",
                    our.fee(), channel.getFee());
            case STATUS_MISMATCH -> "状态不符：我方成功、渠道失败";
            case MATCHED, IN_TRANSIT -> null;
        };
    }

    /** 差异清单文本，可直接发告警 */
    public String reportOf(LocalDate reconDate, String channelCode) {
        List<ReconDiff> diffs = reconRepo.findDiffs(reconDate, channelCode);
        if (diffs.isEmpty()) {
            return String.format("对账 %s/%s：无差异", reconDate, channelCode);
        }
        StringBuilder sb = new StringBuilder(String.format(
                "对账 %s/%s：%d 笔差异%n", reconDate, channelCode, diffs.size()));
        for (ReconDiff d : diffs) {
            sb.append(String.format("  [%s] %s 我方 %d / 渠道 %d  状态=%s%n",
                    d.getDiffType(), d.getBizOrderNo(),
                    d.getOurAmount(), d.getChannelAmount(), d.getStatus()));
        }
        return sb.toString();
    }
}
