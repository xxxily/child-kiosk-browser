# 网页过滤故障排查手册

## 适用范围

本手册用于排查以下现象：

- 开启强力过滤后网页白屏、脚本/样式全部失效。
- 管理页显示过滤开启，但广告、追踪或弹窗仍然通过。
- 更新订阅后规则数骤降、订阅设置回滚或已删除订阅重新出现。
- 某些网站在清理 URL 参数后登录、回调、签名链接或路由失效。
- 元素隐藏没有生效、页面闪烁，或怀疑订阅规则触发了额外网络请求。
- 修改过滤设置后，已打开网页与新打开网页行为不一致。

相关设计与审查结论见：

- `docs/adblock_filtering_requirements.md`
- `docs/reviews/web_filtering_deep_review_2026-07-10.md`

## 安全边界

1. 真实网页必须继续使用 `WebViewActivity -> FrameLayout -> WebView`。
2. `WebViewActivity` 在 `:webview` 进程，不能依赖该进程长期读取的
   SharedPreferences 自动变新。
3. `shouldInterceptRequest()` 不能同步读盘、下载或编译规则。
4. 订阅内容是不可信输入：不能执行远程 JavaScript，也不能把 selector 拼接成可逃逸的
   CSS 声明。
5. 引擎构建、缓存或换代失败时必须使用 last-known-good/bundled fallback 并暴露降级，
   不能静默全量放行。

## 快速分流

### 整站白屏或全部资源变空响应

重点检查：

1. 当前预设是否为 STRONG。
2. 命中规则是否包含 `$removeparam`。
3. 过滤日志的 action、source、matchType 和 ruleText。
4. 普通脚本请求是否错误返回 BLOCK。

历史根因：modifier-only `$removeparam` 曾同时进入普通 blocking index，`*` 因而匹配所有
HTTP(S) 请求。正确行为是 URL 清理规则只进入独立 transformation plane；它可以改变符合条件
的主框架 GET 导航，但不得产生普通 BLOCK。

最小回归：

```text
规则：*$removeparam=utm_source
请求：https://example.com/app.js
网络决策：ALLOW
导航：https://example.com/page?utm_source=x&keep=1
清理后：https://example.com/page?keep=1
```

### 过滤开启但稳定漏拦

重点检查：

- URL 是否超过 2048 字符；长度不能成为全量 ALLOW 条件。
- 规则是否被误判为 hosts，例如 `.ads.controller.js$script`。
- 规则是否使用双端锚、domain path、`*`、`^` 或 regex `$`。
- 请求是否来自 iframe、fetch/beacon、Service Worker 或 WebSocket。
- 当前 Activity 持有的 engine generation/health 是否与 runtime snapshot 一致。
- 是否出现 `DEGRADED_LKG` 或 `DEGRADED_BUNDLED`。

WebView 对部分资源类型没有完整上下文。无法可靠观测的能力应在 UI 标为 partial/unsupported，
不要用“已解析规则数”推断真实覆盖率。

### 更新订阅后规则异常

先记录：

- 订阅 ID、URL、content generation/hash。
- 更新前后总规则数、可用规则数、不支持规则数和首个错误。
- 正式 generation 文件与 metadata pointer。
- 更新期间是否切换预设、开关订阅、添加或删除订阅。

正确发布顺序：

```text
HTTPS stream -> staging byte limit -> content validation -> compile
-> immutable generation file -> lock/CAS current ID+URL
-> metadata pointer commit -> delayed old-generation cleanup
```

任一阶段失败都应继续读取上一 generation。不要直接覆盖当前正式文件，也不要基于下载开始前的
整个订阅列表写回 SharedPreferences。

### URL 清理后登录或签名链接失效

检查清理前后 raw URL，而不是解码后的 URI：

- `%2F` 是否变成 `/`。
- `%2F` 是否被二次编码成 `%252F`。
- 未删除参数的顺序、空值、重复值和 fragment 是否变化。
- URL 是否含 `signature`、`sig`、`x-amz-signature`、`x-goog-signature`、`token`、
  `access_token`、`id_token` 或 `code` 等签名/认证参数。
- 导航是否为主框架 GET；POST 不应自动清参。

清理器必须按原始字符串的 `?`/`#` 边界和 raw query pair 删除目标项，不得用 decoded path/query
重新构造整条 URI。

### 怀疑 cosmetic 规则泄露访问站点

危险模式包括 selector 内出现：

