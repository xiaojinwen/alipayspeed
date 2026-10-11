package xiaojw.alipayspeed

import android.util.Log

/**
 * 模块统一日志。
 *
 * 支付宝主进程日志量大，统一用 TAG 前缀便于 logcat 过滤：
 *   adb logcat -s AlipaySpeed:V
 * 完整判定链路较长时用 [FULL]，需要留存完整现场时开：
 *   adb shell setprop persist.alipayspeed.full 1
 */
object SpeedLog {
    private const val TAG = "AlipaySpeed"

    @Volatile
    var verbose: Boolean = false

    fun i(msg: String) = Log.i(TAG, msg)

    fun w(msg: String, t: Throwable? = null) = if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)

    fun e(msg: String, t: Throwable? = null) = if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)

    /** 判定链路追踪，仅在诊断模式输出，避免刷屏。 */
    fun d(msg: String) {
        if (verbose) Log.i(TAG, "[TRACE] $msg")
    }
}
