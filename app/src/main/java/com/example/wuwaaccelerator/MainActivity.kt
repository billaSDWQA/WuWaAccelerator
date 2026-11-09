package com.example.wuwaaccelerator

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1207
    }

    private lateinit var statusView: TextView
    private lateinit var telemetryView: TextView
    private var acceleratorService: IAcceleratorService? = null
    private val telemetryExecutor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "WuWa-Telemetry-UI").apply { isDaemon = true }
    }
    private var telemetryFuture: ScheduledFuture<*>? = null
    private var serviceBound = false
    private var bindingInProgress = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, GpuUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("accelerator")
            .version(6)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_SHIZUKU_PERMISSION) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectAndStart()
                } else {
                    showStatus("Shizuku 權限遭拒絕；沒有修改任何 GPU 設定")
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { connectAndStart() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            stopTelemetryPolling()
            acceleratorService = null
            serviceBound = false
            bindingInProgress = false
            showStatus("Shizuku 服務已中斷；若先前套用 GPU 設定，備份仍保留，重新啟動 Shizuku 後會先嘗試還原")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingInProgress = false
            if (binder == null) {
                serviceBound = false
                stopTelemetryPolling()
                showStatus("Shizuku 服務回傳空連線")
                return
            }

            serviceBound = true
            val remote = IAcceleratorService.Stub.asInterface(binder)
            acceleratorService = remote
            startTelemetryPolling(remote)

            thread(name = "WuWa-Service-Connect") {
                val started = runCatching { remote.startMonitoring() }.getOrDefault(false)
                val currentStatus = runCatching { remote.getStatus() }
                    .getOrElse { "無法讀取加速服務狀態：${it.message}" }

                runOnUiThread {
                    showStatus(
                        if (started) currentStatus
                        else "自動偵測未能啟動：$currentStatus"
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stopTelemetryPolling()
            acceleratorService = null
            serviceBound = false
            bindingInProgress = false
            showStatus("GPU 控制服務已中斷；若先前套用 GPU 設定，重新連線後會先嘗試還原")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.tv_status)
        telemetryView = findViewById(R.id.tv_gpu_telemetry)
        findViewById<Button>(R.id.btn_start).setOnClickListener { connectAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopAndRestore() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Shizuku.pingBinder()) {
            connectAndStart()
        } else {
            showStatus("請先啟動 Shizuku，然後按「啟動自動加速」")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        stopTelemetryPolling()
        telemetryExecutor.shutdownNow()

        // The UserService is a daemon so monitoring continues while the game is open.
        // Unbind this Activity's callback without destroying the remote service.
        if (serviceBound || bindingInProgress) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false)
            }
        }

        super.onDestroy()
    }

    private fun connectAndStart() {
        if (!Shizuku.pingBinder()) {
            showStatus("Shizuku 尚未啟動；未修改任何設定")
            return
        }

        if (Shizuku.isPreV11()) {
            showStatus("目前 Shizuku 版本過舊，請更新 Shizuku")
            return
        }

        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (exception: Exception) {
            showStatus("無法檢查 Shizuku 權限：${exception.message}")
            return
        }

        if (!permissionGranted) {
            showStatus("等待 Shizuku 授權")
            runCatching { Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION) }
                .onFailure { showStatus("無法請求 Shizuku 權限：${it.message}") }
            return
        }

        val currentService = acceleratorService
        if (serviceBound && currentService != null) {
            thread(name = "WuWa-Service-Start") {
                val started = runCatching { currentService.startMonitoring() }.getOrDefault(false)
                val currentStatus = runCatching { currentService.getStatus() }
                    .getOrElse { "服務錯誤：${it.message}" }
                runOnUiThread {
                    showStatus(
                        if (started) currentStatus
                        else "自動偵測未能啟動：$currentStatus"
                    )
                }
            }
            return
        }

        if (bindingInProgress) return

        bindingInProgress = true
        showStatus("正在連接 Shizuku GPU 控制服務…")
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (exception: Exception) {
            bindingInProgress = false
            serviceBound = false
            showStatus("連接 Shizuku 服務失敗：${exception.message}")
        }
    }

    private fun stopAndRestore() {
        val remote = acceleratorService
        if (!serviceBound || remote == null) {
            showStatus("目前沒有連線中的加速服務")
            return
        }

        showStatus("正在停止監控並還原 GPU 原始設定…")
        thread(name = "WuWa-Service-Stop") {
            val restored = runCatching { remote.stopAndRestore() }.getOrDefault(false)
            val currentStatus = runCatching { remote.getStatus() }
                .getOrElse { "無法讀取還原結果：${it.message}" }

            runOnUiThread {
                showStatus(currentStatus)
                if (restored) {
                    stopTelemetryPolling()
                    runCatching {
                        Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
                    }
                    acceleratorService = null
                    serviceBound = false
                    bindingInProgress = false
                } else {
                    showStatus("$currentStatus；服務與備份保留，會繼續嘗試還原")
                }
            }
        }
    }

    private fun refreshStatus() {
        val remote = acceleratorService ?: return
        thread(name = "WuWa-Service-Status") {
            val currentStatus = runCatching { remote.getStatus() }.getOrNull() ?: return@thread
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showStatus(currentStatus)
            }
        }
    }

    private fun startTelemetryPolling(remote: IAcceleratorService) {
        telemetryFuture?.cancel(false)
        telemetryFuture = telemetryExecutor.scheduleWithFixedDelay({
            val payload = runCatching { remote.getGpuTelemetry() }.getOrNull()
            if (!payload.isNullOrBlank()) {
                runOnUiThread {
                    if (acceleratorService === remote && !isFinishing && !isDestroyed) {
                        showTelemetry(payload)
                    }
                }
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    private fun stopTelemetryPolling() {
        telemetryFuture?.cancel(false)
        telemetryFuture = null
    }

    private fun showTelemetry(payload: String) {
        if (!::telemetryView.isInitialized) return
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json == null) {
            telemetryView.text = "GPU 遙測資料格式錯誤"
            return
        }

        val sampledAt = json.optLong("sampledAtMs", 0L)
        val ageMs = if (sampledAt > 0L) (System.currentTimeMillis() - sampledAt).coerceAtLeast(0L) else -1L
        val updated = if (ageMs >= 0L) String.format(Locale.US, "%.1f 秒前", ageMs / 1000.0) else "尚未取樣"

        fun value(key: String): String? =
            if (json.isNull(key)) null else json.optString(key, null)

        fun source(key: String): String =
            value(key + "Source")?.substringAfterLast('/') ?: "未知來源"

        fun raw(key: String): String = value(key + "Raw")?.replace("\\n", " ") ?: "—"

        fun reason(key: String): String = value(key + "Reason") ?: "沒有額外說明"

        fun loadLine(): String {
            val status = json.optString("loadStatus", "UNAVAILABLE")
            return when (status) {
                "AVAILABLE" -> "GPU 負載：${value("load") ?: "—"}%（${source("load")}；raw=${raw("load")}）"
                "MALFORMED" -> "GPU 負載：資料格式異常（${source("load")}；raw=${raw("load")}）"
                else -> "GPU 負載：不可用（${reason("load")}）"
            }
        }

        fun frequencyLine(label: String, key: String): String {
            val status = json.optString(key + "Status", "UNAVAILABLE")
            if (status != "AVAILABLE") {
                return "$label：不可用（${reason(key)}；raw=${raw(key)}）"
            }
            val khz = value(key)?.toLongOrNull()
                ?: return "$label：格式異常（raw=${raw(key)}）"
            return String.format(Locale.US, "%s：%.0f MHz（%d kHz）", label, khz / 1000.0, khz)
        }

        val governor = if (json.optString("governorStatus") == "AVAILABLE") {
            "GPU governor：${value("governor") ?: "—"}"
        } else {
            "GPU governor：不可用（${reason("governor")}）"
        }

        val table = if (json.optString("availableFrequenciesKHzStatus") == "AVAILABLE") {
            val values = value("availableFrequenciesKHz").orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() }
            "支援頻率表：${values.joinToString(" / ") { "${it / 1000} MHz" }}"
        } else {
            "支援頻率表：不可用（${reason("availableFrequenciesKHz")}）"
        }

        val freshness = if (ageMs > 3000L) "資料可能已過期" else "獨立讀取中"
        telemetryView.text = listOf(
            "GPU 即時監視（$freshness；更新 $updated）",
            loadLine(),
            frequencyLine("實際 GPU 時脈 cur_freq", "currentFrequencyKHz"),
            frequencyLine("最低頻率要求 min_freq", "minimumFrequencyKHz"),
            frequencyLine("硬體頻率上限 max_freq", "maximumFrequencyKHz"),
            governor,
            table,
            "FPS：此版本尚未取得可信的遊戲 present-to-present 數據，不顯示猜測值。"
        ).joinToString("\\n")
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }
}
