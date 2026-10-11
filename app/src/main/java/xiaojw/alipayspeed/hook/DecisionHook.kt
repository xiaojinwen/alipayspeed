package xiaojw.alipayspeed.hook

import xiaojw.alipayspeed.SpeedLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * L3 判定逻辑短路层 —— 核心层。
 *
 * ── 反编译结论（v12.12.30.8000 / classes.dex）──
 * 支付宝「极速模式」的底层实现是 com.alipay.mobile.framework.degrade（降级）体系，
 * 而非独立的 fastMode 开关。核心链路：
 *
 *   LowEndDeviceUtil.isLowEndDeviceZ(Context)      低端设备判定
 *   LowEndDeviceUtil.needDowngradeStartZ(Context)  是否启动降级 ← 总开关
 *   TaskDegradeLowDeviceUtil                       低端设备降级任务（触发条件之一）
 *   TaskDegradeLowBatteryUtil                      低电量降级
 *   TaskDegradeLowNet                              弱网降级
 *   cpu.StateResultConfig + TaskGradeStateCallback CPU 负载/温度状态
 *   ue.StableGradeUtil / StableGradeResult         稳定档位结果输出
 *   down.NewDegradeController                      降级总控制器
 *
 * 注意：性能极速模式在 Java 层没有独立 fastMode 开关，
 * 它是 degrade 体系在满足条件时的输出。因此不能靠改某个 SP 布尔值达成，
 * 只能短路上述判定 + 伪造系统状态。
 *
 * 支付宝自带 setForceLowEndDevice(...) —— 说明官方本身就有「强制低端」能力，
 * 本层优先复用该入口，失败再回退到直接短路判定方法。
 *
 * 本层每条规则都做了「类/方法不存在就跳过」处理：
 * 支付宝更新会改类名，缺失的规则自动失效但不影响其它规则，更不会导致模块崩溃。
 */
object DecisionHook {

    private const val LAYER = "L3"

    // ── 判定类名常量（集中管理，版本更新时只改这里）──
    private const val CLS_LOW_END_UTIL = "com.alipay.mobile.common.logging.util.LowEndDeviceUtil"
    private const val CLS_LOW_END_UTIL_FW = "com.alipay.mobile.framework.degrade.util.LowEndDeviceUtil"
    private const val CLS_LOW_DEVICE_TASK_UTIL = "com.alipay.mobile.framework.degrade.TaskDegradeLowDeviceUtil"
    private const val CLS_NEW_DEGRADE_CTRL = "com.alipay.mobile.framework.degrade.down.NewDegradeController"
    private const val CLS_GRADE_STATE_HOLDER = "com.alipay.mobile.framework.degrade.dynamic.GradeStateMemHolder"
    private const val CLS_CPU_STATE_CONFIG = "com.alipay.mobile.framework.degrade.cpu.StateResultConfig"
    private const val CLS_STABLE_GRADE_UTIL = "com.alipay.mobile.framework.degrade.ue.StableGradeUtil"
    private const val CLS_STABLE_GRADE_RESULT = "com.alipay.mobile.framework.degrade.ue.StableGradeResult"
    private const val CLS_LOW_BATTERY_UTIL = "com.alipay.mobile.framework.degrade.TaskDegradeLowBatteryUtil"

    // 需要强制返回 true 的判定方法名（覆盖不同版本的命名变体）
    private val TRUE_METHODS = listOf(
        "isLowEndDevice", "isLowEndDeviceZ", "isLowEndDeviceForBiz",
        "needDowngradeStart", "needDowngradeStartZ",
        "shouldDegrade", "isDegrade", "isDegraded",
        "isLowDevice", "isLowDeviceByScanFlag",
        "checkLowBatteryMode", "isLowPerformanceDevice"
    )

    fun install(loader: ClassLoader) {
        forceLowEndDeviceFlag(loader)
        shortCircuitDecisionMethods(loader)
        lockCpuStateConfig(loader)
        keepDegradeState(loader)
    }

