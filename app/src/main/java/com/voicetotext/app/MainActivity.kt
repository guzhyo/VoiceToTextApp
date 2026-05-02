package com.voicetotext.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etResult: EditText
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var rvHistory: RecyclerView
    private val history = mutableListOf<RecognitionItem>()
    private lateinit var historyAdapter: HistoryAdapter
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioFile: File? = null
    private var currentText = ""
    private val REQUEST_RECORD_AUDIO = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        tvStatus = TextView(this).apply {
            text = "语音转文字"
            textSize = 20f
        }
        root.addView(tvStatus)

        btnRecord = Button(this).apply {
            text = "🎤 开始录音"
            setOnClickListener {
                if (isRecording) stopRecording() else startRecording()
            }
        }
        root.addView(btnRecord)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "📋 复制"
            setOnClickListener { copyText() }
            row1.addView(this, LinearLayout.LayoutParams(0, 100, 1f))
        }
        Button(this).apply {
            text = "💾 保存"
            setOnClickListener { saveText() }
            row1.addView(this, LinearLayout.LayoutParams(0, 100, 1f))
        }
        Button(this).apply {
            text = "✏️ 追加"
            setOnClickListener { appendText() }
            row1.addView(this, LinearLayout.LayoutParams(0, 100, 1f))
        }
        Button(this).apply {
            text = "🗑️ 清空"
            setOnClickListener { clearText() }
            row1.addView(this, LinearLayout.LayoutParams(0, 100, 1f))
        }
        root.addView(row1)

        etResult = EditText(this).apply {
            hint = "识别结果"
            gravity = android.view.Gravity.TOP
        }
        root.addView(etResult, LinearLayout.LayoutParams(-1, 300))

        root.addView(TextView(this).apply { text = "历史记录" })

        historyAdapter = HistoryAdapter(history) { item ->
            etResult.setText(item.text); currentText = item.text
        }
        rvHistory = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
            minimumHeight = 200
        }
        root.addView(rvHistory)

        scroll.addView(root)
        setContentView(scroll)

        checkPermissions()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        try {
            audioFile = File(cacheDir, "rec_${System.currentTimeMillis()}.mp3")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(audioFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
            btnRecord.text = "⏹ 停止"
            tvStatus.text = "🎤 录音中..."
        } catch (e: Exception) {
            Toast.makeText(this, "录音失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            btnRecord.text = "🎤 开始"
            audioFile?.let { f ->
                if (f.exists()) {
                    currentText = "录音完成: ${f.name} (${f.length()/1024}KB)"
                    etResult.setText(currentText)
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    history.add(0, RecognitionItem(ts, currentText, "录音"))
                    historyAdapter.notifyItemInserted(0)
                }
            }
            tvStatus.text = "就绪"
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText() {
        val t = etResult.text.toString()
        if (t.isEmpty()) { Toast.makeText(this, "没有文字", Toast.LENGTH_SHORT).show(); return }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", t))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun saveText() {
        val t = etResult.text.toString()
        if (t.isEmpty()) { Toast.makeText(this, "没有文字", Toast.LENGTH_SHORT).show(); return }
        try {
            val f = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
            f.writeText(t)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show() }
    }

    private fun appendText() {
        etResult.setSelection(etResult.text.length)
        etResult.requestFocus()
    }

    private fun clearText() { etResult.text.clear(); currentText = "" }
}

data class RecognitionItem(val timestamp: String, val text: String, val source: String)
