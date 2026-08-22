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
domain/       实体与枚举（Account / Voucher / AccountingEntry / AccountSerial ...）
dto/          BookingRequest / EntryCommand
engine/       AccountingEngine  记账主流程、冲正、冻结解冻
              EntryGenerator    业务 → 分录组
              BalanceValidator  借贷平衡校验
repository/   四张表的数据访问
resources/    schema.sql 建表 · data.sql 科目树与测试账户
```

## 六个 TODO（建议按顺序做）

| # | 位置 | 内容 | 验收 |
|---|---|---|---|
| 1 | `BalanceValidator.validate()` | 借贷平衡校验 | `-Dtest=BalanceValidatorTest` |
| 2 | `EntryGenerator.generate()` | 9 种业务的分录规则 | `-Dtest=EntryGeneratorTest` |
| 3 | `Account.isIncrease()` | 方向判断中枢 | 见下 |
| 4 | `AccountRepository.applyDelta()` | 带余额校验的条件更新 SQL | 见下 |
| 5 | `AccountingEngine.book()` | 幂等三层防护 | 见下 |
| 6 | `AccountingEngine.doReverse()` | 冲正的全额镜像 | 见下 |

3–6 做完后跑 `-Dtest=AccountingEngineTest`（14 个用例）。
六个全部做完，`-Dtest=LedgerInvariantTest`（8 个勾稽校验）才会绿。

每处 TODO 的注释里写了完整要求和提示，直接看代码里的说明。

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
