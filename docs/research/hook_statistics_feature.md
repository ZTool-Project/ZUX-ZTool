# ZToolStatistics（Hook 状态统计）可行性调研

Status: research only. 本文只回答“能不能做、代价多大、怎么做”，不包含实现。

调研设备：Lenovo TB710FU，Android 17（SDK 37），LSPosed v2.2.1 (7912)，KernelSU，ZTool 当前工作区构建。
调研时间：2026-11-14。

## TL;DR

结论：**功能整体可行，但用户描述里的四个前提需要修正，其中两个会直接改变实现方案。**

1. **当前 logcat 里没有 `ZToolXposedModule` 的 hook 日志。** `ModuleLog` 只调用 `xposed.log()`，LSPosed 2.2.1 把它直接写进 `/data/adb/lspd/log/modules_*.log`，不经过 logcat。实测 `logcat -d -s ZToolXposedModule:V` 为 0 行，而同一时段 LSPosed modules log 里有 390 条 I、229 条 W、9 条 E。要实现“优先 logcat”，必须在 hook 侧把 W/E/F 镜像到 `android.util.Log`。
2. **LSPosed 2.2.1 没有“日志数据库”。** `/data/adb/lspd/config/*.db` 是模块配置（`modules_config.db`）和 dex2oat 服务（`dex2oat_service.db`），不是日志库。日志是纯文本 `modules_*.log` / `verbose_*.log`。所谓“回退数据库”应重新定义为“LSPosed 日志目录”。
3. **W/E/F 直接标红误报很高。** 实测 1.5 小时内 ZTool 有 9 条 E、229 条 W。9 条 E 分布在 3 个模块，且消息本身写着 “this is normal for ZUXOS” 之类的预期回退；229 条 W 中有 173 条是没有模块归属的 `ProtectiveHooker` 框架异常，另有 44 条是 `expand_qs_panel_portrait` 的瞬时 0 宽高。按“有 W/E/F 就标红”会在健康设备上误标至少 3 个功能。
4. **“模块名 → 前端开关 title”没有现成映射。** 当前 152 个已注册 hook 模块、143 个唯一模块名、约 245 个前端设置行 key；模块名是 preference key（如 `StatusBarDisplay_Seconds`），设置行 key 是另一套（如 `status_bar_display_seconds`）。要做标题标红，必须新增一张映射和一条标题着色通道。

性能结论（详见 §4）：

| 场景 | 实测 | 判断 |
| --- | --- | --- |
| 前台实时 logcat（仅 `ZToolXposedModule:W`，含 E/F） | 空闲 0% CPU / 4.7 MB RSS；灌 200 条 W/s 时约 1.8% 单核 | 可忽略；ZTool 实际 W/E 约 0.8 条/分钟 |
| 首次/重启后分析 LSPosed 日志 | 488 KB / 4037 行：grep 70 ms，逐行扫描 30 ms；19 MB 合成文件：grep 2.56 s，逐行 0.62 s | 后台异步可接受；最坏 40 MB（4 MB × 10 个分片）约 2–5 s |
| logcat 缓冲区 | main/system/crash 各 256 KiB，整缓冲约 1.66 万行 | 只能覆盖最近几分钟，重启后必须回退 LSPosed 文件 |

建议的最终形态：

- `ZToolStatistics` 是 app 侧单例/仓库门面，负责“收集 + 解析 + 发布状态 + 导出快照”，不做 UI。
- 启动时**总是**异步分析当前 boot 的 LSPosed `modules_*.log`（不依赖“是否被杀”的判定），同时用 logcat dump 判断覆盖窗口；窗口不完整才把 LSPosed 解析结果作为历史数据。
- 前台期间额外跑一条 root `logcat` 流，只抓 `ZToolXposedModule:W`（W 过滤器天然包含 E/F）；回前台时用 LSPosed 文件补齐后台空窗。
- 问题状态分级：E/F 默认红，W 默认琥珀色计数；再加“已知良性消息”抑制和“重复次数/进程数”阈值，避免 9 条 E 把 3 个正常功能标红。
- 标题标红通过 CompositionLocal 注入，不逐个改 245 个 `SettingItem` 调用点；映射表集中在 `SearchIndex` 或独立 `HookUiMap`。
- 导出时把已启用的 hook 名称/数量、问题 hook 名称/级别/次数、健康自检结果写成 zip 根目录的 `hook_statistics.txt`。

## 1. 需求拆解与术语

用户给的 4 条需求，拆成可验证的行为：

| # | 需求 | 关键行为 |
| --- | --- | --- |
| 1a | 前台监听 `ZToolXposedModule` 日志 | 抓 W/E/F；优先 logcat；回退 LSPosed 日志；都失败则抛异常退出 |
| 1b | 应用重启/系统重启后首次启动分析 | 从 LSPosed 目录统计 W/E/F 及对应模块；不依赖完整 logcat |
| 1c | 前端数据流标红 | 把问题模块映射到对应功能开关 title，标红 |
| 2 | Hook 数量统计 | 总功能数、已启用数、问题 hook 名称列表、已启用 hook 名称列表 |
| 3 | 导出日志增强 | 导出 zip 里额外包含已启用/故障 hook 数量和名称 |
| 4 | 健康度自检 | logcat + LSPosed logger 服务是否健康 |

本文使用的术语：

- **Hook 模块（registered module）**：`HookManager.registerHookModule()` 注册的 `BaseHookModule` 实例。当前 152 个。
- **Hook 功能（feature / switch）**：`getModuleName()` 的唯一值，也就是前端开关对应的 preference key。当前 143 个唯一值。
- **问题 Hook**：在统计窗口内产生过 W/E/F 的 Hook 功能。
- **LSPosed 日志目录**：当前 boot 的 `/data/adb/lspd/log/`；上一 boot 的 `/data/adb/lspd/log.old/` 默认不参与统计，避免把旧 boot 的问题带到本次。
- **统计窗口**：首次运行 = 当前 boot；之后 = 上次成功分析的 wall-clock 时间到当前，或当前 boot 起点，取较晚者。

需要用户确认的术语：**“LSPosed 数据库”具体指什么？** 本机 LSPosed 2.2.1 没有日志数据库。如果用户指的是某个特定文件/版本（例如某个 fork 的 SQLite 日志库），需要给出路径或版本，否则本文按“LSPosed 日志目录”处理。

