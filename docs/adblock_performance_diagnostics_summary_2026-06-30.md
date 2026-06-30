# 网页过滤性能调优诊断总结（2026-06-30）

## 1. 结论摘要

本次真实设备日志说明：前几轮网页过滤性能调优已经生效，且诊断数据已经来自真实网页拦截所在的 `:webview` 进程，不再是主进程空指标。当前热路径已经从“全量规则线性扫描”的不可控状态，推进到“Token/域名索引后的小候选集匹配”状态。

但这还不是最终状态。现阶段最值得继续投入的不是立即上 Native 引擎，而是先解决三个更直接的问题：

1. **候选集仍偏大**：`candidatesPerDecision p50=117, p95=136`，说明每次请求仍要评估约 100 条规则，和最初全量扫描相比已经大幅降低，但距离目标的 30-80 条仍有差距。
2. **正则评估仍偏多**：`regexEvaluationCount=33508`，约 `8.4` 次/决策，下一轮要把正则前置过滤做好，避免无 literal token 的正则落入每次请求的通用候选集。
3. **误伤风险已经暴露**：近期拦截里出现 `Search`、`hidden` 这类过宽裸规则，已经拦截了 `searchBtn`、`wechatcode.png`、`hidden=1` 等正常资源形态。儿童浏览器的核心目标是免广告，但不能靠过宽规则破坏正常站点可用性。

因此，下一阶段应优先做 **规则选择性治理 + universal/regex 候选集削减 + 误伤可观测性**。方案四（二进制预编译）可作为冷启动优化预案；方案五（Native Rust/C++ 引擎）目前不应作为下一步主线，因为 Native 不能自动解决宽泛规则误伤，且当前 Kotlin 热路径的 p50/p95 已经进入可用区间。

## 2. 本次诊断样本

诊断信息：

| 项目 | 值 |
| --- | --- |
| 生成时间 | 2026-06-30 21:44:55 |
| 指标来源 | WebView 进程 |
| 指标进程 | `site.anzz.childkiosk:webview` |
| 指标更新时间 | 2026-06-30 21:44:14 |
| 过滤开关 | 已启用 |

构建指标：

| 指标 | 值 | 判断 |
| --- | ---: | --- |
| 构建耗时 | `1662 ms` | 可接受，但低端设备仍需观察 |
| 规则总行数/启用规则 | `56003 / 85569` | 已启用规则规模较大 |
| 网络规则 | `56003` | 当前主热路径规则量 |
| Cosmetic 规则 | `29061` | 数量大，但本次 CPU 指标不构成瓶颈 |
| Scriptlet 规则 | `34` | 数量很小 |
| 不支持规则 | `636` | 可接受，但需持续可解释 |
| 编译错误 | `规则编译失败: /* Override primer focus outline color for marketing header dropdown links for better contrast */` | 需要定位来源，疑似订阅/自定义源混入 CSS 注释或 parser 未跳过该类内容 |

运行时指标：

| 指标 | 值 | 派生判断 |
| --- | ---: | --- |
| 决策次数 | `3984` | 样本量足够初步判断 |
| 缓存命中 | `645` | 命中率 `16.2%`，偏低 |
| 缓存未命中 | `3339` | 未命中率约 `83.8%` |
| 候选评估总数 | `399067` | 平均约 `100.2` 条/决策，约 `119.5` 条/缓存未命中 |
| 正则评估总数 | `33508` | 平均约 `8.4` 次/决策，约 `10.0` 次/缓存未命中 |
| Cosmetic 调用 | `39` | 调用量低 |
| Scriptlet 调用 | `38` | 调用量低 |
| 生成 CSS | `441168 bytes` | 平均约 `11.3 KB`/次 cosmetic 调用 |
| 生成 Scriptlet JS | `0 bytes` | 当前样本中没有实际注入 JS |

延迟样本：

| 指标 | p50 | p95 | p99 | max | 判断 |
| --- | ---: | ---: | ---: | ---: | --- |
| `shouldBlock` | `879 us` | `5.20 ms` | `8.75 ms` | `20.63 ms` | p50 已好，p95/p99 仍需压低 |
| `decision` | `640 us` | `3.98 ms` | `8.04 ms` | `20.15 ms` | 热路径可用，但高分位还有空间 |
| 候选规则/决策 | `117` | `136` | `136` | `136` | 候选集有固定下限，说明通用规则占比明显 |
| Cosmetic | `4 us` | `7 us` | `1.68 ms` | `3.15 ms` | 当前不是主要 CPU 瓶颈 |
| Scriptlet | `2 us` | `5 us` | `13 us` | `29 us` | 当前不是主要 CPU 瓶颈 |

