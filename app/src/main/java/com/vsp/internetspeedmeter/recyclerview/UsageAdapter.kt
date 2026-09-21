package com.vsp.internetspeedmeter.recyclerview

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.DayCycle
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.Palette
import com.vsp.internetspeedmeter.util.PersianFormat
import androidx.core.os.ConfigurationCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Usage table row: header-colored date cell + alternating data cells in the
 * shades of the current palette, like the Internet Speed Meter Lite table.
 */
class UsageAdapter : ListAdapter<Usage, UsageAdapter.UsageViewHolder>(UsageDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val odd = position % 2 == 1
        val context = holder.itemView.context
        val rowDark = Palette.color(context, R.attr.ismRowDark)
        val rowLight = Palette.color(context, R.attr.ismRowLight)
        val rowLighter = Palette.color(context, R.attr.ismRowLighter)
        holder.bind(
            usage = getItem(position),
            todayStr = DayCycle.currentDate(holder.itemView.context),
            cellA = if (odd) rowDark else rowLight,
            cellB = if (odd) rowLight else rowLighter
        )
    }

    class UsageViewHolder(private val binding: ItemUsageRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(usage: Usage, todayStr: String, cellA: Int, cellB: Int) {
            val context = binding.root.context
            val persian = ConfigurationCompat
                .getLocales(context.resources.configuration)[0]?.language == "fa"

            binding.tvDate.text = if (persian) {
                PersianFormat.dateLabel(usage.date, todayStr)
            } else {
                englishDateLabel(usage.date, todayStr, context.getString(R.string.row_today))
            }
            binding.tvMobile.text = formatCell(usage.mobile, persian)
            binding.tvWifi.text = formatCell(usage.wifi, persian)
            binding.tvTotal.text = formatCell(usage.total, persian)

            binding.tvMobile.setBackgroundColor(cellA)
            binding.tvWifi.setBackgroundColor(cellB)
            binding.tvTotal.setBackgroundColor(cellA)
        }
    }

    companion object {
        private val dbDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        private val uiDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

        private fun formatCell(bytes: Long, persian: Boolean): String =
            if (persian) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)

        private fun englishDateLabel(dbDate: String, todayStr: String, todayLabel: String): String {
            if (dbDate == todayStr) return todayLabel
            return try {
                val parsed = dbDateFormat.parse(dbDate) ?: return dbDate
                uiDateFormat.format(parsed)
            } catch (_: Exception) {
                dbDate
            }
        }
    }

    object UsageDiffCallback : DiffUtil.ItemCallback<Usage>() {
        override fun areItemsTheSame(oldItem: Usage, newItem: Usage) = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: Usage, newItem: Usage) = oldItem == newItem
    }
}
