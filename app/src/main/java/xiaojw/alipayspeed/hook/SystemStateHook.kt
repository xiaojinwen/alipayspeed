package xiaojw.alipayspeed.hook

import xiaojw.alipayspeed.SpeedLog
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * L2 系统状态伪造层。
 *
 * 支付宝判定「设备运行较慢」的输入无非来自这几类系统 API。
 * 把它们统一改写成「高性能 / 低温 / 大内存」状态，判定自然落到极速模式分支。
 *
 * 本层不依赖支付宝任何内部类名 —— 支付宝改版也不失效，是最稳的一层。
 */
object SystemStateHook {

    private const val LAYER = "L2"

    //── 伪造后的设备画像 ──
    // 可用内存抬到 8G：判定阈值大多按「可用内存 < N MB」写，抬高后必然不触发。
    // ActivityManager.MemoryInfo.availMem/totalMem 在 API 26+ 已是 long 字段。
    private const val FAKE_AVAILABLE_MEM = 8L * 1024 * 1024 * 1024
    private const val FAKE_TOTAL_MEM = 16L * 1024 * 1024 * 1024
    private const val FAKE_THRESHOLD = (8L * 1024 * 1024 * 1024)
    // 电池温度：32.5°C，远低于任何高温阈值（通常 40~45°C 起判）
    private const val FAKE_BATTERY_TEMP = 325

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, loader: ClassLoader) {
        hookActivityManager(loader)
        hookDebugMemoryInfo(loader)
        hookBatteryTemperature(loader)
        hookBuildDevice(loader)
        hookSystemProperties(loader)
        hookFileReads()
    }

    /**
     * ActivityManager 是最核心的数据源。
     * - isLowRamDevice()：低内存设备标记，小内存机必判「慢」，直接返回 false
     * - getMemoryInfo()：内存快照，lowMemory 位与 availMem 都要改
     * - getMyMemoryState()：进程自身内存状态，lowMemory 位同理
     * - getProcessMemoryInfo()：批量版本同样处理
     */
    private fun hookActivityManager(loader: ClassLoader) {
        val amClass = HookCore.findClassInApp(loader, "android.app.ActivityManager") ?: return

        HookCore.safe(LAYER, "ActivityManager.isLowRamDevice") {
            XposedHelpers.findAndHookMethod(amClass, "isLowRamDevice", XC_MethodReplacement.returnConstant(false))
        }

        HookCore.safe(LAYER, "ActivityManager.getMemoryInfo") {
            XposedHelpers.findAndHookMethod(
                amClass, "getMemoryInfo", android.app.ActivityManager.MemoryInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.args.getOrNull(0) as? android.app.ActivityManager.MemoryInfo ?: return
                        info.lowMemory = false
                        if (info.availMem < FAKE_AVAILABLE_MEM) info.availMem = FAKE_AVAILABLE_MEM
                        if (info.totalMem > 0 && info.totalMem < FAKE_TOTAL_MEM) info.totalMem = FAKE_TOTAL_MEM
                        // threshold 字段低版本无值，公开字段不存在时直接跳过（用反射保护）
                        setLongFieldSafe(info, "threshold", FAKE_THRESHOLD)
                        SpeedLog.d("getMemoryInfo 伪造 lowMemory=false availMem=8G")
                    }
                })
        }

        // getMyMemoryState / getProcessMemoryInfo 在 Android 16 已移除或签名变更，
        // 用「名字 + 参数个数」模糊匹配，能挂上就挂，挂不上不影响主判定（getMemoryInfo 已覆盖）
        HookCore.safe(LAYER, "ActivityManager.getMyMemoryState") {
            val ok = HookCore.hookByNameAndArity(amClass, "getMyMemoryState", 1,
                android.app.ActivityManager.MemoryInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.args.getOrNull(0) as? android.app.ActivityManager.MemoryInfo ?: return
                        info.lowMemory = false
                        if (info.availMem < FAKE_AVAILABLE_MEM) info.availMem = FAKE_AVAILABLE_MEM
                        SpeedLog.d("getMyMemoryState 伪造 lowMemory=false")
                    }
                })
            if (!ok) SpeedLog.d("getMyMemoryState 在本 ROM 不存在（Android 16 已移除），跳过")
        }

        HookCore.safe(LAYER, "ActivityManager.getProcessMemoryInfo") {
            val ok = HookCore.hookByNameAndArity(amClass, "getProcessMemoryInfo", 1, null,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.args.getOrNull(0) as? MutableList<android.app.ActivityManager.MemoryInfo> ?: return
                        list.forEach { info ->
                            info.lowMemory = false
                            if (info.availMem < FAKE_AVAILABLE_MEM) info.availMem = FAKE_AVAILABLE_MEM
                        }
                        SpeedLog.d("getProcessMemoryInfo 批量伪造 ${list.size} 项")
                    }
                })
            if (!ok) SpeedLog.d("getProcessMemoryInfo 在本 ROM 不存在，跳过")
        }
    }

    /**
     * Debug.getMemoryInfo / ActivityManager.getProcessMemoryInfo(Debt.MemoryInfo)
     * 支付宝也常用 Debug.getMemoryInfo 走另一条判定路径。
     */
    private fun hookDebugMemoryInfo(loader: ClassLoader) {
        val dbgClass = HookCore.findClassInApp(loader, "android.os.Debug") ?: return

        HookCore.safe(LAYER, "Debug.getMemoryInfo") {
            XposedHelpers.findAndHookMethod(
                dbgClass, "getMemoryInfo", Debug.MemoryInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.args.getOrNull(0) as? Debug.MemoryInfo ?: return
                        // Debug.MemoryInfo.totalPss 是只读 val，无法直接改写；
                        // 进程占用类判定主要由 ActivityManager 侧覆盖，这里只做观测记录。
                        SpeedLog.d("Debug.MemoryInfo totalPss=${info.totalPss}KB（只读，不改写）")
                    }
                })
        }
    }

    /**
     * 电池温度伪造。
     *
     * 说明：反编译确认温度**不是**极速模式的触发条件（见 README），
     * 本项属于纵深防御 —— 万一后续版本把温度纳入了判定，这里能兜住。
     */
    private fun hookBatteryTemperature(loader: ClassLoader) {
        val bmClass = HookCore.findClassInApp(loader, "android.os.BatteryManager") ?: return
        val propTemp = 3 // BatteryManager.BATTERY_PROPERTY_TEMPERATURE

        HookCore.safe(LAYER, "BatteryManager.getIntProperty(TEMP)") {
            // 兼容 getIntProperty(int) 与 getIntProperty(int, int) 两种重载
            var hooked = false
            for (arity in intArrayOf(1, 2)) {
                hooked = HookCore.hookByNameAndArity(bmClass, "getIntProperty", arity, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(0) as? Int == propTemp) {
                                param.result = FAKE_BATTERY_TEMP
                                SpeedLog.d("电池温度伪造 -> ${FAKE_BATTERY_TEMP / 10.0}C")
                            }
                        }
                    }) || hooked
            }
            if (!hooked) SpeedLog.d("getIntProperty 未匹配到，跳过")
        }

        // 部分厂商 ROM 走 BatteryManager.BatteryManagerService 的原生实现，反射补一层
        HookCore.safe(LAYER, "BatteryManager 服务侧 getProperty") {
            val svc = HookCore.findClassInApp(loader, "android.os.BatteryManager\$BatteryManagerService") ?: return@safe
            HookCore.hookByNameAndArity(svc, "getProperty", 1, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(0) as? Int == propTemp) param.result = FAKE_BATTERY_TEMP
                    }
                })
        }
    }

    /**
     * 机型信息伪造。
     *
     * 背景：搜到的实测案例里，红米 K80 Pro（骁龙 8E）都被支付宝判定为「性能较弱」并自动开极速模式，
     * 说明支付宝的机型分级偏保守。把它伪装成旗舰机，可绕过基于 Build 字段的分级逻辑。
     * 注意 assets/soc_config.json 是 SoC 型号识别表，改 Build.PRODUCT 即可影响其识别结果。
     *
     *作用域说明：改的是**支付宝进程内**Build 类的静态字段副本，
     * 不写系统属性、不调resetprop，因此对其它应用零影响。
     */
    private fun hookBuildDevice(loader: ClassLoader) {
        HookCore.safe(LAYER, "Build.MODEL 机型伪装") {
            setStaticFieldSafe(Build::class.java, "MODEL", "SM-S931B")
            setStaticFieldSafe(Build::class.java, "DEVICE", "dm3q")
            setStaticFieldSafe(Build::class.java, "PRODUCT", "dm3q")
            setStaticFieldSafe(Build::class.java, "BRAND", "samsung")
            setStaticFieldSafe(Build::class.java, "MANUFACTURER", "samsung")
            setStaticFieldSafe(Build.VERSION::class.java, "MODEL", "SM-S931B")
            SpeedLog.i("机型伪装 -> SM-S931B（骁龙 8 Elite 旗舰档）")
        }
    }

    /**
     * 内核节点文件读取兜底。
     *
     * 重要：这里**不做**任何全局 thermal 处理。
     * 抬高内核 trip_point 会让整机温控失效（真过热也不降频），
     * 影响所有应用与硬件安全，属于绝不能做的事。
     *
     * 支付宝判定温度走的是BatteryManager.getIntProperty(TEMPERATURE)，
     * 已在 hookBatteryTemperature 中拦截 —— 该 hook 天然只在本进程生效。
     * 若支付宝确实绕过 Java API 直读内核节点，正确做法是进程内拦截其读取，
     * 而非改内核。默认不启用该高开销路径。
     */
    private fun hookFileReads() {
        SpeedLog.d("thermal 节点不做全局处理（本进程已拦截 BatteryManager 温度接口）")
    }

    /**
     * SystemProperties 进程内拦截 —— 替代原先的全局 resetprop。
     *
     * 背景：早前版本用 KernelSU 模块 resetprop 改 ro.product.model 伪装机型，
     * 但那是全局生效的，银行/游戏/支付类App 都会读到假机型（可能触发风控、
     * 加载错误 GPU 驱动配置），必须废弃。
     *
     * 现在只在支付宝进程内 hook SystemProperties.get(String)，
     * 对 ro.product.* 相关的 key 返回伪造值，其它应用完全不受影响。
     */
    private fun hookSystemProperties(loader: ClassLoader) {
        val sp = HookCore.findClassInApp(loader, "android.os.SystemProperties") ?: return

        // 只伪造机型相关 key，其余属性一律透传，避免误伤其它判定维度
        val FAKE_PROPS = mapOf(
            "ro.product.model" to "SM-S931B",
            "ro.product.brand" to "samsung",
            "ro.product.manufacturer" to "samsung",
            "ro.product.name" to "dm3q",
            "ro.product.device" to "dm3q",
            "ro.product.board" to "dm3q"
        )

        HookCore.safe(LAYER, "SystemProperties.get 机型 key 进程内伪造") {
            val m = sp.methods.firstOrNull {
                it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            } ?: return@safe

            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    val fake = FAKE_PROPS[key] ?: return
                    param.result = fake
                    SpeedLog.d("SystemProperties[$key] -> $fake（仅本进程）")
                }
            })
        }
    }

    //── 反射工具 ──

    private fun setStaticFieldSafe(cls: Class<*>, name: String, value: Any) {
        runCatching {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            f.set(null, value)
        }
    }

    private fun setIntFieldSafe(target: Any, name: String, value: Int) {
        runCatching {
            val f = target.javaClass.getField(name)
            f.setInt(target, value)
        }
    }

    private fun setLongFieldSafe(target: Any, name: String, value: Long) {
        runCatching {
            val f = target.javaClass.getField(name)
            f.setLong(target, value)
        }
    }
}
