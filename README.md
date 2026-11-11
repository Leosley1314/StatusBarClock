# 状态栏时钟（StatusBarClock）

一个基于 LSPosed / Xposed 的状态栏时间格式调整模块，界面按 HyperOS 设置页的视觉重做（分组标题在卡片外、行式条目、纯黑深色、16sp 行标题），并支持隐藏桌面图标。

- 包名：`com.sbclock.xposed`
- 仓库：<https://github.com/Leosley1314/StatusBarClock>
- 版本：**1.0**（`versionName="1.0"` / `versionCode=25`）
- 作用域：`com.android.systemui`
- `minSdk` 29 / `targetSdk` 34

> 关于版本号：对外可见的版本号（界面页脚、APK 文件名、本文件）统一为 `1.0`。
> `versionCode` 是递增的内部序号（现已到 25）而不是降到 1，是因为 Android 覆盖安装要求新包的 `versionCode`
> **不小于**已装包的 18，降到 1 会让 `adb install -r` 直接失败。`versionCode` 只是安装器的
> 递增序号，不显示给用户。

## 功能

界面按 MIUI / HyperOS「时钟 → 时间格式」的顺序组织，与系统设置页保持一致的操作习惯。

| 功能 | 说明 |
| --- | --- |
| 总开关 | 状态卡里一行「启用模块」。拨一下立刻落盘，不用点「保存并应用」；关闭后 1–2 秒内三处时钟全部回到系统原生样式，下面的设置原样保留。状态卡会跟着变成灰点「已停用」 |
| 显示秒 | 状态栏、下拉通知面板、锁屏的时钟都每秒刷新（模块自己起 ticker，不依赖系统刷新频率） |
| 固定宽度 | 0–120 dp，给时钟视图设 `minWidth`，避免秒位/数字宽度变化导致状态栏抖动；0 表示「默认」 |
| 24 小时制 | 开启 `HH`，关闭 `hh`（12 小时制） |
| 显示时段 | 在时间**左侧**显示时段，**12 / 24 小时制都生效**（`晚上 21:01:23`、`晚上 09:01:23`）。中文系统按 MIUI 原来的分段输出：`0 半夜 · 1–5 凌晨 · 6–11 上午 · 12 中午 · 13–17 下午 · 18 傍晚 · 19–23 晚上`；非中文系统回退 `AM` / `PM`。开关在任何小时制下都可点 |
| 小时补 0 | 开启 `HH`/`hh`，关闭 `H`/`h`，即 `09:05` 与 `9:05` |
| 开启自定义格式 | 打开后忽略上面所有开关，直接按 `SimpleDateFormat` 输出 |
| 设置格式 | 对话框内填 pattern，带**实时预览**与快捷 chips（`HH:mm` / `HH:mm:ss` / `h:mm` / `a h:mm`），非法格式当场报错 |
| 兼容模式 | 精确 hook 失效时，改写 SystemUI 内的 `DateFormat#getTimeFormat`，覆盖所有时间显示 |
| 隐藏桌面图标 | 通过 `activity-alias` 开关，隐藏后可从 LSPosed 管理器进入 |
| 激活诊断 | 状态卡只显示「已激活 / 未激活」二态，右上角是「刷新」；**模块不写任何日志文件**，也没有日志查看入口 |
| 锁屏 / 通知面板 | 大字时钟同样按本模块格式输出（`晚上 22:35:48`），字号按可用宽度自动缩放，日期行保持系统原样 |
| 界面风格 | HyperOS 视觉：分组标题在卡片外（14sp 灰）、行标题 16sp、左缩进分隔线、卡片圆角 16dp、按钮 46dp 高；浅色 `#F2F3F5` / 白卡，深色纯黑 `#000000` / `#17181A` 卡 |

「应用」卡片会实时显示**当前配置的真实输出**（例如 `晚上 21:01:23`），保存前就能确认结果。它和状态栏走的是同一段格式化代码，不会出现「界面写一套、状态栏跑另一套」。页脚版本号从 `PackageManager` 读取，不需要每次发版改代码。

## 支持的系统版本

模块启动时读取系统属性判定 ROM 与版本，在状态卡、激活判定、注入顺序里统一体现。

| ROM | 可用版本 | 判定属性 | 注入策略 |
| --- | --- | --- | --- |
| HyperOS | 1.x / 2.x / 3.x | `ro.mi.os.version.name`（`OS1.0`、`OS2.0.1.0.VNBCNXM`）、`ro.mi.os.version.code` | 按真机实存类注入：`views.MiuiClock` → `views.MiuiStatusBarClock` → `policy.MiuiStatusBarClockController` → `policy.SplitClockView` → `policy.VariableDateViewController` → `policy.VariableDateView` → `shared.clocks.AnimatableClockView` → `policy.Clock` |
| MIUI | 12 / 12.5 / 13 / 14 | `ro.miui.ui.version.name`（`V12`、`V125`、`V14`…） | `policy.MiuiClock` → `views.MiuiClock` → `views.MiuiStatusBarClock` → `policy.Clock` |
| 类原生 | 全部 | 上述属性为空 | `policy.Clock` → `widget.TextClock` |

**判定顺序必须先看 HyperOS**：HyperOS 设备同时带着 `ro.miui.ui.version.name=V816`，若先读 MIUI 属性，`V816` 会被误判成「MIUI 8.16 → 未适配」。

