package com.yourname.recentsstack

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentsStackAdapter(private val tasks: List<RecentTaskStub>) : RecyclerView.Adapter<RecentsStackAdapter.VH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = tasks[position]
        val title = if (!t.title.isNullOrBlank()) t.title else t.packageName
        holder.title.text = title
        holder.pkg.text = t.packageName ?: ""
    }

    override fun getItemCount(): Int = tasks.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.card_title)
        val pkg: TextView = view.findViewById(R.id.card_pkg)
    }
}