索引状态：

| 索引 | 桶数 | 索引规则 | 兜底规则 | 判断 |
| --- | ---: | ---: | ---: | --- |
| important | `8` | `8` | `0` | 很小 |
| exception | `725` | `907` | `1` | 正常 |
| blocking | `50672` | `54975` | `112` | 索引覆盖率高，但 112 条兜底规则会形成每次请求的候选下限 |
| removeparam | `0` | `0` | `0` | 当前未启用 |

## 3. 已取得的调优成果

### 3.1 真实运行指标已经打通

前一轮问题是后台诊断页读到主进程 `FilterEngine`，导致真实拦截日志存在但运行指标全为 0。本次日志显示：

- `Metric source: WebView 进程`
- `Metric process: site.anzz.childkiosk:webview`
- `decisionCount=3984`
- `cacheHitCount=645`
- `candidateEvaluationCount=399067`

这说明跨进程诊断链路已经修复，后续可以用真实设备数据闭环优化。

### 3.2 全量扫描瓶颈已经被解除

当前网络规则为 `56003` 条。如果仍是线性扫描，每个请求在最坏情况下会触达数万条规则。现在候选规则采样为：

- p50：`117` 条
- p95：`136` 条
- p99：`136` 条

按 `56003` 条网络规则粗略估算，单次决策的候选集已经被压缩到全量规则的约 `0.21% - 0.24%`。这意味着索引优化已经产生决定性收益，当前性能问题不再是“完全没有索引”的问题。

### 3.3 p50 延迟已经进入可用区间

当前：

- `decision p50=640 us`
- `shouldBlock p50=879 us`

对普通页面来说，单次请求的中位数过滤成本已经低于 1ms。这个结果说明 Kotlin 层引擎在经过索引和预计算后不是天然不可用，现阶段没有必要立刻把主线切到 JNI/Native。

### 3.4 Cosmetic 和 Scriptlet 不是当前 CPU 主瓶颈

Cosmetic 和 Scriptlet 的采样耗时都非常低：

- cosmetic p95：`7 us`
- scriptlet p95：`5 us`

需要注意的是，当前指标只覆盖 CSS/JS 字符串生成，不覆盖 WebView 注入后触发的 DOM/style recalculation 成本。因此 cosmetic 仍要继续监控页面渲染体验，但它不是本次日志暴露出的第一性能瓶颈。

## 4. 当前剩余瓶颈与风险

### 4.1 候选集存在固定下限

`blocking` 索引中有 `112` 条兜底规则，而 `candidatesPerDecision p50=117`。两者非常接近，说明很多请求至少要评估这批兜底规则。

这类规则通常来自：

- 无法提取安全 literal token 的正则规则。
- 过短、过泛的 substring/wildcard 规则。
- 缺少域名、资源类型、一方/三方限制的通用规则。

下一轮优化的核心是降低这个固定下限，而不是只扩大缓存容量。

### 4.2 正则评估仍偏多

当前 `regexEvaluationCount=33508`，平均约：

- `8.4` 次/决策
- `10.0` 次/缓存未命中

即使正则已经经过安全性检查，频繁执行正则仍会推高 p95/p99。下一阶段要给正则规则增加 literal prefilter：只有 URL/host 命中可提取 literal 时才进入正则求值；没有 literal 且没有强约束的正则规则应进入隔离/降级队列。

### 4.3 缓存命中率偏低，但不是唯一瓶颈

缓存命中率 `16.2%` 偏低，说明大量请求 URL 仍是唯一的，尤其图片、统计、带时间戳或随机 query 的资源会穿透完整 URL cache key。

但当前缓存未命中路径的平均候选集已经约 `119.5` 条，不再是数万条。所以缓存优化应作为 P1，而不是绕过候选集治理的唯一手段。

后续可以做安全的二级缓存：

- 对 `image/script/stylesheet/font` 等静态资源，剥离常见 cache-busting 参数形成 normalized key。
- 对没有 query 依赖的命中规则，缓存 `host + path + resourceType + topLevelHost + thirdParty` 结果。
- 不对 `document`、`xmlhttprequest`、表单/授权相关请求盲目归一化，避免误放行或误拦截。

### 4.4 规则质量和误伤已经成为同级重点

近期事件中出现以下命中：