不在表内的版本不会被拦死：模块照常尝试注入，状态卡显示「未适配」并提示开启「兼容模式」。`minSdk` 为 29（Android 10），MIUI 11 及更早无法安装。

## 安装

1. 手机上已安装并激活 LSPosed（或兼容 Xposed API 93+ 的框架）。
2. 安装 `StatusBarClock-1.0.apk`。
3. 打开 LSPosed 管理器 → 模块 → 启用「状态栏时钟」。
4. 作用域勾选 **系统界面（com.android.systemui）**。
5. **重启一次 SystemUI**（模块界面底部有「重启 SystemUI」按钮，需要 root）。

> 重启 SystemUI 就够了，**不需要重启整机**。实测：装上模块 → SystemUI pid 从 10858 变 17901，状态卡随即从「未激活」变成「已激活」。

> 模块需要 root：写配置（`/data/system/sbclock.conf`）和重启 SystemUI 都要走 `su`。
> `RootUtil` 会依次尝试 `su`、`/system/bin/su`、`/system/xbin/su`、`/sbin/su`、
> `/debug_ramdisk/su`、`/data/adb/ksu/bin/su` 六条路径，因此 KernelSU / SukiSU /
> BakaSU / APatch / Magisk 都能识别；命中后缓存可用路径。
> 首次点「保存并应用」时若弹出授权框，请允许（KernelSU 系管理器需在「超级用户」里放行）。

## 使用

- 打开模块界面，按需切换各开关，或开启自定义格式后点「设置格式」。
- 点「保存并应用」，配置写入后 SystemUI 会在 2 秒内自动重读，状态栏随即更新。
- 「重启 SystemUI」是故障时的兜底手段（状态栏会短暂消失后恢复）。
  **别连着点**：短时间内反复重启 SystemUI，LSPosed 会判成 bootloop 并进入安全模式，
  此时所有模块都被框架跳过（表现为状态栏回到原生、状态卡却说已激活），只能重启一次整机才解除，见坑 11。
- 状态卡只有两种结论：`已激活` / `未激活`（没 root 时也是 `未激活`，副标题会点明缺 root）。右上角「刷新」按需重查。

## 配置字段

`/data/system/sbclock.conf` 是纯文本 `key=value`，由 su 写入并 `chmod 644`，SystemUI 直接读：

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `enabled` | 1/0 | 总开关。0 时模块照常注入、照常上报激活状态，但一个字都不改（没有这个键的老配置按 1 处理） |
| `hour24` | 1/0 | 24 小时制 |
| `seconds` | 1/0 | 显示秒 |
| `show_period` | 1/0 | 在时间左侧显示时段，12 / 24 小时制都生效；中文按 MIUI 七段分时段，其它语言为 `AM` / `PM` |
| `hour_pad` | 1/0 | 小时补 0 |
| `fixed_width` | int(dp) | 固定宽度，0 = 默认 |
| `custom_enabled` | 1/0 | 是否启用自定义格式 |
| `custom` | string | `SimpleDateFormat` pattern |
| `compat` | 1/0 | 兼容模式 |

兼容：旧版配置没有 `custom_enabled` 时，只要 `custom` 非空即视为启用。

## 工作原理

1. App 侧把配置通过 `su` 写到 `/data/system/sbclock.conf`（`system` uid 可读）。
2. Xposed 入口 `XposedEntry` 在 `com.android.systemui` 进程加载时注入 `HookImpl`。
3. `HookImpl` 依次尝试 hook（候选类顺序由 `DeviceProfile` 按 ROM 决定）：
   - 各候选类的 `getSmallTime`、`updateClock`、`updateText`、`updateTime`、`onTimeChanged`
   - 兜底：全进程 `TextView#setText` 常驻挂载。它有两个作用——精确 hook 未命中时负责改文本；命中时用于捕获时钟视图以挂秒表。回调内部按「精确 hook 已命中且未开秒」走快速返回，正常机型几乎零开销。
4. 「固定宽度」通过给捕获到的时钟 `TextView` 设 `minWidth`（dp→px）实现。
5. 开启「显示秒」时，模块为该视图挂一个每秒执行的 Handler，保证秒位跳动。
6. 配置在 hook 内缓存 2 秒，因此改配置无需重启 SystemUI。
7. **锁屏与通知面板的大字时钟**：判定条件是「字号 ≥ 80px 的时钟文本」（`isBigText`），
   和状态栏共用同一段格式化代码。带秒后变宽，`fitBigText` 会按当前可用宽度把字号缩到放得下，
   并且每次都从原始字号重算，所以秒位增减不会把字号越缩越小。
8. **注入状态回传**：`HookImpl` 注入成功后把时间戳与策略写进 `Settings.Global`
   （`sbclock_hook_at` 为 long，`sbclock_hook_state` 为 `precise|HyperOS 1 · Android 14` 形式）。
   首选从 `ActivityThread.currentApplication()` 取 Context；拿不到时退化为
   **在 `TextView#setText` 回调里用被 hook 的那个 View 自己的 `getContext()`** 写一次
   （`sPublished` 短路，之后零开销）。界面侧读这个值：直接读返回 0（HyperOS 拦了普通应用的
   Global 读取）时，退回 `su -c settings get global sbclock_hook_at`。

