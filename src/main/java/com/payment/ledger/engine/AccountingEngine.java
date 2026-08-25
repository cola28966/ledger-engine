package com.payment.ledger.engine;

import com.payment.ledger.domain.*;
import com.payment.ledger.dto.BookingRequest;
import com.payment.ledger.dto.BookingResult;
import com.payment.ledger.dto.EntryCommand;
import com.payment.ledger.exception.LedgerException;
import com.payment.ledger.repository.AccountRepository;
import com.payment.ledger.repository.EntryRepository;
import com.payment.ledger.repository.SerialRepository;
import com.payment.ledger.repository.VoucherRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 记账引擎。整个账务系统的核心。
 *
 * <p>记账主流程：
 * <pre>
 *   幂等预检 → 生成分录组 → 借贷平衡校验 → 【本地事务】写凭证 + 改余额 + 写分录 + 写流水
 * </pre>
 *
 * <p>三条不可动摇的设计约束：
 * <ol>
 *   <li><b>一组分录必须在同一个本地事务内落库</b>，且落库前校验借贷平衡</li>
 *   <li><b>记账必须幂等</b>，且必须可按 requestId 查询——
 *       幂等接口 + 可查询 + 对账补偿，比任何分布式事务框架都可靠</li>
 *   <li><b>已落库的分录永不 UPDATE、永不 DELETE</b>，只能红冲</li>
 * </ol>
 */
@Slf4j
@Service
public class AccountingEngine {

    private final AccountRepository accountRepo;
    private final VoucherRepository voucherRepo;
    private final EntryRepository entryRepo;
    private final SerialRepository serialRepo;
    private final EntryGenerator generator;
    private final BalanceValidator validator;
    private final FeeValidator feeValidator;
    private final AccountingCalendar calendar;
    private final HotAccountRouter router;
    private final TransactionTemplate tx;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final AtomicLong SEQ = new AtomicLong(0);

    public AccountingEngine(AccountRepository accountRepo,
                            VoucherRepository voucherRepo,
                            EntryRepository entryRepo,
                            SerialRepository serialRepo,
                            EntryGenerator generator,
                            BalanceValidator validator,
                            FeeValidator feeValidator,
                            AccountingCalendar calendar,
                            HotAccountRouter router,
                            PlatformTransactionManager txManager) {
        this.accountRepo = accountRepo;
        this.voucherRepo = voucherRepo;
        this.entryRepo = entryRepo;
        this.serialRepo = serialRepo;
        this.generator = generator;
        this.validator = validator;
        this.feeValidator = feeValidator;
        this.calendar = calendar;
        this.router = router;
        this.tx = new TransactionTemplate(txManager);
    }

    // ================================================================
    //  记账
    // ================================================================

    /**
     * 执行一次记账。
     *
     * <p><b>幂等三层防护：</b>
     * <ol>
     *   <li>应用层：先查幂等表，命中直接返回（挡住 99% 的重复）</li>
     *   <li>数据库层：request_id 唯一索引，并发下靠 DB 兜底</li>
     *   <li>代码层：捕获 DuplicateKeyException 转为"幂等命中"返回，而不是抛错</li>
     * </ol>
     */
    public BookingResult book(BookingRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw LedgerException.invalidRequest("requestId 不能为空");
        }
        // ── 防线一：会计日期由日历统一裁定 ──────────────────────
        // 传了就校验（已关账/日切中一律拒绝），没传就取当前会计日。
        // 引擎内部永远不会出现 LocalDate.now()。
        req.setAccountingDate(calendar.resolve(req.getAccountingDate()));

        // ── 防线二：费率复核 ────────────────────────────────────
        // 借贷平衡挡不住"金额算错"——20000 = 18800 + 1200 也是平的。
        // 这里按签约费率独立试算一遍，与上游传入的 fee 比对，不一致拒绝记账。
        feeValidator.verify(req.getBizType(), req.getMerchantId(),
                req.getAmount(), req.getFee(), req.getAccountingDate());

