package com.vsp.internetspeedmeter.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.databinding.ItemAppRowBinding

/** One app (or hotspot) with its usage and a bar relative to the largest row. */
class AppRow(
    val uid: Int,
    val label: String,
    val icon: Drawable?,
    val bytes: Long,
    val fraction: Float,
    /** Used the network since the previous refresh. */
    val active: Boolean = false
)

class AppRowAdapter(private val onClick: ((AppRow) -> Unit)? = null) :
    ListAdapter<AppRow, AppRowAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val b = holder.b
        b.tvName.text = row.label
        b.tvUsage.text = Fmt.bytes(b.root.context, row.bytes)
        b.tvActive.visibility = if (row.active) View.VISIBLE else View.GONE
        b.progressUsage.setProgressCompat((row.fraction * 1000).toInt().coerceIn(0, 1000), false)
        if (row.icon != null) b.ivIcon.setImageDrawable(row.icon) else b.ivIcon.setImageDrawable(null)
        if (onClick != null) b.root.setOnClickListener { onClick.invoke(row) }
        else b.root.isClickable = false
    }

    class Holder(val b: ItemAppRowBinding) : RecyclerView.ViewHolder(b.root)

    private object Diff : DiffUtil.ItemCallback<AppRow>() {
        override fun areItemsTheSame(a: AppRow, b: AppRow) = a.uid == b.uid
        override fun areContentsTheSame(a: AppRow, b: AppRow) =
            a.bytes == b.bytes && a.active == b.active && a.label == b.label && a.fraction == b.fraction
    }

    companion object {
        /** Rows for [totals] (bytes per uid), largest first; uids without a name are dropped. */
        fun rows(
            context: android.content.Context,
            totals: Map<Int, Long>,
            previous: Map<Int, Long>? = null,
            limit: Int = Int.MAX_VALUE
        ): List<AppRow> {
            val named = totals.filter { it.value > 0L }.mapNotNull { (uid, bytes) ->
                val info = AppNetworkStats.appInfo(context, uid) ?: return@mapNotNull null
                val active = previous != null && bytes > (previous[uid] ?: 0L)
                Triple(uid, info, bytes) to active
            }
            val max = named.maxOfOrNull { it.first.third }?.coerceAtLeast(1L) ?: 1L
            return named
                .sortedWith(compareByDescending<Pair<Triple<Int, AppNetworkStats.AppInfo, Long>, Boolean>> { it.second }
                    .thenByDescending { it.first.third })
                .take(limit)
                .map { (t, active) ->
                    AppRow(t.first, t.second.label, t.second.icon, t.third, t.third.toFloat() / max, active)
                }
        }
    }
}