- `{`、`}`、`;`、`@import`、`url()`。
- CSS 注释、反斜杠转义或控制字符。
- 未支持的 procedural selector。

正确注入路径只允许：

1. Kotlin selector policy 接受的 selector。
2. selector 作为 JSON 数组传入 JavaScript。
3. `querySelectorAll(selector)` 查找节点。
4. 命中节点只添加固定本地 class。
5. stylesheet 只包含固定的 `.child-kiosk-filter-hidden` 声明。

订阅 selector 永远不能进入 `style.textContent` 的 CSS grammar。

## 诊断信号

过滤健康状态应至少区分：

| 状态 | 含义 | 处理 |
| --- | --- | --- |
| `PREPARING` | 后台准备 snapshot 对应引擎，导航暂存 | 不阻塞 UI/拦截线程；等待有界超时 |
| `READY` | snapshot 与 engine generation 一致 | 正常服务 |
| `DEGRADED_LKG` | 新引擎失败，继续上一可用 generation | 提示管理员，保留过滤，不静默放行 |
| `DEGRADED_BUNDLED` | 首次构建失败，使用内置最小安全规则 | 提示管理员并允许重试 |

同时观察：

- buildDurationMs、decision P95/P99。
- cache hit/miss、candidateCount、regexEvaluationCount。
- sourceReports 中 network/cosmetic/scriptlet/unsupported/skipped 的独立口径。
- 当前 Activity snapshot generation 与 engine generation。
- 订阅 metadata pointer 指向的不可变文件是否存在且 hash 相符。

## 验证命令

使用 JDK 17：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :app:testStandardDebugUnitTest :app:testEnhancedDebugUnitTest

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :app:assembleStandardDebug :app:assembleEnhancedDebug
```

重点回归：

- removeparam 不产生 BLOCK，raw URL 保真。
- hosts 与 ABP 不混淆；未知 cosmetic marker 计 unsupported。
- 双端锚、domain path、wildcard、separator 行为。
- 恶意 selector 只能作为 JSON 数据，不能进入 CSS。
- 10KB/100KB tracker URL 仍被 domain anchor 阻断。
- 预设往返保留自定义订阅。
- 更新中切换/删除/新增不会被旧任务覆盖。
- 任一发布阶段失败仍可读取上一 generation。
- engine cache eviction/build failure 不会造成静默 ALLOW。
- popup 使用 target + opener + user gesture 决策。
- popup 的 `about:blank` 变体和非 HTTP(S) 中间页不得注册；HTTP 重定向每跳保持 POPUP 上下文，最终 commit 后才注册。
- pending popup 的并发上限、10 秒超时、阻断销毁和 Activity teardown 必须由 Activity 主线程 Handler 驱动；不要对未 attach 的临时 WebView 使用 `View.post/postDelayed`。
- matcher 对抗语料触发候选/字符预算时必须记录 `budget-exhausted-*` 并 fail-safe BLOCK，不能静默 ALLOW。

## 真机检查

至少覆盖 Android 9、12、13/14 与一个当前 WebView 版本：

1. 使用本地测试页触发长 URL、iframe、fetch、beacon、Service Worker、WebSocket 和 popup。
2. 使用测试 HTTP server 观察恶意 cosmetic payload 是否产生任何额外请求。
3. 更新过滤设置后复用已有 Activity，确认 runtime generation 原子换代。
4. 模拟下载中切换预设、删除订阅和杀进程，确认 last-known-good。
5. Android 9–12 从另一测试 App 发送过滤日志广播，确认接收端不可达或拒绝。

## 回滚策略

- 规则更新异常：把 metadata pointer 回指上一不可变 generation，不覆盖/修补旧文件。
- 新引擎构建失败：维持 `DEGRADED_LKG`；首次安装使用 bundled fallback。
- Cosmetic 兼容事故：对目标站点关闭 cosmetic，或临时停用不可信订阅的 cosmetic；不要关闭全部网络过滤。
- `$removeparam` 事故：停用 URL transformation plane，不要删除普通广告/追踪规则。
- Popup gate 事故：保留现有 WebView tab/back 顺序；不要通过关闭 kiosk 退出验证来绕过问题。

## 隐私提示

过滤日志的默认 UI 与 Logcat 路径必须隐藏 query、fragment 和完整远程规则，只保留有界的
scheme/host/path、source 与 match type。只有家长明确确认后才能导出完整诊断；不要把日志自动上传给订阅维护方。
