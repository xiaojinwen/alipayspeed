package xiaojw.alipayspeed

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * 诊断页。
 *
 * 装完模块后先打开这里确认各层是否命中 —— 若某层显示 0，
 * 说明支付宝该版本改了对应实现，可据此在源码里补规则。
 *
 * 注：本页运行在模块自身进程，看不到支付宝进程内的 hook 状态，
 * 因此这里展示的是模块配置与安装指引，实际命中情况请看 logcat：
 *   adb logcat -s AlipaySpeed:V
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sb = StringBuilder()
        sb.append("支付宝极速模式强制常开\n")
        sb.append("=".repeat(30)).append("\n\n")
        sb.append("工作原理\n")
        sb.append("极速模式底层是支付宝的降级体系\n")
        sb.append("(com.alipay.mobile.framework.degrade)，\n")
        sb.append("模块短路其低端设备/性能判定，\n")
        sb.append("并伪造内存、温度、机型等系统状态。\n\n")

        sb.append("五层拦截\n")
        sb.append("-".repeat(30)).append("\n")
        sb.append("L1 SP 开关强制 true\n")
        sb.append("L2 内存/温度/机型伪造\n")
        sb.append("L3 degrade 判定短路（核心）\n")
        sb.append("L4 冷启动布防 + 热启动复查\n")
        sb.append("L5 KernelSU 前置注入（可选）\n\n")

        sb.append("验证方法\n")
        sb.append("-".repeat(30)).append("\n")
        sb.append("1. 重启支付宝（先force-stop 再开）\n")
        sb.append("2. 首页底部应显示「已开启极速模式」\n")
        sb.append("3. 或：设置 > 通用 > 极速模式 开关为开\n\n")

        sb.append("查看日志\n")
        sb.append("-".repeat(30)).append("\n")
        sb.append("adb logcat -s AlipaySpeed:V\n\n")

        sb.append("若未生效\n")
        sb.append("-".repeat(30)).append("\n")
        sb.append("· 确认 LSPosed 已勾选支付宝作用域\n")
        sb.append("· 支付宝更新后 L3 类名可能变化，\n")
        sb.append("  看日志里哪层未命中再补规则\n")

        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 13f
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
