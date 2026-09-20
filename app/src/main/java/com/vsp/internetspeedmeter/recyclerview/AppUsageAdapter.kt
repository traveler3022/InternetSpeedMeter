package com.vsp.internetspeedmeter.recyclerview

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.databinding.ItemAppUsageBinding
import com.vsp.internetspeedmeter.util.FormatUtils

data class AppUsageItem(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val bytesUsed: Long
)

class AppUsageAdapter : ListAdapter<AppUsageItem, AppUsageAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppUsageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(private val b: ItemAppUsageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: AppUsageItem) {
            b.tvAppName.text = item.label
            b.tvAppUsage.text = FormatUtils.formatBytes(item.bytesUsed)
            item.icon?.let { b.ivAppIcon.setImageDrawable(it) }
        }
    }

    object Diff : DiffUtil.ItemCallback<AppUsageItem>() {
        override fun areItemsTheSame(a: AppUsageItem, b: AppUsageItem) = a.uid == b.uid
        override fun areContentsTheSame(a: AppUsageItem, b: AppUsageItem) = a == b
    }
}