**本模块不写日志**：没有 `sbclock.log`、没有日志查看入口、`LogFile` 类已删除。
只在注入失败时往 LSPosed 框架日志打一条（`SBClock: …`），作为最小可诊断性。

## 激活判定（状态卡那两态怎么来的）

状态卡只回答一件事：**当前这个 SystemUI 进程里有没有跑着本模块**。

1. SystemUI 注入成功时，把时间戳写进 `Settings.Global` 的 `sbclock_hook_at`（毫秒）。
2. 界面上「刷新」时用一个 su 脚本一次取回两件事：
   - `settings get global sbclock_hook_at`
   - **当前 SystemUI 进程的启动时刻**：`/proc/<pid>/stat` 第 22 字段是「开机以来的时钟
     滴答数」（`CLK_TCK=100`），配合 `/proc/uptime` 反推 `now - (uptime - ticks/100)`。
3. 判定：`hook_at >= SystemUI 启动时刻 - 90 秒` 才算 `已激活`。

为什么不能只看「`hook_at` 大于 0」：SystemUI 重启后旧时间戳还躺在 `Settings.Global` 里，
新进程可能根本没被注入，只看非零就等于永远显示已激活。加了进程启动时刻这道比对，
「重启了但没注入」会如实变成 `未激活`。

实测精度：本轮重启 SystemUI，反推启动时刻 `23:33:51`，模块写入的 `hook_at` 为 `23:33:52`，
一秒内吻合，`CLK_TCK=100` 的假设在这台机器上成立（90 秒容差是给取整误差留的余量）。

## 踩过的坑（重要，改这个模块前务必看）

### 1. `stub` 的签名必须与真实 Xposed API 完全一致

这是 v2.0–v2.4 始终「注入成功但完全不生效」的真正原因。

`stub/de/robv/android/xposed/XposedBridge.java` 里曾经把 `hookAllMethods` 的返回类型写成：

```java
public static XC_MethodHook.Unhook hookAllMethods(Class<?>, String, XC_MethodHook)  // 错
```

而真实 API 是：

```java
public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?>, String, XC_MethodHook)  // 对
```

Java 编译期用 stub 做类型检查，所以 `Set` 和 `Unhook` 都能编译通过；但 dex 里记录的是
**方法描述符**（名称 + 参数类型 + **返回类型**）。运行时 LSPosed 提供的真实
`XposedBridge.hookAllMethods` 返回 `Set`，DEX 却按 `...LXC_MethodHook$Unhook;` 去查找，
于是抛出 `NoSuchMethodError`，被 `catch (Throwable)` 吞掉，日志只留下一句「注入失败」。

表现特征：`XposedBridge.log` 正常（签名对）、`XposedHelpers.findClass` 正常（签名对），
但**所有 `hookAllMethods` 调用全部失败**。

修复后校验方式（`classes.dex` 里必须看到 `Ljava/util/Set;`）：

```
hookAllMethods  ret=Ljava/util/Set;  in Lde/robv/android/xposed/XposedBridge;
```

教训：`catch (Throwable)` 里**必须**把异常打出来（本模块现在统一用
`XposedBridge.log("SBClock: … " + t)`），否则一个签名笔误会伪装成「框架没加载」。

### 2. 不生效时先看 LSPosed 日志，别急着怪重启

LSPosed 管理器 → 概览 → 日志。本模块只在**注入失败**时打日志，关键一条是
`SBClock: 精确时钟类未命中，由 TextView 兜底接管`——看到它就说明模块加载了、
只是走的是兜底路径（功能照旧，只是接管方式不同）。

模块自己不再写日志文件，所以**不要**再去 `/data/system` 找 `sbclock.log`；
判断「生效没生效」看界面状态卡的 `已激活 / 未激活`。

### 3. HyperOS 的类名和 AOSP 完全不同

Redmi K30 Pro / HyperOS 1.0 实测：

- `com.android.systemui.statusbar.policy.Clock` 只剩 2 个方法（`<init>`、`run`）
- `com.android.systemui.statusbar.policy.MiuiClock` **不存在**（真名是 `statusbar.views.MiuiClock`）
- `com.android.systemui.statusbar.policy.VariableDateView` 只剩 `<clinit>`

真实承载时钟的类：`statusbar.views.MiuiClock`、`statusbar.views.MiuiStatusBarClock`、
`statusbar.policy.MiuiStatusBarClockController`（含 `ClockListener`）、`statusbar.policy.SplitClockView`、
`statusbar.policy.VariableDateViewController`、`shared.clocks.AnimatableClockView`。

### 4. 中性色不能按 Material tone 取系统资源

Android 的 `system_neutral1_*` 编号方向与 Material tone **相反**（0 = 最浅/白，1000 = 最深/黑）。
按 tone 语义取名会把 surface 与 onSurface 取反，出现「黑底 + 白卡 + 近白文字」这种对比度归零的界面
（v2.2 之前的实际故障）。中性色固定两套：

| | 背景 | 卡片 | 主文字 | 次文字 |
| --- | --- | --- | --- | --- |
| 浅色 | `#F6F7FA` | `#FFFFFF` | `#1A1C1E` | `#5C6069` |
| 深色 | `#121316` | `#1E2024` | `#E9EBEF` | `#ADB2BC` |

只有强调色取 Monet 的 `system_accent1_600`（配白字保证对比度），低版本回退 Google 蓝 `#0B57D0`。

