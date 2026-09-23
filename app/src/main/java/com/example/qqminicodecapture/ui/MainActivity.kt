// 由 huojiujian 开源免费使用，二改请备注来源；地址 https://github.com/huojiujian2

package com.example.qqminicodecapture.ui

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.qqminicodecapture.BuildConfig
import com.example.qqminicodecapture.R
import com.example.qqminicodecapture.crypto.CaManager
import com.example.qqminicodecapture.crypto.CaTrustChecker
import com.example.qqminicodecapture.util.AppLogger
import com.example.qqminicodecapture.util.CertExporter
import com.example.qqminicodecapture.util.CertInstallerGuide
import com.example.qqminicodecapture.util.CodeBus
import com.example.qqminicodecapture.util.FileLog
import com.example.qqminicodecapture.vpn.CaptureVpnService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主界面
 *
 *  - 顶部状态点 + 状态文字（"未启动 / 证书正常 / 证书异常 / 错误"）
 *  - 版本号卡片（含"复制版本号"按钮）
 *  - code 卡片
 *  - 复制 / 下载证书 / 检测证书 按钮
 *  - 启动/停止按钮（居中、较窄）
 *  - 调试版额外显示折叠日志区
 *
 * 状态点 + 文字：
 *  - 灰：未启动
 *  - 绿：证书正常
 *  - 黄：证书异常
 *  - 红：错误
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** 日志面板最大字符数，超过后从中间裁掉一半，防止 TextView 无限膨胀 */
        private const val MAX_LOG_CHARS = 200_000

        /** 日志面板刷新周期：把高频日志攒起来批量刷新，而不是每条都 post 主线程 */
        private const val LOG_FLUSH_INTERVAL_MS = 300L

        /** 面板配置 SharedPreferences 文件名与键（code 不持久化，仅存 api 地址 / token / 备注） */
        private const val PREFS_PANEL = "panel_config"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_ADMIN_TOKEN = "admin_token"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_AUTO_FILL = "auto_fill_code"
        private const val KEY_ADMIN_USER = "admin_user"
        private const val KEY_ADMIN_PASSWORD = "admin_password"
    }

    private lateinit var btnStartCapture: Button
    private lateinit var etCode: EditText
    private lateinit var btnCopy: Button
    private lateinit var btnDownloadCert: Button
    private lateinit var btnCheckCert: Button
    private lateinit var btnCopyVersion: Button
    private lateinit var statusDot: View
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvVersion: TextView

    // ---- v2.1 抓包即解析：好友 GID ----
    private lateinit var tvGidStatus: TextView
    private lateinit var tvGidResult: TextView
    private lateinit var btnCopyGids: Button  // 唯一复制按钮：内容为 JSON 数组（可直接导入服务器）
    private var lastActionableGids: List<Long> = emptyList()
    private var gidRawJson: String = ""
    private var mergedFriends: Map<Long, JSONObject> = emptyMap()

    // ---- 上传到 qq-farm-bot 面板 ----
    private lateinit var etApiBase: EditText
    private lateinit var etAdminToken: EditText
    private lateinit var etAccountName: EditText
    private lateinit var etUploadCode: EditText
    private lateinit var tvUploadStatus: TextView
    private lateinit var btnFillCapturedCode: Button
    private lateinit var btnSavePanelConfig: Button
    private lateinit var btnValidateToken: Button
    private lateinit var btnSubmitCode: Button
    private lateinit var switchAutoFill: SwitchCompat
    private lateinit var btnQueryOnlineStatus: Button
    private lateinit var etAdminUser: EditText
    private lateinit var etAdminPassword: EditText
    private lateinit var btnLoginToken: Button

    // 仅调试版存在的控件
    private var tvLog: TextView? = null
    private var logScroll: ScrollView? = null
    private var btnCopyLog: Button? = null
    private var btnClearLog: Button? = null

    // ── 日志面板节流缓冲 ──────────────────────────────────────────
    // ★ 关键修复：原来每条日志都 runOnUiThread{ append + post{fullScroll} }，
    // 也就是一条日志产生两个主线程消息。tun2proxy/代理在高流量下每秒能吐几百条
    // 日志，主线程消息队列被瞬间打爆 → ANR → 系统杀进程（表现就是"点停止就闪退"，
    // 因为停止瞬间会集中爆发一批关闭日志）。
    // 现在改成：日志先写入 StringBuilder，由主线程 Handler 每 300ms 统一刷一次屏。
    private val logBuffer = StringBuilder(16 * 1024)
    private val logFlushHandler = Handler(Looper.getMainLooper())
    private val logFlushRunnable = object : Runnable {
        override fun run() {
            flushLogPanel()
            logFlushHandler.postDelayed(this, LOG_FLUSH_INTERVAL_MS)
        }
    }

    private var isCapturing: Boolean = false
        set(value) {
            field = value
            // ★ 兜底：view 已 detach / Activity 已 finish 时 text setter 可能抛
            try {
                btnStartCapture.text = if (value) getString(R.string.btn_stop) else getString(R.string.btn_start)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "btnStartCapture.text 设置失败: ${t.message}")
            }
        }

    /** 启动流程（权限申请）进行中标志，防止双击 */
    private var hasPendingStart = false

    /** 0=idle, 1=ok, 2=warn, 3=err */
    private var certState: Int = 0
        set(value) {
            field = value
            updateStatusDot()
        }

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            AppLogger.i(TAG, "VPN 权限已授予，启动服务...")
            startVpnService()
        } else {
            AppLogger.w(TAG, "VPN 权限被拒绝")
            Toast.makeText(this, R.string.vpn_prepare, Toast.LENGTH_LONG).show()
            isCapturing = false
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLogger.i(TAG, "通知权限已授予")
            requestVpnAndStart()
        } else {
            AppLogger.w(TAG, "通知权限被拒绝，前台服务可能无法启动")
            Toast.makeText(this, "通知权限被拒绝，抓包服务可能无法正常启动", Toast.LENGTH_LONG).show()
            isCapturing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程启动即开文件日志（哪怕 stop 时被 SIGABRT 也能留痕取证）
        FileLog.init(applicationContext)
        FileLog.i(TAG, "MainActivity.onCreate")
        setContentView(R.layout.activity_main)

        // 注意：本地 Native 网关中转由 CaptureVpnService(:vpn 进程) 负责启动/停止，
        // 引擎 WebView 与桥通信走 127.0.0.1 回环，跨进程可达。

        btnStartCapture = findViewById(R.id.btnStartCapture)
        etCode          = findViewById(R.id.etCode)
        btnCopy         = findViewById(R.id.btnCopy)
        btnDownloadCert = findViewById(R.id.btnDownloadCert)
        btnCheckCert    = findViewById(R.id.btnCheckCert)
        btnCopyVersion  = findViewById(R.id.btnCopyVersion)
        statusDot       = findViewById(R.id.statusDot)
        tvStatusLabel   = findViewById(R.id.tvStatusLabel)
        tvVersion       = findViewById(R.id.tvVersion)

        // v2.1 好友 GID 控件
        tvGidStatus     = findViewById(R.id.tvGidStatus)
        tvGidResult     = findViewById(R.id.tvGidResult)
        btnCopyGids      = findViewById(R.id.btnCopyGidsJson)
        tvGidStatus.text = getString(R.string.gid_status_idle)
        tvGidResult.text = ""
        btnCopyGids.isEnabled = false

        if (BuildConfig.DEBUG) {
            tvLog       = findViewById(R.id.tvLog)
            logScroll   = findViewById(R.id.logScroll)
            btnCopyLog  = findViewById(R.id.btnCopyLog)
            btnClearLog = findViewById(R.id.btnClearLog)
        }

        // 注册日志更新回调（仅调试版实际显示）：
        // 日志先攒进 StringBuilder 缓冲，由 Handler 每 300ms 统一刷屏。
        // （原实现每条日志都 runOnUiThread + post 滚动，日志一多就打爆主线程 → ANR。）
        tvLog?.text = ""
        AppLogger.setOnLogUpdated { line ->
            synchronized(logBuffer) { logBuffer.append(line).append('\n') }
        }
        if (BuildConfig.DEBUG) {
            logFlushHandler.post(logFlushRunnable)
        }

        // 应用启动即生成 CA 证书
        AppLogger.i(TAG, "应用启动，初始化 CA 证书...")
        val (caCert, _) = try {
            CaManager.ensureCa(applicationContext)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "CA 证书初始化失败: ${t.message}")
            Log.e(TAG, "Failed to init CA", t)
            Toast.makeText(this, "证书初始化失败: ${t.message}", Toast.LENGTH_LONG).show()
            null
        } ?: return

        // 启动时自动做一次证书信任自检（后台执行）
        runCertCheck(silentOnTrusted = true)

        btnStartCapture.setOnClickListener { onStartStopClick() }
        findViewById<View>(R.id.btnGithub).setOnClickListener { openGithub() }
        btnCopy.setOnClickListener { copyCode() }
        btnDownloadCert.setOnClickListener { downloadCert() }
        btnCheckCert.setOnClickListener { runCertCheck(silentOnTrusted = false) }
        btnCopyVersion.setOnClickListener { copyVersion() }
        btnCopyLog?.setOnClickListener { copyLog() }
        btnClearLog?.setOnClickListener { clearLog() }

        // v2.1 抓包即解析：复制 GID / 复制 JSON 数组
        btnCopyGids.setOnClickListener { copyActionableGidsJson() }

        // ---- 上传到 qq-farm-bot 面板 ----
        etApiBase           = findViewById(R.id.etApiBase)
        etAdminToken        = findViewById(R.id.etAdminToken)
        etAccountName       = findViewById(R.id.etAccountName)
        etUploadCode        = findViewById(R.id.etUploadCode)
        tvUploadStatus      = findViewById(R.id.tvUploadStatus)
        btnFillCapturedCode = findViewById(R.id.btnFillCapturedCode)
        btnSavePanelConfig  = findViewById(R.id.btnSavePanelConfig)
        btnValidateToken    = findViewById(R.id.btnValidateToken)
        btnSubmitCode       = findViewById(R.id.btnSubmitCode)
        switchAutoFill      = findViewById(R.id.switchAutoFill)
        btnQueryOnlineStatus = findViewById(R.id.btnQueryOnlineStatus)
        etAdminUser         = findViewById(R.id.etAdminUser)
        etAdminPassword     = findViewById(R.id.etAdminPassword)
        btnLoginToken       = findViewById(R.id.btnLoginToken)
        loadPanelConfig()
        btnSavePanelConfig.setOnClickListener { savePanelConfig() }
        btnFillCapturedCode.setOnClickListener { fillCapturedCode() }
        btnValidateToken.setOnClickListener { validatePanelToken() }
        btnSubmitCode.setOnClickListener { submitPanelCode() }
        btnQueryOnlineStatus.setOnClickListener { queryOnlineStatus() }
        btnLoginToken.setOnClickListener { loginAndFillToken() }
        // 开关状态即时持久化（「保存配置」里也会再写一次，双保险）
        switchAutoFill.setOnCheckedChangeListener { _, isChecked ->
            try {
                getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_FILL, isChecked).apply()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "保存自动填入开关状态失败: ${t.message}")
            }
        }

        // ★ 跨进程事件通道：CaptureVpnService 跑在 ":vpn" 独立进程，
        // 抓到的 code/状态/错误/好友数据经广播回到主进程，由 receiver 驱动统一的事件方法。
        CodeBus.attach(applicationContext)
        CodeBus.setOnCodeListener { code -> onCodeEvent(code) }
        CodeBus.setOnVersionListener { ver -> onVersionEvent(ver) }
        CodeBus.setOnStatusListener { running -> onStatusEvent(running) }
        CodeBus.setOnErrorListener { msg -> onErrorEvent(msg) }
        CodeBus.setOnFriendsListener { selfGid, json -> onFriendsParsed(selfGid, json) }
        registerEventReceiver()
    }

    // ---------------- 跨进程事件接收（:vpn 服务进程 → 主进程 UI） ----------------

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            CodeBus.onRemoteAction(i.action ?: return, i)
        }
    }

    private fun registerEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(CodeBus.ACTION_CODE)
            addAction(CodeBus.ACTION_VERSION)
            addAction(CodeBus.ACTION_STATUS)
            addAction(CodeBus.ACTION_ERROR)
            addAction(CodeBus.ACTION_FRIENDS)
        }
        try {
            // targetSdk 33+ 注册非系统广播必须显式声明导出性；RECEIVER_NOT_EXPORTED
            // 配合发送端 setPackage，只有本应用能收到。
            ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "registerReceiver(带flag)失败，回退普通注册: ${t.message}")
            runCatching { registerReceiver(eventReceiver, filter) }
        }
    }

    private fun onCodeEvent(code: String) {
        AppLogger.i(TAG, "捕获到 code: $code")
        try { etCode.setText(code) } catch (_: Throwable) {}
        // 「抓到Code后自动填入」开关开启时，同步填入上传卡片（只填入，不自动提交）
        try {
            if (::switchAutoFill.isInitialized && switchAutoFill.isChecked) {
                etUploadCode.setText(code)
            }
        } catch (_: Throwable) {}
        // v2.1：好友 GID 走"抓包自动解析"，无需再用 code 手动拉取
    }

    // ---------------- v2.1 抓包即解析：好友 GID ----------------

    /** 卡片预览最多显示的行数（GID 卡在页面底部，展示少量代表即可，完整列表走复制） */
    private val gidPreviewLimit = 8

    /** 服务器→客户端方向解析到的一批好友（可能来自 SyncAll / GetAll / GetGameFriends） */
    private fun onFriendsParsed(selfGid: Long, friendsJson: String) {
        try {
            AppLogger.i(TAG, "收到好友数据 ${friendsJson.length}B")
            val arr = try { JSONArray(friendsJson) } catch (_: Throwable) { return }
            val byGid = LinkedHashMap<Long, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val gid = o.optLong("gid")
                if (gid <= 0) continue
                // 后到的覆盖先到的（GetGameFriends 比 SyncAll 字段更全）
                byGid[gid] = o
            }
            if (byGid.isEmpty()) return

            // 与已积累结果合并（一次抓包会话可能多帧分批返回）
            val merged = LinkedHashMap<Long, JSONObject>()
            for ((gid, o) in mergedFriends) merged[gid] = o
            for ((gid, o) in byGid) merged[gid] = o

            // 排除自身（Login 响应动态提供）与内置 NPC（小果 10001），剩余为可操作真人。
            // 注意：不同账号自身 gid 不同，只能依赖嗅探器从 Login 帧动态取出的 selfGid，不可硬编码。
            val actionable = merged.values.filter { o ->
                val g = o.optLong("gid")
                g != selfGid && g != 10001L
            }.sortedBy { it.optLong("gid") }

            val gids = actionable.map { it.optLong("gid") }
            lastActionableGids = gids
            gidRawJson = JSONArray(merged.values.toList()).toString()
            mergedFriends = merged

            val sb = StringBuilder()
            sb.append("已解析 ").append(merged.size)
                .append(" 位好友，可操作 ").append(gids.size).append(" 位")
            if (selfGid > 0) sb.append("（已排除自身 gid=").append(selfGid).append("）")
            sb.append('\n')

            // 列表只显示前 gidPreviewLimit 行，避免卡片挤爆
            if (actionable.isNotEmpty()) {
                val show = minOf(gidPreviewLimit, actionable.size)
                for (i in 0 until show) {
                    val f = actionable[i]
                    val name = f.optString("name", "")
                    val lv = f.optLong("level")
                    sb.append(f.optLong("gid"))
                    if (name.isNotBlank()) sb.append("  ").append(name)
                    if (lv > 0) sb.append("  Lv").append(lv)
                    sb.append('\n')
                }
                if (actionable.size > show) {
                    sb.append(getString(R.string.gid_more_hint, show, actionable.size))
                }
            } else {
                sb.append("（暂无真人好友，可到 QQ 农场加几个好友后再试）")
            }
            tvGidStatus.text = getString(R.string.gid_status_ready)
            tvGidResult.text = sb.toString()
            btnCopyGids.isEnabled = gids.isNotEmpty()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "解析好友数据异常: ${t.message}")
        }
    }

    /**
     * 复制"直接导入服务器"的 GID 列表（JSON 数组格式）：[gid, gid, ...]
     * 这就是 bot core 里 fetch-gid 输出的 actionableGids 同款格式，
     * 服务器从 JSON 文件 / 命令行参数 / API 直接读取。
     */
    private fun copyActionableGidsJson() {
        if (lastActionableGids.isEmpty()) {
            Toast.makeText(this, R.string.gid_empty_copy, Toast.LENGTH_SHORT).show()
            return
        }
        val arr = JSONArray()
        for (g in lastActionableGids) arr.put(g)
        val text = arr.toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq_gids", text))
        AppLogger.i(TAG, "复制 GID(${lastActionableGids.size} 个)，长度=${text.length}")
        Toast.makeText(this, R.string.gid_copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun onVersionEvent(ver: String) {
        AppLogger.i(TAG, "捕获到版本号: $ver")
        try {
            tvVersion.text = ver
            tvVersion.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } catch (_: Throwable) {}
    }

    private fun onStatusEvent(running: Boolean) {
        // ★ 防御性：isCapturing setter 会调 btnStartCapture.text，
        // 如果 Activity 已被 finish 且 view 已 detach 可能抛 IllegalStateException。
        try { isCapturing = running } catch (t: Throwable) {
            AppLogger.w(TAG, "更新 isCapturing 失败: ${t.message}")
        }
        if (running) {
            // 新一轮抓包：清空上一轮结果，等待新的好友数据
            mergedFriends = emptyMap()
            lastActionableGids = emptyList()
            gidRawJson = ""
            try {
                tvGidStatus.text = getString(R.string.gid_status_busy)
                tvGidResult.text = ""
                btnCopyGids.isEnabled = false
            } catch (_: Throwable) {}
        }
    }

    private fun onErrorEvent(msg: String) {
        AppLogger.e(TAG, "抓包错误: $msg")
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            // Activity 已死时 Toast 会抛 BadTokenException，吞掉即可
            AppLogger.w(TAG, "Toast 显示失败: ${t.message}")
        }
        try { isCapturing = false } catch (_: Throwable) {}
    }

    // ---------------- 上传到 qq-farm-bot 面板 ----------------

    /** 启动时回填已保存的面板配置（api 地址 / token / 备注；code 不持久化） */
    private fun loadPanelConfig() {
        try {
            val sp = getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE)
            etApiBase.setText(sp.getString(KEY_API_BASE, "") ?: "")
            etAdminToken.setText(sp.getString(KEY_ADMIN_TOKEN, "") ?: "")
            etAccountName.setText(sp.getString(KEY_ACCOUNT_NAME, "") ?: "")
            etAdminUser.setText(sp.getString(KEY_ADMIN_USER, "") ?: "")
            etAdminPassword.setText(sp.getString(KEY_ADMIN_PASSWORD, "") ?: "")
            // 自动填入开关默认开
            switchAutoFill.isChecked = sp.getBoolean(KEY_AUTO_FILL, true)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "读取面板配置失败: ${t.message}")
        }
    }

    /** 「保存配置」：持久化 api 地址 / token / 备注（不存 code） */
    private fun savePanelConfig() {
        try {
            getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).edit()
                .putString(KEY_API_BASE, normalizeBaseUrl(etApiBase.text.toString()))
                .putString(KEY_ADMIN_TOKEN, etAdminToken.text.toString().trim())
                .putString(KEY_ACCOUNT_NAME, etAccountName.text.toString().trim())
                .putString(KEY_ADMIN_USER, etAdminUser.text.toString().trim())
                .putString(KEY_ADMIN_PASSWORD, etAdminPassword.text.toString())
                .putBoolean(KEY_AUTO_FILL, switchAutoFill.isChecked)
                .apply()
            AppLogger.i(TAG, "面板配置已保存")
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "保存面板配置失败: ${t.message}")
            Toast.makeText(this, "保存失败: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 「填入已抓取的Code」：把上方捕获卡片里的 code 填进上传输入框 */
    private fun fillCapturedCode() {
        val code = try { etCode.text.toString().trim() } catch (_: Throwable) { "" }
        if (code.isEmpty()) {
            Toast.makeText(this, "暂未捕获到 code", Toast.LENGTH_SHORT).show()
            return
        }
        etUploadCode.setText(code)
        AppLogger.i(TAG, "已填入当前捕获的 code")
        Toast.makeText(this, "已填入当前捕获的 Code", Toast.LENGTH_SHORT).show()
    }

    /** 去掉首尾空白与结尾的 '/'，便于拼接 /api/... */
    private fun normalizeBaseUrl(raw: String): String =
        raw.trim().trimEnd('/')

    /** 「验证Token」：GET {base}/api/auth/validate，检查 ok 与 data.valid */
    private fun validatePanelToken() {
        val base = normalizeBaseUrl(etApiBase.text.toString())
        val token = etAdminToken.text.toString().trim()
        if (base.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "请先填写面板 API 地址和 Token", Toast.LENGTH_SHORT).show()
            return
        }
        btnValidateToken.isEnabled = false
        tvUploadStatus.text = "正在验证 Token..."
        AppLogger.i(TAG, "验证面板 Token: $base/api/auth/validate")
        Thread({
            val msg = try {
                val (httpCode, resp) = panelRequestWithRelogin(base, "GET", "/api/auth/validate", token, null)
                val json = try { JSONObject(resp) } catch (_: Throwable) { null }
                val ok = json?.optBoolean("ok", false) == true
                val valid = json?.optJSONObject("data")?.optBoolean("valid", false) == true
                if (httpCode == 200 && ok && valid) {
                    "Token 有效，面板连接正常"
                } else {
                    val err = json?.optString("error").orEmpty()
                    withUnauthorizedHint(httpCode,
                        if (err.isNotBlank()) "验证失败 HTTP $httpCode: $err"
                        else "验证失败 HTTP $httpCode: $resp")
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "验证 Token 异常: ${t.message}")
                "验证异常: ${t.message}"
            }
            runOnUiThread {
                try {
                    tvUploadStatus.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
                try { btnValidateToken.isEnabled = true } catch (_: Throwable) {}
            }
        }, "panel-validate").start()
    }

    /**
     * 「提交 Code」：POST {base}/api/accounts  {"name","code","platform":"qq"}
     * 注意：面板运行期错误也可能返回 HTTP 200，必须以业务字段 ok 为准；
     * name（备注）必填，同名账号会被更新 code 并重启，否则新建并自动启动。
     */
    private fun submitPanelCode() {
        val base = normalizeBaseUrl(etApiBase.text.toString())
        val token = etAdminToken.text.toString().trim()
        val name = etAccountName.text.toString().trim()
        val code = etUploadCode.text.toString().trim()
        if (base.isEmpty() || token.isEmpty() || name.isEmpty() || code.isEmpty()) {
            tvUploadStatus.text = "请完整填写 API 地址、Token、账号备注和 Code"
            Toast.makeText(this, "请完整填写 API 地址、Token、账号备注和 Code", Toast.LENGTH_SHORT).show()
            return
        }
        btnSubmitCode.isEnabled = false
        tvUploadStatus.text = "正在提交..."
        AppLogger.i(TAG, "提交 code 到面板: $base/api/accounts name=$name")
        Thread({
            val msg = try {
                val body = JSONObject().apply {
                    put("name", name)
                    put("code", code)
                    put("platform", "qq")
                }.toString()
                val (httpCode, resp) = panelRequestWithRelogin(base, "POST", "/api/accounts", token, body)
                val json = try { JSONObject(resp) } catch (_: Throwable) { null }
                if (json?.optBoolean("ok", false) == true) {
                    AppLogger.i(TAG, "面板提交成功")
                    "已提交：账号已添加/更新并启动"
                } else {
                    val err = json?.optString("error").orEmpty()
                    AppLogger.w(TAG, "面板提交失败 HTTP $httpCode: $err")
                    withUnauthorizedHint(httpCode,
                        if (err.isNotBlank()) "提交失败 HTTP $httpCode: $err"
                        else "提交失败 HTTP $httpCode: $resp")
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "提交 code 异常: ${t.message}")
                "提交异常: ${t.message}"
            }
            runOnUiThread {
                try {
                    tvUploadStatus.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
                try { btnSubmitCode.isEnabled = true } catch (_: Throwable) {}
            }
        }, "panel-submit").start()
    }

    /**
     * 「在线状态」：查询面板账号运行状态。
     * 备注非空 → 精确匹配该账号，显示在线情况/昵称/QQ；运行中时再用
     * GET /api/status（x-account-id 头）附带等级与经验。
     * 备注为空 → 列出面板全部账号（每行：备注 | id | 运行状态 | 昵称）。
     */
    private fun queryOnlineStatus() {
        val base = normalizeBaseUrl(etApiBase.text.toString())
        val token = etAdminToken.text.toString().trim()
        val name = etAccountName.text.toString().trim()
        if (base.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "请先填写面板 API 地址和 Token", Toast.LENGTH_SHORT).show()
            return
        }
        btnQueryOnlineStatus.isEnabled = false
        tvUploadStatus.text = "正在查询在线状态..."
        AppLogger.i(TAG, "查询面板账号状态: $base/api/accounts name=${name.ifBlank { "(全部)" }}")
        Thread({
            var toastMsg = "查询完成"
            val msg = try {
                val (httpCode, resp) = panelRequestWithRelogin(base, "GET", "/api/accounts", token, null)
                val json = try { JSONObject(resp) } catch (_: Throwable) { null }
                if (json?.optBoolean("ok", false) != true) {
                    val err = json?.optString("error").orEmpty()
                    toastMsg = "查询失败"
                    withUnauthorizedHint(httpCode,
                        if (err.isNotBlank()) "查询失败 HTTP $httpCode: $err"
                        else "查询失败 HTTP $httpCode: $resp")
                } else {
                    val accounts = json.optJSONObject("data")?.optJSONArray("accounts")
                    if (accounts == null || accounts.length() == 0) {
                        toastMsg = "面板暂无账号"
                        "面板中还没有任何账号"
                    } else if (name.isEmpty()) {
                        // 备注为空：列出全部账号
                        val sb = StringBuilder("面板共 ${accounts.length()} 个账号：\n")
                        for (i in 0 until accounts.length()) {
                            val acc = accounts.optJSONObject(i) ?: continue
                            sb.append(describeAccountLine(acc)).append('\n')
                        }
                        toastMsg = "共 ${accounts.length()} 个账号"
                        sb.toString().trimEnd()
                    } else {
                        var found: JSONObject? = null
                        for (i in 0 until accounts.length()) {
                            val acc = accounts.optJSONObject(i) ?: continue
                            if (acc.optString("name").trim() == name) { found = acc; break }
                        }
                        val acc = found
                        if (acc == null) {
                            toastMsg = "未找到账号"
                            "面板中未找到备注为「$name」的账号"
                        } else {
                            val running = acc.optBoolean("running", false)
                            toastMsg = if (running) "在线" else "已停止"
                            val sb = StringBuilder()
                            sb.append("备注：").append(acc.optString("name")).append('\n')
                            sb.append("ID：").append(acc.optLong("id")).append('\n')
                            sb.append("状态：").append(if (running) "在线运行中" else "已停止")
                            val nick = acc.optString("nick").trim()
                            if (nick.isNotEmpty()) sb.append('\n').append("昵称：").append(nick)
                            val uin = acc.optString("uin").trim()
                                .ifEmpty { acc.optString("qq").trim() }
                            if (uin.isNotEmpty()) sb.append('\n').append("QQ：").append(uin)
                            // 运行中时拉取实时状态（等级/经验），字段缺失时静默跳过
                            if (running) {
                                try {
                                    val (_, resp2) = panelRequestWithRelogin(base, "GET", "/api/status",
                                        token, null, acc.optLong("id").toString())
                                    val j2 = try { JSONObject(resp2) } catch (_: Throwable) { null }
                                    val st = j2?.optJSONObject("data")?.optJSONObject("status")
                                    if (j2?.optBoolean("ok", false) == true && st != null) {
                                        if (st.has("level")) sb.append('\n').append("等级：").append(st.opt("level"))
                                        if (st.has("exp")) sb.append("  经验：").append(st.opt("exp"))
                                    }
                                } catch (t: Throwable) {
                                    AppLogger.w(TAG, "拉取账号实时状态失败: ${t.message}")
                                }
                            }
                            sb.toString()
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "查询在线状态异常: ${t.message}")
                toastMsg = "查询异常"
                "查询异常: ${t.message}"
            }
            val toast = toastMsg
            runOnUiThread {
                try {
                    tvUploadStatus.text = msg
                    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
                try { btnQueryOnlineStatus.isEnabled = true } catch (_: Throwable) {}
            }
        }, "panel-status").start()
    }

    /** 单行账号描述：备注 | id | 运行状态 | 昵称 */
    private fun describeAccountLine(acc: JSONObject): String {
        val nick = acc.optString("nick").trim()
        return buildString {
            append(acc.optString("name").ifBlank { "(无备注)" })
            append(" | id=").append(acc.optLong("id"))
            append(" | ").append(if (acc.optBoolean("running", false)) "运行中" else "已停止")
            if (nick.isNotEmpty()) append(" | ").append(nick)
        }
    }

    /**
     * 「登录获取Token」：用管理员账号密码登录面板换取 token 并自动填入。
     * 每次点击只登录一次（错误密码连续 5 次会被面板锁定 15 分钟，绝不自动重试）。
     */
    private fun loginAndFillToken() {
        val base = normalizeBaseUrl(etApiBase.text.toString())
        val user = etAdminUser.text.toString().trim()
        val pwd = etAdminPassword.text.toString()
        if (base.isEmpty() || user.isEmpty() || pwd.isEmpty()) {
            Toast.makeText(this, "请先填写面板 API 地址、管理员账号和密码", Toast.LENGTH_SHORT).show()
            return
        }
        btnLoginToken.isEnabled = false
        tvUploadStatus.text = "正在登录获取 Token..."
        AppLogger.i(TAG, "登录面板获取 Token: $base/api/login user=$user")
        Thread({
            val (token, loginMsg) = performPanelLogin(base, user, pwd)
            if (token != null) {
                saveLoginResult(token, user, pwd)
                AppLogger.i(TAG, "面板登录成功，Token 已自动填入并保存")
            } else {
                AppLogger.w(TAG, "面板登录失败: $loginMsg")
            }
            val msg = if (token != null) "登录成功，Token 已自动填入并保存" else loginMsg
            runOnUiThread {
                try {
                    tvUploadStatus.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
                try { btnLoginToken.isEnabled = true } catch (_: Throwable) {}
            }
        }, "panel-login").start()
    }

    /**
     * 面板登录（后台线程）：POST /api/login {"username","password"}。
     * 成功返回 token（data.token）；失败返回 null + 含 HTTP 码与服务器 error 的说明
     * （401 invalid_credentials / 429 rate_limit / 423 locked 等原文展示）。
     * 只调用一次，绝不自动重试，也不经过 401 重登包装，避免递归。
     */
    private fun performPanelLogin(base: String, user: String, pwd: String): Pair<String?, String> {
        return try {
            val body = JSONObject().apply {
                put("username", user)
                put("password", pwd)
            }.toString()
            // 登录接口不需要 token，x-admin-token 传空串即可（面板忽略）
            val (httpCode, resp) = panelRequest("POST", "$base/api/login", "", body)
            val json = try { JSONObject(resp) } catch (_: Throwable) { null }
            if (json?.optBoolean("ok", false) == true) {
                val token = json.optJSONObject("data")?.optString("token")?.trim().orEmpty()
                if (token.isNotEmpty()) token to "ok"
                else null to "登录响应缺少 token 字段"
            } else {
                val err = json?.optString("error").orEmpty()
                null to (if (err.isNotBlank()) "登录失败 HTTP $httpCode: $err"
                        else "登录失败 HTTP $httpCode: $resp")
            }
        } catch (t: Throwable) {
            null to "登录异常: ${t.message}"
        }
    }

    /** 登录成功后：持久化 token + 管理员账号密码，并在主线程更新 token 输入框 */
    private fun saveLoginResult(token: String, user: String, pwd: String) {
        try {
            getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).edit()
                .putString(KEY_ADMIN_TOKEN, token)
                .putString(KEY_ADMIN_USER, user)
                .putString(KEY_ADMIN_PASSWORD, pwd)
                .apply()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "保存登录结果失败: ${t.message}")
        }
        runOnUiThread {
            try { etAdminToken.setText(token) } catch (_: Throwable) {}
        }
    }

    /**
     * 带 401 自动重登的面板请求（必须在后台线程调用）：
     * 已鉴权接口返回 HTTP 401（token 失效/面板重启）且本地存有管理员账号密码时，
     * 自动登录一次换新 token，更新输入框与本地存储后**重试原请求一次**并返回重试结果；
     * 未保存凭据则原样返回 401（由调用方提示去登录/检查 token）。
     * 登录请求本身不经过本包装，不会递归重试。
     */
    private fun panelRequestWithRelogin(base: String, method: String, path: String, token: String,
                                        body: String?, accountId: String? = null): Pair<Int, String> {
        val result = panelRequest(method, base + path, token, body, accountId)
        if (result.first != 401) return result
        val sp = getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE)
        val user = sp.getString(KEY_ADMIN_USER, "")?.trim().orEmpty()
        val pwd = sp.getString(KEY_ADMIN_PASSWORD, "") ?: ""
        if (user.isEmpty() || pwd.isEmpty()) {
            AppLogger.w(TAG, "面板返回 401 且未保存管理员账号密码，无法自动重登")
            return result
        }
        AppLogger.i(TAG, "面板返回 401，使用保存的管理员账号自动重登并重试 $path ...")
        val (newToken, loginMsg) = performPanelLogin(base, user, pwd)
        if (newToken == null) {
            AppLogger.w(TAG, "自动重登失败: $loginMsg")
            return result
        }
        saveLoginResult(newToken, user, pwd)
        AppLogger.i(TAG, "自动重登成功，重试原请求 $path")
        return panelRequest(method, base + path, newToken, body, accountId)
    }

    /** 401 且自动重登不可用/仍失败时，给错误信息追加操作提示 */
    private fun withUnauthorizedHint(httpCode: Int, msg: String): String =
        if (httpCode == 401) "$msg（Token 已失效：可点「登录获取Token」自动换新，或检查手动填写的 Token）" else msg

    /**
     * 面板 HTTP 请求：HttpURLConnection，连接/读取超时各 15s，
     * 鉴权头为 x-admin-token（不是 Authorization Bearer）；
     * accountId 非空时附带 x-account-id 头（/api/status 需要）。
     * 返回 HTTP 状态码 + 响应体文本。
     */
    private fun panelRequest(method: String, url: String, token: String, body: String?,
                             accountId: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("x-admin-token", token)
            if (accountId != null) conn.setRequestProperty("x-account-id", accountId)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val httpCode = conn.responseCode
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return httpCode to text
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 启动/停止 ----------------

    private fun onStartStopClick() {
        // ★ click handler 第一行就打 trace，崩前能看到 click 是否被触发
        FileLog.stopTrace("onStartStopClick.enter isCapturing=$isCapturing")
        // ★ 立刻禁用按钮，防止双击导致 click 内部状态错乱触发 crash
        try { btnStartCapture.isEnabled = false } catch (_: Throwable) {}
        try {
            if (isCapturing) {
                AppLogger.i(TAG, "用户点击停止抓包")
                stopVpnService()
            } else {
                AppLogger.i(TAG, "用户点击启动抓包")
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    AppLogger.i(TAG, "请求通知权限...")
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                requestVpnAndStart()
            }
        } catch (t: Throwable) {
            // ★ click handler 兜底：所有未捕获异常都吞掉 + 写文件 + 恢复按钮状态
            FileLog.crash("ClickHandler", "onStartStopClick 异常", t)
            try { Toast.makeText(this, "操作失败: ${t.message}", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        } finally {
            // 无论成功失败都恢复按钮（如果 Activity 还在）
            try { btnStartCapture.isEnabled = true } catch (_: Throwable) {}
        }
        FileLog.stopTrace("onStartStopClick.exit")
    }

    private fun requestVpnAndStart() {
        AppLogger.i(TAG, "准备 VPN 权限检查...")
        val intent = VpnService.prepare(this)
        if (intent != null) {
            AppLogger.i(TAG, "需要用户授权 VPN 权限")
            vpnPermLauncher.launch(intent)
        } else {
            AppLogger.i(TAG, "VPN 权限已就绪，直接启动")
            startVpnService()
        }
    }

    private fun startVpnService() {
        AppLogger.i(TAG, "启动 CaptureVpnService...")
        val intent = Intent(this, CaptureVpnService::class.java).apply {
            action = CaptureVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isCapturing = true
    }

    private fun stopVpnService() {
        AppLogger.i(TAG, "停止 CaptureVpnService...")
        FileLog.stopTrace("stopVpnService.begin")
        // 防御性：如果用户快速双击"停止"，第二次直接返回（避免重复发 intent）
        if (!isCapturing) {
            AppLogger.i(TAG, "stopVpnService: 未在运行，跳过")
            FileLog.stopTrace("stopVpnService.skip")
            return
        }
        val intent = Intent(this, CaptureVpnService::class.java).apply {
            action = CaptureVpnService.ACTION_STOP
        }
        try {
            // ★ 关键：stop 时必须用 startService，不能用 startForegroundService！
            //
            // Android 8+ 规定：用 startForegroundService() 启动的服务必须在 5 秒内调
            // startForeground()，否则系统抛 ForegroundServiceDidNotStartInTimeException
            // → RemoteServiceException → 进程被 SIGKILL。这就是之前"点停止 5 秒左右闪退"
            // 的真正根因（用户日志里 onDestroy.afterSuper 之后才崩，时间窗对得上）。
            //
            // start 时用 startForegroundService（首次启动必须提升为前台）。
            // stop 时服务已经在前台了，stop intent 只是发个消息让它取消前台并自停，
            // 不能再"再启动一次前台服务"，否则会触发上面的 5 秒契约。
            // 用 startService 不触发该契约；Service 收到 STOP action 后调
            // stopForeground() + stopSelf() 正常退出。
            startService(intent)
            FileLog.stopTrace("stopVpnService.startServiceSent")
        } catch (t: Throwable) {
            // 兜底：即使发送失败也把 UI 状态置为停止
            AppLogger.e(TAG, "停止服务 intent 发送失败: ${t.message}")
            FileLog.stopTrace("stopVpnService.startServiceFailed: ${t.message}")
        }
        isCapturing = false
        FileLog.stopTrace("stopVpnService.end isCapturing=false")
    }

    // ---------------- 状态点 + 状态文字 ----------------

    private fun updateStatusDot() {
        val (dot, color, label) = when (certState) {
            1 -> Triple(R.drawable.dot_ok,    R.color.state_ok,    R.string.state_ok)
            2 -> Triple(R.drawable.dot_warn,  R.color.state_warn,  R.string.state_warn)
            3 -> Triple(R.drawable.dot_err,   R.color.state_err,   R.string.state_err)
            else -> Triple(R.drawable.dot_unknown, R.color.state_idle, R.string.state_idle)
        }
        statusDot.setBackgroundResource(dot)
        tvStatusLabel.setText(label)
        tvStatusLabel.setTextColor(ContextCompat.getColor(this, color))
    }

    // ---------------- UI 行为 ----------------

    private fun copyCode() {
        val text = etCode.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "暂未捕获到 code", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq_code", text))
        AppLogger.i(TAG, "复制 code 到剪贴板: $text")
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** 打开项目 GitHub 主页（开源来源） */
    private fun openGithub() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))
            startActivity(intent)
            AppLogger.i(TAG, "打开 GitHub: ${getString(R.string.github_url)}")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "打开 GitHub 失败: ${t.message}")
            Toast.makeText(this, "无法打开浏览器: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyVersion() {
        val ver = tvVersion.text.toString()
        if (ver.isEmpty() || ver == getString(R.string.version_hint)) {
            Toast.makeText(this, "暂未捕获到版本号", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq_version", ver))
        AppLogger.i(TAG, "复制版本号到剪贴板: $ver")
        Toast.makeText(this, R.string.copied_version, Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        // 内存日志 + 落盘日志一起复制。落盘日志（FileLog）是 fsync 持久化的，
        // native 崩溃 / 进程被杀时内存日志会丢，但 FileLog 完整保留 STOP trace
        // 和未捕获异常堆栈 —— 这是定位"停止后闪退"的关键证据。
        val appLog = AppLogger.getFullLog()
        val fileLog = try { FileLog.tail(300_000) } catch (_: Throwable) { "" }
        val fullLog = buildString {
            append("===== 内存日志 =====\n")
            append(appLog).append('\n')
            append("\n===== 落盘日志(崩溃取证) =====\n")
            append(fileLog)
        }
        if (fullLog.isBlank()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq_capture_log", fullLog))
        AppLogger.i(TAG, "复制完整日志到剪贴板，长度=${fullLog.length}")
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        AppLogger.clear()
        tvLog?.text = ""
        AppLogger.i(TAG, "日志已清空")
    }

    /**
     * 把缓冲日志批量刷到 TextView（只跑在主线程）。
     * 由 [logFlushRunnable] 每 300ms 调一次；Activity 销毁后 tvLog 为 null 直接返回。
     */
    private fun flushLogPanel() {
        val tv = tvLog ?: return
        val text = synchronized(logBuffer) {
            if (logBuffer.isEmpty()) return
            val s = logBuffer.toString()
            logBuffer.setLength(0)
            s
        }
        try {
            tv.append(text)
            // 上限裁剪：超过 MAX_LOG_CHARS 就从中间某行断开、丢掉前面一半，
            // 防止 TextView 无限膨胀把内存吃光（配合 AppLogger 环形缓冲双保险）
            if (tv.text.length > MAX_LOG_CHARS) {
                val s = tv.text.toString()
                val keepStart = s.length - MAX_LOG_CHARS / 2
                val cutAt = s.indexOf('\n', keepStart)
                tv.text = if (cutAt in 1 until s.length) s.substring(cutAt + 1) else s.substring(keepStart)
            }
            logScroll?.post { logScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
        } catch (_: Throwable) {
            // TextView 已 detach / Activity 已销毁，静默忽略
        }
    }

    override fun onDestroy() {
        // 停掉日志定时刷新并解绑回调，避免 Activity 销毁后 Handler/AppLogger 仍持有
        // 本 Activity 的引用造成泄漏；日志线程此后写入的缓冲不再刷屏。
        logFlushHandler.removeCallbacks(logFlushRunnable)
        AppLogger.setOnLogUpdated(null)
        runCatching { unregisterReceiver(eventReceiver) }
        CodeBus.setOnCodeListener {}
        CodeBus.setOnVersionListener {}
        CodeBus.setOnStatusListener {}
        CodeBus.setOnErrorListener {}
        CodeBus.setOnFriendsListener { _, _ -> }
        super.onDestroy()
    }

    private fun downloadCert() {
        AppLogger.i(TAG, "用户点击下载证书")
        try {
            val pem = CaManager.readPem(applicationContext)
            val uri = CertExporter.exportToDownloads(this, pem)
            if (uri == null) {
                AppLogger.e(TAG, "导出证书到 Downloads 失败")
                Toast.makeText(this, R.string.cert_failed, Toast.LENGTH_LONG).show()
            } else {
                AppLogger.i(TAG, "证书已导出: $uri")
                Toast.makeText(this, R.string.cert_saved, Toast.LENGTH_LONG).show()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    showInstallCertDialog()
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "读取证书失败: ${t.message}")
            Toast.makeText(this, "读取证书失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInstallCertDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.install_cert_guide_title)
            .setMessage(R.string.install_cert_guide_text)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                AppLogger.i(TAG, "用户打开系统设置安装证书")
                val ok = CertInstallerGuide.openSecuritySettings(this)
                if (!ok) Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 证书信任自检 --------

    private fun runCertCheck(silentOnTrusted: Boolean) {
        AppLogger.i(TAG, "开始证书信任自检 (silentOnTrusted=$silentOnTrusted)...")
        Toast.makeText(this, R.string.cert_checking, Toast.LENGTH_SHORT).show()

        Thread({
            val result = try {
                val (caCert, caKey) = CaManager.ensureCa(applicationContext)
                CaTrustChecker.check(caCert, caKey)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "证书自检异常: ${t.message}")
                CaTrustChecker.Result.Error("自检异常: ${t.message}")
            }
            runOnUiThread { showCertCheckResult(result, silentOnTrusted) }
        }, "cert-check").start()
    }

    private fun showCertCheckResult(result: CaTrustChecker.Result, silentOnTrusted: Boolean) {
        when (result) {
            is CaTrustChecker.Result.Trusted -> {
                certState = 1
                if (!result.chainValidates) {
                    AlertDialog.Builder(this)
                        .setTitle("证书已安装，但链校验失败")
                        .setMessage(
                            "用户 CA 已安装且与当前签名 CA 一致，但用其签发的叶子证书" +
                                    "未通过 PKIX 校验：\n\n${result.detail}\n\n" +
                                    "这属于证书构造问题，请把日志复制发给开发者。"
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else if (!silentOnTrusted) {
                    AlertDialog.Builder(this)
                        .setTitle("证书状态正常")
                        .setMessage("用户 CA 已安装且与当前签名 CA 一致，叶子证书链通过 PKIX 校验。\n\n${result.detail}")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    AppLogger.i(TAG, "启动自检通过：证书已安装且一致，链校验 OK")
                }
            }
            is CaTrustChecker.Result.Stale -> {
                certState = 2
                AlertDialog.Builder(this)
                    .setTitle("检测到旧证书（CA 已轮换）")
                    .setMessage(
                        result.detail + "\n\n" +
                                "请到 系统设置 → 安全 → 加密与凭据 → 受信任的凭据 → 用户，" +
                                "删除旧的 QQMiniCodeCapture 证书，再回本 App 重新\"下载证书\"并安装。"
                    )
                    .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                        CertInstallerGuide.openSecuritySettings(this)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is CaTrustChecker.Result.NotFound -> {
                certState = 2
                AlertDialog.Builder(this)
                    .setTitle("未检测到 CA 证书")
                    .setMessage(
                        result.detail + "\n\n" +
                                "请先点击\"下载证书\"，然后按引导安装到系统。"
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is CaTrustChecker.Result.Error -> {
                certState = 3
                AlertDialog.Builder(this)
                    .setTitle("证书自检失败")
                    .setMessage(result.detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}