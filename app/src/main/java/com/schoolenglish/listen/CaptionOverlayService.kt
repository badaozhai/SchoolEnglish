package com.schoolenglish.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.schoolenglish.listen.databinding.ViewCaptionOverlayBinding

@UnstableApi
class CaptionOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var binding: ViewCaptionOverlayBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lines: List<TranscriptLine> = emptyList()
    private var durationMs = 0L
    private var basePositionMs = 0L
    private var baseRealtimeMs = 0L
    private var playbackSpeed = 1f
    private var isPlaying = false
    private var activeIndex = -1

    private val captionTicker = object : Runnable {
        override fun run() {
            updateCaption()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        val hasPermission = Settings.canDrawOverlays(this)
        if (intent?.action != ACTION_SHOW || !hasPermission) {
            stopSelf()
            return START_NOT_STICKY
        }

        val textLines = intent.getStringArrayListExtra(EXTRA_LINES).orEmpty()
        lines = textLines.mapIndexed { index, text -> TranscriptLine(index.toLong(), text, index) }
        durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)
        basePositionMs = intent.getLongExtra(EXTRA_POSITION, 0L)
        playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, 1f)
        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
        baseRealtimeMs = SystemClock.elapsedRealtime()
        activeIndex = -1
        showOverlay(intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty())
        return START_NOT_STICKY
    }

    private fun showOverlay(trackName: String) {
        val overlayBinding = binding ?: ViewCaptionOverlayBinding.inflate(
            LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_SchoolEnglish))
        ).also { created ->
            binding = created
            created.overlayClose.setOnClickListener {
                setEnabled(this, false)
                stopSelf()
            }
            enableDragging(created.overlayHeader)
        }
        overlayBinding.overlayTrackName.text = trackName

        if (overlayBinding.root.parent == null) {
            val metrics = resources.displayMetrics
            val horizontalMargin = dp(16)
            layoutParams = WindowManager.LayoutParams(
                metrics.widthPixels - horizontalMargin * 2,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = horizontalMargin
                y = dp(96)
            }
            windowManager.addView(overlayBinding.root, layoutParams)
        }
        handler.removeCallbacks(captionTicker)
        captionTicker.run()
    }

    private fun updateCaption() {
        val overlayBinding = binding ?: return
        if (lines.isEmpty() || durationMs <= 0L) {
            overlayBinding.overlayCaption.text = "暂无匹配录音稿"
            return
        }
        val elapsedMediaMs = if (isPlaying) {
            ((SystemClock.elapsedRealtime() - baseRealtimeMs) * playbackSpeed).toLong()
        } else {
            0L
        }
        val positionMs = (basePositionMs + elapsedMediaMs).coerceAtMost(durationMs)
        val index = TranscriptTimeline.indexAt(positionMs, durationMs, lines)
        if (index == activeIndex) return
        activeIndex = index
        overlayBinding.overlayCaption.text = lines[index].text
    }

    private fun enableDragging(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        handle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    params.x = (downX + (event.rawX - downRawX).toInt())
                        .coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (downY + (event.rawY - downRawY).toInt())
                        .coerceIn(0, metrics.heightPixels - dp(96))
                    binding?.root?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮字幕", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_audio)
        .setContentTitle("悬浮字幕正在显示")
        .setContentText("点击返回英语随声听")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(captionTicker)
        binding?.root?.takeIf { it.parent != null }?.let(windowManager::removeView)
        binding = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_SHOW = "com.schoolenglish.listen.action.SHOW_CAPTION_OVERLAY"
        private const val EXTRA_TRACK_NAME = "track_name"
        private const val EXTRA_LINES = "lines"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_IS_PLAYING = "is_playing"
        private const val PREFS_NAME = "caption_overlay"
        private const val PREF_ENABLED = "enabled"
        private const val CHANNEL_ID = "caption_overlay"
        private const val NOTIFICATION_ID = 2001

        fun show(
            context: Context,
            trackName: String,
            lines: List<TranscriptLine>,
            positionMs: Long,
            durationMs: Long,
            playbackSpeed: Float,
            isPlaying: Boolean
        ) {
            val intent = Intent(context, CaptionOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_TRACK_NAME, trackName)
                .putStringArrayListExtra(EXTRA_LINES, ArrayList(lines.map { it.text }))
                .putExtra(EXTRA_POSITION, positionMs)
                .putExtra(EXTRA_DURATION, durationMs)
                .putExtra(EXTRA_SPEED, playbackSpeed)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptionOverlayService::class.java))
        }

        fun isEnabled(context: Context): Boolean = context
            .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, enabled)
                .apply()
        }
    }
}
