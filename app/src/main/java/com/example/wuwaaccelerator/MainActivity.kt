package com.example.wuwaaccelerator

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
    @Volatile private var acceleratorService: IAcceleratorService? = null
    @Volatile private var serviceBound = false
    private var bindingInProgress = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, GpuUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("accelerator")
            .version(11)
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
            showStatus("Shizuku 服務已中斷；若先前套用 GPU 設定，備份仍保留，重新啟動後會先嘗試還原")
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
                        else "自動加速未能啟動：$currentStatus"
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
        findViewById<Button>(R.id.btn_start).setOnClickListener { connectAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopAndRestore() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Shizuku.pingBinder()) {
            connectAndStart()
        } else {
            showStatus("請先啟動 Shizuku，然後按「啟動順滑加速」")
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

        // The UserService is a daemon; unbind this Activity without destroying it.
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
            if (currentService.asBinder().isBinderAlive) {
                thread(name = "WuWa-Service-Start") {
                    val started = runCatching { currentService.startMonitoring() }.getOrDefault(false)
                    val currentStatus = runCatching { currentService.getStatus() }
                        .getOrElse { "服務錯誤：${it.message}" }
                    runOnUiThread {
                        showStatus(
                            if (started) currentStatus
                            else "自動加速未能啟動：$currentStatus"
                        )
                    }
                }
                return
            }
            acceleratorService = null
            serviceBound = false
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

        showStatus("正在停止加速並還原 GPU 原始設定…")
        thread(name = "WuWa-Service-Stop") {
            val restored = runCatching { remote.stopAndRestore() }.getOrDefault(false)
            val currentStatus = runCatching { remote.getStatus() }
                .getOrElse { "無法讀取還原結果：${it.message}" }

            runOnUiThread {
                showStatus(currentStatus)
                if (restored) {
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

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }
}