### 5. 时段不要交给 `SimpleDateFormat` 的 `a`

`a` 的输出完全由格式化器的 `Locale` 决定：`Locale.US` → `AM` / `PM`，中文系统 → 「上午 / 下午」。
后者只有两段，而 MIUI 状态栏一直是**七段**分时段，`a` 根本表达不出来。所以本模块不把 `a` 拼进 pattern：

1. `Cfg.pattern()` 只产出时间本身（`HH:mm` / `hh:mm`，可追加 `ss`）；
2. `Cfg.periodText()` 按小时选文案：中文走 `dayPeriod()` 七段，其它语言回退 `AM` / `PM`；
3. `Cfg.format()` 把时段拼在时间**左侧**（`晚上 21:01:23`）。

分段边界照搬 MIUITime（`net.hestudio.miuitime`）模块 `SystemUiClockHook#dayPeriod(int hour)` 的反编译结果：

| `HOUR_OF_DAY` | 时段 |
| --- | --- |
| 0 | 半夜 |
| 1 – 5 | 凌晨 |
| 6 – 11 | 上午 |
| 12 | 中午 |
| 13 – 17 | 下午 |
| 18 | 傍晚 |
| 19 – 23 | 晚上 |

中文的判定只看语言：`"zh".equals(Locale.getDefault().getLanguage())`，不看国家/地区，
所以简体、繁体、`zh_TW`、`zh_HK` 都走分段。

界面预览与 SystemUI 侧的格式化都走 `Cfg.format()`，两边永远一致。参考模块本身只实现了
12 小时制路径（dex 里只有 `h:mm` 和 `aa h:mm`），24 小时制下也拼时段是本模块按需求补的。

### 6. `TextView#setText` 兜底的接管范围必须收得很紧

兜底 hook 是全进程挂载，判定条件一旦宽松就会改掉不该改的视图。历史上出过两个问题：

1. **判定宽松**：早期用「长度 3–24 且含冒号」判定时间，任何带时间的复合文本都会被整段覆盖。
   现改为**整段正则**匹配：
   `^\s*(?:(?:AM|PM|上午|下午|凌晨|中午|傍晚|晚上|半夜)\s*)?(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(?:(?:AM|PM|上午|下午|凌晨|中午|傍晚|晚上|半夜))?\s*$`
2. **`VariableDateView` 被当成时钟**：类名里含 `VariableDateView` 曾被 `isClockView()` 认作时钟，
   结果下拉通知中心时，头部那行日期被替换成时间、**日期直接消失**。
   现在 `isClockView()` 明确排除类名含 `date` 的类。
3. **通知面板 / 锁屏的位置**：v2.9–v3.0 曾用 `isExcludedPlace()` 沿父容器 id 名排除
   `keyguard` / `qs*` / `shade` / `notification` / `lockscreen`，只让状态栏生效。
   **v3.1 起删除该逻辑**（用户要求锁屏与通知面板也接管），改为靠「类名 + 整段正则」两个条件判定，
   位置不再参与判断。实测这两个位置的日期行仍然完好（见下方坑 8）。

> 注意：排除逻辑只决定「改不改文本」，不改则视图保持系统原生内容，不会让状态栏时钟消失。
> 排查时如果看到「桌面顶部没有时间」，那是 HyperOS 桌面本身不渲染状态栏时钟，不是模块问题——
> 去任意 app 或模块界面里看状态栏即可。

### 7. 锁屏大字时钟别从 `SimpleDateFormat#format` 抢

锁屏那颗大字时钟看起来是自绘的，于是 v3.2 / v3.3 试过 hook `SimpleDateFormat#format`，
用栈判定（栈里含 `Clock` / `keyguard` 就替换结果）。结果两次都翻车：

- v3.2：通知面板变成 `晚上 晚上 晚 上 21:23`，锁屏 `晚上 晚上` 与 `21:24` 叠在一起；
- v3.3：锁屏上出现两行同样的时间（`21:28` 与 `21:28 周三`）。

根因不是栈判定写错，而是**锁屏时钟下面还挂着一个「第二行时间」文本视图**，
平时文本为空、只在自绘时钟做折叠动画时才用。从格式化入口塞时间进去，
这个空视图就被填满了，于是同一屏出现两条时间。

结论：锁屏 / 面板的大字时钟**其实就是普通 `TextView`**（`uiautomator dump` 里能看到
`text="21:32"` 的节点，`class` 报基类名 `android.widget.TextView`，真实类名取自
`getAccessibilityClassName()`），`TextView#setText` 兜底够得着，**不要**再去 hook 格式化入口。

### 8. 大字时钟是「时段视图 + 时间视图」两段，别重复拼时段

HyperOS 的锁屏与面板大字时钟本身是 MIUI 那套「时段 + 时间」的两段式渲染，
12 小时制下系统自己会显示「晚上」。所以：

- 时段视图的文本是纯「晚上」，**不匹配** `TIME_ONLY` 整段正则（正则要求含 `\d{1,2}:\d{2}`），
  所以不会被改写——这是它能保留下来、和模块拼出来的时段不打架的原因；
- 但如果放宽正则去接受「纯时段」，时段视图就会被写成一整段时间，立刻出现
  「晚上 晚上 晚上 21:23」这种叠字。

另外 12 小时制下，系统的日期行末尾会自己拼一个时段（`11月11日 周三晚上`）。
v3.6 用 `DATE_WITH_PERIOD` 把它剥掉，只留 `11月11日 周三`。

