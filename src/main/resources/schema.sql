-- ============================================================
-- 支付账务系统 - 五张核心表
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8.x
-- ============================================================

DROP TABLE IF EXISTS account_serial;
DROP TABLE IF EXISTS accounting_entry;
DROP TABLE IF EXISTS voucher;
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
    version           INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (account_no)
);

CREATE INDEX idx_account_owner ON account (owner_id);

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
    created_at      TIMESTAMP    NOT NULL,
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
    created_at      TIMESTAMP    NOT NULL,
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
    created_at      TIMESTAMP    NOT NULL,
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
    opened_at       TIMESTAMP,
    closed_at       TIMESTAMP,
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
CREATE TABLE balance_snapshot (
    accounting_date DATE         NOT NULL,
    account_no      VARCHAR(32)  NOT NULL,
    opening_balance BIGINT       NOT NULL,
    debit_amount    BIGINT       NOT NULL,
    credit_amount   BIGINT       NOT NULL,
    closing_balance BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (accounting_date, account_no)
);