## 2. 现状：关键事实与证据

### 2.1 Hook 日志链路：`xposed.log()` → LSPosed 文件，不经过 logcat

`hook/base/ModuleLog.kt:72-76`：

```kotlin
private fun log(level: LogLevel, msg: String) {
    refreshLogLevel()
    if (LEVEL.priority > level.priority) return
    xposed?.log(level.priority, TAG, "[$moduleName] $msg")
}
```

`TAG` 是 `ZToolXposedModule`（`ModuleLog.kt:94`）。`BaseHookModule.safeHandleLoadPackage()` 里另有少量 `android.util.Log.d/e`（`BaseHookModule.kt:100-131`），但那些是框架适配层的“模块执行/异常”日志，不是 `logger.*` 的业务日志，数量很少。

LSPosed 2.2.1 的 modules log 实际行格式：

```text
[ 2026-11-14T16:03:53.966    10205:  4533:  4533 W/LSPosedFramework ] (com.android.systemui)[com.qimian233.ztool,ZToolXposedModule,11b5-1a1d9159965-2-11,0,1] [NativeNotificationIcon] replaceTheSmallIcon not found in QSUtil or NotificationListener, skipping hook.
```

对比 logcat：

```text
# adb shell su -c 'logcat -d -b all -s ZToolXposedModule:V ZToolXposedModuleInit:V | wc -l'
0
```

同一时段 LSPosed modules log 中的 ZTool 条目（按日志行首的 `级别/标签` 统计）：

| 级别 | 条数 |
| --- | ---: |
| I | 390 |
| W | 229 |
| E | 9 |
| F | 0 |

其中 229 条 W 包含 173 条 `ProtectiveHooker`（LSPosed 框架捕获 hook 异常后打的 W，没有模块名），如果只 grep 带 `ZToolXposedModule` 的行，W 是 56 条。这个区别对“问题归属”很重要。

**结论：** “监听 `ZToolXposedModule` 日志”在 logcat 侧目前是空数据源。要实现 logcat-first，必须在 `ModuleLog` 里增加 W/E/F 的 `android.util.Log` 镜像；否则只能监听 LSPosed 文件。

### 2.2 LSPosed 2.2.1 的日志存储

本机实测：

```text
/data/adb/lspd/
├── config/
│   ├── modules_config.db        # 模块配置，SQLite
│   └── dex2oat_service.db       # dex2oat 服务，SQLite
├── log/                         # 当前 boot
│   ├── modules_2026-11-14T16:03:37.918241.log
│   ├── verbose_2026-11-14T16:03:37.917167.log
│   ├── props.txt
│   └── kmsg.log
└── log.old/                     # 上一 boot
```

- `modules_*.log`：模块日志，`xposed.log()` 的落点。当前样本 488,241 B / 4037 行（约 1.5 小时）。
- `verbose_*.log`：框架 verbose 日志，明显更大且包含大量框架噪声；统计不需要它。
- `log.old` 是 LSPosed 守护进程启动时把旧 `log` 目录整体 rename 过去的；系统重启后旧 boot 日志进 `log.old`，不会污染当前 boot。
- 目录权限：目录 `drwxrwxrwx`，文件 `-rwxrwx--- root root`。**app 直接读不了，必须 root。**
- `lspd` 进程常驻（本机 PID 2916，RSS 约 154 MB）；日志由 daemon 内部写文件。

LSPosed 上游源码（`LogcatService.java` / `logcat.cpp`，master 分支）给出的硬限制：

- 单个日志分片最大 `kMaxLogSize = 4 * 1024 * 1024`（4 MB），达到后 `RefreshFd()` 切新文件。
- `LogLRU` 每类最多 `MAX_ENTRIES = 10` 个文件，超过删除最旧。
- 因此当前 boot 的 modules log 上限约 **40 MB**（10 × 4 MB）。
- 文件里会出现 `----part N start----` / `-----part N end----` 分片标记。
- 来源：[LSPosed LogcatService.java](https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/java/org/lsposed/lspd/service/LogcatService.java)、[logcat.cpp](https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/jni/logcat.cpp)。

**没有日志数据库。** 本机 `sqlite3` 不可用，但把 `modules_config.db` 拉到主机检查过 schema：`modules`、`modules_state`、`scope`、`module_configs`、`app_configs`、`lspd_configs`，没有日志表。`module_configs.data` 是 Java 序列化的 preference 值（`AC ED 00 05 ...`），也不是日志。

### 2.3 日志行格式与解析难点

`logcat.cpp` 的 `PrintLogLine()` 固定格式：

```text
[ %Y-%m-%dT%H:%M:%S.%03ld %8uid:%6pid:%6tid %c/%-15tag ] %message
```

对 `xposed.log()` 的条目，message 前面还会带 LSPosed 自己加的模块元信息：

```text
(进程名)[模块包名,调用方tag,调用id,序号,是否异常] 实际消息
```

解析时要注意：

1. **级别字符**：`V D I W E F S`；`ModuleLog.fatal()` 映射到 priority 7，落盘是 `F`。
2. **模块名**：在 message 开头的 `[moduleName]`。`ModuleLog.error(msg, t)` 会先拼一次 `[module]`，`log()` 又拼一次，实际会出现 `[module] [module] ...` 的重复（见 `ModuleLog.kt:56-59` 与 `72-76`）。
3. **多行堆栈**：`xposed.log()` 的 message 可以包含换行，文件里后续行没有 `[` 前缀，需要归并到上一条记录。
4. **没有模块归属的行**：`ProtectiveHooker` 的 W 只有 `Exception in hooker`，没有模块名；不能可靠映射到某个开关。
5. **模块名可能重复**：6 个模块名被多个类共享（见 §2.5）。
6. **时间没有时区**：`localtime_r` 输出本地时间，没有 offset；解析时用设备默认时区，持久化用 epoch millis。

### 2.4 App 侧现有的日志/导出/启动流程