### 9. 曾经的「状态卡永远等待生效」，以及为什么最后干脆不要日志了

v1.0 修掉的真实 bug，表象和根因离得很远。**1.0 之后日志体系整套删除**，这段留作历史教训。

- 现象：状态卡一直写「等待生效 / 还没收到注入日志」，点「查看日志」显示「暂无日志」；
  与此同时状态栏时钟明显已经被改写。
- 查证：`adb shell ls -l /data/system/sbclock.log` → `No such file or directory`。
  普通 adb shell 下 `su` 也不可达（`su: inaccessible or not found`），只有模块 App 内部能跑 su。
- 根因：**SystemUI 是 `system_app`，对 `/data/system` 只有「写已有文件」的权限，没有「创建文件」的权限。**
  历史版本 `LogFile` 里写着「文件超过 32KB 就 `delete`」，一旦删掉就再也创建不回来
  → 日志永远为空 → 界面永远误报。
- 当时的修法：`LogFile` 改成三路径回退 + 覆盖轮转、`RootUtil.apply()` 顺手 `touch`/`chmod 666`、
  **并把注入状态改走 `Settings.Global`**。
- 最终结论：既然状态回传已经完全不依赖文件，**日志这东西就是纯负担**——写不进去、还得给它造权限、
  界面上多一个大概率显示「暂无日志」的入口。1.0 直接删掉 `LogFile`，只在注入失败时往
  LSPosed 日志吐一条。

### 10. 别用反射去拿 `ActivityThread.currentApplication()`

同一件事的第二个坑。`XposedHelpers.callStaticMethod(ActivityThread, "currentApplication")`
在 SystemUI 里不一定拿得到 Application，失败又只能落在写不进去的日志里——等于什么都没发生，
排查时完全静默。可靠做法是**从被 hook 的 `TextView` 上 `getContext()`**：
只要 hook 跑起来了，就一定有一个有效 Context 可以拿。反射只作为「立即写入」的锦上添花。

顺带一条：stub 里必须补上 `callStaticMethod`（`public static Object callStaticMethod(Class<?>, String, Object...)`），
否则源码编译不过；补的时候要对照真实 API 的返回类型，理由同坑 1。

### 11. 连着重启 SystemUI 会把 LSPosed 拖进安全模式

调这个模块时「改一下 → 重启 SystemUI → 截图」很容易连点四五次。LSPosed 2.x 对系统应用有
bootloop 保护，短时间内反复重启 `com.android.systemui` 会被判成崩溃循环，**整个框架进入安全模式**：

- 现象：状态栏、面板、锁屏全部回到系统原生样式（`12:02`、日期带「晚上 / 半夜」尾巴），
  但 `settings get global sbclock_hook_at` 还停在很久之前的旧值不再更新；
  状态卡却因为「旧时间戳落在当前进程之后」而看起来正常。
  通知栏会有一条「LSPosed 处于安全模式 所有 Xposed 模块…」。
- 关键判据：**重启 SystemUI 后 `sbclock_hook_at` 不变** = 这次启动模块根本没被加载，
  不是模块自身的 bug，别去翻源码。
- 解除：重启**一次整机**（不是 SystemUI）。`adb reboot` 在这台机器上被安全策略拦，
  普通 shell 又摸不到 su，所以只能手动重启。
  注意：解除后如果模块仍然不接管（状态卡「未激活」、`sbclock_hook_at` 在新 pid 下不动），
  说明 LSPosed daemon 还在内存里缓存着安全模式下那份「全部禁用」的模块表；
  这时在管理器里把模块和作用域「关 → 开」各一次把数据库改回来，**再重启一次整机**才会真正加载。
  判据依旧是 `sbclock_hook_at` 是否随新 pid 更新。
- 规避：验证时给每次重启留 30 秒以上间隔，或一次改完多项再重启一次。

### 12. 总开关拨了没反应——因为状态栏只会按分钟刷新

`setText` 是「事件驱动」的：未开秒时 MIUI 状态栏只在分钟边界调一次 `setText`，
所以「拨总开关 → 等下一次系统刷新」最长要等近一分钟，看起来像没生效。
两个补救都在 `HookImpl` 里：

1. `sTick`/ticker：给带秒的视图每秒推一次，关闭总开关时它负责把这一帧还回系统再退场；
2. `watchEnabled()`：主线程每 1.5 秒比对一次 `cfg().enabled`，一变就把 `KNOWN`
   （所有见过的时钟视图）整体 `applyText` / `restoreNative` 一遍，
   连日期行的时段尾巴也重新剥一次（`KNOWN_DATE`）。

实测：拨开关到状态栏变化 ≤ 2 秒。

### 13. 卸载重装之后，LSPosed 里那份登记是「未启用」

`adb uninstall` + `adb install --no-incremental` 之后，模块在 LSPosed 的模块列表里是
**排在最后、整卡发灰**的状态（模块 tab 的徽标数字还会掉一个）；点进详情能看到
「启用模块」是关着的，而「系统界面」作用域上的勾还在——所以很容易误判成
「已经启用、只是模块自己有 bug」，于是反复重启 SystemUI 也不见效果。

判据还是老一条：**`sbclock_hook_at` 不随新 pid 更新 = 这次启动模块没被加载**。

