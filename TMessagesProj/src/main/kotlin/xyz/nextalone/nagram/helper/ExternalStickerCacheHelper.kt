package xyz.nextalone.nagram.helper

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationCenter.NotificationCenterDelegate
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC.TL_messages_stickerSet
import tw.nekomimi.nekogram.config.cell.ConfigCellAutoTextCheck
import tw.nekomimi.nekogram.utils.AlertUtil
import xyz.nextalone.nagram.NaConfig
import java.io.File

object ExternalStickerCacheHelper {
    const val TAG = "ExternalStickerCache"

    private var _cachePath: File? = null
    private val cachePath: File
        get() {
            // 使用缓存，避免重复检查
            if (_cachePath == null || !isDirectoryUsable(_cachePath!!)) {
                _cachePath = determineBestCachePath()
            }
            return _cachePath!!
        }

    /**
     * 确定最佳缓存路径
     */
    private fun determineBestCachePath(): File {
        val pathsToTry = mutableListOf<File>()

        // 1. 优先尝试内部缓存目录 (getCacheDir)
        pathsToTry.add(getCacheDirPath())

        // 2. 尝试默认的外部存储路径
        pathsToTry.add(getDefaultCachePath())

        // 3. 尝试内部文件目录作为最后备选
        pathsToTry.add(ApplicationLoader.applicationContext.filesDir.resolve("caches"))

        // 按顺序尝试每个路径
        for (path in pathsToTry) {
            if (isDirectoryUsable(path)) {
                logD("Using cache path: ${path.absolutePath}")
                return path
            }
        }

        // 如果所有路径都失败，使用默认路径并记录错误
        val fallback = getDefaultCachePath()
        logD("All cache paths failed, using fallback: ${fallback.absolutePath}")
        return fallback
    }

    /**
     * 获取内部缓存目录路径
     */
    private fun getCacheDirPath(): File {
        return ApplicationLoader.applicationContext.cacheDir.resolve("sticker_cache").apply {
            mkdirs()
        }
    }

    /**
     * 获取默认缓存路径（外部存储）
     */
    private fun getDefaultCachePath(): File {
        return ApplicationLoader.applicationContext.getExternalFilesDir(null)!!.resolve("caches").apply {
            mkdirs()
        }
    }

    /**
     * 检查目录是否可用（可读可写）
     */
    private fun isDirectoryUsable(directory: File): Boolean {
        return try {
            if (!directory.exists()) {
                val created = directory.mkdirs()
                if (!created) {
                    logD("Failed to create directory: ${directory.absolutePath}")
                    return false
                }
            }
            
            val testFile = File(directory, ".test_write")
            val canWrite = try {
                testFile.writeText("test")
                testFile.delete()
                true
            } catch (e: Exception) {
                false
            }
            
            val result = directory.exists() && directory.isDirectory && directory.canRead() && canWrite
            if (!result) {
                logD("Directory not usable: ${directory.absolutePath} (exists: ${directory.exists()}, isDir: ${directory.isDirectory}, canRead: ${directory.canRead()}, canWrite: $canWrite)")
            }
            result
        } catch (e: Exception) {
            logException(e, "checking directory usability for ${directory.absolutePath}")
            false
        }
    }

    /**
     * 获取当前使用的缓存路径（用于调试）
     */
    @JvmStatic
    fun getCurrentCachePath(): String {
        return cachePath.absolutePath
    }

    /**
     * 强制重新加载缓存路径（当配置改变时调用）
     */
    @JvmStatic
    fun reloadCachePath() {
        _cachePath = null
        logD("Cache path reloaded, current path: ${getCurrentCachePath()}")
    }

    @JvmStatic
    fun checkUri(configCell: ConfigCellAutoTextCheck, context: Context) {
        async {
            NaConfig.externalStickerCacheUri?.let { uri ->
                AndroidUtilities.runOnUIThread { configCell.setSubtitle("Loading...") }
                try {
                    val dir = DocumentFile.fromTreeUri(context, uri)
                    val subtitle: String
                    if (dir == null) {
                        subtitle = "Error: failed to access document"
                    } else {
                        if (dir.isDirectory) {
                            val nomedia = ".nomedia"
                            val testFile = dir.findFile(nomedia) ?: dir.createFile("", nomedia)
                            if (testFile == null) {
                                subtitle = "Error: cannot create $nomedia"
                            } else {
                                if (testFile.canRead() && testFile.canWrite()) {
                                    subtitle = "Currently using: ${dir.name}"
                                } else {
                                    subtitle = "Error: read/write is not supported"
                                }
                            }
                        } else {
                            subtitle = "Error: not a directory"
                        }
                    }
                    AndroidUtilities.runOnUIThread { configCell.setSubtitle(subtitle) }
                } catch (e: Exception) {
                    logException(e, "checking Uri")
                }
            }
        }
    }

