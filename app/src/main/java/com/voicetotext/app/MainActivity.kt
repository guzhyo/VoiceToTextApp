package com.voicetotext.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.vosk.Recognizer
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var etResult: EditText
    private lateinit var tvPartial: TextView  // 实时中间结果
    private lateinit var btnHistory: Button

    // 录音
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recognizeThread: Thread? = null
    private var currentRecognizer: Recognizer? = null

    // 引擎
    private val voskEngine = VoskEngine()
    private var isModelReady = false
    private var modelLoading = false
    private var currentText = ""

    // 文件选择器
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> recognizeAudioFile(uri) }
        }
    }

    companion object {
        val history = mutableListOf<RecognitionItem>()
        private const val SAMPLE_RATE = 16000
        private const val REQUEST_RECORD_AUDIO = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        checkPermissions()
        initModel()
    }

    // ===================== UI 构建 =====================

    private fun buildUI() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 标题 + 状态
        tvStatus = TextView(this).apply {
            text = "📝 语音转文字 v2.0"
            textSize = 20f
        }
        root.addView(tvStatus)

        // 录音按钮（大）
        btnRecord = Button(this).apply {
            text = "🎤 开始录音"
            textSize = 24f
            setOnClickListener {
                if (isRecording) stopRecording() else startRecording()
            }
        }.also {
            root.addView(it, LinearLayout.LayoutParams(-1, 120))
        }

        // 实时中间结果
        tvPartial = TextView(this).apply {
            text = "（准备就绪）"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            minLines = 1
            maxLines = 2
        }
        root.addView(tvPartial)

        // 编辑按钮行：复制 | 保存 | 追加 | 清空
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "📋 复制"
            setOnClickListener { copyText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "💾 保存"
            setOnClickListener { saveText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "✏️ 追加"
            setOnClickListener { appendText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "🗑️ 清空"
            setOnClickListener { clearText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row1)

        // 第二行：文件识别 + 历史
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "📁 选择文件识别"
            setOnClickListener { pickAudioFile() }
            row2.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btnHistory = Button(this).apply {
            text = "📄 历史记录 (0)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }
            row2.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row2)

        // 识别结果编辑框
        etResult = EditText(this).apply {
            hint = "识别结果（可编辑）"
            gravity = android.view.Gravity.TOP
            textSize = 16f
        }
        root.addView(etResult, LinearLayout.LayoutParams(-1, 400))

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        btnHistory.text = "📄 历史记录 (${history.size})"
    }

    // ===================== 模型初始化 =====================

    private fun initModel() {
        if (modelLoading || isModelReady) return
        modelLoading = true
        tvStatus.text = "⌛ 加载语音模型中..."
        tvPartial.text = "首次启动需要解压模型，请稍候..."

        Thread {
            try {
                ModelManager.extractModelFromAssets(this, ModelManager.availableModels[0].id)
                val modelDir = File(ModelManager.getModelDir(this), ModelManager.availableModels[0].id)
                val ok = voskEngine.loadModel(modelDir.absolutePath)
                runOnUiThread {
                    modelLoading = false
                    if (ok) {
                        isModelReady = true
                        tvStatus.text = "✅ 模型就绪"
                        tvPartial.text = "（点击录音按钮开始）"
                    } else {
                        tvStatus.text = "❌ 模型加载失败"
                        tvPartial.text = "请检查模型文件"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelLoading = false
                    tvStatus.text = "❌ 模型初始化失败"
                    tvPartial.text = e.message ?: "未知错误"
                }
            }
        }.start()
    }

    // ===================== 录音 =====================

    private fun startRecording() {
        if (!isModelReady) {
            Toast.makeText(this, "模型尚未就绪，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            // 创建新 Recognizer
            voskEngine.closeRecognizer()
            currentRecognizer = voskEngine.createRecognizer(true)

            audioRecord?.startRecording()
            isRecording = true
            btnRecord.text = "⏹ 停止录音"
            tvStatus.text = "🎤 录音中..."
            tvPartial.text = "（等待语音输入...）"
            etResult.hint = "识别中..."

            // 实时识别线程
            recognizeThread = Thread {
                val buffer = ByteArray(bufferSize)
                val rec = currentRecognizer
                while (isRecording && rec != null) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        if (rec.acceptWaveform(buffer, bytesRead)) {
                            val partial = rec.partialResult
                            val text = voskEngine.extractText(partial)
                            if (text.isNotEmpty()) {
                                runOnUiThread {
                                    tvPartial.text = "💬 $text"
                                }
                            }
                        }
                    }
                }
            }
            recognizeThread?.start()

        } catch (e: Exception) {
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // 停止录音硬件
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        btnRecord.text = "🎤 开始录音"
        tvStatus.text = "⏳ 处理识别结果..."
        tvPartial.text = "（正在完成识别...）"

        // 等待识别线程结束
        recognizeThread?.join(2000)

        try {
            val finalJson = currentRecognizer?.finalResult ?: "{}"
            val finalText = voskEngine.extractText(finalJson)

            val partialJson = currentRecognizer?.partialResult ?: "{}"
            val partialText = voskEngine.extractText(partialJson)

            val resultText = if (finalText.isNotEmpty()) finalText else partialText

            if (resultText.isNotEmpty()) {
                currentText = resultText
                etResult.setText(currentText)
                tvPartial.text = "✅ 识别完成 (${resultText.length}字)"
                tvStatus.text = "✅ 就绪"

                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                history.add(0, RecognitionItem(ts, resultText, "录音识别"))
                btnHistory.text = "📄 历史记录 (${history.size})"
            } else {
                tvPartial.text = "（未检测到语音）"
                tvStatus.text = "就绪"
            }
        } catch (e: Exception) {
            tvPartial.text = "（识别出错）"
            tvStatus.text = "就绪"
        } finally {
            voskEngine.closeRecognizer()
            currentRecognizer = null
        }
    }

    // ===================== 文件识别 =====================

    private fun pickAudioFile() {
        if (!isModelReady) {
            Toast.makeText(this, "模型尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        pickAudioLauncher.launch(intent)
    }

    private fun recognizeAudioFile(uri: Uri) {
        tvStatus.text = "⏳ 正在识别音频文件..."
        tvPartial.text = "（将音频转为 WAV...）"

        Thread {
            try {
                // 复制到缓存
                val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}")
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 转 WAV
                val wavFile = File(cacheDir, "converted_${System.currentTimeMillis()}.wav")
                convertToWav(cacheFile.absolutePath, wavFile.absolutePath)

                // 识别
                val result = voskEngine.recognizeFile(wavFile)

                // 清理
                cacheFile.delete()
                wavFile.delete()

                runOnUiThread {
                    if (result != null && result.isNotEmpty()) {
                        currentText = result
                        etResult.setText(currentText)
                        tvStatus.text = "✅ 文件识别完成"
                        tvPartial.text = "（${result.length}字）"

                        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        history.add(0, RecognitionItem(ts, result, "文件识别"))
                        btnHistory.text = "📄 历史记录 (${history.size})"
                    } else {
                        tvStatus.text = "⚠️ 文件识别未出结果"
                        tvPartial.text = "（可能是格式不支持或语音内容为空）"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 文件识别失败"
                    tvPartial.text = e.message ?: "未知错误"
                }
            }
        }.start()
    }

    /** MediaExtractor 转 WAV */
    private fun convertToWav(inputPath: String, outputPath: String) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) throw RuntimeException("未找到音频轨道")
            extractor.selectTrack(audioTrackIndex)

            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (extractor.readSampleData(buffer, 0).also { bytesRead = it } >= 0) {
                byteArrayOutputStream.write(buffer, 0, bytesRead)
                extractor.advance()
            }
            writeWavFile(outputPath, byteArrayOutputStream.toByteArray(), 16000)
        } finally {
            extractor.release()
        }
    }

    /** 写入 WAV 头 */
    private fun writeWavFile(path: String, pcmData: ByteArray, sampleRate: Int) {
        FileOutputStream(path).use { fos ->
            val dataSize = pcmData.size
            val fileSize = 36 + dataSize
            fun wLEI(v: Int) { fos.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())) }
            fun wLES(v: Int) { fos.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) }

            fos.write("RIFF".toByteArray()); wLEI(fileSize); fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray()); wLEI(16); wLES(1); wLES(1); wLEI(sampleRate)
            wLEI(sampleRate * 2); wLES(2); wLES(16)
            fos.write("data".toByteArray()); wLEI(dataSize)
            fos.write(pcmData)
        }
    }

    // ===================== 编辑功能 =====================

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
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val f = File(dir, "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
            f.writeText(t)
            Toast.makeText(this, "已保存: ${f.name}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show() }
    }

    private fun appendText() {
        etResult.setSelection(etResult.text.length)
        etResult.requestFocus()
    }

    private fun clearText() {
        etResult.text.clear(); currentText = ""
        tvPartial.text = "（已清空）"
    }

    // ===================== 权限 =====================

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    // ===================== 生命周期 =====================

    override fun onDestroy() {
        isRecording = false
        recognizeThread?.join(1000)
        voskEngine.release()
        super.onDestroy()
    }
}

data class RecognitionItem(val timestamp: String, val text: String, val source: String)
