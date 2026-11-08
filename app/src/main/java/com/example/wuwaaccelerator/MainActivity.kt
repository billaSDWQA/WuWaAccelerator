package com.example.wuwaaccelerator

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE = 1001
    private val GAME_PACKAGE = "com.kurogame.wutheringwaves.global"

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showToast("Shizuku 授權成功！請再次點擊按鈕")
            } else {
                showToast("你拒絕了授權，無法執行優化")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        val btnBoost = findViewById<Button>(R.id.btn_boost)
        val btnRestore = findViewById<Button>(R.id.btn_restore)

        btnBoost.setOnClickListener {
            if (checkShizukuPermission()) executeExtremeBoost()
            else Shizuku.requestPermission(REQUEST_CODE)
        }

        btnRestore.setOnClickListener {
            if (checkShizukuPermission()) executeRestore()
            else Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun checkShizukuPermission(): Boolean {
        return if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    // ================= 核心：終極安全穩定版 =================
    private fun executeExtremeBoost() {
        thread {
            val pid = getGamePid()
            if (pid.isEmpty() || pid == "0") {
                runOnUiThread { showToast("⚠️ 未檢測到遊戲運行，請先打開鳴潮！") }
                return@thread
            }

            val boostCommands = mutableListOf(
                // 1. 記憶體溫和清理（只殺後台與暫存，絕不碰系統進程數）
                "am kill-all",
                "pm trim-caches 99999999999G",

                // 2. 觸發官方遊戲模式（由系統底層調度，拒絕第三方暴力鎖頻）
                "cmd game mode performance $GAME_PACKAGE",

                // 3. 網路加速（你實測 36~61ms 的神級代碼，保留）
                "settings put global private_dns_mode hostname",
                "settings put global private_dns_specifier dns.google",

                // 4. 【新發現】強制 AOT 編譯遊戲（把遊戲代碼預先翻譯成機器碼，減少遊戲中的即時編譯卡頓）
                "cmd package compile -m speed -f $GAME_PACKAGE",

                // 5. 螢幕與 UI 減負（關閉毛玻璃與動畫，節省 GPU 算力）
                // 注意：保留 120Hz 是為了觸控採樣率。若覺得太耗電，可把 120.0 改為 60.0
                "settings put system peak_refresh_rate 120.0",
                "settings put system min_refresh_rate 120.0",
                "settings put global disable_window_blurs 1",
                "settings put global window_animation_scale 0.0",
                "settings put global transition_animation_scale 0.0",
                "settings put global animator_duration_scale 0.0"

                // ❌ 徹底刪除：renice, schedutil, oom_score_adj 等會觸發系統反殺的毒瘤
            )

            runShellCommands(boostCommands)
            runOnUiThread { showToast("🔥 官方遊戲模式已啟動！AOT 預編譯完成！") }
        }
    }

    // ================= 核心：恢復日常 =================
    private fun executeRestore() {
        thread {
            val restoreCommands = listOf(
                "cmd game mode standard $GAME_PACKAGE",
                "settings put global private_dns_mode off",
                "settings put system min_refresh_rate 60.0",
                "settings put global disable_window_blurs 0",
                "settings put global window_animation_scale 1.0",
                "settings put global transition_animation_scale 1.0",
                "settings put global animator_duration_scale 1.0"
            )
            runShellCommands(restoreCommands)
            runOnUiThread { showToast("🛡️ 已恢復日常模式！系統限制已解除") }
        }
    }

    // 輔助方法：獲取遊戲 PID
    private fun getGamePid(): String {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", "pidof $GAME_PACKAGE"), null, null) as rikka.shizuku.ShizukuRemoteProcess
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val pid = reader.readLine()?.trim() ?: ""
            reader.close()
            process.waitFor()
            process.destroy()
            pid
        } catch (e: Exception) { "" }
    }

    // 輔助方法：透過反射執行 Shizuku 指令
    private fun runShellCommands(commands: List<String>) {
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            newProcessMethod.isAccessible = true
            for (cmd in commands) {
                val remoteProcess = newProcessMethod.invoke(null, arrayOf("sh", "-c", cmd), null, null) as rikka.shizuku.ShizukuRemoteProcess
                remoteProcess.waitFor()
                remoteProcess.destroy()
            }
        } catch (e: Exception) {}
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}