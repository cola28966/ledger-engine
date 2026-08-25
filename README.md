# 记账引擎 · 动手实现

一个能跑的最小账务系统骨架。**6 处核心逻辑挖成了 TODO，由你实现。**
41 个测试已经写好并验证过（完整实现下全绿），实现完 `mvn test` 全绿即验收通过。

## 跑起来

```bash
mvn test                              # 跑全部测试
mvn test -Dtest=BalanceValidatorTest  # 只跑某一个
```

默认 H2 内存库，零配置。切真实 MySQL：`-Dspring-boot.run.profiles=mysql`。

## 项目结构

```
domain/       实体与枚举（Account / Voucher / AccountingEntry / AccountSerial / FeeRule ...）
dto/          BookingRequest / EntryCommand / DailyMovement
engine/       AccountingEngine    记账主流程、冲正、冻结解冻
              EntryGenerator      业务 → 分录组
              BalanceValidator    借贷平衡校验
              FeeValidator        费率复核（平衡校验的盲区补丁）
              AccountingCalendar  会计日期的唯一权威来源
recon/        ReconService        渠道对账：双向核对、差异定性、自动补记账
batch/        DayEndService       日终结账：五项勾稽 + 快照 + 日切
repository/   八张表的数据访问
resources/    schema.sql 建表 · data.sql 科目树、账户、日历、计费规则
```

## 十一张表

| 表 | 作用 |
|---|---|
| `subject` | 会计科目树 |
| `account` | 账户与余额（含热点分桶配置） |
| `voucher` | 记账凭证（`request_id` 唯一索引 = 幂等锚点） |
| `accounting_entry` | 会计分录，面向财务/总账 |
| `account_serial` | 账户流水，面向用户/商户/客服 |
| `accounting_calendar` | 会计日历，日期状态机 OPEN→CUTTING→CLOSED |
| `fee_rule` | 计费规则，费率以基点(bp)存整数 |
| `balance_snapshot` | 日终余额快照 |
| `accounting_template` | 记账模板，业务类型 → 分录组的配置 |
| `channel_statement` | 渠道对账单明细，外部事实的镜像（只插不改） |
| `recon_diff` | 对账差异，状态机 PENDING → AUTO_REPAIRED / MANUAL_RESOLVED / IGNORED |

## TODO 清单

每处 TODO 的注释里写了完整要求和提示，直接看代码里的说明。

### 阶段 1 · 记账引擎（已完成）

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 1 | `BalanceValidator.validate()` | 借贷平衡校验 | `-Dtest=BalanceValidatorTest` |
| 2 | `EntryGenerator.generate()` | 9 种业务的分录规则 | `-Dtest=EntryGeneratorTest` |
| 3 | `Account.isIncrease()` | 方向判断中枢 | 见下 |
| 4 | `AccountRepository.applyDelta()` | 带余额校验的条件更新 SQL | 见下 |
| 5 | `AccountingEngine.book()` | 幂等三层防护 | 见下 |
| 6 | `AccountingEngine.doReverse()` | 冲正的全额镜像 | 见下 |

3–6 做完后跑 `-Dtest=AccountingEngineTest`。

### 阶段 2 · 防线与体检

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 7 | `FeeValidator.calculateByRule()` | 手续费计算：基点、取整、保底、封顶 | `-Dtest=FeeValidatorTest` |
| 8 | `AccountingCalendar.resolve()` | 会计日期裁定，拒绝往已关账日记账 | `-Dtest=AccountingCalendarTest` |
| 9 | `DayEndService.checkTrialBalance()` | 日终试算平衡 | `-Dtest=DayEndServiceTest` |
| 10 | `DayEndService.checkReserveInvariant()` | 备付金勾稽（监管红线） | 同上 |

7、8 做完，`AccountingEngineTest` 和 `LedgerInvariantTest` 才会恢复绿——
记账主流程现在会先过这两道防线。

