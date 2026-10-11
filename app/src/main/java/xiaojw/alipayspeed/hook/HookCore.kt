package xiaojw.alipayspeed.hook

import xiaojw.alipayspeed.SpeedLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * Hook 基础设施：统一异常隔离与执行记录。
 *
 * 设计要点：
 * 1. 每个 hook 点独立 try-catch —— 支付宝强改单个方法签名时，只有该层失效，不拖垮整个模块。
 * 2. 记录命中数，供诊断页展示「哪几层真的生效了」。
 */
object HookCore {

    /** 层标识 -> 该层成功安装的 hook 点数量。 */
    private val layerHits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 失败记录：层 -> 失败原因列表，诊断页展示，便于版本更新后定位。 */
    private val layerErrors = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    private val lock = Any()

    fun markHit(layer: String, point: String) {
        synchronized(lock) {
            layerHits.merge(layer, 1) { a, b -> a + 1 }
        }
        SpeedLog.i("[$layer] 命中: $point")
    }

    fun markMiss(layer: String, point: String, t: Throwable?) {
        synchronized(lock) {
            layerErrors.getOrPut(layer) { java.util.Collections.synchronizedList(ArrayList()) }
                .add("$point -> ${t?.javaClass?.simpleName}: ${t?.message}")
        }
        SpeedLog.w("[$layer] 跳过(未命中): $point  ${t?.javaClass?.simpleName}")
    }

    /** 包裹一次 hook 安装，任何失败都被吞掉并记录，不影响其他层。 */
    inline fun safe(layer: String, point: String, block: () -> Unit) {
        try {
            block()
            markHit(layer, point)
        } catch (t: Throwable) {
            markMiss(layer, point, t)
        }
    }

    /** 在目标进程 classloader 里找类 —— 框架 findClass 拿的是模块 loader，必须指定目标 loader。 */
    fun findClassInApp(loader: ClassLoader, name: String): Class<*>? = try {
        XposedHelpers.findClass(name, loader)
    } catch (t: Throwable) {
        null
    }

    fun findMethodExact(cls: Class<*>, name: String, vararg params: Class<*>): Method? = try {
        XposedHelpers.findMethodExact(cls, name, *params)
    } catch (t: Throwable) {
        null
    }

    /**
     * 按「方法名 + 参数个数」模糊查找并挂hook。
     *
     * 为什么不直接用 XposedHelpers.findAndHookMethod：
     * 那些API 要求精确参数类型，而 Android 各版本 / 支付宝混淆后的签名会变
     * （实测 Android 16 上 ActivityManager.getMyMemoryState 已移除、
     *   BatteryManager.getIntProperty 的 int 参数解析为 primitive 差异等），
     * 精确匹配一旦对不上就整条规则失效。
     *
     * 本方法先按名字取候选，再按参数个数过滤，最后按返回类型过滤，
     * 大幅提升跨版本存活率。找不到返回 false 而不抛异常。
     */
    fun hookByNameAndArity(
        cls: Class<*>,
        methodName: String,
        arity: Int,
        returnType: Class<*>? = null,
        hook: XC_MethodHook
    ): Boolean = try {
        val candidates = (cls.methods + cls.declaredMethods)
            .filter { it.name == methodName && it.parameterCount == arity }
            .filter { returnType == null || it.returnType == returnType }
        val target = candidates.firstOrNull() ?: return false
        XposedBridge.hookMethod(target, hook)
        SpeedLog.d("模糊匹配成功 ${cls.simpleName}.$methodName/$arity -> ${target.parameterTypes.joinToString { it.simpleName }}")
        true
    } catch (t: Throwable) {
        SpeedLog.d("模糊匹配失败 ${cls.simpleName}.$methodName/$arity: ${t.javaClass.simpleName}")
        false
    }

    /** 判断类是否存在，不存在时静默返回 false（用于可选依赖的探测）。 */
    inline fun hasClass(loader: ClassLoader, name: String): Boolean =
        findClassInApp(loader, name) != null

    fun snapshotHits(): Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(layerHits))

    fun snapshotErrors(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        layerErrors.forEach { (k, v) -> out[k] = java.util.Collections.unmodifiableList(ArrayList(v)) }
        return out
    }
}
