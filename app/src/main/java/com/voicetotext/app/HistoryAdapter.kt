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

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val titleView: TextView = (root as LinearLayout).getChildAt(0) as TextView
        val textView: TextView = root.getChildAt(1) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        root.addView(TextView(ctx).apply { textSize = 12f; setTextColor(android.graphics.Color.GRAY) })
        root.addView(TextView(ctx).apply { maxLines = 2 })
        return ViewHolder(root).also { vh ->
            root.setOnClickListener {
                val pos = vh.layoutPosition
                if (pos >= 0 && pos < items.size) onClick(items[pos])
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = "${item.timestamp} - ${item.source}"
        holder.textView.text = item.text
    }

    override fun getItemCount() = items.size
}
