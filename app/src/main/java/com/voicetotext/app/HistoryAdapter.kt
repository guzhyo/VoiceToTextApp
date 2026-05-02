package com.voicetotext.app

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val items: MutableList<RecognitionItem>,
    private val onClick: (RecognitionItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val title = TextView(ctx)
        title.textSize = 12f
        title.setTextColor(android.graphics.Color.GRAY)
        val text = TextView(ctx)
        text.maxLines = 2
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(8, 8, 8, 8)
        root.addView(title)
        root.addView(text)
        return ViewHolder(root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val root = holder.itemView as LinearLayout
        (root.getChildAt(0) as TextView).text = "${item.timestamp} - ${item.source}"
        (root.getChildAt(1) as TextView).text = item.text
        root.setOnClickListener { onClick(items[position]) }
    }

    override fun getItemCount() = items.size
}
