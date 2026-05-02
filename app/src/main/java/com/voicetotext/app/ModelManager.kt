package com.voicetotext.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.TarInputStream

/**
 * 模型管理 — 从 assets 解压模型、管理模型目录
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val MODELS_DIR = "vosk_models"

    /** 可用模型列表 */
    val availableModels = listOf(
        ModelInfo(
            id = "vosk-model-small-cn-0.22",
            name = "中文（小模型）",
            size = "~66MB",
            assetPath = "models/vosk-model-small-cn-0.22.tar.gz"
        )
    )

    /** 获取模型在 filesDir 中的实际路径 */
    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODELS_DIR)
    }

    /** 检查模型是否已解压 */
    fun isModelExtracted(context: Context, modelId: String): Boolean {
        val modelDir = File(getModelDir(context), modelId)
        return modelDir.exists() && File(modelDir, "am").exists()
    }

    /** 从 assets 解压模型（tar.gz）到 filesDir */
    fun extractModelFromAssets(context: Context, modelId: String): Boolean {
        val destDir = File(getModelDir(context), modelId)
        if (destDir.exists()) {
            Log.i(TAG, "模型已存在，跳过解压: $modelId")
            return true
        }

        val assetPath = "models/$modelId.tar.gz"
        return try {
            destDir.mkdirs()

            // 从 assets 打开 tar.gz 文件
            context.assets.open(assetPath).use { input ->
                GZIPInputStream(input).use { gzip ->
                    TarInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            // tar 包里的路径可能是 vosk-model-small-cn-0.22/am/final.mdl
                            // 去掉顶层目录
                            val relativeName = name.substringAfter("/")
                            if (relativeName.isEmpty()) {
                                entry = tar.nextEntry
                                continue
                            }

                            val outFile = File(destDir, relativeName)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    tar.copyTo(out)
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            // 验证解压结果
            val amFile = File(destDir, "am/final.mdl")
            if (!amFile.exists()) {
                Log.e(TAG, "模型解压后关键文件缺失: $modelId")
                destDir.deleteRecursively()
                return false
            }

            Log.i(TAG, "模型解压完成: $modelId -> ${destDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型解压失败: ${e.message}", e)
            destDir.deleteRecursively()
            false
        }
    }

    /** 删除模型 */
    fun deleteModel(context: Context, modelId: String): Boolean {
        val modelDir = File(getModelDir(context), modelId)
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else {
            false
        }
    }

    /** 获取模型占用空间 */
    fun getModelSize(context: Context, modelId: String): Long {
        val modelDir = File(getModelDir(context), modelId)
        if (!modelDir.exists()) return 0
        return modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

data class ModelInfo(
    val id: String,
    val name: String,
    val size: String,
    val assetPath: String
)