- `LogUtils.syncLsposedLogs()`（`LogUtils.kt:166-202`）：root `cp -rf /data/adb/lspd/log/*` 到 app 私有 `filesDir/Log/lsposed/`。**只复制当前 log，不含 log.old。**
- `MainActivity.onCreate()`（`MainActivity.kt:213-221`）：启动时在 `startupScope` + `shellWorkDispatcher` 里异步调用上面的同步；不是首帧关键路径。
- `LogUtils.dumpSystemLogcat()`（`LogUtils.kt:120-147`）：导出时 root dump 全量 logcat 到 `Log/logcat/system_logcat_*.log`。
- `LogUtils.exportLogsToUri()`（`LogUtils.kt:41-60`）：先同步 LSPosed、dump logcat、生成 `system_brief.txt`，再 zip。
- `FileUtils.createZipFromDirectory(..., extraEntries)`（`FileUtils.kt:69-95`）：已经支持在 zip 根目录追加额外条目，`system_brief.txt` 就是这么进去的。加 `hook_statistics.txt` 不需要改 zip 逻辑。
- `LogCollectorService`（`LogCollectorService.kt:253-262`）：只 `--pid=<自己>`，采集 app 自身日志，不能替代跨进程 hook 日志监听。
- `EnhancedShellExecutor.executeRootCommand()`（`EnhancedShellExecutor.kt:92-121`、`220-275`）：读输出到 EOF + `future.get(timeout)`，默认 8 s、最多 2 并发、50 ms 限速；**不能用来跑长时间 logcat 流**，会超时并把进程杀掉。长流必须单独的 `ProcessBuilder` + 独立协程/线程管理。
- `MainActivity` 已有后台启动任务的模式（`startupScope`），但没有 `ProcessLifecycleOwner` 依赖；目前只有 `lifecycle-runtime` / `lifecycle-viewmodel`，没有 `lifecycle-process`。前台/后台切换可以用 Activity 生命周期回调或补一个 `lifecycle-process` 依赖。

### 2.5 Hook 清单与前端 key 的现状

静态扫描结果（`hook/modules/**` + `HookManager.kt`）：

| 指标 | 数量 |
| --- | ---: |
| `HookManager.registerHookModule()` 注册的构造器 | 152 |
| `getModuleName()` 解析出的唯一模块名 | 143 |
| 模块名是 `BoolKey` 的数量 | 143 / 143 |
| 被多个模块共享的模块名 | 6 个 |
| 前端 `SettingItem.*` 的唯一 key（正则近似） | 245 |
| `SearchIndex` 的 entry id | 318 |

6 个共享模块名：

| 模块名 | 使用它的模块数 |
| --- | ---: |
| `work_mode_portrait` | 5 |
| `freeform_keep_alive_enabled` | 2 |
| `auto_owner_info` | 2 |
| `Split_Screen_mandatory` | 2 |
| `fine_volume_steps` | 2 |
| `freeform_edge_bubble` | 2 |

**“总功能数”有两个口径：**

- **152**：实现口径，注册了多少个 Hook 模块。适合“Hook 总数”这种技术统计。
- **143**：用户口径，前端有多少个可独立开关的功能。适合“功能总数/已启用功能数”。

建议 UI 用 143，导出里同时写两个口径（`registered_modules=152`、`unique_features=143`），避免歧义。

**映射缺口：** 模块名是 preference key，设置行 key 是 UI 用的另一套。例如：

- 模块名 `StatusBarDisplay_Seconds` ↔ 设置行 `status_bar_display_seconds`
- 模块名 `Custom_StatusBarClock` ↔ 设置行 `status_bar_custom_clock`
- 模块名 `custom_network_speed_refresh_interval` ↔ 设置行 `status_bar_network_refresh`

不是简单的大小写转换，也没有统一的规则。静态扫描显示 143 个模块常量中有 142 个在 `hook/` 之外被引用（唯一例外是 `FORCE_IMMERSIVE_MODE` / `force_immersive_mode`），说明几乎每个 hook 都有对应的 Repository/ViewModel 数据路径；但从“Repository 引用”到“具体设置行 key”仍需显式映射。

### 2.6 本地/远程 preference 的读取时机

`ModulePreferencesUtils.modulePreferences`（`ModulePreferencesUtils.kt:31-51`）：

- 模块已激活且 `XposedServiceBridge.currentService != null` 时，走 LSPosed remote preferences（跨进程 binder）。
- 否则回退到 `createPackageContext(...).getSharedPreferences(...)`，再退到 app 自己的本地文件。

本机 app 本地 `xposed_module_config.xml` 只有 122 B（只有 `isConfigUpgraded`），真正的开关值都在 LSPosed 的 `modules_config.db` 里。**启动瞬间如果 binder 还没到，读本地会得到“几乎全部关闭”的错误结果。** 统计必须等 `ZToolApplication.isModuleActivatedFlow` 变为 true（带超时），或明确把“已启用数”标成不可用，不能报 0。

## 3. 逐项可行性

### 3.1 需求 1a：前台实时监听 logcat

**可行性：可行，但需要 hook 侧改动。**

现状是 logcat 没有数据。两个选择：

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A. hook 侧镜像（推荐） | `ModuleLog.log()` 在 `level >= WARN` 时额外 `android.util.Log.println(priority, "ZToolXposedModule", body)` | 真正“优先 logcat”；实时性最好；LSPosed 文件仍可兜底 | 需要改 hook 侧；所有 scoped 进程会多一条 logcat 写入；W 洪水时增加 logd 压力 |
| B. 只轮询 LSPosed 文件 | app 定期 root `tail`/读取 `modules_*.log` | 不改 hook | 需要 root 轮询；实时性差；文件很大时 I/O 反复；不是用户要的 logcat |

推荐 A。注意：

- `logcat TAG:W` 的过滤器天然包含 E/F（Android logcat 的优先级过滤是“>=”），所以一条命令 `-s ZToolXposedModule:W` 就够，不需要分别列 W/E/F。
- 流命令形如：
  ```text
  su -c 'logcat -T <起始epoch秒> -b main -b crash -v threadtime -s ZToolXposedModule:W'
  ```
  `-T` 用 epoch 秒最稳（实测 `-T 1787877000` 可用；`-T "11-14 17:30:00.000"` 也可用但受 locale/格式影响）。
