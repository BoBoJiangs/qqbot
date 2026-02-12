## 目标
- 在 DanCalculator 的“匹配丹方/遍历组合”逻辑里，当药引属性类型为“性平”时，只允许药引名称来自 properties/性平.txt。

## 代码改动点
- 在 DanCalculator 增加一个字段：`Set<String> pingLeadNames`（默认空集合）。
- 在 `loadData(Long botId)` 的数据加载流程中，新增 `loadPingLeadNames()`：
  - 读取 `Paths.get(targetDir, "properties", "性平.txt")`（UTF-8，逐行 trim，跳过空行）填充到 `pingLeadNames`。
  - 文件不存在时保持空集合（不启用限制，避免影响旧环境）。
- 在 `calculateAllDans(Long botId)` 的 lead 枚举处加入过滤：
  - 现有条件：`lead.price > 0 && checkBalance(main, lead)`
  - 新增：当 `lead.leadAttrType` 为/以“性平”开头时，要求 `pingLeadNames` 为空（不限制）或 `pingLeadNames.contains(lead.name)`。
  - 这样能确保“性平药引”不会从性平名单外（如剑心竹）被选中。

## 测试与验证
- 新增/补充一个单测：加载 DanCalculator 数据后，校验 pingLeadNames 已包含性平.txt中的典型条目（如“恒心草”），并通过一次最小化的 lead 过滤逻辑断言“性平但不在名单”的名字会被排除（用反射调用或抽一个包内可见 helper）。
- 运行 `mvn test` 做回归。

## 兼容性说明
- 仅对“性平药引”做名单限制；性寒/性热的互补药引选择不受影响。
- 性平.txt 内容改动会在下次调用 loadData 后生效。