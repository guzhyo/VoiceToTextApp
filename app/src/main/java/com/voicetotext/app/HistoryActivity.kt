package com.voicetotext.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private var displayList = mutableListOf<RecognitionItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 顶部栏：返回 + 标题 + 操作
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "← 返回"
            textSize = 13f
            setOnClickListener { finish() }
            topBar.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        tvCount = TextView(this).apply {
            text = "历史记录 (0)"
            textSize = 18f
        }.also {
            topBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = android.view.Gravity.CENTER
            })
        }
        Button(this).apply {
            text = "导出"
            textSize = 13f
            setOnClickListener { exportAll() }
            topBar.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(topBar)

        // 搜索框
        etSearch = EditText(this).apply {
            hint = "搜索历史记录..."
            textSize = 13f
            setSingleLine()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterHistory(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        root.addView(etSearch)

        // 操作按钮行
        val opRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "清空全部"
            textSize = 13f
            setOnClickListener { confirmClearAll() }
            opRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "全选复制"
            textSize = 13f
            setOnClickListener { copyAll() }
            opRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(opRow)

        // 空状态
        tvEmpty = TextView(this).apply {
            text = "暂无历史记录"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(tvEmpty, LinearLayout.LayoutParams(-1, -1, 1f))

        // 历史列表
        displayList.clear()
        displayList.addAll(MainActivity.history)
        historyAdapter = HistoryAdapter(displayList) { item ->
            AlertDialog.Builder(this)
                .setTitle("操作")
                .setMessage(item.text.take(100))
                .setPositiveButton("复制") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("text", item.text))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("删除") { _, _ ->
                    confirmDeleteItem(item)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        rvHistory = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        root.addView(rvHistory, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun updateUI() {
        val size = MainActivity.history.size
        tvCount.text = "历史记录 ($size)"
        filterHistory(etSearch.text.toString())
    }

    private fun filterHistory(query: String) {
        displayList.clear()
        displayList.addAll(
            if (query.isEmpty()) MainActivity.history
            else MainActivity.history.filter {
                it.text.contains(query, ignoreCase = true) ||
                it.timestamp.contains(query, ignoreCase = true)
            }
        )
        historyAdapter.notifyDataSetChanged()
        tvEmpty.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
        rvHistory.visibility = if (displayList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteItem(item: RecognitionItem) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("删除这条记录？\n${item.text.take(50)}...")
            .setPositiveButton("删除") { _, _ ->
                MainActivity.history.remove(item)
                updateUI()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        if (MainActivity.history.isEmpty()) {
            Toast.makeText(this, "没有历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空全部")
            .setMessage("确定删除所有 ${MainActivity.history.size} 条记录？")
            .setPositiveButton("清空") { _, _ ->
                MainActivity.history.clear()
                updateUI()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyAll() {
        if (MainActivity.history.isEmpty()) {
            Toast.makeText(this, "没有历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        val text = MainActivity.history.joinToString("\n---\n") { "${it.timestamp} [${it.source}]\n${it.text}" }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, "已复制全部 ${MainActivity.history.size} 条", Toast.LENGTH_SHORT).show()
    }

    private fun exportAll() {
        if (MainActivity.history.isEmpty()) {
            Toast.makeText(this, "没有历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val f = File(dir,
                "history_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            )
            val content = MainActivity.history.joinToString("\n\n=====\n\n") {
                "【${it.timestamp}】来源: ${it.source}\n${it.text}"
            }
            f.writeText(content)
            Toast.makeText(this, "已导出: ${f.name} (${content.length}字)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
