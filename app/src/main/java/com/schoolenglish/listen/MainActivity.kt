package com.schoolenglish.listen

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.schoolenglish.listen.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MediaViewModel by viewModels()
    private lateinit var adapter: MediaFileAdapter
    private lateinit var player: ExoPlayer
    private var currentFiles: List<MediaFile> = emptyList()
    private var speedIndex = 1
    private var repeatMode = Player.REPEAT_MODE_OFF
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val positions by lazy { getSharedPreferences("positions", MODE_PRIVATE) }

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
        setSupportActionBar(binding.toolbar)
        adapter = MediaFileAdapter(::play, ::showMore)
        binding.fileList.adapter = adapter
        binding.fileList.itemAnimator = null
        player = ExoPlayer.Builder(this).build()
        binding.audioControls.player = player
        binding.videoPlayer.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val item = player.currentMediaItem ?: return
                if (!isPlaying) positions.edit().putLong(item.mediaId, player.currentPosition).apply()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val file = currentFiles.firstOrNull { it.file.absolutePath == mediaItem?.mediaId } ?: return
                binding.nowPlaying.text = file.file.name
                binding.videoPlayer.visibility = if (file.type == MediaType.VIDEO) View.VISIBLE else View.GONE
                binding.audioControls.visibility = if (file.type == MediaType.AUDIO) View.VISIBLE else View.GONE
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
            binding.repeatButton.text = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "单曲循环"
                Player.REPEAT_MODE_ALL -> "顺序循环"
                else -> "不循环"
            }
        }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
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
        importLauncher.launch(arrayOf("audio/mpeg", "video/mp4", "application/vnd.rar", "application/x-rar-compressed", "*/*"))
    }

    private fun play(media: MediaFile) {
        val index = currentFiles.indexOfFirst { it.file.absolutePath == media.file.absolutePath }
        if (index < 0) return
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
        binding.nowPlaying.text = media.file.name
        binding.videoPlayer.visibility = if (media.type == MediaType.VIDEO) View.VISIBLE else View.GONE
        binding.audioControls.visibility = if (media.type == MediaType.AUDIO) View.VISIBLE else View.GONE
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

    override fun onStop() {
        if (::player.isInitialized && player.currentMediaItem != null) {
            positions.edit().putLong(player.currentMediaItem!!.mediaId, player.currentPosition).apply()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