- **必须 root**。Manifest 里虽然声明了 `READ_LOGS`（`AndroidManifest.xml:11`），但它是 signature|privileged 权限，普通 app 不会被授予；跨 UID 读其他进程的 logcat 只能走 root。LSPosed 本身也要求 root，所以这不是新增硬依赖。
- **只在前台跑**：Activity `onStart/onStop` 或 `ProcessLifecycleOwner`。进后台停流，回前台用 `-T` 从上次处理时间继续；后台空窗用 LSPosed 文件补齐。
- **进程泄漏风险**：长期存活的 `su` + `logcat` 子进程在 app 进程被杀后可能被 reparent 到 init 并继续运行。需要 watchdog/超时包装（例如 `timeout 1800 logcat ...`）和下次启动的清理（按精确参数 `pkill -f` 或记录 PID）。这是方案 A 的主要工程风险。
- **日志级别耦合**：`ModuleLog` 默认 `INFO`，W/E/F 都会输出；如果用户把日志级别设为 `ERROR`，W 在 hook 侧就被丢弃，logcat 和 LSPosed 文件都不会有。统计开启时应把该设置视为“W 统计不可用”，或在统计期间临时提升到 WARN（需要产品决策，不建议偷偷改用户设置）。

性能（实测）：

| 指标 | 数值 |
| --- | --- |
| 空闲过滤流 CPU | 0%（`top` 两次采样） |
| 空闲过滤流 RSS | logcat 4.7 MB + `sh` 3.8 MB（`su` 已退出到后台） |
| 200 条 W/s 时 logcat CPU | 约 1.8% 单核 |
| 200 条 W/s 时 RSS | 4.7 MB（不变） |
| 200 条 W/s 时 `lspd` CPU | 约 1% |
| 实际 ZTool W/E 速率 | 约 0.8 条/分钟（80 分钟 65 条带 `ZToolXposedModule` 的 W/E） |

结论：实时 logcat 的固定成本是一个常驻进程 + 约 5–8 MB RSS；动态成本与实际 W/E/F 速率成正比。ZTool 当前速率下可以忽略。真正的风险不是 CPU，而是“W 洪水 + 长流进程泄漏 + hook 侧镜像带来的日志噪声”。

### 3.2 需求 1b：应用重启/系统重启后首次启动分析 LSPosed 日志

**可行性：可行，而且比“判定是否被杀”更可靠。**

关键事实：

- 系统重启：LSPosed 把上一个 boot 的 `log` 移到 `log.old`，当前 `log` 从 boot 开始重新写。首次启动解析当前 `log` 即可，不会混入旧 boot。
- 应用被杀/重启：当前 boot 的 `log` 一直在写；解析窗口取 `max(lastSeen, bootStart)`，可以覆盖被杀期间的空窗。
- 不需要精确知道“是不是被杀”：启动时**总是**异步解析 LSPosed 当前 boot 的 `modules_*.log`，用 logcat dump 的覆盖窗口决定是否还需要补历史。这样即使 `ApplicationExitInfo` 不可用、或用户强制停止后重启，也不会漏。
- 如果确实要判定，可以用（可选，不作为主路径）：
  - `ActivityManager.getHistoricalProcessExitReasons()`（API 30+）读自己包最近的退出原因；
  - `Settings.Global.BOOT_COUNT` 或 `/proc/sys/kernel/random/boot_id` 判定系统重启；
  - 持久化 `lastSeenEpochMs` / `lastBootId`。

数据来源建议：**复用 `MainActivity` 启动时已经同步到 `filesDir/Log/lsposed/` 的本地副本**，不要再发一次 root `cp`。但 `syncLsposedLogs()` 目前复制整个目录（含 `verbose_*.log`），统计只需要 `modules_*.log`；可以在同步命令里只复制 `modules_*.log`，或解析时只挑 `modules_*`。

性能：

| 操作 | 实测 |
| --- | --- |
| 设备内 `su -c true` | 10–30 ms |
| 复制 1 MB LSPosed log 目录 | 30 ms |
| 488 KB / 4037 行：grep W/E/F | 70 ms |
| 488 KB / 4037 行：逐行扫描（awk） | 30 ms |
| 19 MB / 16.1 万行：grep W/E/F | 2.56 s |
| 19 MB / 16.1 万行：逐行扫描 | 0.62 s |
| `logcat -d -b main,system,crash` 全量 dump | 60 ms |
| 100 字以内的 `logcat -d -T ... -s TAG:W` | 40–50 ms |

按 4 MB/分片、10 分片的最坏 40 MB 估算：逐行解析约 1.3 s，带正则/字符串切割约 3–5 s。放在 `Dispatchers.IO`、首帧之后执行，对 UI 无感；但会和启动时的其他 root/IO 任务竞争，建议延迟 1–2 s、低优先级、可取消。

内存：必须 `BufferedReader.readLine()` 流式解析，不能 `readText()` 整个 40 MB；只保留“每个模块的计数 + 最近一条消息（截断）+ 最近时间 + 进程集合”，问题条目上限例如 500 条。堆内存可控在几 MB。

解析建议：

- 只读 `modules_*.log`，按文件名/ mtime 升序；跳过 `----part N start/end----`。
- 用“快速前缀判断 + 只在候选行上跑正则”的方式，避免每行正则。
- 多行堆栈归并到上一条；只对首行提取 `[module]`。
- 过滤 `package == com.qimian233.ztool`；区分 `tag == ZToolXposedModule`（可归属）和 `ProtectiveHooker`（不可归属）。
- 解析失败 / 文件缺失 / 无 root：`ZToolStatistics` 进入 `Unavailable(reason)`，抛 `HookStatisticsUnavailableException` 给调用方；**不要在主线程抛未捕获异常**。用户说的“抛异常退出”建议解释为“统计任务退出”，不是 app 崩溃。

### 3.3 需求 2：Hook 总数、已启用数、问题/已启用名称列表

**可行性：可行；难点在“模块清单”和“已启用数”的读取时机。**

模块清单：

- 152 个注册模块/143 个唯一功能名是从源码静态扫描得到的，但运行时不应该再维护一份手写列表。
- 推荐在 `HookManager` 里增加一个只注册、不注入 `XposedInterface` 的入口，例如 `HookManager.allModuleNames()`；app 侧调用它拿到 152 个名称并 `distinct()`。需要确认这些模块的构造函数没有副作用（目前看只有基类 `ModuleLog("", null)` 和常量初始化）。
- 或者新增 `HookCatalog`（模块名 → 标题资源 / UI key / 是否 always-on），用测试断言它与 `HookManager` 注册列表一致。前者更省维护，后者更适合做 UI 映射；可以两者结合：`allModuleNames()` 保证清单不漂移，`HookUiMap` 只负责 UI。

