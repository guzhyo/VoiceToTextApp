package com.voicetotext.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 顶部栏：返回按钮 + 标题
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        Button(this).apply {
            text = "\u2190 返回"
            setOnClickListener { finish() }
            topBar.addView(this, LinearLayout.LayoutParams(-2, -2))
        }

        TextView(this).apply {
            text = "历史记录"
            textSize = 20f
        }.also {
            topBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = android.view.Gravity.CENTER
            })
        }

        Button(this).apply {
            text = "\uD83D\uDDD1\uFE0F 清空全部"
            setOnClickListener {
                if (MainActivity.history.isEmpty()) {
                    Toast.makeText(this@HistoryActivity, "没有历史记录", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                MainActivity.history.clear()
                historyAdapter.notifyDataSetChanged()
                updateEmptyState()
                Toast.makeText(this@HistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
            topBar.addView(this, LinearLayout.LayoutParams(-2, -2))
        }

        root.addView(topBar)

        // 空状态提示
        tvEmpty = TextView(this).apply {
            text = "暂无历史记录"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = if (MainActivity.history.isEmpty()) View.VISIBLE else View.GONE
        }
        root.addView(tvEmpty, LinearLayout.LayoutParams(-1, -1, 1f))

        // 历史列表
        historyAdapter = HistoryAdapter(MainActivity.history) { item: RecognitionItem ->
            // 点击复制文字到剪贴板
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("text", item.text))
            Toast.makeText(this, "已复制: ${item.text.take(20)}...", Toast.LENGTH_SHORT).show()
        }
        rvHistory = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        root.addView(rvHistory, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun updateEmptyState() {
        tvEmpty.visibility = if (MainActivity.history.isEmpty()) View.VISIBLE else View.GONE
        rvHistory.visibility = if (MainActivity.history.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        historyAdapter.notifyDataSetChanged()
        updateEmptyState()
    }
}
