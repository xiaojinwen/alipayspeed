# 支付宝极速模式强制常开

强制支付宝在任何设备状态下都进入并保持「极速模式」，覆盖冷启动与热启动。

## 原理

反编译支付宝 **v12.12.30.8000**（`com.eg.android.AlipayGphone`）后确认：

**极速模式不是一个独立开关，而是支付宝「降级体系」（degrade）的一种输出。**
核心链路位于 `com.alipay.mobile.framework.degrade`：

| 类 | 作用 |
|---|---|
| `LowEndDeviceUtil.isLowEndDeviceZ(Context)` | 低端设备判定 |
| `LowEndDeviceUtil.needDowngradeStartZ(Context)` | **是否启动降级（总开关）** |
| `LowEndDeviceUtil.setForceLowEndDevice(...)` | 官方预留的「强制低端」入口 |
| `TaskDegradeLowDeviceUtil` | 低端设备触发（触发条件之一） |
| `TaskDegradeLowBatteryUtil` | 低电量触发 |
| `TaskDegradeLowNet` | 弱网触发 |
| `cpu.StateResultConfig` + `TaskGradeStateCallback` | CPU 负载/温度状态 |
| `down.NewDegradeController` | 降级总控制器 |
| `ue.StableGradeUtil` / `StableGradeResult` | 稳定档位结果 |

触发条件（温度、负载、机型分级等）决定是否进入降级。
本模块**不解除触发条件，而是让判定结果恒为「需要降级」**，从而极速模式常开。

### 排查过的两个同名陷阱

反编译中确认存在另外两个与本需求无关、但名字相同的「极速模式」，避免后续误改：

| 位置 | 实际含义 |
|---|---|
| `res/string/nfcpay_auto_confirm_pay`「已切换成「极速模式」安全支付中」 | **NFC 碰一下支付**的极速支付 |
| `classes4.dex` 配置 `"menuId": "normal"` / `"title": "极速模式"` / subTitle「直接输出回答，速度更快」 | **AI 深度思考**的快速档位 |

**性能极速模式在 Java 层没有独立的 `fastMode` 开关** —— 它是 degrade 体系在满足条件时的输出结果。
这正是本模块不采用「直接改某个 SP 布尔值」方案的原因：那个开关不存在，
只能通过短路 degrade 判定链路 + 伪造系统状态来达成。

## 架构：四层冗余，全部进程内

支付宝每次更新都可能改判定实现，单点拦截必然随版本失效。
因此采用多层冗余，**任一层命中即达成目标**：

| 层 | 文件 | 作用 | 抗版本变更 |
|---|---|---|---|
| L1 | `PreferenceHook` | SP 开关兜底 | 高 |
| L2 | `SystemStateHook` | 伪造内存/电池温度/机型 | **最高**（不依赖支付宝类名） |
| L3 | `DecisionHook` | 直接短路 degrade 判定链路 | 低（依赖类名，最精准） |
| L4 | `WatchdogHook` | 冷启动布防 + 热启动复查 + 60s巡检 | 高 |

版本更新后若失效，通常只是 L3 的类名变了，补一条规则即可，其余三层继续工作。

## 作用域与安全性

模块作用域只勾 `com.eg.android.AlipayGphone`，**所有操作都在支付宝进程内完成**：

| 做了什么 | 作用域 | 对系统/其它应用 |
|---|---|---|
| hook `ActivityManager` 内存读数 | 支付宝进程 | 无影响 |
| hook `BatteryManager` 温度 | 支付宝进程 | 无影响 |
| 改 `Build.MODEL` 等静态字段 | 支付宝进程的类副本 | 无影响 |
| hook `SystemProperties.get` 机型key | 支付宝进程 | 无影响 |
| 短路 degrade 判定方法 | 支付宝进程 | 无影响 |

**刻意不做的事**（早期版本曾包含，已移除）：

- ❌ **不抬高内核 thermal trip_point** —— 内核 thermal 是全系统唯一的温控机制，
  抬到 100℃ 会导致真过热时不降频，**游戏防烫、充电过热保护、电池健康、相机过热保护全部失效**，
  存在SoC / 电池硬件损伤风险。这是拆掉整机安全网，绝对不能做。