已启用数：

- 必须一次性读取 remote preferences 快照（`modulePreferences.all`），不要 143 次 `getBoolean`（每次都是 binder）。
- 启动时等待 `ZToolApplication.isModuleActivatedFlow.first { it }`，带例如 3 s 超时；超时或 service 死亡时把 `enabledKnown=false`，UI 显示“未知”，不要显示 0。
- 如果 remote 不可用，本地 XML 只有 `isConfigUpgraded`，不能作为真值。
- 如果未来需要“无 binder 也能统计”，可以直接读 `/data/adb/lspd/config/modules_config.db` 的 `module_configs`，但 `data` 是 Java 序列化 blob，解析成本/版本风险都高，不建议作为主路径。

列表语义：

- `enabledHookNames`：143 个唯一功能名中值为 true 的。
- `problemHookNames`：在统计窗口内出现过 W/E/F 的功能名；可以再分 `errorHookNames` / `warningHookNames`。
- `unattributedIssueCount`：`ProtectiveHooker` 等无法归属的条目数量，单独展示，不要硬塞给某个功能。
- 建议同时返回 `registeredModuleCount=152` 和 `enabledFeatureCount`，避免口径争议。
- 问题列表要同时记录“当前是否仍启用”：一个功能可能是开启时失败、之后被用户关掉；标题是否还标红需要产品决策（建议开关关闭时不标红，详情页保留历史）。
- “总数”目前按全部注册功能算，不按目标 app 是否安装过滤。如果以后要区分，可再加 `applicableFeatures`（目标包已安装的功能数），但不要把两个口径混成一个字段。

### 3.4 需求 3：导出日志增强

**可行性：高，改动小。**

`LogUtils.exportLogsToUri()` 已经通过 `FileUtils.createZipFromDirectory(..., extraEntries)` 追加根目录文件（`system_brief.txt`）。新增一个 `hook_statistics.txt` 即可：

```text
Hook Statistics
generated_at=2026-11-14T17:40:00+08:00
source=logcat+lsposed
window_start=2026-11-14T16:03:37+08:00
registered_modules=152
unique_features=143
enabled_features=42
enabled_names=disable_GameAudio, launcher_wide_grid, ...
problem_features=3
problem_names=notification_center_blur(E,4), launcher_wide_grid(E,3), PermissionControllerHook(E,2)
unattributed_warnings=173
health_logcat=ok
health_lsposed=ok
```

实现要点：

- 导出时用**内存快照**，不要重新解析 40 MB；如果快照过期（例如超过 30 s），可以在导出协程里做一次带 2 s 超时的增量刷新。
- 文本 UTF-8 + LF，和 `SystemBriefBuilder` 保持一致。
- 只写模块名、级别、次数、时间、截断后的消息；完整日志已经在 zip 的 `lsposed/` 和 `logcat/` 里，不必重复。
- 如果统计开关关闭：写 `statistics=disabled`，不要伪造 0。
- 如果统计不可用：写 `statistics=unavailable` + 原因。

### 3.5 需求 4：logcat + LSPosed logger 健康度自检

**可行性：可行；用可观测代理，不碰 LSPosed 私有内部状态。**

| 检查 | 方法 | 失败含义 |
| --- | --- | --- |
| root 可用 | `su -c id` 返回 `uid=0` | 所有跨进程日志都不可用 |
| logcat 缓冲可用 | `logcat -g` 返回 main/system/crash 且大小 > 0 | logd 异常/被裁剪 |
| logcat 回环 | app 用唯一 nonce 写一条 `Log.i("ZToolStatsProbe", nonce)`，1 s 内 `logcat -d -s ZToolStatsProbe:I` 能读到 | logcat 读写链路故障 |
| 实时流存活 | `Process.isAlive` + 最后一条日志时间 < 2 min | 流被杀/阻塞 |
| LSPosed daemon | `pidof lspd` 非空 | 框架未启动 |
| LSPosed 日志目录 | `/data/adb/lspd/log` 存在且至少一个 `modules_*.log` 可读 | 日志服务未初始化/权限问题 |
| LSPosed 日志新鲜度 | 当前 modules 文件 mtime 在最近 N 分钟（N 可按是否有启用 hook 调整） | logger 可能停写 |
| LSPosed 格式可解析 | 能解析出至少一条 `[... W/LSPosedFramework ]` 行 | 版本不兼容/文件损坏 |
| LSPosed binder | `XposedServiceBridge.currentService != null` 且 `ModuleActivationProbe.isModuleActive()` | 模块未激活/service 死亡 |

注意：

- 不要试图读 `lspd` 的内部 thread 状态（`LogcatService` 的 `isRunning` 在 daemon 进程里，app 拿不到）。
- “没有日志”不等于“logger 坏了”：如果用户没有启用任何 hook，modules log 本来就可能没有 ZTool 条目。健康判定要结合 `enabledFeatureCount > 0`。
- logcat 回环会写一条 probe 日志，建议只在统计开启/手动自检时做，不要每秒做。

### 3.6 需求 1c：前端标题标红

这是**最容易被低估**的一块，不是“加个颜色”那么简单。

当前标题渲染路径：

- `SettingItem` 是 sealed interface（`ZToolSettingsModel.kt:60-193`），有 9 个变体；`ZToolSettingItem()`（`424-573`）是统一渲染入口。
- Material 路径的 `ZToolSwitchRow` 在 `ZToolSettings.kt:228-340`，Miuix 路径用 `MiuixBasicComponent(titleColor = ...)`。
- 每个设置行都被 `HighlightableSettingRow(highlightKey = item.key)` 包了一层（`ZToolSettingsModel.kt:296-299`，`SettingHighlight.kt:262-330`）。
- `SettingItem.Custom` 也走这个 wrapper，但 `ZToolSettingItem()` 对 `Custom` 直接 `item.content()`，所以 `Custom` 里的 `ZToolSwitchRow` 只能靠 CompositionLocal 拿到 key。

推荐做法（改动面最小）：

