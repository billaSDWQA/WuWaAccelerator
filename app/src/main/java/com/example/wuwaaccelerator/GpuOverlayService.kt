package com.example.wuwaaccelerator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * User-started, read-only in-game GPU HUD.
 *
 * The Shizuku binder is passed from the visible Activity. This process never reads
 * or writes GPU sysfs directly and never changes GPU frequency.
 */
class GpuOverlayService : Service() {

    companion object {
        const val EXTRA_REMOTE_BINDER = "com.example.wuwaaccelerator.extra.REMOTE_BINDER"
        const val EXTRA_REMOTE_BUNDLE = "com.example.wuwaaccelerator.extra.REMOTE_BUNDLE"
        private const val ACTION_STOP = "com.example.wuwaaccelerator.action.STOP_GPU_HUD"
        private const val CHANNEL_ID = "gpu_hud"
        private const val NOTIFICATION_ID = 9031
        private const val POLL_INTERVAL_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WuWa-GPU-HUD").apply { isDaemon = true }
        }

    private lateinit var windowManager: WindowManager
    private var remote: IAcceleratorService? = null
    private var remoteBinder: IBinder? = null
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var overlayRoot: LinearLayout? = null
    private var headerView: LinearLayout? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var latestSnapshotText = "等待 Shizuku GPU 遙測資料"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val binder = intent?.getBundleExtra(EXTRA_REMOTE_BUNDLE)?.getBinder(EXTRA_REMOTE_BINDER)
        if (binder != null && remoteBinder !== binder) {
            val oldBinder = remoteBinder
            val oldRecipient = remoteDeathRecipient
            if (oldBinder != null && oldRecipient != null) {
                runCatching { oldBinder.unlinkToDeath(oldRecipient, 0) }
            }
            remoteBinder = null
            remoteDeathRecipient = null
            remote = null

            val newRecipient = IBinder.DeathRecipient {
                mainHandler.post {
                    // Ignore a late death callback from a superseded Binder if a
                    // fresh Shizuku UserService has already reconnected.
                    if (remoteBinder === binder) {
                        latestSnapshotText = "Shizuku GPU 服務已中斷；HUD 已停止"
                        renderLatestSnapshot()
                        stopSelf()
                    }
                }
            }
            try {
                binder.linkToDeath(newRecipient, 0)
                remoteBinder = binder
                remoteDeathRecipient = newRecipient
                remote = IAcceleratorService.Stub.asInterface(binder)
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (remote == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay()
        startPolling()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollFuture?.cancel(false)
        pollFuture = null
        poller.shutdownNow()
        overlayRoot?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayRoot = null
        val binder = remoteBinder
        val recipient = remoteDeathRecipient
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        remoteDeathRecipient = null
        remoteBinder = null
        remote = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "鳴潮 GPU 懸浮監控",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "使用者主動啟動的唯讀 GPU 遙測 HUD"
                    setShowBadge(false)
                }
            )
        }

        val stopIntent = Intent(this, GpuOverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("鳴潮 GPU HUD")
            .setContentText("唯讀懸浮監控運作中")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "停止 HUD", stopPendingIntent).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay() {
        if (overlayRoot != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE61B2028.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xAA8CA4BE.toInt())
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val title = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(dp(4), dp(5), dp(8), dp(5))
            maxWidth = dp(260)
        }
        val close = Button(this).apply {
            text = "×"
            textSize = 16f
            minWidth = dp(32)
            minimumWidth = dp(32)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(0, 0, 0, 0)
            contentDescription = "關閉 GPU HUD"
            setOnClickListener { stopSelf() }
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            close,
            LinearLayout.LayoutParams(dp(36), dp(36))
        )

        val details = TextView(this).apply {
            setTextColor(0xFFE5ECF4.toInt())
            textSize = 10.5f
            setPadding(dp(4), dp(6), dp(4), dp(4))
            maxWidth = dp(300)
            visibility = View.GONE
        }

        header.setOnClickListener {
            expanded = !expanded
            renderLatestSnapshot()
        }
        attachDragGesture(header)

        root.addView(header)
        root.addView(details)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        try {
            windowManager.addView(root, params)
            overlayRoot = root
            headerView = header
            titleView = title
            detailView = details
            overlayParams = params
            renderLatestSnapshot()
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun attachDragGesture(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialX = overlayParams?.x ?: 0
                    initialY = overlayParams?.y ?: 0
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragged && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragged = true
                    }
                    if (dragged) {
                        overlayParams?.let { params ->
                            overlayRoot?.let { root ->
                                val display = resources.displayMetrics
                                val maxX = (display.widthPixels - root.width).coerceAtLeast(0)
                                val maxY = (display.heightPixels - root.height).coerceAtLeast(0)
                                params.x = (initialX + dx).coerceIn(0, maxX)
                                params.y = (initialY + dy).coerceIn(0, maxY)
                                runCatching { windowManager.updateViewLayout(root, params) }
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun startPolling() {
        if (pollFuture?.isDone == false && pollFuture?.isCancelled == false) return
        pollFuture = poller.scheduleWithFixedDelay({
            val text = runCatching {
                remote?.getTelemetrySnapshot() ?: "Shizuku GPU 服務不可用"
            }.getOrElse {
                "Shizuku 連線中斷：${it.javaClass.simpleName}"
            }
            mainHandler.post {
                if (overlayRoot != null) {
                    latestSnapshotText = text
                    renderLatestSnapshot()
                }
            }
        }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun renderLatestSnapshot() {
        val title = titleView ?: return
        val details = detailView ?: return
        if (expanded) {
            title.text = "鳴潮 GPU 監控"
            details.text = latestSnapshotText
            details.visibility = View.VISIBLE
        } else {
            title.text = GpuOverlayTextFormatter.compact(latestSnapshotText)
            details.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

}