- `BLOCK image .../wechatcode.png?t=... rule=Search`
- `BLOCK image .../searchBtn-...png rule=Search`
- `BLOCK other https://qiyukf.com/script/...js?hidden=1... rule=hidden`
- `BLOCK other ...adsbygoogle.js... rule=||googlesyndication.com/pagead/`

其中 `||googlesyndication.com/pagead/` 是高置信广告规则，属于正确拦截；但 `Search`、`hidden` 这类裸词规则风险很高。它们很可能会误伤正常搜索按钮、站内搜索资源、客服脚本、普通带 `hidden` 参数的业务资源。

结论：下一轮不能只追求“拦得更多”，还要确保拦截理由足够可信。否则儿童浏览器会从“免广告干扰”变成“正常网页不可用”。

### 4.5 规则编译错误需要定位源头

错误：

```text
规则编译失败: /* Override primer focus outline color for marketing header dropdown links for better contrast */
```

这是 CSS 注释，不是标准网络过滤规则。可能原因：

- 某个订阅源混入了 CSS/网页源码片段。
- 自定义规则导入了非 Adblock 文本。
- parser 对某类 cosmetic/extended CSS 注释跳过不彻底。

这类问题短期不会直接造成性能灾难，但会污染规则统计和错误列表，也可能让 parser 把非规则文本误当规则处理。

## 5. 下一步优化方向

### P0：候选集和误伤治理

目标：把每次请求必须评估的规则数从约 117-136 条压到 60-80 条以内，同时清理 `Search`、`hidden` 这类高风险规则。

实施步骤：

1. 为每条拦截事件记录更多上下文：`sourceId/sourceName`、`rawText`、`matchType`、`indexKey`、是否来自 `universalRules`、候选阶段、候选数、正则耗时。
2. 增加规则质量评分和隔离策略：
   - 无域名限制、无资源类型限制、长度过短的 `SUBSTRING` 规则默认降级或禁用。
   - `Search`、`hidden` 这类常见业务词不能作为裸规则直接生效。
   - 如果业务确实要拦某个客服、搜索、弹窗资源，必须使用域名锚定或更长路径 token。
3. 对 `universalRules` 做二次分桶：
   - 按资源类型分桶。
   - 按 third-party / first-party 分桶。
   - 按 top-level domain 限制分桶。
   - 对 regex 提取 literal prefilter，无法提取的进入 slow/unsafe 队列。
4. 为当前日志中的可疑命中建立回归样例：
   - `searchBtn-*.png` 不应被 `Search` 裸词拦截。
   - `wechatcode.png?t=...` 不应被 `Search` 裸词拦截。
   - `qiyukf.com/...hidden=1...` 不应被 `hidden` 裸词拦截；如要拦截 qiyukf，必须由域名或明确路径规则命中。
   - `adsbygoogle.js` 仍必须被 `||googlesyndication.com/pagead/` 命中。

验收标准：

| 指标 | 当前 | 下一轮目标 |
| --- | ---: | ---: |
| `candidatesPerDecision p50` | `117` | `<= 60` |
| `candidatesPerDecision p95` | `136` | `<= 80` |
| `candidatesPerDecision p99` | `136` | `<= 100` |
| `blocking universalRules` | `112` | `<= 50` |
| `regexEvaluation/decision` | `8.4` | `<= 3.0` |
| `decision p95` | `3.98 ms` | `<= 2.5 ms` |
| `decision p99` | `8.04 ms` | `<= 5 ms` |
| 误伤样例 | 已出现 | 上述样例全部通过 |

### P1：缓存命中率提升

目标：减少重复页面、重复资源和 cache-busting query 对完整 URL cache key 的冲击。

实施步骤：

1. 增加 normalized cache key，只对低风险资源类型启用：`image`、`script`、`stylesheet`、`font`、`media`。
2. 默认剥离常见 cache-busting 参数：`t`、`ts`、`timestamp`、`_`、`rnd`、`random`、`cache`、`v`。需要保留开关，方便排障。
3. 只有在命中规则不依赖 query 参数时，才允许复用 path 级缓存结果。
4. 诊断页增加 cache 细分：
   - 完整 URL cache 命中。
   - normalized cache 命中。
   - 因 query 不安全而拒绝归一化次数。
   - top cache miss host/path。

验收标准：

| 指标 | 当前 | 下一轮目标 |
| --- | ---: | ---: |
| 首次浏览 cache hit rate | `16.2%` | `>= 25%` |
| 同页面刷新 cache hit rate | 未记录 | `>= 50%` |
| normalized cache 误伤 | 未记录 | 0 个已知回归 |
| document/XHR 归一化 | 无 | 默认不启用 |