1. 在 `HighlightableSettingRow` 里 `CompositionLocalProvider(LocalSettingKey provides highlightKey)`。
2. 新增 `LocalHookHealth`（`Map<String, HookVisualState>`，key 是 UI 设置行 key）。
3. `ZToolSwitchRow` / `ZToolCheckboxRow` / `ZListItem` / `SettingsTitleBlock` 读取 `LocalSettingKey` + `LocalHookHealth`；有状态时把 title 颜色改成 `LocalZToolColorScheme.current.error`（Miuix 用 `MiuixBasicComponent(titleColor=...)`；Material 用 `Text(color=...)`）。
4. 不需要逐个改 245 个 `SettingItem` 调用点；只有需要特殊文案/图标的行再显式传参。

映射：

- 新增 `HookUiMap`（或给 `SearchEntry` 加 `hookKeys: List<String>`），把模块名映射到一个或多个 UI key。
- 推荐集中在 `SearchIndex`：它已经有 318 个 entry、title 资源、parent key、route，是现成的“UI key ↔ title”索引；给相关 entry 增加 `hookKeys` 后，`LocalHookHealth` 可以直接按 `entry.id` 查。
- 需要一条一致性测试：每个 Hook 模块名要么出现在 `HookUiMap` 中，要么在 `exempt` 列表里（子 hook、无 UI、always-on），防止新增 hook 忘记映射。
- 143 个功能、245 个设置 key，预计映射表 143 条左右；这是本功能最大的静态工作量。

误报策略（基于实测）：

| 观测 | 数量 | 若直接标红的后果 |
| --- | ---: | --- |
| E：`notification_center_blur` “Failed to hook ... this is normal for ZUXOS” | 4 | 误标“通知中心模糊” |
| E：`launcher_wide_grid` “WorkspaceProfile#copy missing / aborting” | 3 | 误标“桌面宽网格” |
| E：`PermissionControllerHook` “Error hooking” | 2 | 误标“权限控制器” |
| W：`expand_qs_panel_portrait` “root.width = 0” | 44 | 误标“竖屏展开 QS” |
| W：`ProtectiveHooker` 无模块归属 | 173 | 无法归属，只能单独计数 |
| W：`NativeNotificationIcon` / `notification_icon_limit` / `hide_notification_overflow_dot` | 12 | 可能误标 3 个通知图标功能 |

建议默认策略：

- **E/F → 红色**（title 变红 + 详情可点）。
- **W → 琥珀色/计数徽标**，不直接变红；同一模块 W 在多个进程/多次重启重复出现，才升级为红。
- **已知良性消息**：维护一个 `(module, messagePattern, maxSdk)` 的抑制表；例如 `notification_center_blur` 的 “this is normal for ZUXOS”。抑制不等于删除，详情里仍显示“已抑制”。
- **重复/进程维度**：`expand_qs_panel_portrait` 的 44 条 W 都来自同一个进程的瞬时状态，按“unique process count >= 2”或“跨重启仍出现”升级，能过滤掉大部分噪声。
- **清除时机**：问题状态默认保留到下一次系统重启/作用域重启，或用户手动“清除统计”；不要因为后续一条 I 日志就自动清除（否则问题会闪一下就消失）。
- **开关已关闭的功能**：默认不在 title 上标红（用户已经关掉了它，红色只会造成干扰）；详情页保留“历史问题”记录。如果希望关闭状态也提示，需要单独设计图标，不要复用 title 红色。

如果用户坚持“W/E/F 一律标红”，功能仍可做，但需要接受健康设备上至少 3 个功能被标红；本文建议把“标红”定义成“E/F 红、W 琥珀”，并在详情里完整列出 W。

## 4. 建议设计

### 4.1 类与包

建议放在 `data/statistics/`，而不是 `utils/`（涉及状态、持久化、跨进程读取，不是纯工具）：

```text
data/statistics/
├── ZToolStatistics.kt              # 门面/单例：start/stop/refresh/export/snapshot
├── HookStatisticsModels.kt         # HookIssue, HookStatisticsState, Health
├── HookStatisticsRepository.kt     # 启动分析、LSPosed 文件读取、prefs 快照
├── LsposedLogParser.kt             # modules_*.log 流式解析
├── LogcatMonitor.kt                # root logcat 长流 + 行解析 + 去重
├── HookUiMap.kt                    # 模块名 ↔ UI key / title 资源
└── HookHealthChecker.kt            # logcat 回环 + lspd/文件/binder 检查
```

`ZToolStatistics` 对外 API 草案：

```kotlin
object ZToolStatistics {
    val state: StateFlow<HookStatisticsState>

    fun setEnabled(enabled: Boolean)
    fun onForeground()
    fun onBackground()
    suspend fun refresh(reason: RefreshReason): Result<Unit>
    suspend fun healthCheck(): HookHealth
    fun snapshotForExport(): HookStatisticsSnapshot
    fun issuesFor(uiKey: String): List<HookIssue>
    fun enabledHookNames(): List<String>
    fun totalFeatureCount(): Int
}
```

### 4.2 数据模型

```kotlin
enum class HookIssueLevel { WARN, ERROR, FATAL }

data class HookIssue(
    val moduleName: String?,
    val level: HookIssueLevel,
    val count: Int,
    val processNames: Set<String>,
    val firstAt: Long,
    val lastAt: Long,
    val sampleMessage: String,
    val source: IssueSource,          // LOGCAT / LSPOSED
    val suppressed: Boolean = false
)

data class HookStatisticsState(
    val enabled: Boolean,
    val phase: Phase,                 // IDLE / ANALYZING / LIVE / UNAVAILABLE
    val totalRegisteredModules: Int,  // 152
    val totalFeatures: Int,           // 143
    val enabledFeatures: Int?,        // null = 未知
    val enabledNames: List<String>,
    val issues: Map<String, List<HookIssue>>,
    val unattributedIssueCount: Int,
    val health: HookHealth?,
    val windowStart: Long,
    val lastUpdated: Long,
    val error: StatisticsError?
)
```

### 4.3 采集流水线

