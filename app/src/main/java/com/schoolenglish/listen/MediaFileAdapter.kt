package com.schoolenglish.listen

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.schoolenglish.listen.databinding.ItemMediaFileBinding
import java.text.DecimalFormat

class MediaFileAdapter(
    private val onPlay: (MediaFile) -> Unit,
    private val onMore: (MediaFile) -> Unit
) : ListAdapter<MediaFile, MediaFileAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemMediaFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemMediaFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaFile) {
            binding.fileName.text = item.file.name
            binding.fileMeta.text = "${item.typeLabel()}  ·  ${formatBytes(item.sizeBytes)}"
            binding.typeIcon.setImageResource(if (item.type == MediaType.VIDEO) R.drawable.ic_video else R.drawable.ic_audio)
            binding.root.setOnClickListener { onPlay(item) }
            binding.moreButton.setOnClickListener { onMore(item) }
        }
    }

    private fun MediaFile.typeLabel() = if (type == MediaType.VIDEO) "视频" else "音频"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${DecimalFormat("0.0").format(bytes / 1024.0 / 1024.0)} MB"
        bytes >= 1024 -> "${DecimalFormat("0").format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaFile>() {
            override fun areItemsTheSame(old: MediaFile, new: MediaFile) = old.file.absolutePath == new.file.absolutePath
            override fun areContentsTheSame(old: MediaFile, new: MediaFile) = old == new
        }
    }
}
