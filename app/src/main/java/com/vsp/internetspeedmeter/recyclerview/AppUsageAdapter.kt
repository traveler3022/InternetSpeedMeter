package com.vsp.internetspeedmeter.recyclerview

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.databinding.ItemAppUsageBinding
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.Palette

data class AppUsageItem(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val bytesUsed: Long,
    /** Bytes since the previous refresh (about 16 s); > 0 means active now. */
    val recentBytes: Long = 0L
)

class AppUsageAdapter : ListAdapter<AppUsageItem, AppUsageAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppUsageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(private val b: ItemAppUsageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: AppUsageItem) {
            b.tvAppName.text = item.label
            val total = FormatUtils.formatBytes(item.bytesUsed)
            b.tvAppUsage.text = if (item.recentBytes > 0L) {
                val now = "\u2191 " + FormatUtils.formatBytes(item.recentBytes)
                SpannableString("$now   $total").apply {
                    val accent = Palette.color(b.root.context, R.attr.ismAccent)
                    setSpan(ForegroundColorSpan(accent), 0, now.length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, now.length, 0)
                }
            } else {
                total
            }
            item.icon?.let { b.ivAppIcon.setImageDrawable(it) }
        }
    }

    object Diff : DiffUtil.ItemCallback<AppUsageItem>() {
        override fun areItemsTheSame(a: AppUsageItem, b: AppUsageItem) = a.uid == b.uid
        override fun areContentsTheSame(a: AppUsageItem, b: AppUsageItem) = a == b
    }
}
