-- ============================================================
-- 支付账务系统 - 五张核心表
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8.x
-- ============================================================

DROP TABLE IF EXISTS recon_diff;
DROP TABLE IF EXISTS channel_statement;
DROP TABLE IF EXISTS accounting_template;
DROP TABLE IF EXISTS balance_snapshot;
DROP TABLE IF EXISTS account_serial;
DROP TABLE IF EXISTS accounting_entry;
DROP TABLE IF EXISTS voucher;
DROP TABLE IF EXISTS fee_rule;
DROP TABLE IF EXISTS accounting_calendar;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS subject;

-- ------------------------------------------------------------
-- 1. 会计科目表：账户的分类模板
-- ------------------------------------------------------------
CREATE TABLE subject (
    subject_code      VARCHAR(32)  NOT NULL,
    subject_name      VARCHAR(64)  NOT NULL,
    parent_code       VARCHAR(32),
    -- ASSET 资产 / LIABILITY 负债 / EQUITY 权益 / INCOME 收入 / EXPENSE 费用
    subject_type      VARCHAR(16)  NOT NULL,
    -- 余额方向 DR 借 / CR 贷
    balance_direction VARCHAR(2)   NOT NULL,
    is_leaf           BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (subject_code)
);

-- ------------------------------------------------------------
-- 2. 账户表：余额的载体
--    金额单位统一为「分」，用 BIGINT，永不使用浮点
-- ------------------------------------------------------------
CREATE TABLE account (
    account_no        VARCHAR(32)  NOT NULL,
    account_name      VARCHAR(64)  NOT NULL,
    subject_code      VARCHAR(32)  NOT NULL,
    owner_id          VARCHAR(64),
    -- USER 用户户 / MERCHANT 商户户 / INTERNAL 内部户
    account_type      VARCHAR(16)  NOT NULL,
    currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    -- 该账户的余额方向，与所属科目一致
    balance_direction VARCHAR(2)   NOT NULL,
    -- balance 存的是「余额方向上的正数」：
    --   资产户 balance=1000 表示借方余额 1000
    --   负债户 balance=1000 表示贷方余额 1000
    balance           BIGINT       NOT NULL DEFAULT 0,
    available_balance BIGINT       NOT NULL DEFAULT 0,
    frozen_balance    BIGINT       NOT NULL DEFAULT 0,
    -- NORMAL 正常 / FROZEN 冻结 / IN_ONLY 只收不付 / CLOSED 销户
    status            VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    -- 是否允许余额为负。仅极少数内部过渡户可为 TRUE，用户户/商户户永远 FALSE
    allow_negative    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 热点分桶数。0 表示不分桶；>0 表示该账户是"逻辑主户"，
    -- 真实余额分散在 N 个子桶上，记账时按 requestId 哈希路由到某一桶。
    -- 目的：把所有请求争抢的那一行，拆成 N 行并行更新。
    bucket_count      INT          NOT NULL DEFAULT 0,
    -- 桶账户指向其逻辑主户；主户和普通账户为 NULL
    parent_account_no VARCHAR(32),
    version           INT          NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (account_no)
);

CREATE INDEX idx_account_owner ON account (owner_id);
CREATE INDEX idx_account_parent ON account (parent_account_no);

-- ------------------------------------------------------------
-- 3. 记账凭证表：一次记账请求 = 一张凭证 = 一组分录
--    request_id 唯一索引是幂等的核心防线
-- ------------------------------------------------------------
CREATE TABLE voucher (
    voucher_no      VARCHAR(40)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    biz_type        VARCHAR(32)  NOT NULL,
    biz_order_no    VARCHAR(64)  NOT NULL,
    -- 会计日期，绝不等于 now()，由上游传入或由会计日历裁定
    accounting_date DATE         NOT NULL,
    total_amount    BIGINT       NOT NULL,
    -- PROCESSING 记账中 / SUCCESS 成功 / REVERSED 已被冲正
    status          VARCHAR(16)  NOT NULL,
    -- 本凭证是哪张凭证的冲正凭证
    reverse_of      VARCHAR(40),
    -- 本凭证被哪张凭证冲正了
    reversed_by     VARCHAR(40),
    remark          VARCHAR(255),
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (voucher_no),
    CONSTRAINT uk_voucher_request UNIQUE (request_id)
);

CREATE INDEX idx_voucher_biz_order ON voucher (biz_order_no);
CREATE INDEX idx_voucher_acc_date ON voucher (accounting_date);

-- ------------------------------------------------------------
-- 4. 会计分录表：面向财务/总账，组内必须借贷平衡
-- ------------------------------------------------------------
CREATE TABLE accounting_entry (
    entry_id        BIGINT       AUTO_INCREMENT,
    voucher_no      VARCHAR(40)  NOT NULL,
    entry_seq       INT          NOT NULL,
    account_no      VARCHAR(32)  NOT NULL,
    subject_code    VARCHAR(32)  NOT NULL,
    direction       VARCHAR(2)   NOT NULL,
    amount          BIGINT       NOT NULL,
    accounting_date DATE         NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (entry_id)
);

