package com.schoolenglish.listen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.schoolenglish.listen.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MediaViewModel by viewModels()
    private lateinit var adapter: MediaFileAdapter
    private lateinit var player: ExoPlayer
    private lateinit var transcriptAdapter: TranscriptAdapter
    private val transcriptViewModel: TranscriptViewModel by viewModels()
    private var transcriptLines: List<TranscriptLine> = emptyList()
    private var transcriptMediaId: String? = null
    private var lastTranscriptIndex = -1
    private var currentFiles: List<MediaFile> = emptyList()
    private var speedIndex = 1
    private var repeatMode = Player.REPEAT_MODE_ONE
    private var overlayRequestedForBackground = false
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val positions by lazy { getSharedPreferences(POSITIONS_PREFS, MODE_PRIVATE) }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val enabled = Settings.canDrawOverlays(this)
        CaptionOverlayService.setEnabled(this, enabled)
        updateOverlayButton()
        Toast.makeText(
            this,
            if (enabled) "悬浮字幕已开启" else "需要允许悬浮窗权限",
            Toast.LENGTH_SHORT
        ).show()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            viewModel.importFile(uri, queryDisplayName(uri)) { result ->
                result.onSuccess {
                    Toast.makeText(this, "已导入 ${it.importedCount} 个文件", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "导入失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = MediaFileAdapter(::play, ::showMore)
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter
        binding.fileList.itemAnimator = null
        transcriptAdapter = TranscriptAdapter()
        binding.transcriptList.layoutManager = LinearLayoutManager(this)
        binding.transcriptList.adapter = transcriptAdapter
        binding.transcriptList.itemAnimator = null
        player = ExoPlayer.Builder(applicationContext).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = this@MainActivity.repeatMode
        }
        replaceActivePlayer(player)?.release()
        binding.audioControls.player = player
        binding.videoPlayer.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveCurrentPosition()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val file = currentFiles.firstOrNull { it.file.absolutePath == mediaItem?.mediaId } ?: return
                updatePlayerPanel(file)
                showOrLoadTranscript(file)
            }
        })
        binding.speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val speed = speeds[speedIndex]
            player.setPlaybackParameters(PlaybackParameters(speed))
            binding.speedButton.text = "${if (speed % 1f == 0f) speed.toInt() else speed}x"
        }
        binding.repeatButton.setOnClickListener {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            player.repeatMode = repeatMode
            renderRepeatMode()
        }
        renderRepeatMode()
        binding.overlayButton.setOnClickListener { toggleCaptionOverlay() }
        updateOverlayButton()
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_import) openImporter()
            true
        }
        handleIncomingIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.files.collect {
                    currentFiles = it
                    adapter.submitList(it)
                    binding.emptyState.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                    binding.fileList.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                transcriptViewModel.lines.collect {
                    transcriptLines = it
                    lastTranscriptIndex = -1
                    transcriptAdapter.submitList(it)
                    binding.transcriptList.visibility = if (it.isNotEmpty() && isCurrentAudio()) View.VISIBLE else View.GONE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateTranscriptPosition()
                    delay(250)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val incomingIntent = intent ?: return
        val uri = incomingIntent.data
            ?: IntentCompat.getParcelableExtra(incomingIntent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: return
        if (incomingIntent.action != Intent.ACTION_VIEW && incomingIntent.action != Intent.ACTION_SEND) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        viewModel.importFile(uri, queryDisplayName(uri)) { result ->
            result.onSuccess {
                Toast.makeText(this, "已导入 ${it.importedCount} 个文件", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "导入失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openImporter() {
        importLauncher.launch(IMPORT_MIME_TYPES)
    }

    private fun toggleCaptionOverlay() {
        val hasPermission = Settings.canDrawOverlays(this)
        if (CaptionOverlayService.isEnabled(this) && hasPermission) {
            CaptionOverlayService.setEnabled(this, false)
            CaptionOverlayService.stop(this)
            updateOverlayButton()
            return
        }
        if (!hasPermission) {
            CaptionOverlayService.setEnabled(this, false)
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        CaptionOverlayService.setEnabled(this, true)
        updateOverlayButton()
        Toast.makeText(this, "最小化后显示悬浮字幕", Toast.LENGTH_SHORT).show()
    }

    private fun updateOverlayButton() {
        if (!::binding.isInitialized) return
        val enabled = CaptionOverlayService.isEnabled(this) && Settings.canDrawOverlays(this)
        binding.overlayButton.text = if (enabled) "悬浮已开" else "悬浮字幕"
        binding.overlayButton.alpha = if (enabled) 1f else 0.82f
    }

    private fun play(media: MediaFile) {
        val index = currentFiles.indexOfFirst { it.file.absolutePath == media.file.absolutePath }
        if (index < 0) return
        saveCurrentPosition()
        player.pause()
        player.stop()
        player.clearMediaItems()
        val items = currentFiles.map { file ->
            MediaItem.Builder()
                .setUri(file.file.toURI().toString())
                .setMediaId(file.file.absolutePath)
                .build()
        }
        val mediaId = media.file.absolutePath
        player.setMediaItems(items, index, positions.getLong(mediaId, 0L))
        player.prepare()
        player.play()
        binding.playerPanel.visibility = View.VISIBLE
        updatePlayerPanel(media)
    }

    private fun updatePlayerPanel(media: MediaFile) {
        binding.nowPlaying.text = media.file.name
        binding.videoPlayer.visibility = if (media.type == MediaType.VIDEO) View.VISIBLE else View.GONE
        binding.audioControls.visibility = if (media.type == MediaType.AUDIO) View.VISIBLE else View.GONE
    }

    private fun renderRepeatMode() {
        binding.repeatButton.text = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "单曲循环"
            Player.REPEAT_MODE_ALL -> "顺序循环"
            else -> "不循环"
        }
    }

    private fun saveCurrentPosition() {
        player.currentMediaItem?.let { item ->
            positions.edit().putLong(item.mediaId, player.currentPosition).apply()
        }
    }

    private fun showOrLoadTranscript(media: MediaFile) {
        val mediaId = media.file.absolutePath
        if (transcriptMediaId == mediaId && transcriptLines.isNotEmpty()) {
            binding.transcriptList.visibility =
                if (media.type == MediaType.AUDIO) View.VISIBLE else View.GONE
            updateTranscriptPosition()
            return
        }
        transcriptMediaId = mediaId
        transcriptLines = emptyList()
        lastTranscriptIndex = -1
        transcriptAdapter.submitList(emptyList())
        binding.transcriptList.visibility = View.GONE
        transcriptViewModel.loadFor(media.file.name)
    }

    private fun isCurrentAudio(): Boolean {
        val current = currentFiles.firstOrNull { it.file.absolutePath == player.currentMediaItem?.mediaId }
        return current?.type == MediaType.AUDIO
    }

    private fun updateTranscriptPosition() {
        if (!isCurrentAudio() || transcriptLines.isEmpty()) return
        val duration = player.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) return
        val index = TranscriptTimeline.indexAt(player.currentPosition, duration, transcriptLines)
        if (index == lastTranscriptIndex) return
        lastTranscriptIndex = index
        transcriptAdapter.setActiveIndex(index)
        scrollTranscriptToCenter(index)
    }

    private fun scrollTranscriptToCenter(index: Int) {
        val layoutManager = binding.transcriptList.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int = if (viewEnd - viewStart >= boxEnd - boxStart) {
                boxStart - viewStart
            } else {
                (boxStart + boxEnd - viewStart - viewEnd) / 2
            }
        }
        scroller.targetPosition = index
        layoutManager.startSmoothScroll(scroller)
    }

    private fun showMore(media: MediaFile) {
        val popup = PopupMenu(this, binding.root)
        popup.menu.add("删除")
        popup.setOnMenuItemClickListener { item: MenuItem ->
            if (item.title == "删除") confirmDelete(media)
            true
        }
        popup.show()
    }

    private fun confirmDelete(media: MediaFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除文件")
            .setMessage("确定删除“${media.file.name}”吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> viewModel.delete(media) { if (player.currentMediaItem?.mediaId == media.file.absolutePath) player.stop() } }
            .show()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    override fun onStart() {
        super.onStart()
        overlayRequestedForBackground = false
        CaptionOverlayService.stop(this)
        updateOverlayButton()
    }

    override fun onUserLeaveHint() {
        overlayRequestedForBackground = showCaptionOverlayIfEnabled()
        super.onUserLeaveHint()
    }

    private fun showCaptionOverlayIfEnabled(): Boolean {
        val initialized = ::player.isInitialized
        val enabled = CaptionOverlayService.isEnabled(this)
        val hasPermission = Settings.canDrawOverlays(this)
        val isAudio = initialized && isCurrentAudio()
        val hasLines = transcriptLines.isNotEmpty()
        val duration = if (initialized) player.duration else 0L
        val canShow = initialized && enabled && hasPermission && isAudio &&
            hasLines && duration > 0L && duration != androidx.media3.common.C.TIME_UNSET
        if (canShow) {
            CaptionOverlayService.show(
                context = this,
                trackName = player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: currentFiles.firstOrNull {
                        it.file.absolutePath == player.currentMediaItem?.mediaId
                    }?.file?.name.orEmpty(),
                lines = transcriptLines,
                positionMs = player.currentPosition,
                durationMs = duration,
                playbackSpeed = player.playbackParameters.speed,
                isPlaying = player.isPlaying
            )
        }
        return canShow
    }

    override fun onStop() {
        if (::player.isInitialized) saveCurrentPosition()
        if (!overlayRequestedForBackground) showCaptionOverlayIfEnabled()
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized && releaseActivePlayer(player)) player.release()
        super.onDestroy()
    }

    companion object {
        private const val POSITIONS_PREFS = "positions"
        private val IMPORT_MIME_TYPES = arrayOf(
            "audio/mpeg",
            "video/mp4",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "*/*"
        )
        private val ACTIVE_PLAYER_LOCK = Any()
        private var activePlayer: ExoPlayer? = null

        private fun replaceActivePlayer(newPlayer: ExoPlayer): ExoPlayer? =
            synchronized(ACTIVE_PLAYER_LOCK) {
                activePlayer.also { activePlayer = newPlayer }
            }

        private fun releaseActivePlayer(player: ExoPlayer): Boolean =
            synchronized(ACTIVE_PLAYER_LOCK) {
                if (activePlayer !== player) return@synchronized false
                activePlayer = null
                true
            }
    }
}
