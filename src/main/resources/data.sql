-- ============================================================
-- 初始化：会计科目树 + 测试账户
-- ============================================================

-- ---------------- 资产类 ----------------
INSERT INTO subject VALUES ('1001',   '银行存款',            NULL,   'ASSET',     'DR', FALSE);
INSERT INTO subject VALUES ('100101', '备付金存管户',        '1001', 'ASSET',     'DR', TRUE);
INSERT INTO subject VALUES ('100102', '自有资金账户',        '1001', 'ASSET',     'DR', TRUE);
INSERT INTO subject VALUES ('1122',   '应收账款',            NULL,   'ASSET',     'DR', FALSE);
INSERT INTO subject VALUES ('112201', '应收渠道款',          '1122', 'ASSET',     'DR', TRUE);
INSERT INTO subject VALUES ('112202', '应收账款-商户垫付款', '1122', 'ASSET',     'DR', TRUE);

-- ---------------- 负债类 ----------------
-- 注意：客户备付金下的每一个子科目，都必须对应备付金存管账户里真实存在的一笔钱
INSERT INTO subject VALUES ('2241',   '客户备付金',          NULL,   'LIABILITY', 'CR', FALSE);
INSERT INTO subject VALUES ('224101', '用户余额',            '2241', 'LIABILITY', 'CR', TRUE);
INSERT INTO subject VALUES ('224102', '商户待结算',          '2241', 'LIABILITY', 'CR', TRUE);
INSERT INTO subject VALUES ('224103', '担保交易中间户',      '2241', 'LIABILITY', 'CR', TRUE);
INSERT INTO subject VALUES ('224104', '提现在途',            '2241', 'LIABILITY', 'CR', TRUE);
INSERT INTO subject VALUES ('224105', '商户保证金',          '2241', 'LIABILITY', 'CR', TRUE);
INSERT INTO subject VALUES ('2202',   '应付账款',            NULL,   'LIABILITY', 'CR', FALSE);
INSERT INTO subject VALUES ('220201', '应付渠道手续费',      '2202', 'LIABILITY', 'CR', TRUE);

-- ---------------- 收入类 ----------------
INSERT INTO subject VALUES ('6001',   '主营业务收入',        NULL,   'INCOME',    'CR', FALSE);
INSERT INTO subject VALUES ('600101', '支付手续费收入',      '6001', 'INCOME',    'CR', TRUE);
INSERT INTO subject VALUES ('600102', '提现手续费收入',      '6001', 'INCOME',    'CR', TRUE);

-- ---------------- 费用类 ----------------
INSERT INTO subject VALUES ('6401',   '主营业务成本',        NULL,   'EXPENSE',   'DR', FALSE);
INSERT INTO subject VALUES ('640101', '渠道通道成本',        '6401', 'EXPENSE',   'DR', TRUE);
INSERT INTO subject VALUES ('6601',   '销售费用',            NULL,   'EXPENSE',   'DR', FALSE);
INSERT INTO subject VALUES ('660101', '营销费用-红包补贴',   '6601', 'EXPENSE',   'DR', TRUE);


-- ============================================================
-- 测试账户（余额单位：分）
-- ============================================================

-- 内部户 —— 资产类
INSERT INTO account VALUES ('BANK_RESERVE',  '备付金存管户',      '100101', 'PLATFORM', 'INTERNAL', 'CNY', 'DR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('CHANNEL_RECV',  '应收渠道款-银联',   '112201', 'PLATFORM', 'INTERNAL', 'CNY', 'DR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('MERCHANT_RECV', '应收商户垫付款',    '112202', 'PLATFORM', 'INTERNAL', 'CNY', 'DR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());

-- 内部户 —— 中间过渡户
INSERT INTO account VALUES ('ESCROW',        '担保交易中间户',    '224103', 'PLATFORM', 'INTERNAL', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('WD_TRANSIT',    '提现在途户',        '224104', 'PLATFORM', 'INTERNAL', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());

-- 内部户 —— 损益类
INSERT INTO account VALUES ('FEE_INCOME',    '支付手续费收入户',  '600101', 'PLATFORM', 'INTERNAL', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('WD_FEE_INCOME', '提现手续费收入户',  '600102', 'PLATFORM', 'INTERNAL', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('CHANNEL_COST',  '渠道通道成本户',    '640101', 'PLATFORM', 'INTERNAL', 'CNY', 'DR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('CHANNEL_PAY',   '应付渠道手续费',    '220201', 'PLATFORM', 'INTERNAL', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('SUBSIDY',       '营销补贴费用户',    '660101', 'PLATFORM', 'INTERNAL', 'CNY', 'DR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());

-- 用户户
INSERT INTO account VALUES ('U0001', '用户A余额户', '224101', 'U0001', 'USER', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('U0002', '用户B余额户', '224101', 'U0002', 'USER', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());

-- 商户户
INSERT INTO account VALUES ('M0001',        '商户M待结算户', '224102', 'M0001', 'MERCHANT', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('M0001_DEPOSIT','商户M保证金户', '224105', 'M0001', 'MERCHANT', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());
INSERT INTO account VALUES ('M0002',        '商户N待结算户', '224102', 'M0002', 'MERCHANT', 'CNY', 'CR', 0, 0, 0, 'NORMAL', FALSE, 0, NOW(), NOW());


-- ============================================================
-- 会计日历
-- 2026-08-21 已关账（用于验证"禁止往已关账日期记账"）
-- 2026-08-22 起为开放状态
-- ============================================================
INSERT INTO accounting_calendar VALUES ('2026-08-21', 'CLOSED', NOW(), NOW());
INSERT INTO accounting_calendar VALUES ('2026-08-22', 'OPEN',   NOW(), NULL);
INSERT INTO accounting_calendar VALUES ('2026-08-23', 'OPEN',   NOW(), NULL);
INSERT INTO accounting_calendar VALUES ('2026-08-24', 'OPEN',   NOW(), NULL);
INSERT INTO accounting_calendar VALUES ('2026-08-25', 'OPEN',   NOW(), NULL);


-- ============================================================
-- 计费规则
-- rate_bp 为基点（万分之一）：60 bp = 0.6%
-- ============================================================

-- 默认规则（merchant_id 为 NULL）
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('CONSUME',           NULL, 60,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('ESCROW_CONFIRM',    NULL, 60,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
-- 提现：万分之十（0.1%），保底 1 元，封顶 25 元 —— 典型的银行代付计价方式
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('WITHDRAW_SUBMIT',   NULL, 10, 100, 2500, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
-- 免费业务：费率为 0
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('RECHARGE',          NULL,  0,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('TRANSFER',          NULL,  0,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('ESCROW_PAY',        NULL,  0,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('ESCROW_REFUND',     NULL,  0,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('WITHDRAW_SUCCESS',  NULL,  0,  0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');

-- 商户 M0001 的专属协议价：0.38%，优于默认的 0.6%
INSERT INTO fee_rule (biz_type, merchant_id, rate_bp, min_fee, max_fee, rounding_mode, effective_date, expire_date, status)
VALUES ('CONSUME', 'M0001', 38, 0, NULL, 'HALF_UP', '2026-01-01', NULL, 'ACTIVE');