### P1：诊断能力补强

目标：让后续优化不靠猜，而是能直接看到最慢、最宽、最容易误伤的规则。

实施步骤：

1. 增加 Top-N 诊断：
   - 最慢决策 Top 20。
   - 候选最多 URL Top 20。
   - 正则评估最多规则 Top 20。
   - 拦截最多规则 Top 20。
   - 例外最多规则 Top 20。
2. 拦截日志增加规则来源和规则类型。
3. 复制诊断信息时包含上述 Top-N 摘要。
4. 过滤性能诊断 UI 保持默认折叠，避免影响网页过滤管理页的日常配置。

验收标准：

- 家长或开发者复制一份诊断信息后，可以直接定位“哪个订阅源的哪条规则导致了慢/误伤”。
- 任意拦截事件都能追溯到规则源、原始规则、match type 和是否来自兜底候选。

### P2：构建与冷启动优化（方案四预案）

当前 `buildDurationMs=1662`，还没有到必须上二进制预编译的程度。但如果低端设备、规则订阅增多或 WebView 进程重启频繁导致体感卡顿，则应启动方案四。

触发条件：

- 低端设备 `buildDurationMs p95 > 3000 ms`。
- WebView 进程冷启动明显被规则构建阻塞。
- 规则总量超过 `150000` 行后，内存或 GC 抖动明显。

实施步骤：

1. 先做轻量 compiled snapshot，而不是直接 FlatBuffers/Mmap：
   - 保存 parser 结果、规则分类、预计算字段、index key。
   - 记录规则源版本、etag、lastModified、app version、parser version。
2. WebView 进程启动时优先加载 snapshot。
3. snapshot 不匹配或损坏时自动回退文本解析。
4. 后续再评估是否升级到 FlatBuffers/Mmap。

验收标准：

| 指标 | 目标 |
| --- | ---: |
| snapshot 命中构建耗时 | `<= 500 ms` |
| snapshot miss 回退 | 不崩溃，可自动重建 |
| 规则更新后生效 | 新打开网页生效或 UI 明确提示 |
| 解析统计一致性 | snapshot 与文本解析规则数一致 |

### P3：Native Rust/C++ 引擎（方案五门槛）

当前不建议立即启动方案五。原因：

- 当前 p50 已经低于 1ms，说明 Kotlin 热路径不是根本不可用。
- 主要风险是候选选择性、正则前置过滤和规则误伤，Native 不会自动解决这些问题。
- JNI 会增加构建、调试、崩溃定位、ABI 包体、CI 发布复杂度。

只有满足以下条件才进入 Native 立项：

- P0/P1 完成后，低端设备 `decision p95` 仍长期高于 `5 ms`，且瓶颈明确在字符串匹配本身。
- 候选集已经压到 `p95 <= 80`，但 CPU 仍不可接受。
- 已有稳定规则正确性回归集，避免 Native 重写造成行为漂移。

Native 方案验收标准：

- Kotlin 与 Native 对同一测试集决策完全一致。
- 广告高置信样例保持拦截，误伤样例保持放行。
- 低端设备 `decision p95 <= 2 ms`。
- Native crash 不影响主进程，WebView 进程可回退 Kotlin 引擎。

## 6. 建议实施顺序

建议按以下顺序推进：

1. **先做诊断补强**：把 source、matchType、indexKey、universal、Top-N 慢规则补齐。没有这些信息，继续优化会很难判断收益来源。
2. **再做规则质量治理**：隔离过短/过泛裸规则，优先修复 `Search`、`hidden` 类误伤。
3. **压 universal 和 regex**：对 112 条 blocking 兜底规则做分类、literal prefilter 和慢规则隔离，把候选 p95 压到 80 以下。
4. **做安全缓存归一化**：提升重复浏览和动态 query 页面的命中率，但严格限制资源类型。
5. **观察冷启动再决定方案四**：如果 build/prewarm 在低端设备变成主问题，再做 snapshot。
6. **最后再评估方案五**：只有 Kotlin 引擎在规则选择性已经足够好的前提下仍跑不动，才值得引入 Native。

## 7. 当前是否需要继续优化

需要继续优化，但方向要从“粗暴换引擎”转成“可观测、可解释、可验收的精细优化”。

当前状态可以判断为：

