package com.voicetotext.app

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class MeetingSegmentAdapter(
    private val items: MutableList<MeetingSegment>,
    private val onClick: (MeetingSegment) -> Unit
) : RecyclerView.Adapter<MeetingSegmentAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val timeText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.GRAY)
        }
        val contentText = TextView(ctx).apply {
            textSize = 14f
            maxLines = 3
        }
        val indexBadge = TextView(ctx).apply {
            textSize = 10f
            setTextColor(android.graphics.Color.rgb(100, 150, 255))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(indexBadge, LinearLayout.LayoutParams(-2, -2))
            addView(timeText, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = 8
            })
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(android.graphics.Color.argb(15, 100, 150, 255))
            addView(header)
            addView(contentText)
            setOnClickListener {  }
        }
        // 底部间距
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 4
        return ViewHolder(root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val root = holder.itemView as LinearLayout
        val header = root.getChildAt(0) as LinearLayout
        val indexBadge = header.getChildAt(0) as TextView
        val timeText = header.getChildAt(1) as TextView
        val contentText = root.getChildAt(1) as TextView

        indexBadge.text = "#${position + 1}"
        timeText.text = item.time
        contentText.text = item.text
        root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
