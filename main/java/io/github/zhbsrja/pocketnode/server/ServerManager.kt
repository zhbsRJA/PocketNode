package io.github.zhbsrja.pocketnode.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import io.github.zhbsrja.pocketnode.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 中转服务器的生命周期管理。
 *
 * 只负责「起一个、停一个」，不掺业务逻辑 —— 路由和鉴权都在 ApiServer 里。
 */
object ServerManager {

    private const val TAG = "ServerManager"
    private const val PREFS = "pocketnode_server"
    private const val KEY_PORT = "port"

    /** 默认端口。选 8080 是因为大多数人认得，且不和系统端口冲突。 */
    const val DEFAULT_PORT = 8080

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private var server: ApiServer? = null
    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        _port.value = prefs()?.getInt(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        AppLog.i(TAG, "初始化完成，端口 ${_port.value}")
    }

    fun setPort(value: Int) {
        if (value !in 1024..65535) {
            AppLog.w(TAG, "端口 $value 不合法（1024-65535），忽略")
            return
        }
        prefs()?.edit()?.putInt(KEY_PORT, value)?.apply()
        _port.value = value
        AppLog.i(TAG, "端口已改为 $value")
        // 改端口需要重启才生效
        if (_running.value) {
            AppLog.i(TAG, "服务在跑，重启以应用新端口")
            stop()
            context?.let { start(it) }
        }
    }

    /**
     * 启动服务。
     *
     * @return 实际错误信息，null 表示成功
     */
    fun start(ctx: Context): String? {
        if (_running.value) {
            AppLog.i(TAG, "服务已在运行")
            return null
        }
        return try {
            val s = ApiServer(_port.value)
            // 设成守护线程模式的 start，不阻塞调用方。
            // SOCKET_READ_TIMEOUT 设长一点：手机上生成 100 个字要 10 秒，
            // 超时太短会在流式过程中把连接掐断。
            s.start(NanoTimeouts.SOCKET_READ_TIMEOUT, false)
            server = s
            _running.value = true
            AppLog.i(TAG, "服务已启动，端口 ${_port.value}")
            AppLog.i(TAG, "局域网地址: ${lanAddresses().joinToString(", ").ifEmpty { "(未连 WiFi)" }}")
            null
        } catch (t: Throwable) {
            // 最常见的原因是端口被占用
            AppLog.e(TAG, "启动失败", t)
            server = null
            _running.value = false
            t.message ?: t.javaClass.simpleName
        }
    }

    fun stop() {
        val s = server ?: return
        AppLog.i(TAG, "停止服务")
        runCatching { s.stop() }.onFailure { AppLog.w(TAG, "停止时出错", it) }
        server = null
        _running.value = false
    }

    /**
     * 取本机所有可用于局域网访问的 IPv4 地址。
     *
     * 为什么返回列表而不是单个地址：手机可能同时连着 WiFi 和热点，
     * 或者有 VPN 虚拟网卡。用户需要用那个"对方也能访问到"的地址，
     * 我们无法替他判断，所以全给出来让他自己挑。
     *
     * 排除了回环地址和 VPN 常见网段（用户看到 10.x 的 VPN 地址
     * 拿去给别人用会连不通）。
     */
    fun lanAddresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    val name = nif.name.lowercase()
                    // 跳过常见的 VPN / 虚拟网卡
                    if (name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("rmnet")
                    ) continue
                    result += host
                }
            }
        }.onFailure { AppLog.w(TAG, "枚举网卡失败", it) }

        // 按「最像局域网地址」排序：192.168 排最前，然后是 10.x，最后其他
        return result.distinct().sortedBy { ip ->
            when {
                ip.startsWith("192.168.") -> 0
                ip.startsWith("10.") -> 1
                ip.startsWith("172.") -> 2
                else -> 3
            }
        }
    }

    /** 有没有连上网。用来提示用户"先连 WiFi 别人才能访问" */
    fun isNetworkAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val props: LinkProperties = cm.getLinkProperties(net) ?: return false
        return props.interfaceName?.let { !it.startsWith("rmnet") } ?: false
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * NanoHTTPD 的超时参数。
 *
 * 默认值对普通 HTTP 请求够用，但我们的流式接口可能几秒才吐一个字，
 * 默认的读超时会在生成中途把连接掐掉 —— 表现是"客户端收到半句话就断了"。
 */
private object NanoTimeouts {
    /** 10 分钟。手机生成慢，宁可等久点也别中途断 */
    const val SOCKET_READ_TIMEOUT = 10 * 60 * 1000
}
