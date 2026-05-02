package com.voicetotext.app

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val items: MutableList<RecognitionItem>,
    private val onClick: (RecognitionItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(val titleView: TextView, val textView: TextView) : RecyclerView.ViewHolder(titleView.parent as android.view.View)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        val title = TextView(ctx).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        val text = TextView(ctx).apply { maxLines = 2 }
        root.addView(title)
        root.addView(text)
        return ViewHolder(title, text).also { root.setOnClickListener { onClick(items[it.adapterPosition]) } }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = "${item.timestamp} - ${item.source}"
        holder.textView.text = item.text
    }

    override fun getItemCount() = items.size
}