### 阶段 3 · 并发

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 11 | `AccountingEngine.applyEntriesInLockOrder()` | 按账号固定顺序加锁，消除死锁 | `-Dtest=DeadlockTest` |
| 12 | `HotAccountRouter.route()` | 热点账户分桶路由 | `-Dtest=HotAccountRouterTest` |

### 阶段 4 · 记账模板配置化

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 13 | `TemplateEngine.render()` | 按配置渲染分录组，取代硬编码 switch | `-Dtest=TemplateEngineTest` |
| 14 | `TemplateValidator.validateAll()` | 模板自检：启动时校验模板存在、可求值、自平衡 | `-Dtest=TemplateValidatorTest` |

`TemplateEngineTest` 的 11 组断言逐字继承自阶段一的 `EntryGeneratorTest`
（那时测的是硬编码 switch）。**同样的输入、同样的期望输出，实现从 switch
换成数据库配置，结果必须完全一致**——配置化是行为不变的重构。

### 阶段 5 · 渠道对账

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 15 | `ReconService.classify()` | 单笔定性：单边、金额、手续费、状态、在途 | `-Dtest=ReconClassifyTest` |
| 16 | `ReconService.reconcile()` | 双向核对 + 重跑幂等 | `-Dtest=ReconServiceTest` |
| 17 | `ReconService.autoRepair()` | 差异自动补记账 | 同上 |

前四个阶段做的都是「让账记对」，对账做的是「**证明**账记对了」。

日终的五项勾稽全是**内部自洽**校验——它们能证明账本自身没有矛盾，
证明不了账本描述的事情真的发生过。一笔充值的回调丢了、我方完全没记账：

```
借贷平衡 ✓   余额与流水一致 ✓   备付金勾稽 ✓   五项全过
```

因为压根没有这条记录，账本内部当然自洽。而用户的钱已经从银行卡扣走了。
**只有引入外部事实（渠道对账单）才能发现这一类。**

全部做完：**125 个测试**应当全绿。

## 压测

热点效应在 H2 内存库上测不出来（行锁持有时间是微秒级，实测只差 1.13x），
必须用真实数据库。

1. 建库并执行 `schema.sql` + `data.sql`
2. 创建 `src/main/resources/application-perf.yml`（**该文件已在 .gitignore 中，勿提交**）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://<host>:<port>/<db>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: <user>
    password: <password>
    hikari:
      maximum-pool-size: 40   # 必须大于压测线程数，否则瓶颈变成"等连接"
  sql:
    init:
      mode: never
```

3. 运行：

```bash
SPRING_PROFILES_ACTIVE=perf mvn test -Dtest=HotAccountBenchmarkTest
```

### 实测结果（MySQL 5.7，16 线程 × 60 笔）

```
场景                    耗时(ms)     成功    失败      TPS
──────────────────────────────────────────────────────
无热点-TRANSFER            15,106     960       0       64
有热点-不分桶             190,074     960       0        5
有热点-分16桶              20,193     960       0       48

分桶带来的提升: 9.41x  (TPS 5 → 48)
热点差距: 不分桶 12.58x  →  分桶后 1.34x
```

修复死锁前，TRANSFER 一栏在 MySQL 上有 **243/960（25%）** 因死锁回滚。

## 几条贯穿全局的约束

- 金额一律 `long` 存"分"，永不使用 float/double
- 分录金额永远为正，方向由 `Direction` 表达
- 一组分录必须在同一个本地事务内落库，落库前校验借贷平衡
- 已落库的分录永不 UPDATE、永不 DELETE，只能红冲
- 会计日期由调用方传入，引擎内部绝不取 `now()`
- 余额不允许为负（需要透支请显式设计垫资户/应收户）

## 值得单独看的两个测试

- `BalanceValidatorTest.balancedButWrong` —— 费率算错十倍，借贷依然是平的。
  平衡校验的盲区，也是阶段 2 要补的费率复核防线。
- `LedgerInvariantTest.reserveInvariant_feeBreaksIt` —— 平台收手续费会破坏
  备付金勾稽等式，差额恰好等于手续费。这就是实务中必须做手续费划转的原因。