```text
App 启动
  └─ ZToolStatistics.start()
       ├─ 等待 module service（带超时）
       ├─ 读取 prefs 快照 → enabledNames / enabledFeatures
       ├─ 读取当前 boot 的 LSPosed modules_*.log
       │    └─ 从 max(lastSeen, bootStart) 开始流式解析 W/E/F
       ├─ 用 logcat -d -T lastSeen 检查覆盖窗口
       │    ├─ 覆盖完整 → 用 logcat 历史（可选，与 LSPosed 去重）
       │    └─ 有空洞   → 用 LSPosed 结果补历史
       ├─ 发布 state（ANALYZING → IDLE/LIVE）
       └─ 如果前台且开关开 → LogcatMonitor.start()
App 前台
  └─ LogcatMonitor: su -c 'logcat -T <last> ... -s ZToolXposedModule:W'
       └─ 行解析 → 去重 → 合并到 state（500 ms 防抖）
App 后台
  └─ LogcatMonitor.stop(); 持久化 lastSeen
```

关键决策：

- **启动总是分析 LSPosed**，不依赖“是否被杀”的判定；这是最稳的回退。
- **logcat 历史只用于检查覆盖窗口**，不承担首次启动的历史统计。
- **去重键**：`(timestampMs, pid, tid, level, messageHash)`；同一条日志可能同时出现在 logcat 和 LSPosed 文件里。
- **状态发布防抖**：前台 W 洪水时，500 ms 合并一次；不要每条日志都 `StateFlow.value = ...`，否则 Compose 会频繁重组。
- **可取消**：解析循环每 N 行 `ensureActive()`；用户关闭开关/退出前台立即取消。

### 4.4 解析规则

LSPosed 行（简化）：

```kotlin
private val lineRegex = Regex(
    """^\[\s*(\S+)\s+(\d+):\s*(\d+):\s*(\d+)\s+([VDIWEFS])/(\S+?)\s*\]\s+\((.*?)\)\[([^,]+),([^,]+),([^,]+),([^,]+),([^\]]+)\]\s?(.*)$"""
)
```

建议不要对每行跑这个正则：先用 `line.startsWith("[ ") && (line.contains(" W/") || ...)` 做候选过滤，再解析。多行堆栈只在候选行的后续行里归并。

模块名提取：

```kotlin
val module = Regex("""^\[([^\]]+)]""").find(message)?.groupValues?.get(1)
```

对 `error()` 产生的重复前缀 `[module] [module]`，只取第一个。

### 4.5 UI 接线

- 新增 `LocalSettingKey` / `LocalHookHealth`（`ui/components`）。
- `HighlightableSettingRow` 提供 `LocalSettingKey`；`ZToolSettingItem` 不需要改 245 个调用点。
- `ZToolSwitchRow` / `ZToolCheckboxRow` / `ZListItem` / `SettingsTitleBlock` 读取并覆盖 title 颜色；`enabled=false` 的灰色优先级要定义清楚（建议“问题红”优先于 disabled 灰，但要在设计稿确认）。
- 新增一个“Hook 状态”详情页/弹层，展示：
  - 已启用/总数；
  - 问题列表（级别、次数、进程、最近消息）；
  - 未归属问题数；
  - 健康自检结果；
  - 手动刷新/清除。
- 设置入口：建议放在 `设置 → 高级` 或 `设置 → 杂项` 的新 section；“统计 Hook 信息”开关本身用 app 本地设置（不需要进 remote prefs），搜索索引按 `docs/hook/add_frontend_item.md` 注册。

### 4.6 导出

在 `LogUtils.exportLogsToUri()` 的 `extraEntries` 增加：

```kotlin
mapOf(
    SystemBriefBuilder.FILE_NAME to systemBrief.toByteArray(Charsets.UTF_8),
    HookStatisticsExporter.FILE_NAME to ZToolStatistics.snapshotForExport().toText().toByteArray(Charsets.UTF_8)
)
```

导出文本见 §3.4。统计关闭时也写一行 `statistics=disabled`，让排障者知道不是漏了。

### 4.7 健康自检

按 §3.5 的表格实现，结果用一个枚举/数据类：

```kotlin
data class HookHealth(
    val logcat: CheckResult,      // OK / DEGRADED / FAILED
    val lsposed: CheckResult,
    val details: List<String>
)
```

UI 上不要只显示红/绿，最好能展开看具体失败项（root、logd、lspd、文件、binder）。

## 5. 性能预算与实测

### 5.1 实测数据

| 项目 | 命令/方法 | 结果 |
| --- | --- | --- |
| 设备内 `su` 启动 | `time su -c true` | 10–30 ms |
| adb + `su` 往返 | `adb shell 'su -c true'` | 95–100 ms |
| 复制 1 MB LSPosed log | `time cp -rf ...` | 30 ms |
| 488 KB / 4037 行 grep W/E/F | `time grep -cE ...` | 70 ms |
| 488 KB / 4037 行逐行 | `time awk '{print}' ...` | 30 ms |
| 19 MB / 16.1 万行 grep W/E/F | 合成 40 份当前文件 | 2.56 s |
| 19 MB / 16.1 万行逐行 | 同上 | 0.62 s |
| 全量 logcat dump | `logcat -d -b main,system,crash` | 60 ms |
| 过滤 dump | `logcat -d -T ... -s TAG:W` | 40–50 ms |
| logcat 缓冲 | `logcat -g` | main/system/crash 各 256 KiB；整缓冲 16,686 行 |
| 空闲过滤流 | `top` | logcat 0% CPU，4.7 MB RSS；`sh` 3.8 MB |
| 200 条 W/s | 原生 `__android_log_print` 灌流 | logcat 约 1.8% 单核；RSS 4.7 MB；`lspd` 约 1% |
| LSPosed 文件 | 80 分钟后 | modules 488 KB / 4037 行；verbose 535 KB |
| logcat 中 ZTool W/E | 80 分钟 | 65 条（56 W + 9 E，仅 `ZToolXposedModule`） |

### 5.2 预算

| 场景 | 预算 | 说明 |
| --- | --- | --- |
| 首次/重启分析（典型 0.5–4 MB） | CPU 0.1–0.6 s，后台 | 首帧后 1–2 s 启动；不影响 UI |
| 首次/重启分析（最坏 40 MB） | CPU 2–5 s，后台 | 流式、可取消；失败降级 |
| 前台实时流 | 常态 <1% CPU，5–8 MB RSS | W 洪水时按行数线性增长；实测 200 条/s 约 1.8% 单核 |
| 内存 | 解析 <10 MB 堆；状态只存聚合 | 不读整文件、不存原始行 |
| 导出 | 快照 O(1)，必要时 +2 s 刷新 | 不重新解析全量 |

