package com.vsp.internetspeedmeter.recyclerview

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.util.PersianFormat

/**
 * Usage table row: blue date cell + alternating light/dark blue data cells,
 * exactly like the Internet Speed Meter Lite table.
 */
class UsageAdapter : ListAdapter<Usage, UsageAdapter.UsageViewHolder>(UsageDiffCallback) {

    private val rowDark = Color.parseColor("#C3DBFA")
    private val rowLight = Color.parseColor("#EEF5FD")
    private val rowLighter = Color.parseColor("#F7FBFF")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val odd = position % 2 == 1
        holder.bind(
            usage = getItem(position),
            todayStr = PersianFormat.today(),
            cellA = if (odd) rowDark else rowLight,
            cellB = if (odd) rowLight else rowLighter
        )
    }

    class UsageViewHolder(private val binding: ItemUsageRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(usage: Usage, todayStr: String, cellA: Int, cellB: Int) {
            binding.tvDate.text = PersianFormat.dateLabel(usage.date, todayStr)
            binding.tvMobile.text = PersianFormat.bytes(usage.mobile)
            binding.tvWifi.text = PersianFormat.bytes(usage.wifi)
            binding.tvTotal.text = PersianFormat.bytes(usage.total)

            binding.tvMobile.setBackgroundColor(cellA)
            binding.tvWifi.setBackgroundColor(cellB)
            binding.tvTotal.setBackgroundColor(cellA)
        }
    }

    object UsageDiffCallback : DiffUtil.ItemCallback<Usage>() {
        override fun areItemsTheSame(oldItem: Usage, newItem: Usage) = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: Usage, newItem: Usage) = oldItem == newItem
    }
}