- **性能主灾难已解除**：索引后 p50 已经进入可用区间。
- **高分位仍需压低**：p95/p99/max 还会在广告多、请求密集页面上放大。
- **误伤风险必须优先处理**：`Search`、`hidden` 这类命中说明规则质量控制不足，继续提高拦截强度前必须补上安全边界。
- **Native 不是当前首选**：先把候选集、正则和规则质量治理做到位，再决定是否需要方案四/方案五。

下一轮完成后，如果达到以下结果，可以认为网页过滤性能优化进入“稳定可用”阶段：

- `decision p95 <= 2.5 ms`
- `decision p99 <= 5 ms`
- `candidatesPerDecision p95 <= 80`
- `regexEvaluation/decision <= 3`
- 已知误伤样例全部通过
- 诊断信息可定位具体慢规则和误伤规则来源

如果在低端设备或更大规则集下仍达不到这些标准，再启动方案四；只有方案四和 P0/P1 后仍无法满足高分位目标，才进入方案五。

## 8. 0.2.24 实施进度

本轮已按 P0/P1 完成第一批落地，重点针对 anti-AD 自定义订阅暴露出的规则质量、正则兜底和诊断污染问题。

### 已完成

- **P0 规则准入治理**：
  - 自定义订阅会将 GitHub `blob` 页面地址自动规范化为 `raw.githubusercontent.com` 文本地址，避免下载 GitHub HTML 页面后把 `Search`、`hidden` 等页面文案误当过滤规则。
  - parser 跳过 CSS 块注释，避免 `/* ... */` 被当作网络规则导致“规则编译失败”噪音。
  - 对包含不支持选项的网络规则改为不启用，而不是忽略 `$dnstype`、`$denyallow` 等选项后继续参与 WebView 拦截。
  - 对无域名、无资源类型、无一方/三方限制的弱裸词规则做降级跳过，覆盖 `Search`、`hidden`、`button`、`image` 等高误伤词。

- **P0 正则候选削减**：
  - 为可安全提取 literal 的正则规则建立索引 key，例如 `/adserver[0-9]+/` 可通过 `adser` 进入 token 索引，不再默认进入每次请求都评估的 universal 兜底池。
  - 对含顶层 alternation 或无法安全提取 literal 的正则仍保留兜底路径，优先保证正确性。

- **P1 缓存优化**：
  - 新增静态资源 normalized decision cache，只对 `image/script/stylesheet/font/media` 启用。
  - 仅当 query 参数全部属于安全 cache-busting 参数时归一化，例如 `t`、`ts`、`timestamp`、`rnd`、`cb`、`v`。
  - 如果规则集中存在 query-sensitive 网络规则，normalized cache 自动绕过，避免改变带 query 规则语义。

- **P1 诊断补强与清理入口**：
  - 过滤日志新增 `sourceId`、`matchType`、`indexKey`、`candidateCount`、`cacheStatus`。
  - 复制诊断信息新增 normalized cache 指标和最近规则 Top 汇总。
  - 诊断卡片新增“重置”入口，可清空性能计数、最近过滤日志、完整 URL cache、normalized cache、host 注入缓存和持久化 WebView 快照。
  - WebView 进程会读取重置时间戳并清空自身内存统计；后台读取快照时会丢弃早于重置时间的旧快照。

### 已完成测试

- `Search` 不再误拦 `searchBtn.png`。
- `hidden` 不再误拦 `?hidden=1` 的正常脚本。
- `adsbygoogle.js` 仍可被高置信规则拦截。
- `$dnstype`、`$denyallow` 这类 WebView 不支持选项不再被忽略后错误启用。
- GitHub `blob` 自定义订阅 URL 会自动转为 raw 文本 URL。
- 可提取 literal 的正则不会在无关请求上触发正则求值。
- normalized cache 可命中安全 cache-busting 静态资源。
- 重置诊断可清空计数和缓存。

### 下一轮建议

- 用 `0.2.24` 真机重新采样 anti-AD 订阅，重点看：
  - `blocking` 兜底规则是否从 `112` 明显下降。
  - `regexEvaluation/decision` 是否从约 `8.4` 明显下降。
  - 最近事件里是否还出现 `Search`、`hidden` 这类裸词命中。
  - 点击“重置”后重新访问同一批页面，复制诊断信息确认旧统计不再残留。

- 如果候选 p95 仍高于 `80`，下一步应继续做 Top-N 慢规则和 Top-N regex 规则聚合，把兜底规则逐条归因到具体订阅源与 raw rule。
