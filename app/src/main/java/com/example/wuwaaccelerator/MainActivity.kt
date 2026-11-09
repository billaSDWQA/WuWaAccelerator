package com.example.wuwaaccelerator

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1207
    }

    private lateinit var statusView: TextView
    private lateinit var telemetryView: TextView
    @Volatile private var acceleratorService: IAcceleratorService? = null
    @Volatile private var serviceBound = false
    private val telemetryExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "WuWa-Telemetry-UI").apply { isDaemon = true } }
    private var telemetryFuture: ScheduledFuture<*>? = null
    private var bindingInProgress = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, GpuUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("accelerator")
            .version(8)
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
                showStatus("Shizuku 服務回傳空連線")
                return
            }

            serviceBound = true
            val remote = IAcceleratorService.Stub.asInterface(binder)
            acceleratorService = remote

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
        telemetryView = findViewById(R.id.tv_telemetry)
        findViewById<Button>(R.id.btn_start).setOnClickListener { connectAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopAndRestore() }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { startGpuOverlay() }
        findViewById<Button>(R.id.btn_overlay_stop).setOnClickListener { stopGpuOverlay() }

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
        startTelemetryUpdates()
    }

    override fun onPause() {
        stopTelemetryUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        stopTelemetryUpdates()
        telemetryExecutor.shutdownNow()
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

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
                    stopGpuOverlay()
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

    private fun startGpuOverlay() {
        val remote = acceleratorService
        if (!serviceBound || remote == null) {
            showStatus("請先連接 Shizuku 並啟動 GPU 監控，再啟動懸浮 HUD")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showStatus("需要授予「顯示在其他應用程式上層」權限；授權後請再按一次 HUD 啟動")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                showStatus("無法開啟懸浮視窗權限頁面：${it.message}")
            }
            return
        }

        val intent = Intent(this, GpuOverlayService::class.java).apply {
            putExtra(GpuOverlayService.EXTRA_REMOTE_BINDER, remote.asBinder())
        }
        try {
            // This is launched directly from a visible Activity in response to a
            // button tap, satisfying Android's user-visible foreground-service flow.
            startForegroundService(intent)
            showStatus("GPU 懸浮 HUD 已啟動；可拖曳位置，點擊標題展開，按 × 關閉")
        } catch (exception: Exception) {
            showStatus("GPU HUD 啟動失敗：${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun stopGpuOverlay() {
        runCatching { stopService(Intent(this, GpuOverlayService::class.java)) }
    }

    private fun startTelemetryUpdates() {
        if (telemetryFuture?.isDone == false && telemetryFuture?.isCancelled == false) return
        telemetryFuture = telemetryExecutor.scheduleWithFixedDelay({
            val remote = acceleratorService
            val text = if (!serviceBound || remote == null) {
                "等待 Shizuku GPU 控制服務連線"
            } else {
                runCatching { remote.getTelemetrySnapshot() }
                    .getOrElse { "GPU 遙測讀取失敗：${it.message ?: it.javaClass.simpleName}" }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) telemetryView.text = text
            }
        }, 0L, 1000L, TimeUnit.MILLISECONDS)
    }

    private fun stopTelemetryUpdates() {
        telemetryFuture?.cancel(false)
        telemetryFuture = null
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

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }
}