正确姿势：

1. 管理器里把「启用模块」手动打开（作用域保持勾选就行）；
2. 回模块 App 点「重启 SystemUI」；
3. 确认 `settings get global sbclock_hook_at` 随新 pid 更新，
   `sbclock_hook_state` 含 `precise` 就说明精确类命中。

两条伴随副作用必须一起记住：

- 重装会换 UID，**su 授权会丢**，状态卡直接变「缺少 root：无法写入配置，也无法重启 SystemUI」，
  得在 root 管理器里重新授权一次；
- 重装会清掉 App 的 SharedPreferences，界面开关退回默认值（秒关、时段关、24h 开、补 0 开）。
  但 `/data/system/sbclock.conf` 不会被删，所以**先手动把「显示秒」「显示时段」打开，
  再点「保存并应用」**，否则会用默认值把配置覆盖成错的。

## 真机验证记录

Redmi K30 Pro Zoom Edition（`lmipro`），HyperOS 1.0 / Android 14：

- 系统属性：`ro.mi.os.version.name=OS1.0`、`ro.mi.os.version.code=1`、`ro.miui.ui.version.name=V816`
- root：KernelSU 系（BakaSU 管理器，su 位于 `/data/adb/ksu/bin/su`，普通 shell 不可见）
- LSPosed：2.2.1 (7912)，模块标签 `legacy`，作用域 `com.android.systemui` 已勾选
- **注入结果**：SystemUI 重启后 pid 10858 → 17901 → 25285，日志逐条输出
  `SystemUI 已加载，开始注入` / `配置文件可读` / 注入结果；
  状态栏实测显示 `19:58:45`，秒位正常跳动，v2.5 生效

### 1.0（当前）实测 — 界面重做 + 三处时钟 + 两种小时制 + 深浅色

| 验收项 | 结果 |
| --- | --- |
| 状态栏（24 小时制） | `晚上 23:15:20`（带秒、时段在左） |
| 状态栏（12 小时制） | `晚上 11:21:13`（带秒、时段在左） |
| 下拉通知面板大字（24h） | `晚上 23:17:55`，日期行 `11月11日 周三` 正常、无「晚上」尾巴 |
| 下拉通知面板大字（12h） | `晚上 11:21:17`，日期行 `11月11日 周三` 同样干净（`stripDatePeriod` 生效） |
| 锁屏大字 | `晚上 23:16:17`（带秒、时段在左），日期行 `11/11 周三`，无重叠、无重复时段 |
| 状态卡 | 「已激活」绿点 + 「模块已在系统界面中运行 · 23:33」，右上角为「刷新」 |
| 注入策略 | `sbclock_hook_state` = `precise` → 精确类 hook 在本版本**命中**（早期「精确 hook 从未命中」的结论已作废） |
| 界面（浅色） | `#F2F3F5` 底 + 白卡，分组标题在卡片外，行标题 16sp，按钮 46dp 高 |
| 界面（深色） | 纯黑底 `#000000` + `#17181A` 卡 + `#E8E8E8` 文字，开关/链接取 accent 色 |
| 12/24 制切换 | 关掉「24 小时制」并「保存并应用」后，状态栏与面板在数秒内切成 12 小时制，无需重启 SystemUI |
| 去掉日志后重新注入 | 重启 SystemUI 后 pid 4529 → 11848，`sbclock_hook_at` 从 `23:13` 刷新为 `23:33:52`，面板仍 `晚上 23:35:59` + 日期行完好 |
| 激活判定精度 | 反推 SystemUI 启动时刻 `23:33:51` vs 写入 `23:33:52`，一秒内吻合 |

> 切换后必须点「保存并应用」才落盘——开关本身只改界面状态。这是设计如此（与 v2.x 起一致）。

**vc21 – vc25（只动界面）**：在「其他」卡片下面加了「关于」卡片。vc25 定版形态是
「HyperOS 关于手机」式信息头（应用图标 40dp + 「状态栏时钟」16sp + 「HyperOS / MIUI · LSPosed 模块」）
下面两行可点条目：

| 行 | 标题 | 说明 | 点击行为 |
| --- | --- | --- | --- |
| 1 | 项目仓库 | `github.com/Leosley1314/StatusBarClock` | 打开本仓库 |
| 2 | MIUITime | `github.com/hestudio-community/MIUITime` | 打开 MIUITime 仓库 |

两行的副标题都是去掉协议头的地址（`stripScheme()`），口径一致，也都带右端 `›`、整行可点。
真机实测：点「项目仓库」→ Chrome 落到本仓库首页（Public、About 描述正确）；
点「MIUITime」→ Chrome 落到 `hestudio-community/MIUITime`（About 是「为HyperOS还原MIUI的通知栏时间效果」）。
这五版都没有改 `HookImpl`，注入与时钟行为不受影响。

### 1.0（vc20）实测 — 总开关双向 + 三处时钟复验

这一轮是把「总开关」在解除安全模式、重新注入后的真机环境里完整复验一遍，
同时修掉了一个界面同步问题（拨开关后状态卡不同步）。