        String requestId = req.getRequestId();
        Voucher voucher = voucherRepo.findByRequestId(requestId);
        if(voucher != null){
            return BookingResult.idempotentHit(voucher.getVoucherNo());
        }

        try{
            return tx.execute(status -> doBook(req));
        }catch (DuplicateKeyException e){
            voucher = voucherRepo.findByRequestId(requestId);
            if(voucher != null){
                return BookingResult.idempotentHit(voucher.getVoucherNo());
            }
            throw e;
        }
    }

    private BookingResult doBook(BookingRequest req) {
        // 1. 生成分录组
        List<EntryCommand> entries = generator.generate(req);

        // 2. 借贷平衡校验 —— 不平衡的分录永远不允许落库
        long totalAmount = validator.validate(entries);

        // 2.5 热点账户路由：把逻辑主户换成具体的桶账号。
        //     只换账号、不动方向和金额，所以平衡性不受影响。
        entries = routeToBuckets(entries, req.getRequestId());

        // 3. 写凭证（第 2 层：request_id 唯一索引在这里拦住并发重复）
        String voucherNo = nextVoucherNo(req.getAccountingDate());
        Voucher v = new Voucher();
        v.setVoucherNo(voucherNo);
        v.setRequestId(req.getRequestId());
        v.setBizType(req.getBizType());
        v.setBizOrderNo(req.getBizOrderNo());
        v.setAccountingDate(req.getAccountingDate());
        v.setTotalAmount(totalAmount);
        v.setStatus(VoucherStatus.SUCCESS);
        v.setRemark(req.getRemark());
        voucherRepo.insert(v);

        // 4. 逐条落地：改余额 + 写分录 + 写流水（全部在同一事务内）
        applyEntriesInLockOrder(voucherNo, req.getBizType(), req.getAccountingDate(), entries);

        log.info("记账成功: voucherNo={}, bizType={}, amount={}", voucherNo, req.getBizType(), totalAmount);
        return BookingResult.success(voucherNo);
    }

    /**
     * 把逻辑热点账户替换成具体的桶账号。
     *
     * <p>路由键固定用 {@code requestId}：幂等重试时同一请求必须落回同一个桶，
     * 否则重试会在另一个桶上再记一笔。
     */
    private List<EntryCommand> routeToBuckets(List<EntryCommand> entries, String requestId) {
        return entries.stream()
                .map(c -> router.isBucketed(c.getAccountNo())
                        ? new EntryCommand(router.route(c.getAccountNo(), requestId),
                                           c.getDirection(), c.getAmount())
                        : c)
                .toList();
    }

    /**
     * 按<b>固定的账号顺序</b>依次更新余额，避免并发死锁。
     *
     * <p>压测实测：转账场景下 {@code A→B} 与 {@code B→A} 同时发生时，
     * 两个事务的加锁顺序相反，MySQL 上有 <b>25% 的请求因死锁被回滚</b>。
     *
     * @param entries 分录组。其在列表中的原始位置即 entry_seq（业务语义顺序：借在前、贷在后）
     */
    private void applyEntriesInLockOrder(String voucherNo, BizType bizType,
                                         LocalDate accountingDate, List<EntryCommand> entries) {
        List<SeqEntryCommand> commands = new ArrayList<>(entries.size());


        for (int i = 0; i < entries.size(); i++) {
            commands.add(new SeqEntryCommand(i + 1, entries.get(i)));
        }

        commands.sort(
                Comparator.comparing((SeqEntryCommand item) -> item.command().getAccountNo())
                        .thenComparingInt(SeqEntryCommand::seq)
        );

        // 3. 按排序后的顺序执行，但 entry_seq 使用原始业务 seq
        for (SeqEntryCommand item : commands) {
            applyEntry(
                    voucherNo,
                    bizType,
                    accountingDate,
                    item.command(),
                    item.seq()
            );
        }
    }

    private record SeqEntryCommand(int seq, EntryCommand command) {


    }


    /**
     * 把一条分录落到账上：更新余额 → 写会计分录 → 写账户流水。
     */
    private void applyEntry(String voucherNo, BizType bizType, LocalDate accountingDate,
                            EntryCommand cmd, int seq) {

        Account acc = accountRepo.findByNo(cmd.getAccountNo());
        if (acc == null) {
            throw LedgerException.accountNotFound(cmd.getAccountNo());
        }

        // 分录方向 == 账户余额方向 → 余额增加；否则减少
        long delta = acc.isIncrease(cmd.getDirection()) ? cmd.getAmount() : -cmd.getAmount();

        // 关键：余额充足性校验内置在 UPDATE 的 WHERE 里，看影响行数
        int rows = accountRepo.applyDelta(cmd.getAccountNo(), delta);
        if (rows == 0) {
            throw LedgerException.insufficientBalance(cmd.getAccountNo());
        }

        // 事务内重新读取，拿到准确的记账后余额
        long balanceAfter = accountRepo.findByNo(cmd.getAccountNo()).getBalance();
        long balanceBefore = balanceAfter - delta;

        AccountingEntry entry = new AccountingEntry();
        entry.setVoucherNo(voucherNo);
        entry.setEntrySeq(seq);
        entry.setAccountNo(cmd.getAccountNo());
        entry.setSubjectCode(acc.getSubjectCode());
        entry.setDirection(cmd.getDirection());
        entry.setAmount(cmd.getAmount());
        entry.setAccountingDate(accountingDate);
        entryRepo.insert(entry);

        AccountSerial serial = new AccountSerial();
        serial.setAccountNo(cmd.getAccountNo());
        serial.setVoucherNo(voucherNo);
        serial.setSerialType(SerialType.BOOKING);
        serial.setDirection(cmd.getDirection());
        serial.setAmount(cmd.getAmount());
        serial.setBalanceBefore(balanceBefore);
        serial.setBalanceAfter(balanceAfter);
        serial.setBizType(bizType);
        serial.setAccountingDate(accountingDate);
        serialRepo.insert(serial);
    }

    // ================================================================
    //  冲正
    // ================================================================

    /**
     * 冲正（红冲）：生成一张与原凭证<b>完全镜像</b>的反向凭证。
     *
     * <p><b>这是修正错账的唯一合规方式。</b>绝不 UPDATE、绝不 DELETE 原凭证，
     * 也绝不"算差额调整"——差额法会让审计链断裂（账上会出现一笔不对应任何真实业务的金额），
     * 且在一借多贷等复杂分录下根本算不出来。
     *
     * <p>全额镜像的实现只有一句话：<b>方向取反，其余字段照抄</b>。
     * 这个算法能通用于任何复杂度的分录组。
     *
     * @param originVoucherNo 原凭证号
     * @param requestId       冲正操作自己的幂等键（冲正本身也必须幂等）
     * @param accountingDate  冲正的会计日期。<b>必须是当前会计日，不能记回原凭证的日期</b>——
     *                        已关账的会计期间禁止追溯修改
     */
    public BookingResult reverse(String originVoucherNo, String requestId, LocalDate accountingDate) {
        Voucher exist = voucherRepo.findByRequestId(requestId);
        if (exist != null) {
            return BookingResult.idempotentHit(exist.getVoucherNo());
        }

        Voucher origin = voucherRepo.findByNo(originVoucherNo);
        if (origin == null) {
            throw LedgerException.voucherNotFound(originVoucherNo);
        }
        if (origin.getStatus() == VoucherStatus.REVERSED) {
            throw LedgerException.alreadyReversed(originVoucherNo);
        }

        try {
            return tx.execute(status -> doReverse(origin, requestId, accountingDate));
        } catch (DuplicateKeyException e) {
            Voucher v = voucherRepo.findByRequestId(requestId);
            if (v == null) {
                throw e;
            }
            return BookingResult.idempotentHit(v.getVoucherNo());
        }
    }

    private BookingResult doReverse(Voucher origin, String requestId, LocalDate accountingDate) {
        List<AccountingEntry> originEntries = entryRepo.findByVoucherNo(origin.getVoucherNo());
        if (originEntries.isEmpty()) {
            throw LedgerException.voucherNotFound(origin.getVoucherNo());
        }

        List<EntryCommand> reversed =
                originEntries.stream().map(accountingEntry ->
                                new EntryCommand(accountingEntry.getAccountNo(), accountingEntry.getDirection().opposite(),
                                        accountingEntry.getAmount())
                        ).collect(Collectors.toList());

        long totalAmount = validator.validate(reversed);

        String voucherNo = nextVoucherNo(accountingDate);
        Voucher v = new Voucher();
        v.setVoucherNo(voucherNo);
        v.setRequestId(requestId);
        v.setBizType(origin.getBizType());
        v.setBizOrderNo(origin.getBizOrderNo());
        v.setAccountingDate(accountingDate);
        v.setTotalAmount(totalAmount);
        v.setStatus(VoucherStatus.SUCCESS);
        v.setReverseOf(origin.getVoucherNo());
        v.setRemark("冲正 " + origin.getVoucherNo());
        voucherRepo.insert(v);

        // 冲正不再路由：原分录里记的已经是具体的桶账号，
        // 必须原样冲回同一个桶，否则钱会从别的桶里扣走
        applyEntriesInLockOrder(voucherNo, origin.getBizType(), accountingDate, reversed);

        // 原凭证只打标记，不修改任何金额，形成完整审计链
        int rows = voucherRepo.markReversed(origin.getVoucherNo(), voucherNo);
        if (rows == 0) {
            throw LedgerException.alreadyReversed(origin.getVoucherNo());
        }

        log.info("冲正成功: origin={}, reverse={}", origin.getVoucherNo(), voucherNo);
        return BookingResult.success(voucherNo);
    }

    // ================================================================
    //  冻结 / 解冻
    // ================================================================

    /**
     * 冻结：balance 不变，available → frozen。
     *
     * <p>因为 balance 不变，总账层面资金归属没有变化，所以<b>不产生会计分录</b>；
     * 但<b>必须产生账户流水</b>，否则冻结记录无法追溯。
     */
    public void freeze(String accountNo, long amount, LocalDate accountingDate) {
        tx.executeWithoutResult(status -> {
            Account acc = accountRepo.findByNo(accountNo);
            if (acc == null) {
                throw LedgerException.accountNotFound(accountNo);
            }
            int rows = accountRepo.freeze(accountNo, amount);
            if (rows == 0) {
                throw LedgerException.insufficientBalance(accountNo);
            }
            writeFreezeSerial(accountNo, amount, SerialType.FREEZE, acc.getBalance(), accountingDate);
        });
    }

    /** 解冻：balance 不变，frozen → available。同样只有流水，没有分录 */
    public void unfreeze(String accountNo, long amount, LocalDate accountingDate) {
        tx.executeWithoutResult(status -> {
            Account acc = accountRepo.findByNo(accountNo);
            if (acc == null) {
                throw LedgerException.accountNotFound(accountNo);
            }
            int rows = accountRepo.unfreeze(accountNo, amount);
            if (rows == 0) {
                throw LedgerException.insufficientBalance(accountNo);
            }
            writeFreezeSerial(accountNo, amount, SerialType.UNFREEZE, acc.getBalance(), accountingDate);
        });
    }

    private void writeFreezeSerial(String accountNo, long amount, SerialType type,
                                   long balance, LocalDate accountingDate) {
        AccountSerial s = new AccountSerial();
        s.setAccountNo(accountNo);
        s.setSerialType(type);
        s.setAmount(amount);
        // 冻结/解冻不改变 balance，所以前后余额相同
        s.setBalanceBefore(balance);
        s.setBalanceAfter(balance);
        s.setAccountingDate(accountingDate);
        serialRepo.insert(s);
    }

    // ================================================================

    private String nextVoucherNo(LocalDate accountingDate) {
        return "V" + accountingDate.format(DATE_FMT)
                + String.format("%012d", SEQ.incrementAndGet());
    }
}
