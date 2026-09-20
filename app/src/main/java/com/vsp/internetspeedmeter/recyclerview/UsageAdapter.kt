package com.vsp.internetspeedmeter.recyclerview

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding
import com.vsp.internetspeedmeter.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class UsageAdapter : ListAdapter<Usage, UsageAdapter.UsageViewHolder>(UsageDiffCallback) {

    private val dbDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val todayStr = dbDateFormat.format(Calendar.getInstance().time)
        holder.bind(getItem(position), todayStr)
    }

    class UsageViewHolder(private val binding: ItemUsageRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(usage: Usage, todayStr: String) {
            binding.tvDate.text = if (usage.date == todayStr) "Today" else usage.date
            binding.tvMobile.text = FormatUtils.formatBytes(usage.mobile)
            binding.tvWifi.text = FormatUtils.formatBytes(usage.wifi)
            binding.tvTotal.text = FormatUtils.formatBytes(usage.total)
        }
    }

    object UsageDiffCallback : DiffUtil.ItemCallback<Usage>() {
        override fun areItemsTheSame(oldItem: Usage, newItem: Usage): Boolean {
            return oldItem.date == newItem.date
        }

        override fun areContentsTheSame(oldItem: Usage, newItem: Usage): Boolean {
            return oldItem == newItem
        }
    }
}