| 验收项 | 结果 |
| --- | --- |
| 注入 | 重装 + 管理器里手动启用后重启 SystemUI：pid `9262`，`sbclock_hook_at=1794415355884`（00:42:35），`sbclock_hook_state=precise` |
| 状态栏 | `半夜 00:50:51`（时段在左 + 带秒） |
| 通知面板大字 | `半夜 00:50:55`，日期行 `11月12日 周四` 干净、无「半夜」尾巴 |
| 锁屏大字 | `半夜 00:51:26`，无重叠、无重复时段 |
| 总开关 → 关闭 | 状态卡**立刻**变灰点「已停用」+「模块已注入，总开关关闭 · 时钟保持系统原生」，开关同时变灰；时钟 2–4 秒内回到原生 `12:48` |
| 总开关 → 打开 | 状态卡立刻「已激活」绿点，开关变绿；时钟 4 秒内恢复 `半夜 00:49:11` |
| 界面同步修复 | 之前 `applyEnabled()` 里 `updateStatus()` 用的是上一次探测的 `Activation`，`cfgEnabled` 还是旧值，于是「开关跟配置文件对齐」那段逻辑又把开关拨回去——看起来像点了没反应。现在 `applyEnabled()` 先把 `lastAct.cfgEnabled` 改成刚落盘的值再刷状态卡（失败回滚分支同样处理） |
| 诊断日志 | `HookImpl.install()` 第一行那条 `XposedBridge.log("SBClock: install entered…")` 已删除（它只是排查「模块到底有没有被调用」用的临时探针），vc20 起模块不再输出任何日志 |
| 重启 SystemUI 加载 vc20 | pid `11931`，`sbclock_hook_at=1794415788897`（00:49:48），`precise`，`dumpsys` 显示 `versionCode=20` |

### v3.6 实测 — 三处时钟统一

| 验收项 | 结果 |
| --- | --- |
| 状态栏 | `晚上 22:26:51`（带秒、时段在左） |
| 下拉通知面板大字 | `晚上 22:19:20`（带秒、时段在左），日期行 `11月11日 周三` 正常、末尾不再挂「晚上」 |
| 锁屏大字 | `晚上 22:35:48`（带秒、时段在左），字号自动缩放，无重叠、无重复时段 |
| 24 小时制 | 面板 `晚上 22:19:20`（切换系统 12/24 制后仍是模块自己的开关说了算） |
| 页脚 | v3.6 |

### v3.1–v3.5 迭代（踩坑过程）

| 版本 | 改动 | 结果 |
| --- | --- | --- |
| v3.1 | 删掉 `isExcludedPlace`，尝试接管锁屏/面板 | 状态栏正常；面板与锁屏未被接管（那里是普通 `TextView`，`isClockView` 不命中） |
| v3.2 | 加 `isBigText`（≥80px）+ hook `SimpleDateFormat#format` | 面板 `晚上 晚上 晚 上 21:23`、锁屏两行重叠 —— 见坑 7 |
| v3.3 | 格式化入口只输出裸时间 | 锁屏出现两条时间（`21:28` 与 `21:28 周三`），仍未对 |
| v3.4 | 移除格式化 hook，大字只改文本层，时段交给系统那段 | 面板 `晚上 21:46`、锁屏 `晚上 21:46` 干净了，但**没有秒** |
| v3.5 | 大字改回完整 pattern（带秒）+ `fitBigText` 自动缩字号 | 面板 `晚上 22:19:20`、锁屏 `晚上 22:11:46` 通过 |
| v3.6 | 剥掉 12 小时制下日期行末尾的时段 | 面板日期行 `11月11日 周三` 干净 |

### v3.0 实测 — 中文分时段（仅状态栏）

| 验收项 | 结果 |
| --- | --- |
| 12 小时制显示分段时段 | 状态栏 `晚上 08:58:49` |
| 24 小时制同样显示分段时段 | 状态栏 `晚上 21:01:23`（不是 `21:01:23`） |
| 时段在时间左侧 | 两种小时制下时段都在前 |
| 显示时段开关在 24 小时制下仍可用 | 开关可点、点击即生效 |
| 「当前格式」与状态栏一致 | 「应用」卡片显示 `晚上 08:59:55` |
| 通知中心日期保留 | 下拉面板大字时钟保持系统原生 `21:01`，日期行正常显示 `11月11日 周三` |

### v2.9 实测 — 英文 AM/PM（历史）

| 验收项 | 结果 |
| --- | --- |
| 时段是英文 | 状态栏显示 `PM 08:26:28`，不是「上午/下午」 |
| 时段在时间左侧 | `PM 08:26:28`，`a` 在 pattern 最前 |
| 通知中心日期保留 | 下拉面板头部大字时钟保持系统原生 `20:26`，下方日期行正常显示 `11月11日 周三` |
| 时段开关常可点 | 「显示时段」不再随「24 小时制」禁用，任何小时制下都渲染为可点开关 |
| 秒位跳动 | 每秒刷新，lock/unlock 后仍正常 |

> v2.9 的英文时段是阶段性需求，v3.0 已按「中文系统显示 MIUI 分时段」重做，时段文案不再由 `Locale` 决定。

每次验证流程固定：`adb install -r` → 解锁屏幕 → `am start` → 滚到底点「重启 SystemUI」→ 等约 20 秒
SystemUI 重启 → 截图。SystemUI pid 演进：10858 → 17901 → 25285 → … → 20595 → 2871 → 9456 → 12535（v3.6）→ 4529 → 11848（1.0）。

