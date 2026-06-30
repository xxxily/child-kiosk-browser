# Adblock Performance Diagnostics Summary - 2026-07-01

## 背景

本次分析基于 `v0.2.25` 真机复制出的过滤性能诊断日志。该版本已经完成规则质量治理、候选索引优化、资源类型 query 识别修复、`shouldBlock` 分段耗时采样和慢样本追踪。

测试配置中启用规则规模较大：

- 总规则：`75,776 / 163,906`
- 网络规则：`75,776`
- 元素隐藏规则：`73,596`
- Scriptlet：`510`
- 不支持规则：`11,914`

## 当前成果

### 1. 规则判定引擎本身已经不是主要瓶颈

关键指标：

- `decision p50=13us`
- `decision p95=2.00ms`
- `decision p99=4.24ms`
- `decision max=15.42ms`
- `regexEvaluationCount=1,035`
- `candidatesPerDecision p50=0, p95=87, p99=93, max=122`

结论：

- `FilterEngine.decide()` 的常规路径已经足够快。
- p50 候选为 0，说明 token/domain 索引对大量普通资源已经能快速排除。
- 正则评估量相对 `decisionCount=2,890` 已经可控，不是本轮最优先瓶颈。

### 2. 缓存命中率有所提升，但 normalized cache 全部绕过

关键指标：

- `cacheHitRate=35.0%`
- `cacheHitCount=1,012`
- `cacheMissCount=1,878`
- `normalizedCacheHitCount=0`
- `normalizedCacheStoreCount=0`
- `normalizedCacheBypassCount=1,878`

结论：

- 完整 URL cache 有明显收益。
- normalized cache 全部绕过，说明当前规则集存在 query-sensitive 规则，系统为了保持规则语义自动禁用 normalized cache。
- 不建议在没有规则分区前强行打开 normalized cache，否则可能影响 `$removeparam`、URL query 相关规则或特殊例外语义。

### 3. 资源类型识别修复已经生效

最近事件里 `adsbygoogle.js` 被识别为 `script`，`hm.gif` 被识别为 `image`。这说明 `.js?client=...`、`.gif?...` 这类 query 后缀资源已经不再统一落到 `other`。

仍然看到部分 `other`：

- `googleads.g.doubleclick.net/pagead/viewthroughconversion/...`
- `bat.bing.com/p/conversions/c/t`
- `.ts` 分片视频

结论：

- `other` 不再主要来自静态扩展名 query 误识别。
- 后续可以考虑将 `.ts` 识别为 `media`，但这不是广告过滤性能的首要问题。

## 主要瓶颈

### 1. 慢点集中在 `shouldBlockSnapshot`

关键指标：

- `shouldBlock p50=1.05ms`
- `shouldBlock p95=6.32ms`
- `shouldBlock p99=16.03ms`
- `shouldBlock max=53.78ms`
- `shouldBlockSnapshot p50=618us`
- `shouldBlockSnapshot p95=4.35ms`
- `shouldBlockSnapshot p99=12.23ms`
- `shouldBlockSnapshot max=52.78ms`

慢样本中最明显的案例：

- `bat.bing.com/p/conversions/c/t`
  - `total=53.78ms`
  - `engine=24us`
  - `snapshot=52.78ms`
- `c.clarity.ms/c.gif`
  - `total=21.13ms`
  - `engine=519us`
  - `snapshot=20.33ms`
- `www.720yun.com/t/...`
  - `total=22.28ms`
  - `engine=279us`
  - `snapshot=20.79ms`

结论：

- 真正拖慢 `shouldBlock` 尾延迟的不是过滤匹配，而是诊断快照链路。
- 当前 `maybeRecordPerfSnapshot()` 在请求线程上做两件高风险工作：
  - 每次请求都读一次诊断 reset 时间戳文件。
  - 每 2 秒在请求线程内同步序列化并写入 perf snapshot。

### 2. 少量慢样本来自事件记录和规则判定

代表样本：

- `hm.baidu.com/hm.js?...`
  - `total=20.75ms`
  - `event=19.73ms`
  - `cache=full-cache-hit`
- `tree-search-rc34m23l.js`
  - `total=22.19ms`
  - `engine=19.88ms`
  - `candidates=50`
- `pc.meitudata.com/.../de97f5a7.js`
  - `total=20.79ms`
  - `parse=7.55ms`
  - `engine=12.22ms`

结论：

- 事件段偶发慢点可能来自主进程广播或异步事件队列调度，不是常态，`event p95=0us`。
- 规则判定偶发慢点仍需观察具体规则，但 `engine p99=4.37ms`，优先级低于 snapshot。
- parse 偶发慢点可能来自 URL/host/headers 处理，但 p95 只有 `558us`，暂不作为 P0。

## 下一步优化方案

### P0：移除 WebView 请求线程上的诊断快照写入

目标：

- `shouldBlockSnapshot p95` 降到 `< 1ms`
- 慢样本中不再出现 `snapshot=20ms+` 或 `snapshot=50ms+`

实施：

- `maybeRecordPerfSnapshot()` 只在请求线程上做原子节流判断。
- 命中写入窗口后，把 `engine.perfSnapshot()` 和 JSON 文件写入丢到单线程后台 executor。
- 后台 executor 内再次读取快照并写文件，允许快照略滞后，避免阻塞 WebView 请求。
- `force=true` 时仍保留同步能力，用于管理页主动刷新或后续显式导出。

### P0：降低 reset 时间戳文件检查频率

目标：

- 避免每个 `shouldBlock` 都读磁盘文件。
- reset 仍能在 WebView 进程内较快生效。

实施：

- 新增 reset check 节流，例如每 `500ms` 最多检查一次 reset 文件。
- `force=true` 或主进程 reset 后仍直接更新本进程内存状态。
- `applyPendingDiagnosticsReset()` 返回是否真的执行 reset，方便统计和后续诊断。

### P1：为诊断重置增加二次确认

目标：

- 防止误触“重置/清空统计、日志和过滤缓存”导致测试数据丢失。

实施：

- 点击诊断卡片顶部“重置”和底部“清空统计、日志和过滤缓存”时不立即执行。
- 弹出确认对话框，明确说明会清空：
  - 性能计数
  - 最近过滤日志
  - 过滤判定缓存
  - WebView 进程持久化快照
- 用户确认后再调用 `FilterRepository.resetDiagnostics()`。

## 验收标准

完成优化后，建议按以下流程验证：

1. 安装新版本，进入“后台管理 -> 网页过滤管理”。
2. 点击“重置”时必须先出现二次确认；取消后数据不清空。
3. 确认执行重置后再访问同一批广告密集页面。
4. 返回诊断页复制完整日志，重点检查：
   - `shouldBlockSnapshot p95 < 1ms`
   - `shouldBlockSnapshot p99` 明显低于 `v0.2.25` 的 `12.23ms`
   - 慢样本里不再主要由 `snapshot` 贡献 20ms+。
5. 如果仍有 `engine=15ms+` 慢样本，再进入下一轮 P1/P2：
   - 记录慢样本命中规则 source/type/key。
   - 针对高候选 token 做规则桶拆分或高频 allow 快速路径。

## 当前判断

`v0.2.25` 的过滤引擎核心匹配已经达到可用水平，下一步不应继续优先堆规则索引复杂度。真正影响用户体感的尾延迟来自诊断系统自身写快照和读 reset 文件，因此 `v0.2.26` 应优先修复诊断系统对 WebView 请求线程的干扰。