    private var caching = false
    private var cacheAgain = false

    @JvmStatic
    fun cacheStickers(isAutoSync: Boolean = true) {
        if (isAutoSync && !NaConfig.externalStickerCacheAutoRefresh.Bool()) return
        if (NaConfig.externalStickerCache.String().isEmpty()) return
        if (caching) {
            cacheAgain = true
            return
        }
        cacheStickers0(isAutoSync)
    }

    private fun cacheStickers0(isAutoSync: Boolean) {
        async {
            caching = true
            val stickerSets = MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSets(MediaDataController.TYPE_IMAGE)
            val context = ApplicationLoader.applicationContext
            try {
                val uri = NaConfig.externalStickerCacheUri ?: return@async
                val resolver = context.contentResolver
                DocumentFile.fromTreeUri(context, uri)?.let { dir ->
                    logD("Caching ${stickerSets.size} sticker set(s) from path: ${getCurrentCachePath()}...")
                    if (dir.isDirectory) {
                        val stickerSetDirMap = dir.listFiles().run {
                            val map = mutableMapOf<String, DocumentFile>()
                            forEach { it.name?.let { name -> map[name] = it } }
                            map
                        }
                        stickerSets.forEach { set ->
                            val stickers = set.documents
                            val setDirName = getStickerDirName(set)
                            (stickerSetDirMap[setDirName] ?: dir.createDirectory(setDirName))?.let { stickerSetDir ->
                                if (stickerSetDir.isDirectory) {
                                    val stickerFileMap = stickerSetDir.listFiles().run {
                                        val map = mutableMapOf<String, DocumentFile>()
                                        forEach { it.name?.let { name -> map[name] = it } }
                                        map
                                    }
                                    for (sticker in stickers) {
                                        val webp = "image/webp"
                                        val webpExt = ".webp"
                                        val localPath = "${sticker.dc_id}_${sticker.id}.webp"
                                        val localPathLowQuality = "-${sticker.id}_1109.webp"
                                        val stickerFile = File(cachePath, localPath)
                                        val stickerFileLowQuality = File(cachePath, localPathLowQuality)
                                        val destName = "${sticker.id}_high"
                                        val destNameExt = destName + webpExt
                                        val destNameNotFound = stickerFileMap[destNameExt] == null
                                        val destNameLowQuality = "${sticker.id}_low"
                                        val destNameLowQualityExt = destNameLowQuality + webpExt

                                        if (stickerFile.exists()) {
                                            if (destNameNotFound) {
                                                stickerSetDir.createFile(webp, destName)?.let { destFile ->
                                                    resolver.openOutputStream(destFile.uri)?.let { stickerFile.inputStream().copyTo(it) }
                                                    logV("Created file ${destFile.name}")
                                                    stickerFileMap[destNameLowQualityExt]?.let {
                                                        it.delete()
                                                        logV("Deleted low quality file")
                                                    }
                                                }
                                            }
                                        } else if (stickerFileLowQuality.exists()) {
                                            if (stickerFileMap[destNameLowQualityExt] == null && destNameNotFound) {
                                                stickerSetDir.createFile(webp, destNameLowQuality)?.let { destFileLowQuality ->
                                                    resolver.openOutputStream(destFileLowQuality.uri)?.let { stickerFileLowQuality.inputStream().copyTo(it) }
                                                    logV("Created low quality file ${destFileLowQuality.name}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!isAutoSync) showToast(null)
                    }
                }
            } catch (e: Exception) {
                logException(e, "caching stickers")
            }
            if (cacheAgain) {
                delay(30000)
                cacheAgain = false
                cacheStickers0(true)
            } else {
                caching = false
            }
        }
    }

    @JvmStatic
    fun refreshCacheFiles(set: TL_messages_stickerSet) {
        async {
            waitForSync()
            val uri = NaConfig.externalStickerCacheUri ?: return@async
            val context = ApplicationLoader.applicationContext
            try {
                DocumentFile.fromTreeUri(context, uri)?.let { dir ->
                    val setDirName = getStickerDirName(set)
                    logD("Refreshing cache $setDirName from path: ${getCurrentCachePath()}...")
                    dir.findFile(setDirName)?.let {
                        it.delete()
                        logD("Deleting exist files...")
                        while (true) {
                            if (dir.findFile(setDirName) == null) break
                            delay(500)
                        }
                    }
                    dir.createDirectory(setDirName)?.let { stickerSetDir ->
                        val stickers = set.documents
                        val resolver = context.contentResolver
                        for (sticker in stickers) {
                            val localPath = "${sticker.dc_id}_${sticker.id}.webp"
                            val localPathLowQuality = "-${sticker.id}_1109.webp"
                            val stickerFile = File(cachePath, localPath)
                            val stickerFileLowQuality = File(cachePath, localPathLowQuality)
                            fun create(name: String, type: String) = stickerSetDir.createFile(type, name)
                            val webp = "image/webp"
                            val destName = "${sticker.id}_high"
                            val destNameLowQuality = "${sticker.id}_low"

                            if (stickerFile.exists()) {
                                create(destName, webp)?.let { destFile ->
                                    resolver.openOutputStream(destFile.uri)?.let { stickerFile.inputStream().copyTo(it) }
                                    logV("Created file ${destFile.name}")
                                }
                            } else if (stickerFileLowQuality.exists()) {
                                create(destNameLowQuality, webp)?.let { destFileLowQuality ->
                                    resolver.openOutputStream(destFileLowQuality.uri)?.let { stickerFileLowQuality.inputStream().copyTo(it) }
                                    logV("Created low quality file ${destFileLowQuality.name}")
                                }
                            }
                        }
                    }
                }
                showToast(null)
            } catch (e: Exception) {
                logException(e, "refreshing specific cache")
            }
        }
    }

    @JvmStatic
    fun deleteCacheFiles(set: TL_messages_stickerSet) {
        async {
            waitForSync()
            val uri = NaConfig.externalStickerCacheUri ?: return@async
            val context = ApplicationLoader.applicationContext
            try {
                DocumentFile.fromTreeUri(context, uri)?.let { dir ->
                    val setDirName = getStickerDirName(set)
                    dir.findFile(setDirName)?.delete()
                }
                showToast(null)
            } catch (e: Exception) {
                logException(e, "deleting specific cache")
            }
        }
    }

    @JvmStatic
    fun syncAllCaches() {
        async {
            if (caching) {
                showToast(getString(R.string.ExternalStickerCacheSyncNotFinished))
            } else {
                cacheStickers(false)
            }
        }
    }

    @JvmStatic
    fun deleteAllCaches() {
        async {
            waitForSync()
            val uri = NaConfig.externalStickerCacheUri ?: return@async
            val context = ApplicationLoader.applicationContext
            try {
                DocumentFile.fromTreeUri(context, uri)?.let { dir ->
                    dir.listFiles().forEach { if (it.isDirectory) it.delete() }
                }
                showToast(null)
            } catch (e: Exception) {
                logException(e, "deleting all caches")
            }
        }
    }

    private val observer = NotificationCenterDelegate { _, _, _ -> cacheStickers(true) }
    private val notificationIdList = listOf(
        NotificationCenter.stickersDidLoad,
        NotificationCenter.diceStickersDidLoad,
        NotificationCenter.featuredStickersDidLoad,
        NotificationCenter.stickersImportComplete,
    )

    @JvmStatic
    fun addNotificationObservers(currentAccount: Int) {
        NotificationCenter.getInstance(currentAccount).apply {
            notificationIdList.forEach { addObserver(observer, it) }
        }
    }

    @JvmStatic
    fun removeNotificationObservers(currentAccount: Int) {
        NotificationCenter.getInstance(currentAccount).apply {
            notificationIdList.forEach { removeObserver(observer, it) }
        }
    }

    @JvmStatic
    private fun showToast(msg: String?) {
        var realMessage = msg
        if (realMessage == null) {
            realMessage = getString(R.string.Done)
        }
        AndroidUtilities.runOnUIThread {
            if (realMessage != null) {
                AlertUtil.showToast(realMessage)
            }
        }
    }

    private const val TYPE_USERNAME = 0
    private const val TYPE_ID = 1

    private fun getStickerDirName(set: TL_messages_stickerSet): String = when (NaConfig.externalStickerCacheDirNameType.Int()) {
        TYPE_USERNAME -> set.set.short_name
        TYPE_ID -> set.set.id.toString()
        else -> throw RuntimeException("Invalid dir name type")
    }

    private suspend fun waitForSync() {
        if (caching) {
            showToast(getString(R.string.ExternalStickerCacheWaitSync))
            do delay(3000) while (caching)
        }
    }

    private fun async(scope: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch(block = scope)
    }

    private fun logException(e: Exception, s: String) {
        val exception = e.javaClass.canonicalName
        val message = e.message
        val realMessage = "Exception while $s: $exception: $message"
        Log.e(TAG, realMessage)
        showToast(realMessage)
    }
    private fun logV(message: String) = Log.v(TAG, message)
    private fun logD(message: String) = Log.d(TAG, message)
}