package com.vsp.internetspeedmeter.recyclerview

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.FormatUtils

data class AppUsageItem(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val bytesUsed: Long
)

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    private val items = mutableListOf<AppUsageItem>()

    fun submitList(newItems: List<AppUsageItem>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.bytesUsed })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAppName.text = item.appName
        holder.tvAppUsage.text = FormatUtils.formatBytes(item.bytesUsed)
        if (item.icon != null) {
            holder.ivAppIcon.setImageDrawable(item.icon)
        } else {
            holder.ivAppIcon.setImageResource(android.R.mipmap.sym_def_app_icon)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvAppName: TextView = view.findViewById(R.id.tv_app_name)
        val tvAppUsage: TextView = view.findViewById(R.id.tv_app_usage)
    }
}
