package xiaojw.alipayspeed.hook

import android.app.Application
import xiaojw.alipayspeed.SpeedLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * L4 时机层。
 *
 * ── 为什么只有冷启动，没有热启动守护 ──
 * Xposed hook 一旦装上就**永久生效**，直到进程死亡。支付宝在热启动（从后台切回）
 * 时只会重新调用那些已被 hook 的判定方法，它们拿到的仍是被改写后的结果，
 * 不存在「需要重新施加」的问题。
 *
 * 早期版本曾在Activity.onResume / onWindowFocusChanged 上复查 + 每60s 巡检，
 * 实测属于冗余：
 *   · hook 全局 Activity 方法会给支付宝每个页面切换都带来额外开销
 *   · 60s 巡检线程常驻，只为反复确认一个不会变的状态
 * 已移除，热启动与冷启动一样稳定。
 *
 * ── 冷启动唯一需要做的事 ──
 * 把降级状态在 Application.attachBaseContext 阶段补写一次，
 * 走��支付宝自身初始化之前，消除首次读取的竞态。
 */
object WatchdogHook {

    private const val LAYER = "L4"

    fun install(loader: ClassLoader) {
        hookColdStartGuard(loader)
    }

    /**
     * 冷启动布防：attachBaseContext 是支付宝进程内最早的可用时机。
     *
     * 在此处读取并纠正降级相关 SP，能赶在支付宝自身初始化读配置之前完成。
     * 注意此处只做一次（PreferenceHookReapply 内部有去重），不会反复扫盘。
     */
    private fun hookColdStartGuard(loader: ClassLoader) {
        HookCore.safe(LAYER, "Application.attachBaseContext 冷启动布防") {
            val appCls = HookCore.findClassInApp(loader, "android.app.Application") ?: return@safe

            HookCore.hookByNameAndArity(appCls, "attachBaseContext", 1, null,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? android.content.Context ?: return
                        SpeedLog.i("[$LAYER] 冷启动 attachBaseContext 到达，布防降级状态")
                        PreferenceHookReapply.apply(ctx.applicationContext ?: ctx)
                    }
                })
        }

        HookCore.safe(LAYER, "Application.onCreate 完成复查") {
            val appCls = HookCore.findClassInApp(loader, "android.app.Application") ?: return@safe
            HookCore.hookByNameAndArity(appCls, "onCreate", 0, null,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        val ctx = app.applicationContext ?: return
                        SpeedLog.i("[$LAYER] onCreate 完成，复查降级状态")
                        PreferenceHookReapply.apply(ctx)
                    }
                })
        }
    }
}
