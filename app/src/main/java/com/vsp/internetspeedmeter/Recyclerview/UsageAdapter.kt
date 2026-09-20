package com.vsp.internetspeedmeter.Recyclerview

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.Room.Usage
import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding

class UsageAdapter : ListAdapter<Usage, UsageAdapter.UsageViewHolder>(UsageDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class UsageViewHolder(private val binding: ItemUsageRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(usage: Usage) {
            binding.tvDate.text = usage.date
            binding.tvMobile.text = usage.mobile
            binding.tvWifi.text = usage.wifi
            binding.tvTotal.text = usage.total
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