- ❌ **不使用 resetprop 改 `ro.product.model`** —— 系统属性是全局的，
  所有应用和系统都读得到：银行/支付 App 可能因此触发风控，
  游戏会按错误 GPU 型号加载驱动配置档（Adreno ↔ Mali 错配可能花屏/崩溃），
  且 `persist.sys.low_power_mode=0` 会关掉全局省电模式。

替代方案：温度走`BatteryManager.getIntProperty(TEMPERATURE)` 进程内拦截；
机型走进程内 `Build` 字段 + `SystemProperties.get` 拦截。
支付宝判定温度本就走 Java API，**无需触碰内核**。

>结论：**不需要 KernelSU 模块**，纯 LSPosed 即可，且对系统零副作用。


## 触发时机

- **冷启动**：`handleLoadPackage` 早于 `Application.onCreate` 触发 → 此时装 hook，保证支付宝第一次读判定逻辑时就是改写后的值；`attachBaseContext` 再补写一次 SP，走在支付宝自身初始化之前。
- **热启动**：进程仍存活，不再走 `handleLoadPackage` → 在 `onActivityResumed` / `onWindowFocusChanged` 补一次复查，覆盖「切回前台重新评估性能状态」的路径。

## 持续保持

三重保障，避免进入后又被退出：
1. Activity 每次回前台复查并补写 SP
2. 守护线程每 60 秒巡检一次
3. L3 把 `isLowEndDevice` / `needDowngradeStart` 恒定为 true，下游无法退出降级态

## 安装

### 1. 安装 APK

```bash
adb install -r app/build/outputs/apk/release/alipay-speed_1.0.0.apk
```

然后：
1. 打开 **LSPosed → 模块**，启用「支付宝极速模式」
2. **作用域只勾 `com.eg.android.AlipayGphone`**
3. 重启支付宝（必须重启，hook 只在进程启动时安装）

> 只需 root + LSPosed，**不需要 KernelSU 模块**。
> 若 LSPosed 作用域误勾了其它应用，模块对这些应用不会有任何作用
> （`handleLoadPackage` 内有包名白名单，非支付宝直接return）。

## 验证

```bash
# 1. 强制停止后冷启动
adb shell am force-stop com.eg.android.AlipayGphone
adb shell monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1

# 2. 观察日志
adb logcat -s AlipaySpeed:V
```

日志中应出现：

```
=== 支付宝进程已加载，开始安装拦截层 ===
[L3] 判定方法短路成功 N 处
[L4] 冷启动 attachBaseContext 已到达，开始布防
[L2] 机型伪装 -> SM-S931B
拦截层安装完成，命中统计={L1=.., L2=.., L3=.., L4=..}
```

**生效标志**：支付宝首页底部显示「已开启极速模式」。

热启动验证：从后台切回，日志应出现 `[L4] 热启动 resume 复查`，且极速模式不消失。

## 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| 日志无输出 | LSPosed 未启用或作用域没勾 | 重勾作用域并重启支付宝 |
| `L3 判定方法短路成功 0 处` | 支付宝更新改了类名 | 用新版本 APK 重跑反编译，补 `DecisionHook` 里的类名常量 |
| 首页无「已开启极速模式」 | 服务端下发的功能开关未开 | 手动进「我的 > 设置 > 通用 > 极速模式」打开一次，再重启 |
| 温度仍触发 | BatteryManager hook 未命中该路径 | 看日志 `[L2]` 有无温度伪造记录；无则补hook |

开启详细链路追踪：

```bash
adb shell setprop persist.alipayspeed.full 1
```

## 从源码构建

```bash
export JAVA_HOME="/c/Users/23123/.jdks/jbr-21.0.11"
./gradlew assembleDebug --offline
```

产物：`app/build/outputs/apk/debug/alipay-speed_1.0.0-debug.apk`

> 注：本模块刻意不依赖 androidx，纯 Xposed API 已足够，减少版本冲突与离线构建失败点。
