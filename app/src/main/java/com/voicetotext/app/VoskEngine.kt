package com.voicetotext.app

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Vosk 引擎封装 — 模型加载、Recognizer 管理、实时/文件识别
 */
class VoskEngine {

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16000f
    }

    private var model: Model? = null
    private var currentModelPath: String? = null
    private var recognizer: Recognizer? = null

    /** 模型是否已加载 */
    val isModelLoaded: Boolean get() = model != null

    /** 当前模型路径 */
    val loadedModelPath: String? get() = currentModelPath

    /** 加载模型 */
    fun loadModel(modelPath: String): Boolean {
        return try {
            // 检查路径是否存在
            val modelDir = File(modelPath)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                Log.e(TAG, "模型路径不存在: $modelPath")
                return false
            }

            // 关闭旧模型
            model?.close()
            model = null
            currentModelPath = null

            model = Model(modelPath)
            currentModelPath = modelPath
            Log.i(TAG, "模型加载成功: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败: ${e.message}", e)
            false
        }
    }

    /** 创建新的 Recognizer（每次录音/识别用新的实例） */
    fun createRecognizer(granularity: Boolean = true): Recognizer? {
        recognizer?.close()
        recognizer = null
        return try {
            model?.let { m ->
                Recognizer(m, SAMPLE_RATE).also { rec ->
                    if (granularity) rec.setWords(true)
                    recognizer = rec
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 Recognizer 失败: ${e.message}")
            null
        }
    }

    /** 释放当前 Recognizer */
    fun closeRecognizer() {
        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null
    }

    /** 释放模型 */
    fun release() {
        closeRecognizer()
        try { model?.close() } catch (_: Exception) {}
        model = null
        currentModelPath = null
    }

    /** 从 JSON 结果中提取文字 */
    fun extractText(jsonResult: String): String {
        return try {
            val sb = StringBuilder()
            // 简单的 JSON 解析，提取 "text" 字段的值
            val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(jsonResult)
            textMatch?.groupValues?.getOrNull(1) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 对 WAV 文件进行识别
     * @param wavFile WAV 格式音频文件（16kHz, 16bit, mono）
     * @return 识别文字
     */
    fun recognizeFile(wavFile: File): String? {
        if (!isModelLoaded) return null
        val rec = createRecognizer(true) ?: return null
        return try {
            val fis = java.io.FileInputStream(wavFile)
            // 跳过 WAV 文件头（44 字节）
            fis.skip(44)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } >= 0) {
                rec.acceptWaveform(buffer, bytesRead)
            }
            fis.close()
            val finalResult = rec.finalResult
            extractText(finalResult)
        } catch (e: Exception) {
            Log.e(TAG, "文件识别失败: ${e.message}")
            null
        } finally {
            rec.close()
        }
    }
}