## 6. 风险与待确认

### 6.1 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| W/E/F 噪声/误报 | 健康功能被标红 | E/F 红、W 琥珀；良性消息抑制；跨进程/跨重启阈值 |
| 模块名 → UI key 映射缺失 | 部分问题无法标红 | 集中 `HookUiMap` + 一致性测试；MVP 可先覆盖高频功能，其余进详情页 |
| LSPosed 日志格式变化 | 解析失败 | 版本容忍 + `Unavailable` 降级；不因解析失败崩溃 app |
| 长流 root 进程泄漏 | 后台残留 logcat | `timeout` 包装、watchdog、下次启动按参数清理、记录 PID |
| hook 侧镜像增加日志量 | logd/电池 | 只镜像 W/E/F；按模块限速（如 20 条/分钟）；统计关闭时可不镜像（需 remote 开关） |
| 用户日志级别设为 ERROR | W 丢失 | 健康页提示；统计期间临时提升（需确认） |
| remote prefs 未就绪 | 已启用数错误 | 等待 `isModuleActivatedFlow` + 超时；未知不报 0 |
| 多用户/重复日志 | 统计重复 | 按 package/uid/user 去重；只统计当前用户 |
| 隐私 | logcat 含其他 app 数据 | 只按 tag 过滤，只存 ZTool 模块名/截断消息；完整日志沿用现有导出策略 |
| 系统重启/时区 | 时间窗口错乱 | 用 epoch + boot 起点；LSPosed 本地时间按设备时区解析 |

### 6.2 需要用户确认

1. **“LSPosed 数据库”具体指什么？** 本机 LSPosed 2.2.1 没有日志数据库；如果指某个 fork/版本的 SQLite 日志，请给路径或版本。
2. **W 是否也标红？** 实测 W 噪声很大；建议 E/F 红、W 琥珀。如果坚持 W 红，需要接受误标。
3. **“总功能数”用 152 还是 143？** 建议 UI 用 143（用户开关），导出同时给 152/143。
4. **标题标红的覆盖范围？** 全部 143 个功能（映射工作量大）还是先覆盖高频功能 + 详情页？
5. **统计开关放哪里、默认开还是关？** 建议默认关，放“设置 → 高级”。
6. **“数据库仍出错则抛异常退出”是统计任务退出还是 app 崩溃？** 建议只让统计任务失败（`Unavailable` + 日志），不要 crash。
7. **是否接受 hook 侧镜像 logcat？** 这是“优先 logcat”的前置改动；不接受的话只能轮询 LSPosed 文件。

## 7. 实施拆分（估算）

| 阶段 | 内容 | 文件量 |
| --- | --- | --- |
| P0 | 解析器 + 样例 fixture + 单测（正常/多行/无模块/分片/坏行） | 3–4 |
| P1 | `HookManager.allModuleNames()` / `HookUiMap` + 一致性测试 | 3–5 |
| P2 | 启动 LSPosed 分析 + 状态模型 + 仓库 | 4–6 |
| P3 | `ModuleLog` W/E/F 镜像 + `LogcatMonitor` + 前台生命周期 | 3–5 |
| P4 | 标题着色 CompositionLocal + 设置开关 + 状态详情页 | 8–12 |
| P5 | 健康自检 + 导出 `hook_statistics.txt` + 字符串/搜索索引 | 4–6 |

总计约 20–30 个文件（含测试与双语字符串），属于“需要单独写实施计划文档”的规模。建议先做 P0–P2（只读统计 + 导出），验证数据准确性，再做 P3/P4（实时 + 标红），最后做 P5。

## 8. 附录

### 8.1 真实日志样本

W（可归属）：

```text
[ 2026-11-14T16:03:53.966    10205:  4533:  4533 W/LSPosedFramework ] (com.android.systemui)[com.qimian233.ztool,ZToolXposedModule,11b5-1a1d9159965-2-11,0,1] [NativeNotificationIcon] replaceTheSmallIcon not found in QSUtil or NotificationListener, skipping hook.
```

E（可归属，注意重复的 `[module]`）：

```text
[ 2026-11-14T16:03:54.042    10205:  4533:  4533 E/LSPosedFramework ] (com.android.systemui)[com.qimian233.ztool,ZToolXposedModule,11b5-1a1d9159965-2-29,0,1] [notification_center_blur] [notification_center_blur] Failed to hook setNotificationPanelBlurBehind(int), this is normal for ZUXOS.
```

W（不可归属）：

```text
[ 2026-11-14T16:03:55.827    10205:  4533:  4533 W/LSPosedFramework ] (com.android.systemui)[com.qimian233.ztool,ProtectiveHooker,11b5-1a1d9159965-2-39,0,1] Exception in hooker
```

### 8.2 复现命令

```text
adb shell su -c 'logcat -g'
adb shell su -c 'ls -la /data/adb/lspd/log /data/adb/lspd/log.old'
adb shell su -c 'wc -l /data/adb/lspd/log/modules_*.log'
adb shell su -c 'grep -cE " [WE]/(LSPosedFramework|LSPosed-Bridge) " /data/adb/lspd/log/modules_*.log'
adb shell su -c 'logcat -d -s ZToolXposedModule:V | wc -l'
adb shell su -c 'time logcat -d -b main,system,crash -v threadtime > /dev/null'
```

### 8.3 参考

- 项目内：`hook/base/ModuleLog.kt`、`hook/base/BaseHookModule.kt`、`hook/base/HookManager.kt`、`utils/LogUtils.kt`、`utils/EnhancedShellExecutor.kt`、`ui/components/ZToolSettingsModel.kt`、`ui/components/ZToolSettings.kt`、`ui/components/SettingHighlight.kt`、`search/SearchIndex.kt`、`data/keys/PreferenceKeys.kt`。
- LSPosed 上游：`LogcatService.java`、`ConfigFileManager.java`、`logcat.cpp`（master 分支；2.2.1 的 libxposed 日志路径是实测行为，上游 master 可能不完全一致）。
- 相关项目参考（非本项目依赖）：HyperCeiler 的 log diagnostics / logcat import 设计，同样是“LSPosed 文件 + logcat 导入 + Room/SQL 去重”的思路，可作为实现参考。
