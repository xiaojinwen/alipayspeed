package xiaojw.alipayspeed.hook

import xiaojw.alipayspeed.SpeedLog
import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * L1 偏好层（辅助层）。
 *
 * 说明：反编译确认性能极速模式**没有独立的 SP 布尔开关**，
 * 它是 degrade 体系在满足条件时的输出。因此本层不作为主路径，
 * 只做低成本兜底：万一某些版本把降级开关落到了 SharedPreferences，
 * 这里能把它按回开启。
 *
 * 真正起决定作用的是 L2（系统状态伪造）、L3（判定短路）、L5（内核层）。
 */
object PreferenceHook {

    private const val LAYER = "L1"

    /**
     * 疑似降级/极速相关的 SP key 特征。
     * 只保留反编译中真实出现过的词根，避免宽泛匹配误伤无关配置。
     */
    private val KEY_PATTERNS = listOf(
        "degrade", "downgrade", "low_end", "lowend",
        "low_device", "lowdevice", "lite_mode", "litemode"
    )

    fun install(loader: ClassLoader) {
        hookSharedPreferences(loader)
    }

    /**
     * 接管 SharedPreferences 读写。
     *
     * 策略：
     * - getBoolean：命中特征 key 且请求 false 时，返回 true
     * - getInt/getLong/getFloat：命中特征 key 时返回 1（部分版本用数值存开关）
     * - contains：命中特征 key 一律返回 true，避免「未配置走默认值」的分支
     */
    private fun hookSharedPreferences(loader: ClassLoader) {
        val spName = "android.content.SharedPreferences"
        val spImpl = "android.app.SharedPreferencesImpl"

        // ── getBoolean ──
        HookCore.safe(LAYER, "SP.getBoolean 强制 true") {
            val iface = HookCore.findClassInApp(loader, spName) ?: return@safe
            val impl = HookCore.findClassInApp(loader, spImpl)
            val target = impl ?: iface

            val m = target.methods.firstOrNull {
                it.name == "getBoolean" && it.parameterCount == 2
            } ?: return@safe

            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(1) as? String ?: return
                    if (!isSpeedKey(key)) return
                    if (param.result == false) {
                        param.result = true
                        SpeedLog.d("SP[$key].getBoolean false -> true")
                    }
                }
            })
        }

        // ── getInt / getLong ──
        listOf("getInt", "getLong", "getFloat").forEach { getter ->
            HookCore.safe(LAYER, "SP.$getter 强制 1") {
                val iface = HookCore.findClassInApp(loader, spName) ?: return@safe
                val impl = HookCore.findClassInApp(loader, spImpl)
                val target = impl ?: iface
                val m = target.methods.firstOrNull {
                    it.name == getter && it.parameterCount == 2
                } ?: return@safe

                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (!isSpeedKey(key)) return
                        param.result = when (getter) {
                            "getInt" -> 1
                            "getLong" -> 1L
                            else -> 1f
                        }
                        SpeedLog.d("SP[$key].$getter -> ${param.result}")
                    }
                })
            }
        }

        // ── contains ──
        HookCore.safe(LAYER, "SP.contains 强制 true") {
            val iface = HookCore.findClassInApp(loader, spName) ?: return@safe
            val impl = HookCore.findClassInApp(loader, spImpl)
            val target = impl ?: iface
            val m = target.methods.firstOrNull {
                it.name == "contains" && it.parameterCount == 1
            } ?: return@safe

            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    if (!isSpeedKey(key)) return
                    param.result = true
                    SpeedLog.d("SP.contains($key) -> true")
                }
            })
        }

        // ── 兜底：把匹配到的 key 真实写盘 ──
        // 注意：必须 hook **实现类** SharedPreferencesImpl$EditorImpl。
        // hook 接口 SharedPreferences$Editor 会失败（接口方法没有可执行体），
        // 实测报IllegalArgumentException。
        HookCore.safe(LAYER, "SP.edit().putBoolean 强制 true") {
            val editorImpl: Class<*> = HookCore.findClassInApp(
                loader, "android.app.SharedPreferencesImpl\$EditorImpl"
            ) ?: (HookCore.findClassInApp(loader, "android.content.SharedPreferences\$Editor") ?: return@safe)

            val ok = HookCore.hookByNameAndArity(editorImpl, "putBoolean", 2, null,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (!isSpeedKey(key)) return
                        if (param.args.getOrNull(1) == false) {
                            param.args[1] = true
                            SpeedLog.d("SP.edit().putBoolean($key, false) -> true")
                        }
                    }
                })
            if (!ok) SpeedLog.d("putBoolean 未匹配到（Editor 实现类已变化），跳过")
        }
    }

    private fun isSpeedKey(key: String): Boolean {
        val k = key.lowercase()
        return KEY_PATTERNS.any { k.contains(it) }
    }
}
