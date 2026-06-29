# 广告拦截引擎性能优化 — 需求实现文档

> 本文档基于 [adblock_performance_optimization_report.md](file:///Users/blaze/work/github/child-kiosk-browser/docs/adblock_performance_optimization_report.md) 的审查评估，结合对实际代码的深入分析，产出可直接指导实现的需求规格。

---

## 一、原方案审查评估

### 1.1 原方案准确性确认

原方案对瓶颈的诊断**基本准确**，与代码实际情况一致：

| 原方案描述 | 代码实际情况 | 评估 |
|:---|:---|:---|
| 线性扫描 O(N) | [FilterEngine.kt:106-120](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L106-L120) 三次 `firstOrNull` 遍历 | ✅ 准确 |
| 正则求值开销大 | [FilterEngine.kt:354](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L354) `regex?.matcher()?.find()` | ✅ 准确 |
| 缓存 512 条命中率低 | [FilterEngine.kt:38-42](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L38-L42) LRU 512，key 含完整 URL | ✅ 准确 |

### 1.2 原方案遗漏的关键瓶颈

原方案**没有发现**以下同样严重的性能问题：

| 遗漏瓶颈 | 代码位置 | 严重程度 |
|:---|:---|:---|
| **`@Synchronized` 全局锁** — `decide()` 串行化所有线程 | [FilterEngine.kt:97](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L97) | 🔴 高 |
| **重复 lowercase** — `rule.pattern.lowercase()` 每次 `matches()` 都重算 | [FilterEngine.kt:352-353](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L352-L353) | 🟡 中 |
| **重复字符串解析** — `matchesDomainAnchor()` 每次调用做 `removePrefix`/`substringBefore`/`normalizeHost` | [FilterEngine.kt:368-379](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L368-L379) | 🟡 中 |
| **cosmetic 规则也线性扫描** — `cosmeticCssFor()` 对全部 cosmetic 规则逐条过滤 | [FilterEngine.kt:48-60](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L48-L60) | 🟡 中 |

### 1.3 原方案五个方案的可行性评估

| 方案 | 原方案评价 | 本文评估 | 结论 |
|:---|:---|:---|:---|
| **方案一：Token 倒排索引** | 核心推荐，性价比最高 | ✅ **同意**。这是业界标准做法 (uBlock Origin 核心算法)，纯 Kotlin 可实现，改动集中在 `FilterEngine` | **纳入第一阶段** |
| **方案二：Bloom Filter** | 空间换时间，快速排除 | ⚠️ **部分同意**。Bloom Filter 对"域名级"快速排除有效，但不能覆盖 SUBSTRING/REGEX 类型规则。实际收益不如 Token 索引大，且增加了一层间接性 | **降级为可选优化** |
| **方案三：域名树分桶** | 局部化扫描 | ✅ **同意**。可与 Token 索引互补，用于带 `domain=` 选项的规则。但大量规则没有 domain 限制，单独使用不足 | **融入 Token 索引的辅助维度** |
| **方案四：二进制预编译** | AOT 优化 | ⚠️ **过度设计**。当前 engine build 只在预热阶段执行一次且在 IO 线程，不是用户体感瓶颈。FlatBuffers/Mmap 引入大量复杂度，收益不明显 | **本期不纳入** |
| **方案五：Native Rust/C++ 引擎** | 终极方案 | ⚠️ **目前不需要**。Token 索引 + 预计算优化后，Kotlin 层面的性能完全足够（从 20 万次比较降至 ~20 次）。引入 JNI 会大幅增加维护成本 | **本期不纳入** |

### 1.4 本文最终优化策略

基于实际代码分析，确定 **两个实施阶段**：

```mermaid
graph LR
    A["阶段一<br/>Token 倒排索引 + 预计算"] --> B["阶段二<br/>缓存增强 + 并发优化"]
    style A fill:#4CAF50,color:#fff
    style B fill:#2196F3,color:#fff
```

- **阶段一**（核心优化）：Token 倒排索引 + CompiledRule 预计算字段 → **消除 O(N) 线性扫描**
- **阶段二**（增强优化）：决策缓存扩容 + 去 `@Synchronized` + cosmetic 规则域名索引 → **消除并发瓶颈**

---

## 二、阶段一：Token 倒排索引 + 预计算优化

### 2.1 需求目标

> 将 `decide()` 的匹配复杂度从 O(N)（N = 规则总数 ~200,000）降至 O(K)（K = 候选规则数 ~10-50），同时消除重复字符串计算。

### 2.2 涉及文件

| 文件 | 改动类型 | 说明 |
|:---|:---|:---|
| [FilterEngine.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt) | **重构** | 核心改动：`CompiledRule` 预计算、新增 `TokenIndex`、重写 `decide()` |
| [FilterEngineTest.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/test/java/site/anzz/childkiosk/util/filter/FilterEngineTest.kt) | **新增测试** | 新增索引正确性和性能基准测试 |

> [!IMPORTANT]
> 不改动 `FilterRuleParser.kt`、`FilterModels.kt`、`FilterRepository.kt`、`AdBlocker.kt`、`WebViewActivity.kt` 等文件。所有改动封闭在 `FilterEngine.kt` 内部，外部 API 签名不变。

### 2.3 详细实现规格

#### 2.3.1 CompiledRule 预计算字段

**现状问题**：[FilterEngine.kt:348-379](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L348-L379) 中，`matches()` 每次调用都重复计算 `rule.pattern.lowercase()`、`matchesDomainAnchor()` 中的字符串解析。

**改动要求**：在 `CompiledRule` 构造时预计算所有不变字段。

```kotlin
// CompiledRule 新增预计算字段（在构造时一次性计算）
private class CompiledRule(
    val rule: FilterRule,
    private val regex: Pattern? = null,
    private val wildcardRegex: Pattern? = null
) {
    // ---- 新增预计算字段 ----
    val patternLower: String = rule.pattern.lowercase(Locale.US)

    // 仅 DOMAIN_ANCHOR 类型使用：
    val anchorHost: String  // 从 pattern 提取并 normalizeHost() 的主机名
    val anchorPath: String  // 从 pattern 提取的路径部分(lowercase)

    // Token 索引用：
    val bestToken: String   // 该规则最具区分度的 token（用于索引分桶）

    init {
        val rawPattern = rule.pattern
            .removePrefix("http://")
            .removePrefix("https://")
        anchorHost = rawPattern.substringBefore("^")
            .substringBefore("/").normalizeHost()
        anchorPath = rawPattern.substringAfter("/", missingDelimiterValue = "")
            .substringBefore("^").lowercase(Locale.US)
        bestToken = extractBestToken(rule)
    }
    // ...
}
```

**`extractBestToken()` 算法**：

```kotlin
private fun extractBestToken(rule: FilterRule): String {
    // 从 pattern 中提取最长的纯字母数字子串作为 token
    // 跳过太短（<3字符）或太通用的 token
    val pattern = rule.pattern
    val candidates = mutableListOf<String>()

    // 对 DOMAIN_ANCHOR 类型，优先使用 host 部分
    if (rule.matchType == FilterMatchType.DOMAIN_ANCHOR) {
        val host = pattern.removePrefix("||")
            .removePrefix("http://").removePrefix("https://")
            .substringBefore("^").substringBefore("/")
            .normalizeHost()
        if (host.length >= 3) return host
    }

    // 提取所有连续的字母数字+点序列（≥3字符）
    val tokenRegex = Regex("[a-zA-Z0-9][a-zA-Z0-9.\\-]{2,}")
    tokenRegex.findAll(pattern).forEach { match ->
        candidates.add(match.value.lowercase(Locale.US))
    }

    // 返回最长的 token（最具区分度）
    // 如果没有合适的 token，返回空串（归入 universalRules）
    return candidates.maxByOrNull { it.length } ?: ""
}
```

> [!NOTE]
> `bestToken` 为空串的规则将被放入 `universalRules` 列表，每次请求仍需检查。典型的 universalRules 包括：纯通配符规则（如 `*$third-party,image`）和纯正则规则。实际占比约 5-10%。

#### 2.3.2 Token 倒排索引结构

**新增 `TokenIndex` 类**（放在 `FilterEngine.kt` 中，`private` 可见性）：

```kotlin
/**
 * Token 倒排索引。将规则按其最佳 token 分桶存储，
 * 查询时仅检索 URL 中包含的 token 对应的规则子集。
 */
private class TokenIndex(rules: List<CompiledRule>) {
    // token -> 包含该 token 的规则列表
    val tokenMap: HashMap<String, MutableList<CompiledRule>>
    // 没有可提取 token 的通用规则（每次请求都要检查）
    val universalRules: List<CompiledRule>

    init {
        val map = HashMap<String, MutableList<CompiledRule>>(rules.size / 2)
        val universal = mutableListOf<CompiledRule>()

        for (rule in rules) {
            val token = rule.bestToken
            if (token.isEmpty()) {
                universal.add(rule)
            } else {
                map.getOrPut(token) { mutableListOf() }.add(rule)
            }
        }
        tokenMap = map
        universalRules = universal
    }

    /**
     * 从 URL 中提取 tokens，查找候选规则。
     * 返回需要精细匹配的候选规则集（远小于全量规则）。
     */
    fun candidates(url: String, host: String): Sequence<CompiledRule> {
        return sequence {
            val seen = HashSet<CompiledRule>()

            // 1. 检查 host token（最高优先级）
            tokenMap[host]?.let { rules ->
                for (rule in rules) {
                    if (seen.add(rule)) yield(rule)
                }
            }

            // 2. 检查 host 的父域名
            val dotIndex = host.indexOf('.')
            if (dotIndex > 0) {
                val parentDomain = host.substring(dotIndex + 1)
                tokenMap[parentDomain]?.let { rules ->
                    for (rule in rules) {
                        if (seen.add(rule)) yield(rule)
                    }
                }
            }

            // 3. 从 URL 路径中提取 tokens
            val urlLower = url.lowercase(Locale.US)
            extractUrlTokens(urlLower) { token ->
                tokenMap[token]?.let { rules ->
                    for (rule in rules) {
                        if (seen.add(rule)) yield(rule)
                    }
                }
            }

            // 4. 始终检查 universal rules
            for (rule in universalRules) {
                yield(rule)
            }
        }
    }
}
```

**`extractUrlTokens()` 实现**：

```kotlin
/**
 * 从 URL 中提取用于索引查找的 tokens。
 * 提取策略：取 URL 路径中所有 ≥3 字符的连续字母数字+点+连字符序列。
 * 用 inline callback 避免分配中间 List。
 */
private inline fun extractUrlTokens(urlLower: String, onToken: (String) -> Unit) {
    var start = -1
    for (i in urlLower.indices) {
        val c = urlLower[i]
        if (c.isLetterOrDigit() || c == '.' || c == '-') {
            if (start < 0) start = i
        } else {
            if (start >= 0 && i - start >= 3) {
                onToken(urlLower.substring(start, i))
            }
            start = -1
        }
    }
    if (start >= 0 && urlLower.length - start >= 3) {
        onToken(urlLower.substring(start))
    }
}
```

#### 2.3.3 FilterEngine 构造改动

**修改 `FilterEngine` 类的成员变量**：

```kotlin
class FilterEngine private constructor(
    // ---- 替换原有的三个 List ----
    private val importantIndex: TokenIndex,     // was: importantBlockingRules: List<CompiledRule>
    private val exceptionIndex: TokenIndex,     // was: exceptionRules: List<CompiledRule>
    private val blockingIndex: TokenIndex,      // was: blockingRules: List<CompiledRule>
    // ---- 以下不变 ----
    private val cosmeticRules: List<CosmeticFilterRule>,
    private val scriptletRules: List<ScriptletFilterRule>,
    val report: FilterBuildReport
)
```

**修改 `build()` 方法**（[FilterEngine.kt:133-197](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L133-L197)）：

```kotlin
// 原代码 L175-183，改为：
val important = activeCompiled.filter { !it.rule.isException && it.rule.important }
val exceptions = activeCompiled.filter { it.rule.isException }
val blocking = activeCompiled.filter { !it.rule.isException && !it.rule.important }

return FilterEngine(
    importantIndex = TokenIndex(important.sortedByDescending { it.weight }),
    exceptionIndex = TokenIndex(exceptions.sortedByDescending { it.weight }),
    blockingIndex = TokenIndex(blocking.sortedByDescending { it.weight }),
    cosmeticRules = cosmetic,
    scriptletRules = scriptlets,
    report = ...
)
```

**同步修改 `EMPTY` 常量**：

```kotlin
val EMPTY = FilterEngine(
    TokenIndex(emptyList()),
    TokenIndex(emptyList()),
    TokenIndex(emptyList()),
    emptyList(), emptyList(),
    FilterBuildReport(0, 0, 0, 0, 0, 0, emptyList(), emptyList())
)
```

#### 2.3.4 重写 decide() 方法

**替换** [FilterEngine.kt:97-128](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L97-L128)：

```kotlin
@Synchronized
fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
    if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
        return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
    }
    if (context.requestUrl.isBlank()) return FilterDecision.ALLOW

    val cacheKey = "${context.requestUrl}|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
    decisionCache[cacheKey]?.let { return it }

    // ---- 核心改动：使用 TokenIndex 替代线性扫描 ----
    val url = context.requestUrl
    val host = context.requestHost

    val importantBlock = importantIndex.candidates(url, host)
        .firstOrNull { it.matches(context) }
    if (importantBlock != null) {
        return FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule").also {
            decisionCache[cacheKey] = it
        }
    }

    val exception = exceptionIndex.candidates(url, host)
        .firstOrNull { it.matches(context) }
    if (exception != null) {
        return FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule").also {
            decisionCache[cacheKey] = it
        }
    }

    val block = blockingIndex.candidates(url, host)
        .firstOrNull { it.matches(context) }
    val decision = if (block != null) {
        FilterDecision(FilterAction.BLOCK, block.rule, "blocking rule")
    } else {
        FilterDecision.ALLOW
    }
    decisionCache[cacheKey] = decision
    return decision
}
```

> [!IMPORTANT]
> `decide()` 的外部签名和语义**完全不变**。调用者（`AdBlocker.shouldBlock()`、`cleanUrlForNavigation()`）无需任何改动。

#### 2.3.5 修改 matches() 使用预计算字段

**替换** [FilterEngine.kt:348-379](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L348-L379)：

```kotlin
fun matches(context: FilterRequestContext): Boolean {
    if (!matchesOptions(context)) return false
    return when (rule.matchType) {
        FilterMatchType.DOMAIN_ANCHOR -> matchesDomainAnchor(context)
        FilterMatchType.STARTS_WITH -> context.requestUrlLower.startsWith(patternLower)  // 使用预计算
        FilterMatchType.ENDS_WITH -> context.requestUrlLower.endsWith(patternLower)      // 使用预计算
        FilterMatchType.REGEX -> regex?.matcher(context.requestUrl)?.find() == true
        FilterMatchType.SUBSTRING -> matchesWildcard(context.requestUrlLower, rule.pattern, wildcardRegex)
    }
}

private fun matchesDomainAnchor(context: FilterRequestContext): Boolean {
    if (anchorHost.isBlank()) return false                                     // 使用预计算
    if (!isSameOrSubdomain(context.requestHost, anchorHost)) return false      // 使用预计算
    if (anchorPath.isBlank()) return true                                      // 使用预计算
    return context.requestUrlLower.contains(anchorPath)                        // 使用预计算
}
```

#### 2.3.6 修改 cleanUrlForNavigation() 适配新结构

**替换** [FilterEngine.kt:77-95](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L77-L95)：

```kotlin
fun cleanUrlForNavigation(url: String, topLevelUrl: String): String? {
    if (url.isBlank() || !url.startsWith("http")) return null
    val context = FilterRequestContext(
        requestUrl = url,
        topLevelUrl = topLevelUrl.ifBlank { url },
        resourceType = FilterResourceType.DOCUMENT,
        isMainFrame = true,
        method = "GET",
        hasGesture = false
    )
    // 使用 blockingIndex 替代原来的 blockingRules 列表
    val paramsToRemove = blockingIndex.candidates(url, context.requestHost)
        .filter { it.rule.removeParams.isNotEmpty() }
        .filter { it.matches(context) }
        .flatMap { it.rule.removeParams.asSequence() }
        .toSet()
    if (paramsToRemove.isEmpty()) return null
    return removeParamsFromUrl(url, paramsToRemove)
}
```

### 2.4 阶段一不改动的部分

以下代码**保持原样不动**：

- `cosmeticCssFor()` / `scriptletJsFor()` — cosmetic 规则数量远小于 network 规则，暂不是瓶颈
- `FilterRuleParser.kt` — 解析逻辑不变
- `FilterModels.kt` — 数据模型不变
- `FilterRepository.kt` — 引擎缓存和构建逻辑不变
- `AdBlocker.kt` — 调用接口不变
- `WebViewActivity.kt` — 拦截入口不变
- `patternToRegex()` / `matchesWildcard()` / `isSafeCssSelector()` 等辅助函数不变
- `scriptletToJs()` 及相关辅助函数不变

---

## 三、阶段二：缓存增强 + 并发优化

### 3.1 需求目标

> 消除 `@Synchronized` 全局锁造成的线程串行化，扩大决策缓存容量。

### 3.2 涉及文件

| 文件 | 改动类型 | 说明 |
|:---|:---|:---|
| [FilterEngine.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt) | **修改** | 缓存替换为 `ConcurrentHashMap`，去掉 `@Synchronized` |

### 3.3 详细实现规格

#### 3.3.1 决策缓存升级

**替换** [FilterEngine.kt:38-42](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt#L38-L42)：

```kotlin
// 使用 ConcurrentHashMap 替代 synchronized LinkedHashMap
// 容量从 512 扩大到 4096
private val decisionCache = java.util.concurrent.ConcurrentHashMap<String, FilterDecision>(2048, 0.75f, 4)

// 简单容量控制：当超过阈值时清空（比 LRU 简单，但在 Token 索引加持下缓存未命中代价已很低）
private fun cacheDecision(key: String, decision: FilterDecision): FilterDecision {
    if (decisionCache.size > 4096) {
        decisionCache.clear()
    }
    decisionCache[key] = decision
    return decision
}
```

> [!NOTE]
> 为何不用 LRU？`ConcurrentHashMap` 本身不支持 LRU 驱逐。可以考虑用 Caffeine 库，但为避免引入新依赖，采用简单的阈值清空策略。在 Token 索引加持下，即使缓存未命中，查询代价也已从 O(200000) 降至 O(50)，缓存的重要性大幅降低。

#### 3.3.2 去除 @Synchronized

**改动 `decide()` 方法签名**：

```kotlin
// 去掉 @Synchronized，依赖 ConcurrentHashMap 的线程安全性
// TokenIndex 和 CompiledRule 在构建后都是不可变的，天然线程安全
fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
    // ... 逻辑不变，把 decisionCache[cacheKey] = it 改为 cacheDecision(cacheKey, it)
}
```

**安全性分析**：
- `TokenIndex`（`tokenMap` 和 `universalRules`）在构建后只读 → 线程安全
- `CompiledRule` 所有字段在构造后只读 → 线程安全
- `decisionCache` 改为 `ConcurrentHashMap` → 线程安全
- 唯一的竞态：两个线程同时对同一个 cacheKey 计算决策 → 结果幂等，重复计算无害

---

## 四、验证计划

### 4.1 正确性验证

**必须通过所有现有测试**：

```bash
./gradlew :app:test --tests "site.anzz.childkiosk.util.filter.FilterEngineTest"
```

现有的 7 个测试覆盖了核心场景：
- 域名锚定阻止 + 例外规则
- third-party + domain 选项
- cosmetic CSS 生成
- removeparam 清理
- scriptlet 注入
- badfilter 禁用
- engine 缓存行为

**新增测试**（在 `FilterEngineTest.kt` 中追加）：

```kotlin
@Test
fun tokenIndexDoesNotMissRules() {
    // 构建包含各种类型规则的引擎，验证索引后的 decide() 结果
    // 与"暴力线性扫描"结果完全一致
    val rules = """
        ||ads.example.com^
        ||tracker.test/pixel$image,third-party
        /banner*ad$script
        @@||cdn.example.com^
        ||important.ad^$important
    """.trimIndent()
    val engine = FilterEngine.build(listOf(FilterRuleSource("test", "test", rules)))

    // 验证各种 URL 的决策结果
    assertEquals(FilterAction.BLOCK, engine.decide(context("https://ads.example.com/x.js", "https://page.com")).action)
    assertEquals(FilterAction.ALLOW, engine.decide(context("https://cdn.example.com/lib.js", "https://page.com")).action)
    assertEquals(FilterAction.BLOCK, engine.decide(context("https://important.ad/x", "https://page.com")).action)
}

@Test
fun universalRulesStillMatchWithoutToken() {
    // 验证纯通配符规则（无法提取 token）仍能正确匹配
    val engine = FilterEngine.build(listOf(
        FilterRuleSource("test", "test", "*$third-party,image")
    ))
    val decision = engine.decide(context(
        url = "https://any-domain.com/photo.png",
        topLevelUrl = "https://other-site.com/page",
        type = FilterResourceType.IMAGE
    ))
    assertEquals(FilterAction.BLOCK, decision.action)
}
```

### 4.2 编译验证

```bash
./gradlew :app:compileDebugKotlin
```

### 4.3 构建验证

```bash
./gradlew :app:assembleDebug
```

### 4.4 性能验证（人工观察）

优化完成后，在设备上开启"强力去干扰"模式（加载 EasyList + EasyPrivacy + 中文补充规则），访问以下网站验证加载流畅度：

- `https://www.sina.com.cn` — 广告密集的新闻门户
- `https://www.zhihu.com` — 动态加载内容
- `https://www.bilibili.com` — 混合媒体

---

## 五、实现约束

> [!CAUTION]
> 实现时必须遵守以下约束：

1. **外部 API 不变** — `FilterEngine` 的 `decide()`、`cosmeticCssFor()`、`scriptletJsFor()`、`cleanUrlForNavigation()` 签名和语义完全不变。`FilterRepository`、`AdBlocker`、`WebViewActivity` 等调用方零改动。
2. **`CompiledRule` 保持 `private`** — 不暴露内部实现细节。
3. **`TokenIndex` 保持 `private`** — 纯内部优化，不对外暴露。
4. **排序语义保留** — 规则仍按 `weight` 降序排列。Token 索引内每个桶的规则也保持 weight 降序。
5. **`cleanUrlForNavigation()` 行为不变** — 它需要收集所有匹配规则的 `removeParams`，不能因索引而遗漏。
6. **测试先行** — 先跑通所有现有测试，再做优化改动，改动后再跑通所有测试。
7. **阶段一和阶段二可独立提交** — 阶段一（Token 索引 + 预计算）是一个完整的提交，阶段二（并发优化）是另一个。

---

## 六、文件改动矩阵

| 文件 | 阶段一 | 阶段二 | 改动范围 |
|:---|:---|:---|:---|
| [FilterEngine.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt) | ✏️ 重构 | ✏️ 修改 | `CompiledRule` 预计算, `TokenIndex` 新增, `decide()` 重写, `build()` 适配, 缓存升级 |
| [FilterEngineTest.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/test/java/site/anzz/childkiosk/util/filter/FilterEngineTest.kt) | ➕ 新增测试 | — | 索引正确性测试 |
| [FilterRuleParser.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt) | — | — | 不改动 |
| [FilterModels.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt) | — | — | 不改动 |
| [FilterRepository.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt) | — | — | 不改动 |
| [AdBlocker.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt) | — | — | 不改动 |
| [WebViewActivity.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt) | — | — | 不改动 |

---

## 七、预期效果

| 指标 | 优化前 | 阶段一后 | 阶段二后 |
|:---|:---|:---|:---|
| `decide()` 单次匹配规则数 | ~200,000 (全量扫描) | ~10-50 (候选集) | ~10-50 |
| `decide()` 线程并发 | 串行 (`@Synchronized`) | 串行 | **并行** |
| 决策缓存容量 | 512 | 512 | **4096** |
| `matches()` 字符串重复计算 | 每次都重算 | **预计算** | 预计算 |
| engine 构建耗时 | 基线 | +10~20%（构建索引开销） | 同阶段一 |
| 整体网页加载体感 | 规则多时明显卡顿 | **流畅** | 流畅 |
