package com.schoolenglish.listen

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.schoolenglish.listen.databinding.ItemTranscriptLineBinding

class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.Holder>() {
    private var lines: List<TranscriptLine> = emptyList()
    private var activeIndex = RecyclerView.NO_POSITION

    fun submitList(value: List<TranscriptLine>) {
        lines = value
        activeIndex = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun setActiveIndex(index: Int) {
        if (index == activeIndex || index !in lines.indices) return
        val previous = activeIndex
        activeIndex = index
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
        notifyItemChanged(activeIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemTranscriptLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(lines[position], position == activeIndex)
    override fun getItemCount(): Int = lines.size

    class Holder(private val binding: ItemTranscriptLineBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(line: TranscriptLine, active: Boolean) {
            binding.transcriptText.text = line.text
            binding.transcriptText.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            binding.transcriptText.alpha = if (active) 1f else 0.62f
        }
    }
}
