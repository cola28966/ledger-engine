package com.payment.ledger.recon;

import com.payment.ledger.domain.*;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.BookingResult;
import com.payment.ledger.dto.OurRecord;
import com.payment.ledger.dto.ReconSummary;
import com.payment.ledger.engine.AccountingEngine;
import com.payment.ledger.repository.ChannelStatementRepository;
import com.payment.ledger.repository.ReconRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    public DiffType classify(OurRecord our, ChannelStatement channel) {
        if(our == null && channel == null) {
            throw new IllegalArgumentException("recon our and channel is null");
        }

        DiffType resDiffType = DiffType.MATCHED;
        if(our != null && our.status() == VoucherStatus.PROCESSING){
            resDiffType = DiffType.IN_TRANSIT;
        }
        else if(our != null && channel == null){
            resDiffType =  DiffType.OUR_MORE;
        }else if(our == null){
            resDiffType = channel.getTradeStatus() == ChannelTradeStatus.SUCCESS ? DiffType.CHANNEL_MORE : DiffType.MATCHED;
        }else {
            if(channel.getTradeStatus() == ChannelTradeStatus.FAIL){
                resDiffType = DiffType.STATUS_MISMATCH;
            }else if(our.amount() != channel.getAmount()){
                resDiffType = DiffType.AMOUNT_MISMATCH;
            }else if(our.fee() != channel.getFee()){
                resDiffType = DiffType.FEE_MISMATCH;
            }
        }
        return resDiffType;
    }

    public ReconSummary reconcile(LocalDate reconDate, String channelCode) {
        reconRepo.deletePendingDiffs(reconDate, channelCode);

        List<ReconDiff> reconDiffList = new ArrayList<>();
        List<OurRecord> ourRecordList = reconRepo.findOurRecords(reconDate.minusDays(TOLERANCE_DAYS) , reconDate, RECON_BIZ_TYPES);
        List<ChannelStatement> channelStatementList = statementRepo.findByDate(reconDate, channelCode);

        int matchedCount = 0;
        int inTransitCount = 0;
        // bizOrderNo 不唯一：幂等键是 requestId，同一订单被记两次账幂等挡不住。
        // 这里合并保留一笔，避免整个批次崩在读数据这一步。
        //
        // 注意这只是权宜：重复入账本身就是对账该发现的差异，而 error 日志
        // 没有推动闭环的能力——没人处理它，它就只是一行日志。
        // 彻底的做法是加一种 DiffType，走差异表 + 人工处理流程。
        Map<String, OurRecord> bizOrderOurRecordMap = ourRecordList.stream()
                .collect(Collectors.toMap(
                        OurRecord::bizOrderNo,
                        Function.identity(),
                        (kept, dropped) -> {
                            log.error("我方同一订单号存在多笔记账，疑似重复入账："
                                            + "bizOrderNo={}, 保留={}, 丢弃={}",
                                    kept.bizOrderNo(), kept.voucherNo(), dropped.voucherNo());
                            return kept;
                        }));

        for (ChannelStatement channelStatement : channelStatementList) {
            String bizOrderNo = channelStatement.getBizOrderNo();
            OurRecord ourRecord = bizOrderOurRecordMap.get(bizOrderNo);
            DiffType diffType = classify(ourRecord, channelStatement);
            boolean needsHandling = diffType.needsHandling();
            if(needsHandling){
                ReconDiff reconDiff = buildDiff(reconDate, channelCode, diffType, ourRecord, channelStatement);
                reconDiffList.add(reconDiff);
            }
            else if(diffType == DiffType.IN_TRANSIT){
                inTransitCount++;
            }else if(diffType == DiffType.MATCHED){
                matchedCount++;
            }
            bizOrderOurRecordMap.remove(bizOrderNo);
        }

        inTransitCount += bizOrderOurRecordMap.values().stream().filter(e -> e.status() == VoucherStatus.PROCESSING).count();
        for (OurRecord stringOurRecordEntry : bizOrderOurRecordMap.values()) {
            if(stringOurRecordEntry.status() == VoucherStatus.SUCCESS){
                DiffType diffType = DiffType.OUR_MORE;
                ReconDiff reconDiff = buildDiff(reconDate, channelCode, diffType, stringOurRecordEntry, null);
                reconDiffList.add(reconDiff);
            }
        }

        if(!CollectionUtils.isEmpty(reconDiffList)) {
            List<String> handledOrderNos = reconRepo.findHandledOrderNos(reconDate, channelCode);
            if(!CollectionUtils.isEmpty(handledOrderNos)){
                reconDiffList = reconDiffList.stream().filter(e -> !handledOrderNos.contains(e.getBizOrderNo())).collect(Collectors.toList());
            }
        }

        for (ReconDiff reconDiff : reconDiffList) {
            reconRepo.insertDiff(reconDiff);
        }

        return  new ReconSummary(reconDate, channelCode, ourRecordList.size(), channelStatementList.size(), matchedCount, inTransitCount, reconDiffList);
    }

    public int autoRepair(LocalDate reconDate, String channelCode) {
        List<ReconDiff> reconDiffList = reconRepo.findDiffsByTypeAndStatus(reconDate, channelCode, DiffType.CHANNEL_MORE, DiffStatus.PENDING);

        int autoRepairCnt = 0;
        for (ReconDiff reconDiff : reconDiffList) {
            String channelTradeNo = reconDiff.getChannelTradeNo();

            ChannelStatement channelStatement = statementRepo.findByTradeNo(channelCode, channelTradeNo);
            if(channelStatement == null) {
                continue;
            }

            BizType bizType = channelStatement.getBizType();
            String ourAccountNo = channelStatement.getOurAccountNo();
            String bizOrderNo = channelStatement.getBizOrderNo();
            long amount = channelStatement.getAmount();
            if(bizType != BizType.RECHARGE || !StringUtils.hasText(ourAccountNo)) {
                continue;
            }

            String repairRequestId = repairRequestId(channelCode, channelTradeNo);
            BookingRequest bookingRequest = BookingRequest.builder()
                    .requestId(repairRequestId)
                    .bizType(BizType.RECHARGE)
                    .bizOrderNo(bizOrderNo)
                    .accountingDate(null)
                    .amount(amount)
                    .payeeAccount(ourAccountNo)
                    .build();
            BookingResult book = engine.book(bookingRequest);
            int updatedCnt = reconRepo.markHandled(reconDiff.getDiffId(), DiffStatus.AUTO_REPAIRED, book.getVoucherNo(),"多帐自动差异修复");
            if(updatedCnt > 0) {
                autoRepairCnt++;
            }
        }

        return autoRepairCnt;
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