CREATE INDEX idx_entry_voucher ON accounting_entry (voucher_no);
CREATE INDEX idx_entry_acc_date ON accounting_entry (accounting_date);

-- ------------------------------------------------------------
-- 5. 账户流水表：面向用户/商户/客服
--    balance_after 是排查串号、漏记、重复记账的利器
--    冻结/解冻不产生会计分录，但必须产生账户流水
-- ------------------------------------------------------------
CREATE TABLE account_serial (
    serial_no       BIGINT       AUTO_INCREMENT,
    account_no      VARCHAR(32)  NOT NULL,
    voucher_no      VARCHAR(40),
    -- BOOKING 记账 / FREEZE 冻结 / UNFREEZE 解冻
    serial_type     VARCHAR(16)  NOT NULL,
    direction       VARCHAR(2),
    amount          BIGINT       NOT NULL,
    balance_before  BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    biz_type        VARCHAR(32),
    accounting_date DATE         NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (serial_no)
);

CREATE INDEX idx_serial_account ON account_serial (account_no, accounting_date);
CREATE INDEX idx_serial_voucher ON account_serial (voucher_no);

-- ------------------------------------------------------------
-- 6. 会计日历：会计日期的唯一权威来源
--    绝不允许各模块各自 now()，否则跨日切必然串账
-- ------------------------------------------------------------
CREATE TABLE accounting_calendar (
    accounting_date DATE         NOT NULL,
    -- OPEN 开放记账 / CUTTING 日切中（暂停记账）/ CLOSED 已关账（禁止追溯）
    status          VARCHAR(16)  NOT NULL,
    opened_at       DATETIME,
    closed_at       DATETIME,
    PRIMARY KEY (accounting_date)
);

-- ------------------------------------------------------------
-- 7. 计费规则：费率复核的依据
--    费率用「基点 bp」存整数，万分之一为 1 bp —— 0.6% = 60 bp。
--    绝不用 double 存 0.006，那是资损的开始。
-- ------------------------------------------------------------
CREATE TABLE fee_rule (
    rule_id        BIGINT       AUTO_INCREMENT,
    biz_type       VARCHAR(32)  NOT NULL,
    -- NULL 表示该业务类型的默认规则；有值表示商户专属协议价
    merchant_id    VARCHAR(64),
    -- 基点：万分之一。60 = 0.6%
    rate_bp        INT          NOT NULL,
    -- 保底手续费（分）。小额交易按费率算出来可能不足一分，用它兜底
    min_fee        BIGINT       NOT NULL DEFAULT 0,
    -- 封顶手续费（分）。NULL 表示不封顶
    max_fee        BIGINT,
    -- 取整规则：HALF_UP 四舍五入 / UP 向上取整 / DOWN 截断
    rounding_mode  VARCHAR(16)  NOT NULL DEFAULT 'HALF_UP',
    effective_date DATE         NOT NULL,
    expire_date    DATE,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (rule_id)
);

CREATE INDEX idx_fee_rule_lookup ON fee_rule (biz_type, merchant_id, status);

-- ------------------------------------------------------------
-- 8. 日终余额快照
--    勾稽等式：期末 = 期初 + 本期借方 - 本期贷方（借方科目；贷方科目反向）
--    让"查询任意历史日期余额"变成 O(1)
-- ------------------------------------------------------------
-- ------------------------------------------------------------
-- 9. 记账模板：把「业务类型 → 分录组」的映射从代码搬进配置
--
--    业务类型会无限增长（新支付方式、新营销玩法、新分账模式），
--    每加一种就改一次代码、发一次版，是账务系统最典型的效率瓶颈。
--    配置化之后，新增业务只需插几行记录。
--
--    account_rule / amount_rule 是 SpEL 表达式，求值上下文为 BookingRequest：
--      账户：payerAccount / payeeAccount / 'FEE_INCOME'（单引号为字面量）
--      金额：amount / fee / amount - fee
-- ------------------------------------------------------------
CREATE TABLE accounting_template (
    template_id    BIGINT       AUTO_INCREMENT,
    biz_type       VARCHAR(32)  NOT NULL,
    -- 组内序号，同时决定 entry_seq（业务语义顺序：借在前、贷在后）
    entry_seq      INT          NOT NULL,
    direction      VARCHAR(2)   NOT NULL,
    account_rule   VARCHAR(128) NOT NULL,
    amount_rule    VARCHAR(128) NOT NULL,
    remark         VARCHAR(255),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (template_id)
);

CREATE INDEX idx_template_biz ON accounting_template (biz_type, status);

