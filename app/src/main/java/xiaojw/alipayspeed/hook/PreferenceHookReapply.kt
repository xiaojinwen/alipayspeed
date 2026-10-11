package xiaojw.alipayspeed.hook

import xiaojw.alipayspeed.SpeedLog
import android.content.Context
import java.io.File

/**
 * SP 复查器：L1/L4 共用。
 *
 * 两件事：
 * 1) 在 attachBaseContext 阶段（早于支付宝自身初始化）遍历支付宝的 SP 目录，
 *    把疑似极速模式开关的 key 直接写成 true —— 冷启动零竞态。
 * 2) 进程存活期被调用时，读取现有 SP 检查开关是否被改回 false，是则纠正。
 *
 * 支付宝 data 目录在 LSPosed 下无需 root 即可访问（模块以支付宝 uid 身份运行在进程内）。
 */
object PreferenceHookReapply {

    /**
     * 降级/极速相关的 key 特征词（与 PreferenceHook 保持一致）。
     * 依据真机 775 个 SP 文件中 grep 出的实际 key 词根，非猜测。
     */
    private val KEY_HINTS = listOf(
        "degrade", "downgrade", "low_end", "lowend",
        "low_device", "lowdevice", "lite_mode", "litemode"
    )

    private val checkedFiles = HashSet<String>()

    fun apply(ctx: Context) {
        runCatching { sweepSharedPrefs(ctx) }
            .onFailure { SpeedLog.w("SP 复查失败: ${it.message}") }
    }

    private fun sweepSharedPrefs(ctx: Context) {
        val spDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        if (!spDir.isDirectory) return

        // 性能极速模式在 Java 层没有独立 SP 开关（是 degrade 体系的输出），
        // 因此这里不做全量 XML 扫描 —— 只处理少量可能承载降级状态的配置文件，
        // 避免每次 attachBaseContext 都遍历上百个 SP 文件拖慢支付宝冷启动。
        val targets = CANDIDATE_FILES
            .map { File(spDir, "$it.xml") }
            .filter { it.isFile }

        if (targets.isEmpty()) {
            SpeedLog.d("未找到候选 SP 文件（支付宝可能改名），跳过 L1 文件层")
            return
        }

        targets.forEach { file ->
            val firstScan = checkedFiles.add(file.name)
            runCatching {
                val prefs = ctx.getSharedPreferences(file.name.removeSuffix(".xml"), Context.MODE_PRIVATE)
                val all = prefs.all
                if (all.isEmpty()) return@runCatching

                val hits = all.filterKeys { isSpeedKey(it) }
                if (hits.isEmpty()) return@runCatching

                // last_task_degrade_* 存的是「上次降级任务的结果/原因/时间」，
                // 属于状态记录而非开关 —— 改写它只会污染数据、且对判定无影响，
                // 因此这里只观察不改。
                if (file.name.startsWith("last_task_degrade")) {
                    if (firstScan) SpeedLog.d("[L1] ${file.name}（状态记录，仅观察）: $hits")
                    return@runCatching
                }

                var changed = false
                hits.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> if (!v) { prefs.edit().putBoolean(k, true).apply(); changed = true }
                        is Int -> if (v == 0) { prefs.edit().putInt(k, 1).apply(); changed = true }
                        is Long -> if (v == 0L) { prefs.edit().putLong(k, 1L).apply(); changed = true }
                    }
                }
                if (changed) {
                    prefs.edit().apply()
                    SpeedLog.i("[L1] 已纠正 ${file.name} 中的降级开关")
                } else if (firstScan) {
                    SpeedLog.d("[L1] ${file.name} 相关开关已开启: $hits")
                }
            }
        }
    }

    /**
     * 候选 SP 文件名 —— 来自真机实测（v12.12.30.8000，775 个 SP 文件中筛出）。
     *
     * grep -l -iE "degrade|low_end" 命中的文件里，
     * `last_task_degrade_info` 是降级任务的状态落盘，是唯一直接相关的。
     * 其余几个是业务缓存/AB 配置，列出来只为低成本观察，不做改写。
     */
    private val CANDIDATE_FILES = listOf(
        "last_task_degrade_info",   // 降级任务状态（实测存在，直接相关）
        "CommonConfigAssist",       // 通用配置
        "antsp"                     // antsp 通用 SP
    )

    private fun isSpeedKey(key: String): Boolean {
        val k = key.lowercase()
        return KEY_HINTS.any { k.contains(it) }
    }
}
