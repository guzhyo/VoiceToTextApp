package com.voicetotext.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * 模型管理 — 解压、下载、扫描、切换
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val MODELS_DIR = "vosk_models"
    const val IMPORT_DIR = "VoskModels"
    private const val DOWNLOAD_DIR = "VoskDownloads"
    private const val PREFS_NAME = "model_prefs"
    private const val KEY_CURRENT_MODEL = "current_model_id"
    private const val DEFAULT_MODEL_ID = "vosk-model-small-cn-0.22"

    /** 在线模型列表 */
    val onlineModels = listOf(
        OnlineModel(
            id = "vosk-model-small-cn-0.22",
            name = "中文（小）",
            size = "~66MB",
            lang = "中文",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        ),
        OnlineModel(
            id = "vosk-model-small-en-us-0.15",
            name = "English (small)",
            size = "~42MB",
            lang = "English",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        ),
        OnlineModel(
            id = "vosk-model-cn-0.22",
            name = "中文（大）",
            size = "~2GB",
            lang = "中文",
            url = "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"
        ),
        OnlineModel(
            id = "vosk-model-en-us-0.22",
            name = "English (large)",
            size = "~1.8GB",
            lang = "English",
            url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"
        )
    )

    /** 获取当前使用的模型 ID */
    fun getCurrentModelId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
    }

    /** 设置当前使用的模型 ID */
    fun setCurrentModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT_MODEL, modelId).apply()
    }

    /** 获取模型信息（从在线列表或本地扫描） */
    fun getModelInfo(modelId: String): OnlineModel? {
        return onlineModels.find { it.id == modelId }
    }

    /** 检查模型是否已安装（含自定义路径） */
    fun isModelExtracted(context: Context, modelId: String): Boolean {
        if (modelId == "_custom_") {
            return getCustomModelPath(context) != null
        }
        val dir = File(getModelDir(context), modelId)
        return dir.exists() && File(dir, "am").exists()
    }

    /** 获取模型实际目录路径（支持自定义路径） */
    fun getModelPath(context: Context, modelId: String): String? {
        return when {
            modelId == "_custom_" -> getCustomModelPath(context)
            else -> {
                val dir = File(getModelDir(context), modelId)
                if (dir.exists() && File(dir, "am").exists()) dir.absolutePath else null
            }
        }
    }

    /** 从 assets 解压模型（兼容旧调用名） */
    fun extractModelFromAssets(context: Context, modelId: String): Boolean {
        return extractAssetModel(context, modelId)
    }

    private const val PREFS_CUSTOM_PATH = "custom_model_path"

    /** 获取模型存储根目录 */
    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODELS_DIR)
    }

    /** 获取或设置自定义模型路径 — 指向手机上任一已解压的模型目录 */
    fun getCustomModelPath(context: Context): String? {
        val path = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_CUSTOM_PATH, null)
        if (path != null) {
            val dir = File(path)
            if (dir.exists() && File(dir, "am").exists()) return path
        }
        return null
    }

    fun setCustomModelPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREFS_CUSTOM_PATH, path).apply()
    }

    fun clearCustomModelPath(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREFS_CUSTOM_PATH).apply()
    }

    /** 获取手机共享目录（用于导入）——用户在此目录放 zip 模型文件 */
    fun getImportDir(context: Context): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), IMPORT_DIR).also {
            it.mkdirs()
        }
    }

    /** 获取下载缓存目录 */
    fun getDownloadDir(context: Context): File {
        return File(context.cacheDir, DOWNLOAD_DIR).also { it.mkdirs() }
    }

    /** 扫描已安装的模型（含自定义路径） */
    fun scanInstalledModels(context: Context): List<InstalledModel> {
        val result = mutableListOf<InstalledModel>()

        // 从 models 目录扫描
        val modelsDir = getModelDir(context)
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && File(dir, "am").exists()) {
                    val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val online = onlineModels.find { it.id == dir.name }
                    result.add(InstalledModel(
                        id = dir.name,
                        name = online?.name ?: dir.name,
                        lang = online?.lang ?: "未知",
                        sizeBytes = size,
                        path = dir.absolutePath,
                        source = if (online != null) "在线" else "导入"
                    ))
                }
            }
        }

        // 自定义路径 — 直接指向外部目录，不复制
        val customPath = getCustomModelPath(context)
        if (customPath != null && !result.any { it.path == customPath }) {
            val dir = File(customPath)
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            result.add(InstalledModel(
                id = "_custom_",
                name = "📁 自定义: ${dir.name}",
                lang = "中文",
                sizeBytes = size,
                path = dir.absolutePath,
                source = "自定义路径"
            ))
        }

        return result.sortedByDescending { it.sizeBytes }
    }

    /** 从 assets 解压内置模型（支持 .zip 和 .tar.gz） */
    fun extractAssetModel(context: Context, modelId: String): Boolean {
        val destDir = File(getModelDir(context), modelId)
        if (destDir.exists()) {
            Log.i(TAG, "模型已存在: $modelId")
            return true
        }

        destDir.mkdirs()

        // 尝试 tar.gz
        var success = try {
            context.assets.open("models/$modelId.tar.gz").use { input ->
                extractAssetTarGz(input, destDir)
            }
        } catch (_: Exception) { false }

        // 尝试 zip
        if (!success) {
            success = try {
                context.assets.open("models/$modelId.zip").use { input ->
                    extractAssetZip(input, destDir)
                }
            } catch (_: Exception) { false }
        }

        if (success && File(destDir, "am").exists()) {
            Log.i(TAG, "内置模型解压完成: $modelId")
            return true
        }
        destDir.deleteRecursively()
        Log.e(TAG, "内置模型解压失败: $modelId")
        return false
    }

    private fun extractAssetZip(input: java.io.InputStream, destDir: File): Boolean {
        java.util.zip.ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfter("/")
                    if (name.isNotEmpty()) {
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return true
    }

    private fun extractAssetTarGz(input: java.io.InputStream, destDir: File): Boolean {
        val buffer = ByteArray(8192)
        GZIPInputStream(input).use { gzis ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis).use { tis ->
                var entry = tis.nextTarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfter("/")
                        if (name.isNotEmpty()) {
                            val outFile = File(destDir, name)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                var bytesRead: Int
                                while (tis.read(buffer).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }
                    entry = tis.nextTarEntry
                }
            }
        }
        return true
    }

    /** 从导入目录扫描并导入模型（支持 .zip、.tar.gz 和已解压目录） */
    fun importFromDirectory(context: Context): List<String> {
        val imported = mutableListOf<String>()
        val importDir = getImportDir(context)
        if (!importDir.exists()) return imported

        importDir.listFiles()?.forEach { file ->
            val isZip = file.name.endsWith(".zip") && !file.name.endsWith(".tar.gz")
            val isTarGz = file.name.endsWith(".tar.gz")
            val isModelDir = file.isDirectory && File(file, "am").exists()
            if (isZip || isTarGz || isModelDir) {
                val modelId = when {
                    isZip -> file.name.removeSuffix(".zip")
                    isTarGz -> file.name.removeSuffix(".tar.gz")
                    else -> file.name
                }
                val destDir = File(getModelDir(context), modelId)
                if (destDir.exists()) {
                    Log.i(TAG, "模型已存在，跳过导入: $modelId")
                    imported.add("已存在: $modelId")
                    return@forEach
                }
                try {
                    when {
                        isModelDir -> {
                            // 直接复制已解压的模型目录
                            file.copyRecursively(destDir, overwrite = false)
                        }
                        isZip -> extractZip(file, destDir)
                        else -> extractTarGz(file, destDir)
                    }
                    if (File(destDir, "am").exists()) {
                        imported.add("成功: $modelId")
                        if (!isModelDir) file.delete()
                    } else {
                        destDir.deleteRecursively()
                        imported.add("失败: $modelId (无效模型)")
                    }
                } catch (e: Exception) {
                    destDir.deleteRecursively()
                    imported.add("失败: $modelId (${e.message})")
                }
            }
        }
        return imported
    }

    private fun extractZip(file: File, destDir: File) {
        java.util.zip.ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfter("/")
                    if (name.isNotEmpty()) {
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTarGz(file: File, destDir: File) {
        val buffer = ByteArray(8192)
        GZIPInputStream(FileInputStream(file)).use { gzis ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis).use { tis ->
                var entry = tis.nextTarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfter("/")
                        if (name.isNotEmpty()) {
                            val outFile = File(destDir, name)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                var bytesRead: Int
                                while (tis.read(buffer).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }
                    entry = tis.nextTarEntry
                }
            }
        }
    }

    /** 启动在线下载 */
    fun downloadModel(context: Context, model: OnlineModel, listener: DownloadListener): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val destFile = File(getDownloadDir(context), "${model.id}.zip")

        // 清理旧文件
        destFile.delete()

        val request = DownloadManager.Request(Uri.parse(model.url)).apply {
            setTitle("下载: ${model.name}")
            setDescription("${model.size}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)

        // 注册下载完成监听
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // 解压
                        Thread {
                            val result = extractZipToModel(ctx, destFile, model.id)
                            destFile.delete()
                            listener.onComplete(result)
                        }.start()
                    } else {
                        val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                        listener.onError("下载失败: $reason")
                    }
                }
                cursor.close()
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        // 轮询进度
        Thread {
            var lastProgress = -1
            while (true) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED) {
                        val downloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                listener.onProgress(progress)
                            }
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        break
                    } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        break
                    }
                }
                cursor.close()
                try { Thread.sleep(1000) } catch (_: Exception) { break }
                if (lastProgress < 0) continue
            }
        }.start()

        return downloadId
    }

    /** 解压 zip 到模型目录 */
    private fun extractZipToModel(context: Context, zipFile: File, modelId: String): String {
        val destDir = File(getModelDir(context), modelId)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        return try {
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfter("/")  // 去掉顶层目录
                        if (name.isNotEmpty()) {
                            val outFile = File(destDir, name)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (File(destDir, "am").exists()) {
                val size = destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                "成功: ${modelId} (${size / 1024 / 1024}MB)"
            } else {
                destDir.deleteRecursively()
                "失败: 模型文件不完整"
            }
        } catch (e: Exception) {
            destDir.deleteRecursively()
            "失败: ${e.message}"
        }
    }

    /** 删除模型 */
    fun deleteModel(context: Context, modelId: String): Boolean {
        val dir = File(getModelDir(context), modelId)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onComplete(result: String)
        fun onError(error: String)
    }
}

data class OnlineModel(
    val id: String,
    val name: String,
    val size: String,
    val lang: String,
    val url: String
)

data class InstalledModel(
    val id: String,
    val name: String,
    val lang: String,
    val sizeBytes: Long,
    val path: String,
    val source: String
)

enum class ModelSource {
    ASSETS, ONLINE, IMPORT
}