CREATE TABLE balance_snapshot (
    accounting_date DATE         NOT NULL,
    account_no      VARCHAR(32)  NOT NULL,
    opening_balance BIGINT       NOT NULL,
    debit_amount    BIGINT       NOT NULL,
    credit_amount   BIGINT       NOT NULL,
    closing_balance BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (accounting_date, account_no)
);

-- ------------------------------------------------------------
-- 10. 渠道对账单明细：从渠道下载的对账文件，解析后逐行落库
--
--     这张表是「外部事实」的镜像，代表钱在银行/渠道那一侧真实发生了什么。
--     我方账务是「内部记录」。对账 = 两者逐笔核对。
--
--     落库即冻结：解析入库之后永不修改。渠道重发对账单只能新增批次，
--     否则「昨天对平了、今天数据变了」这种事根本查不清。
-- ------------------------------------------------------------
CREATE TABLE channel_statement (
    id               BIGINT       AUTO_INCREMENT,
    -- 渠道标识：UNIONPAY / ALIPAY / WECHAT ...
    channel_code     VARCHAR(32)  NOT NULL,
    -- 渠道侧流水号。渠道内唯一，是补记账幂等键的来源
    channel_trade_no VARCHAR(64)  NOT NULL,
    -- 平台订单号 —— 双方唯一都认的匹配键
    biz_order_no     VARCHAR(64)  NOT NULL,
    -- 渠道的交易类型映射到我方 BizType。解析对账单时完成映射，
    -- 差异自动修复要靠它判断该补记哪种账 —— 代收和代付补反了就是双倍窟窿
    biz_type         VARCHAR(32)  NOT NULL,
    -- 交易金额（分）
    amount           BIGINT       NOT NULL,
    -- 渠道向我方收取的通道费（分）。我方账上应有等额的成本，没有就是漏记
    fee              BIGINT       NOT NULL DEFAULT 0,
    -- SUCCESS 成功 / FAIL 失败
    trade_status     VARCHAR(16)  NOT NULL,
    -- 对账单归属日期。注意：它不一定等于我方的会计日期，跨日临界必然错开
    statement_date   DATE         NOT NULL,
    -- 入账账户。渠道对账单本身没有这个字段，是解析入库时反查订单系统补上的。
    -- 差异自动补记账需要它——否则知道"少了一笔钱"，却不知道该记给谁
    our_account_no   VARCHAR(32),
    trade_time       DATETIME,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    -- 同一渠道的同一笔流水只允许入库一次：挡住对账单重复导入
    CONSTRAINT uk_stmt_trade UNIQUE (channel_code, channel_trade_no)
);

CREATE INDEX idx_stmt_lookup ON channel_statement (statement_date, channel_code);
CREATE INDEX idx_stmt_order ON channel_statement (biz_order_no);

-- ------------------------------------------------------------
-- 11. 对账差异表：对账的产物
--
--     对账不产出「对账单」，产出的是「差异清单」——对平的部分不需要留痕，
--     不平的部分每一条都必须能追踪到闭环。
--
--     status 是一个状态机：PENDING → AUTO_REPAIRED / MANUAL_RESOLVED / IGNORED。
--     对账批次可以重跑，但重跑绝不能把人工处理结果冲掉。
-- ------------------------------------------------------------
CREATE TABLE recon_diff (
    diff_id           BIGINT       AUTO_INCREMENT,
    recon_date        DATE         NOT NULL,
    channel_code      VARCHAR(32)  NOT NULL,
    biz_order_no      VARCHAR(64)  NOT NULL,
    channel_trade_no  VARCHAR(64),
    -- OUR_MORE 我方单边 / CHANNEL_MORE 渠道单边 / AMOUNT_MISMATCH 金额不符
    -- / FEE_MISMATCH 手续费不符 / STATUS_MISMATCH 状态不符
    diff_type         VARCHAR(32)  NOT NULL,
    our_amount        BIGINT       NOT NULL DEFAULT 0,
    channel_amount    BIGINT       NOT NULL DEFAULT 0,
    our_fee           BIGINT       NOT NULL DEFAULT 0,
    channel_fee       BIGINT       NOT NULL DEFAULT 0,
    our_voucher_no    VARCHAR(40),
    -- PENDING 待处理 / AUTO_REPAIRED 已自动补记 / MANUAL_RESOLVED 已人工处理 / IGNORED 已忽略
    status            VARCHAR(16)  NOT NULL,
    -- 自动补记账产生的凭证号，形成「差异 → 处理动作」的审计链
    repair_voucher_no VARCHAR(40),
    remark            VARCHAR(255),
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (diff_id),
    -- 一个批次里同一笔订单只允许有一条差异记录。
    -- 这是「重跑幂等」的数据库兜底：应用层忘了去重，这里会直接拒绝
    CONSTRAINT uk_recon_diff UNIQUE (recon_date, channel_code, biz_order_no)
);

CREATE INDEX idx_recon_batch ON recon_diff (recon_date, channel_code, status);
