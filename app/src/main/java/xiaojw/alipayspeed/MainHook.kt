package xiaojw.alipayspeed

import xiaojw.alipayspeed.SpeedLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import xiaojw.alipayspeed.hook.DecisionHook
import xiaojw.alipayspeed.hook.HookCore
import xiaojw.alipayspeed.hook.PreferenceHook
import xiaojw.alipayspeed.hook.SystemStateHook
import xiaojw.alipayspeed.hook.WatchdogHook

/**
 * 支付宝极速模式强制常开 —— LSPosed 模块主入口。
 *
 * 目标：无论设备温度、内存、负载、机型如何，支付宝冷启动与热启动均强制进入并持续保持极速模式。
 *
 *分五层冗余拦截（任一层命中即达成目标，单点失效不影响整体）：
 *   L1 PreferenceHook       强制 SP 中极速模式开关为 true
 *   L2 SystemStateHook      伪造内存/电池温度/机型等系统状态
 *   L3 DecisionHook         直接短路 com.alipay.mobile.framework.degrade 判定链路
 *   L4 WatchdogHook         冷启动布防+ 热启动复查 + 周期巡检
 *
 * ── 作用域保证（重要）──
 * 本模块所有操作都发生在**支付宝进程内**，作用域仅 com.eg.android.AlipayGphone：
 *   · 不修改内核 thermal trip_point（那会让整机温控失效，属全局高危操作）
 *   · 不使用 resetprop 改系统属性（那会影响银行/游戏等所有应用，可能触发风控）
 *   · 机型与温度伪装仅改本进程内的 Build 静态字段副本与 API 返回值
 * 因此对系统与其它应用**零副作用**，也无需 KernelSU 模块配合。
 *
 * 判定链路依据：支付宝 v12.12.30.8000 反编译，极速模式底层为
 * com.alipay.mobile.framework.degrade（降级）体系。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        SpeedLog.i("模块已加载 zygote（sdk=${android.os.Build.VERSION.SDK_INT}）")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        // 只处理支付宝主包；子进程（:tools/:push 等）不需要极速模式
        if (!pkg.equals(TARGET_PKG, ignoreCase = true)) return
        // 子进程名形如 com.eg.android.AlipayGphone:xxx
        if (pkg.contains(":")) return

        val loader = lpparam.classLoader ?: return
        SpeedLog.i("=== 支付宝进程已加载，开始安装拦截层 (pid=${android.os.Process.myPid()}) ===")

        try {
            // L4 先装：要在支付宝读配置之前完成冷启动布防
            WatchdogHook.install(loader)
            // L3 核心：判定逻辑短路
            DecisionHook.install(loader)
            // L2 系统状态伪造
            SystemStateHook.install(lpparam, loader)
            // L1 用户开关
            PreferenceHook.install(loader)
        } catch (t: Throwable) {
            SpeedLog.e("拦截层安装异常", t)
        }

        SpeedLog.i("拦截层安装完成，命中统计=${HookCore.snapshotHits()}")
    }

    companion object {
        const val TARGET_PKG = "com.eg.android.AlipayGphone"
    }
}
