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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.vosk.Recognizer
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnMeeting: Button
    private lateinit var btnModel: Button  // 模型选择按钮
    private lateinit var etResult: EditText
    private lateinit var tvPartial: TextView
    private lateinit var btnHistory: Button

    // 会议记录分段列表
    private lateinit var rvSegments: RecyclerView
    private var segments = mutableListOf<MeetingSegment>()
    private lateinit var segmentAdapter: MeetingSegmentAdapter
    private lateinit var tvEmptySegments: TextView
    private lateinit var layoutSegments: LinearLayout

    // 录音
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isMeetingMode = false
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

        // 标题 + 状态 + 当前模型
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TextView(this).apply {
            text = "语音转文字"
            textSize = 18f
            titleRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        tvStatus = TextView(this).apply {
            text = "加载中..."
            textSize = 13f
            gravity = android.view.Gravity.END
        }
        titleRow.addView(tvStatus)
        root.addView(titleRow)

        // 录音模式切换 + 录音按钮
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRecord = Button(this).apply {
            text = "🎤 录音"
            textSize = 16f
            setOnClickListener {
                if (isRecording) stopRecording() else startNormalRecording()
            }
            modeRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btnMeeting = Button(this).apply {
            text = "👥 会议"
            textSize = 16f
            setOnClickListener {
                if (isRecording) stopRecording() else startMeetingRecording()
            }
            modeRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(modeRow)

        // 实时中间结果
        tvPartial = TextView(this).apply {
            text = "（准备就绪）"
            textSize = 13f
            setTextColor(android.graphics.Color.GRAY)
            minLines = 1
            maxLines = 2
        }
        root.addView(tvPartial)

        // 编辑按钮行
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "复制"
            textSize = 13f
            setOnClickListener { copyText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "保存"
            textSize = 13f
            setOnClickListener { saveText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "追加"
            textSize = 13f
            setOnClickListener { appendText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "清空"
            textSize = 13f
            setOnClickListener { clearText() }
            row1.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row1)

        // 第二行：文件识别 + 模型 + 历史
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "📁 文件"
            textSize = 13f
            setOnClickListener { pickAudioFile() }
            row2.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btnModel = Button(this).apply {
            text = "📦 模型"
            textSize = 13f
            setOnClickListener { showModelChooser() }
            row2.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btnHistory = Button(this).apply {
            text = "📄 历史 (0)"
            textSize = 13f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }
            row2.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row2)

        // 识别结果编辑框（普通模式用）
        etResult = EditText(this).apply {
            hint = "识别结果（可编辑）"
            gravity = android.view.Gravity.TOP
            textSize = 15f
        }
        root.addView(etResult, LinearLayout.LayoutParams(-1, 300))

        // ===== 会议分段区域 =====
        layoutSegments = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 分段标题 + 操作
        val segHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TextView(this).apply {
            text = "会议分段"
            textSize = 15f
            segHeader.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Button(this).apply {
            text = "导出全部"
            textSize = 12f
            setOnClickListener { exportMeetingSegments() }
            segHeader.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        layoutSegments.addView(segHeader)

        // 空状态
        tvEmptySegments = TextView(this).apply {
            text = "（会议模式下自动分段显示）"
            textSize = 13f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
            minHeight = 80
        }
        layoutSegments.addView(tvEmptySegments)

        // 分段列表
        segmentAdapter = MeetingSegmentAdapter(segments) { seg ->
            // 点击分段，把文字填入编辑框
            etResult.setText(seg.text)
            currentText = seg.text
            tvPartial.text = "📍 已选择第${segments.indexOf(seg) + 1}段"
        }
        rvSegments = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = segmentAdapter
            minimumHeight = 200
        }
        layoutSegments.addView(rvSegments, LinearLayout.LayoutParams(-1, 0, 1f))

        // 默认隐藏会议分段区域
        layoutSegments.visibility = android.view.View.GONE
        root.addView(layoutSegments)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun updateSegmentsVisibility() {
        layoutSegments.visibility = if (isMeetingMode || segments.isNotEmpty())
            android.view.View.VISIBLE else android.view.View.GONE
        tvEmptySegments.visibility = if (segments.isEmpty())
            android.view.View.VISIBLE else android.view.View.GONE
        rvSegments.visibility = if (segments.isEmpty())
            android.view.View.GONE else android.view.View.VISIBLE
        etResult.visibility = if (isMeetingMode)
            android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        btnHistory.text = "📄 历史 (${history.size})"
    }

    // ===================== 模型初始化 =====================

    private fun initModel() {
        if (modelLoading || isModelReady) return
        modelLoading = true

        // 读取上次使用的模型
        val modelId = ModelManager.getCurrentModelId(this)
        val modelInfo = ModelManager.getModelInfo(modelId)

        tvStatus.text = "⌛ 加载模型中..."
        tvPartial.text = if (modelInfo != null) "模型: ${modelInfo.name}" else "准备中..."

        Thread {
            try {
                // 确保模型已解压
                if (!ModelManager.isModelExtracted(this, modelId)) {
                    runOnUiThread { tvPartial.text = "正在解压模型..." }

                    // 尝试从 assets 解压（内置模型）
                    val extracted = ModelManager.extractModelFromAssets(this, modelId)
                    if (!extracted) {
                        runOnUiThread {
                            modelLoading = false
                            tvStatus.text = "❌ 模型未安装"
                            tvPartial.text = "点击 📦 模型 按钮下载或导入"
                        }
                        return@Thread
                    }
                }

                val modelDir = File(ModelManager.getModelDir(this), modelId)
                val ok = voskEngine.loadModel(modelDir.absolutePath)
                runOnUiThread {
                    modelLoading = false
                    if (ok) {
                        isModelReady = true
                        tvStatus.text = "✅ 就绪"
                        tvPartial.text = "（点击录音或会议按钮开始）"
                        btnModel.text = "📦 ${modelInfo?.lang ?: "模型"}"
                    } else {
                        tvStatus.text = "❌ 加载失败"
                        tvPartial.text = "模型文件异常: $modelId"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelLoading = false
                    tvStatus.text = "❌ 初始化失败"
                    tvPartial.text = e.message ?: "未知错误"
                }
            }
        }.start()
    }

    // ===================== 普通录音 =====================

    private fun startNormalRecording() {
        if (!isModelReady) {
            Toast.makeText(this, "模型尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        isMeetingMode = false
        updateSegmentsVisibility()
        startAudioCapture()
    }

    // ===================== 会议模式 =====================

    private fun startMeetingRecording() {
        if (!isModelReady) {
            Toast.makeText(this, "模型尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        isMeetingMode = true
        segments.clear()
        segmentAdapter.notifyDataSetChanged()
        updateSegmentsVisibility()

        tvStatus.text = "👥 会议模式"
        tvPartial.text = "（等待发言...）"
        startAudioCapture()
    }

    // ===================== 录音核心 =====================

    private fun startAudioCapture() {
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

            voskEngine.closeRecognizer()
            currentRecognizer = voskEngine.createRecognizer(true)

            if (isMeetingMode) {
                // 会议模式：启用端指针，自动切分段落
                currentRecognizer?.setEndpointerMode(Recognizer.EndpointerMode.LONG)
                // 设置较短的静音判定时间（ms）
                currentRecognizer?.setEndpointerDelays(1000f, 500f, 5000f)
            }

            audioRecord?.startRecording()
            isRecording = true

            if (isMeetingMode) {
                btnMeeting.text = "⏹ 停止会议"
                btnRecord.isEnabled = false
                tvStatus.text = "👥 会议录音中..."
            } else {
                btnRecord.text = "⏹ 停止录音"
                btnMeeting.isEnabled = false
                tvStatus.text = "🎤 录音中..."
            }
            tvPartial.text = "（等待语音输入...）"

            // 识别线程
            recognizeThread = Thread {
                val buffer = ByteArray(bufferSize)
                val rec = currentRecognizer
                var lastPartialText = ""

                while (isRecording && rec != null) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        rec.acceptWaveForm(buffer, bytesRead)

                        // 实时获取中间结果（两种模式都显示）
                        val partial = rec.getPartialResult()
                        val partialText = voskEngine.extractText(partial)
                        if (partialText.isNotEmpty() && partialText != lastPartialText) {
                            lastPartialText = partialText
                            runOnUiThread {
                                tvPartial.text = "💬 $partialText"
                            }
                        }

                        // acceptWaveForm 返回 true 时，有一段完整的话语识别完成
                        // 但注意：不开启 endpointer 时，它只会在缓冲区满时返回 true
                        // 所以停止时获取 getFinalResult 才是完整结果
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

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        btnRecord.isEnabled = true
        btnMeeting.isEnabled = true

        if (isMeetingMode) {
            btnMeeting.text = "👥 会议"
            tvStatus.text = "✅ 会议结束"
            tvPartial.text = "共 ${segments.size} 段记录"
        } else {
            btnRecord.text = "🎤 录音"
            tvStatus.text = "✅ 就绪"
            tvPartial.text = "（点击录音或会议按钮开始）"
        }

        // 等待识别线程结束
        recognizeThread?.join(2000)

        try {
            // 获取最终结果
            val finalJson = currentRecognizer?.getFinalResult() ?: "{}"
            val finalText = voskEngine.extractText(finalJson)

            if (isMeetingMode) {
                // 会议模式：检查是否有残留分段
                if (finalText.isNotEmpty()) {
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    segments.add(MeetingSegment(ts, finalText))
                    segmentAdapter.notifyItemInserted(segments.size - 1)
                    updateSegmentsVisibility()
                }
                tvPartial.text = "共 ${segments.size} 段记录"

                // 会议模式也加入历史（所有分段合并）
                if (segments.isNotEmpty()) {
                    val fullText = segments.joinToString("\n") { "【${it.time}】${it.text}" }
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    history.add(0, RecognitionItem(ts, fullText, "会议记录"))
                    btnHistory.text = "📄 历史 (${history.size})"
                }
            } else {
                // 普通模式：最终结果是完整识别文字
                if (finalText.isNotEmpty()) {
                    currentText = finalText
                    etResult.setText(currentText)
                    tvPartial.text = "✅ 识别完成 (${finalText.length}字)"
                } else {
                    tvPartial.text = "（未检测到语音）"
                }

                if (currentText.isNotEmpty()) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    history.add(0, RecognitionItem(ts, currentText, "录音识别"))
                    btnHistory.text = "📄 历史 (${history.size})"
                }
            }
        } catch (_: Exception) {}

        voskEngine.closeRecognizer()
        currentRecognizer = null
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
                val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}")
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                val wavFile = File(cacheDir, "converted_${System.currentTimeMillis()}.wav")
                convertToWav(cacheFile.absolutePath, wavFile.absolutePath)
                val result = voskEngine.recognizeFile(wavFile)
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
                        btnHistory.text = "📄 历史 (${history.size})"
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
            val buf = ByteBuffer.allocate(65536)
            var bytesRead: Int
            while (extractor.readSampleData(buf, 0).also { bytesRead = it } >= 0) {
                val chunk = ByteArray(bytesRead)
                buf.rewind()
                buf.get(chunk, 0, bytesRead)
                byteArrayOutputStream.write(chunk)
                extractor.advance()
                buf.clear()
            }
            writeWavFile(outputPath, byteArrayOutputStream.toByteArray(), 16000)
        } finally {
            extractor.release()
        }
    }

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
        val textToSave = if (segments.isNotEmpty()) {
            segments.joinToString("\n\n") { "【${it.time}】${it.text}" }
        } else {
            etResult.text.toString()
        }
        if (textToSave.isEmpty()) { Toast.makeText(this, "没有文字", Toast.LENGTH_SHORT).show(); return }
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val name = if (segments.isNotEmpty()) "meeting_" else "voice_"
            val f = File(dir, "${name}${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
            f.writeText(textToSave)
            Toast.makeText(this, "已保存: ${f.name}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show() }
    }

    private fun appendText() {
        etResult.setSelection(etResult.text.length)
        etResult.requestFocus()
    }

    private fun clearText() {
        etResult.text.clear(); currentText = ""
        segments.clear()
        segmentAdapter.notifyDataSetChanged()
        updateSegmentsVisibility()
        tvPartial.text = "（已清空）"
    }

    private fun exportMeetingSegments() {
        if (segments.isEmpty()) { Toast.makeText(this, "没有会议记录", Toast.LENGTH_SHORT).show(); return }
        saveText()
    }

    // ===================== 模型选择 =====================

    private fun showModelChooser() {
        if (isRecording) {
            Toast.makeText(this, "请先停止录音", Toast.LENGTH_SHORT).show()
            return
        }

        val installed = ModelManager.scanInstalledModels(this)
        val currentId = ModelManager.getCurrentModelId(this)

        val items = mutableListOf<String>()
        val modelIds = mutableListOf<String>()

        // 已安装模型
        if (installed.isNotEmpty()) {
            items.add("── 已安装模型 ──")
            modelIds.add("")
            for (m in installed) {
                val mark = if (m.id == currentId) " ✓" else ""
                items.add("${m.name} (${m.lang})${mark}")
                modelIds.add(m.id)
            }
        }

        // 在线可下载
        items.add("── 在线下载 ──")
        modelIds.add("")
        for (m in ModelManager.onlineModels) {
            val isInstalled = installed.any { it.id == m.id }
            val status = if (isInstalled) " [已安装]" else ""
            items.add("${m.name} (${m.lang}, ${m.size})${status}")
            modelIds.add("download:${m.id}")
        }

        // 导入
        items.add("── 其他 ──")
        modelIds.add("")
        items.add("📂 从文件夹导入")
        modelIds.add("import")

        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(items.toTypedArray()) { _, which ->
                val action = modelIds[which]
                when {
                    action.startsWith("download:") -> {
                        val modelId = action.removePrefix("download:")
                        val model = ModelManager.onlineModels.find { it.id == modelId }
                        if (model != null) downloadAndSwitchModel(model)
                    }
                    action == "import" -> importAndSwitchModel()
                    action.isNotEmpty() -> switchModel(action)
                }
            }
            .show()
    }

    private fun downloadAndSwitchModel(model: OnlineModel) {
        // 检查是否已安装
        if (ModelManager.isModelExtracted(this, model.id)) {
            switchModel(model.id)
            return
        }

        tvStatus.text = "⏳ 下载中..."
        tvPartial.text = "正在下载 ${model.name}..."

        ModelManager.downloadModel(this, model, object : ModelManager.DownloadListener {
            override fun onProgress(percent: Int) {
                runOnUiThread { tvPartial.text = "下载中: $percent%" }
            }
            override fun onComplete(result: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, result, Toast.LENGTH_SHORT).show()
                    if (result.startsWith("成功")) {
                        switchModel(model.id)
                    } else {
                        tvStatus.text = "❌ 下载失败"
                        tvPartial.text = result
                    }
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    tvStatus.text = "❌ 下载失败"
                    tvPartial.text = error
                }
            }
        })
    }

    private fun importAndSwitchModel() {
        tvStatus.text = "⏳ 正在扫描导入..."
        tvPartial.text = "检查 ${ModelManager.IMPORT_DIR} 目录..."

        Thread {
            val results = ModelManager.importFromDirectory(this)
            runOnUiThread {
                if (results.isEmpty()) {
                    Toast.makeText(this, "未找到模型文件，请放入 ${ModelManager.getImportDir(this).absolutePath}", Toast.LENGTH_LONG).show()
                    tvStatus.text = "✅ 就绪"
                    tvPartial.text = "（导入目录为空）"
                } else {
                    for (r in results) {
                        Toast.makeText(this, r, Toast.LENGTH_SHORT).show()
                        if (r.startsWith("成功")) {
                            val modelId = r.removePrefix("成功: ").substringBefore(" ")
                            switchModel(modelId)
                        }
                    }
                }
            }
        }.start()
    }

    private fun switchModel(modelId: String) {
        if (modelId == ModelManager.getCurrentModelId(this) && isModelReady) {
            Toast.makeText(this, "已是当前模型", Toast.LENGTH_SHORT).show()
            return
        }

        ModelManager.setCurrentModelId(this, modelId)
        isModelReady = false
        modelLoading = false

        // 重新初始化
        voskEngine.release()
        initModel()
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
data class MeetingSegment(val time: String, val text: String)