    /**
     * 首选路径：调用支付宝自带的 setForceLowEndDevice。
     *
     * 这是官方预留的「强制低端设备」入口 —— 一旦置位，
     * isLowEndDevice / needDowngradeStart 等所有下游判定都会走降级分支，
     * 比逐个短路判定方法更彻底、也更抗版本变更。
     */
    private fun forceLowEndDeviceFlag(loader: ClassLoader) {
        HookCore.safe(LAYER, "setForceLowEndDevice 调用") {
            val cls = HookCore.findClassInApp(loader, CLS_LOW_END_UTIL_FW)
                ?: HookCore.findClassInApp(loader, CLS_LOW_END_UTIL)
                ?: return@safe
            val m = cls.methods.firstOrNull { it.name == "setForceLowEndDevice" && it.parameterCount >= 1 }
                ?: return@safe
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 强制把入参改成 true，防止支付宝内部把标记复位
                    SpeedLog.d("setForceLowEndDevice 被调用，强制入参为 true")
                    for (i in 0 until param.args.size) {
                        val a = param.args[i]
                        if (a is Boolean) param.args[i] = true
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    SpeedLog.d("setForceLowEndDevice 执行完成，当前值=${param.result}")
                }
            })
        }
    }

    /**
     * 兜底路径：直接把判定方法短路为 true。
     *
     * 遍历两个 LowEndDeviceUtil（common + framework 各一份，支付宝存在重复实现），
     * 对所有匹配 TRUE_METHODS 的方法按参数个数分别挂钩，
     * 无论参数是 Context / int / boolean / 无参都能覆盖。
     */
    private fun shortCircuitDecisionMethods(loader: ClassLoader) {        val classes = listOfNotNull(
            HookCore.findClassInApp(loader, CLS_LOW_END_UTIL),
            HookCore.findClassInApp(loader, CLS_LOW_END_UTIL_FW),
            HookCore.findClassInApp(loader, CLS_LOW_DEVICE_TASK_UTIL),
            HookCore.findClassInApp(loader, CLS_NEW_DEGRADE_CTRL),
            HookCore.findClassInApp(loader, CLS_LOW_BATTERY_UTIL),
            HookCore.findClassInApp(loader, CLS_STABLE_GRADE_UTIL)
        )
        if (classes.isEmpty()) {
            SpeedLog.w("[$LAYER] 未找到任何 degrade 判定类，全部规则跳过")
            return
        }

        var hooked = 0
        classes.forEach { cls ->
            cls.declaredMethods.forEach { m ->
                val name = m.name
                if (TRUE_METHODS.none { name.equals(it, ignoreCase = true) || name.contains(it, ignoreCase = true) }) return@forEach
                // 只处理无参与单参（Context/int/boolean），多参业务方法不动，避免误伤
                if (m.parameterCount > 1) return@forEach
                // 返回值必须是 boolean 才有短路意义
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                if (m.returnType != java.lang.Boolean.TYPE && m.returnType != java.lang.Boolean::class.java) return@forEach

                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    hooked++
                    SpeedLog.d("短路 ${cls.simpleName}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                } catch (t: Throwable) {
                    SpeedLog.d("跳过 ${cls.simpleName}.${m.name}: ${t.javaClass.simpleName}")
                }
            }
        }
        if (hooked > 0) SpeedLog.i("[$LAYER] 判定方法短路成功 $hooked 处")
    }

    /**
     * CPU 状态锁死。
     *
     * StateResultConfig 承载 CPU 负载/温度采样结果 —— 用户点名的「温度过高」条件。
     * 把它的字段读写一并接管，无论采样多少都呈现「空闲低温」状态。
     */
    private fun lockCpuStateConfig(loader: ClassLoader) {
        val cls = HookCore.findClassInApp(loader, CLS_CPU_STATE_CONFIG)
        if (cls == null) {
            SpeedLog.d("StateResultConfig 不存在（版本差异），跳过 CPU 状态锁")
            return
        }
        HookCore.safe(LAYER, "StateResultConfig 字段锁") {
            cls.declaredFields.forEach { f ->
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) return@forEach
                val t = f.type
                // int 型状态值一律归零（0 = 正常/未降级）
                if (t == Int::class.javaPrimitiveType || t == Int::class.java ||
                    t == Boolean::class.javaPrimitiveType || t == Boolean::class.java ||
                    t == Long::class.javaPrimitiveType || t == Long::class.java ||
                    t == Float::class.javaPrimitiveType || t == Float::class.java ||
                    t == Double::class.javaPrimitiveType || t == Double::class.java
                ) {
                    try {
                        XposedBridge.hookAllMethods(cls, f.name, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val cur = param.result
                                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                                val zero: Any = when (cur) {
                                    is Int -> 0
                                    is Boolean -> false
                                    is Long -> 0L
                                    is Float -> 0f
                                    is Double -> 0.0
                                    is java.lang.Integer -> 0
                                    is java.lang.Boolean -> false
                                    is java.lang.Long -> 0L
                                    is java.lang.Float -> 0f
                                    is java.lang.Double -> 0.0
                                    else -> return
                                }
                                param.result = zero
                                SpeedLog.d("CPU状态归零: ${cls.simpleName}.${f.name}")
                            }
                        })
                    } catch (_: Throwable) { }
                }
            }
        }
    }

    /**
     * 降级状态保持。
     *
     * GradeStateMemHolder 内存等级 + CPU 状态回调：
     * 强制走「不降级」分支，避免运行期把 L3 已建立的状态改回去。
     * 类/方法在某些版本不存在（NoSuchMethodError），属正常降级，不影响主链路。
     */
    private fun keepDegradeState(loader: ClassLoader) {
        HookCore.safe(LAYER, "GradeStateMemHolder 状态保持") {
            val cls = HookCore.findClassInApp(loader, CLS_GRADE_STATE_HOLDER) ?: return@safe
            // 方法名带混淆后缀（V / Z 等），按前缀匹配以覆盖多版本
            val hooked = cls.methods.filter { it.name.startsWith("dispatchMemoryLevel") }
                .any { m ->
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // 内存等级归零 = 最高等级，不触发内存降级
                                param.args.forEachIndexed { i, a -> if (a is Int) param.args[i] = 0 }
                                SpeedLog.d("内存等级强制归零，取消内存降级")
                            }
                        })
                        true
                    }.getOrDefault(false)
                }
            if (!hooked) SpeedLog.d("dispatchMemoryLevel* 未匹配到（本版本无此方法），跳过")
        }

        HookCore.safe(LAYER, "TaskGradeStateCallback.onResult") {
            val cls = HookCore.findClassInApp(loader, "com.alipay.mobile.framework.degrade.cpu.TaskGradeStateCallback") ?: return@safe
            // onResult 重载可能带不同参数，用名字+ arity 兜住 0/1/2 三种形态
            var hooked = false
            for (arity in 0..2) {
                hooked = HookCore.hookByNameAndArity(cls, "onResult", arity, null,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.forEachIndexed { i, a -> if (a is Int) param.args[i] = 0 }
                            SpeedLog.d("CPU 状态回调结果归零")
                        }
                    }) || hooked
            }
            if (!hooked) SpeedLog.d("TaskGradeStateCallback.onResult 未匹配到，跳过")
        }
    }
}