> 踩坑：这台机器有数字密码锁屏，`input swipe` 解锁只在亮屏后立即执行才有效；
> 长时间无操作会进 Dozing（`mWakefulness=Dozing`），此时 `screencap` 得到的是 0 字节空文件或纯黑图。
> 截图前务必 `input keyevent 26`（电源键）+ `input keyevent 224`（WAKEUP）并确认 `mWakefulness=Awake`。

## 已知限制

- **MIUI 未在真机上验证**：本模块只有一台 HyperOS 1.0 / Android 14 真机可用，MIUI 侧靠
  `DeviceProfile` 的候选类名表（`policy.MiuiClock` → `views.MiuiClock` → `views.MiuiStatusBarClock` → `policy.Clock`）
  加 `TextView#setText` 兜底做保障。这是代码层面的通用性，不是实机验证过的结论；
  任何 HyperOS / MIUI 版本上如果状态栏没变，先看状态卡是不是「已激活」，再考虑开「兼容模式」。
- 精确类 hook 在 HyperOS 1.0 上已命中（`sbclock_hook_state=precise`），但 `TextView#setText` 兜底
  仍然常驻——它是锁屏 / 面板大字时钟的唯一入口，不能撤。
- 状态栏宽度有限，`HH:mm:ss` 加日期之类的长格式可能被截断。
- 「兼容模式」会改变 SystemUI 内**所有**用 `DateFormat#getTimeFormat` 的时间显示（包括通知栏时间），非必要不要开。
- 隐藏桌面图标后，MIUI 桌面可能需要等几秒或滑动一下才刷新；恢复入口在 LSPosed 管理器。
- 图2 顶部的「右侧间距」「上下偏移量」属于时钟**位置**调整，不在「时间格式」分组内，本模块未实现。
- 锁屏与通知面板的大字时钟带秒后确实变宽，模块会自动缩小字号塞进可用宽度，所以那里的字号会**比系统原生小一点**；不想要小字号就把「显示秒」关掉。
- 锁屏 / 面板的时段由系统那段视图显示（MIUI 原生就有），模块只负责时间部分，因此那里不会出现「时段重复」；但若系统语言不是中文，系统那段会显示英文 `AM` / `PM`，与模块的中文分段可能不一致。
- 桌面时钟小组件（HyperOS 桌面那个 `11:16 / 11月11日 / 周三晚上`）**不在接管范围内**：它的时间是
  自绘的，日期行「周三晚上」也是系统自己拼的，模块不改它。验收请看状态栏、下拉面板、锁屏三处。

## 卸载与还原

- 在 LSPosed 里停用模块，卸载 APK 即可，不会改动系统文件。
- 想彻底清掉痕迹，可在 root shell 执行：

```
rm -f /data/system/sbclock.conf
settings delete global sbclock_hook_at
settings delete global sbclock_hook_state
```

（模块已不再产生日志文件；若早期版本留下过 `/data/system/sbclock.log`，可顺手 `rm -f` 掉。）

## 从源码构建

工程目录 `StatusBarClockX/` 是完整的可编译源码，不依赖 Gradle：

```
pwsh -File build.ps1
```

脚本使用 `aapt2 → javac → d8 → zipalign → apksigner` 手工打包，需要：

- JDK 17
- Android SDK build-tools 34.0.0
- Android SDK platform android-34

`stub/` 目录只放 Xposed API 的编译期桩类，**不会**被打进 APK（避免覆盖框架自身的类）。
改 stub 时务必对照真实 API 的完整签名（见上文「踩过的坑 1」）。

### 构建产物的校验记录

- `aapt2 compile / link`：通过；Manifest 解析出 `activity-alias .Launcher`（MAIN + LAUNCHER）、`xposedmodule`、`xposedminversion=93`、`xposedscope` 指向 `com.android.systemui`。
- `assets/xposed_init` = `com.sbclock.xposed.XposedEntry`，且 `classes.dex` 中确实含该类。
- `classes.dex` 类列表只有 `com.sbclock.xposed.*`，**没有** `de.robv.android.xposed.*` 的定义类。
- **`classes.dex` 中 `XposedBridge.hookAllMethods` 的返回类型为 `Ljava/util/Set;`**（签名正确性的硬校验）。
- `resources.arsc` 以 STORED 存放且 4 字节对齐（Android 11+ 安装的硬性要求）。
- `apksigner verify`：通过（v2.0–1.0 同签名，证书 SHA-256 `6dd5878f…36876`，可覆盖安装）。
- `zipalign -c 4`：通过。
- 产物 `StatusBarClock-1.0.apk` 约 40.6 KB，`aapt2 dump badging` 读到 `versionCode='25' versionName='1.0'`。

## 致谢

- 仓库：<https://github.com/Leosley1314/StatusBarClock>
- 部分灵感来自 **MIUITime**（<https://github.com/hestudio-community/MIUITime>）：本模块的中文分时段边界
  （`0→半夜`、`1..5→凌晨`、`6..11→上午`、`12→中午`、`13..17→下午`、`18→傍晚`、`19..23→晚上`）
  是对着它的 `SystemUiClockHook#dayPeriod(int)` 反编译结果核定的。感谢原作者。
- 两个地址在模块界面「关于」卡片里也能直接点开（`MainActivity` 的 `URL_REPO` / `URL_MIUITIME`）。

本模块为独立实现：只在计算式分时段这一处参考了上面的思路，代码结构、注入策略、界面与配置体系均自研。
