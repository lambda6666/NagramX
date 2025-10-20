package tw.nekomimi.nekogram.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.content.pm.ApplicationInfo
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.BuildConfig
import tw.nekomimi.nekogram.NekoConfig
import java.io.File
import java.util.*

object EnvUtil {

    // 添加 getDataDirFixed 方法
    @SuppressLint("SdCardPath")
    private fun getDataDirFixed(): File {
        try {
            val path = ApplicationLoader.applicationContext.filesDir
            if (path != null) {
                return path.parentFile
            }
        } catch (ignored: Exception) {
        }
        try {
            val info = ApplicationLoader.applicationContext.applicationInfo
            return File(info.dataDir)
        } catch (ignored: Exception) {
        }
        return File("/data/data/" + BuildConfig.APPLICATION_ID + "/")
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    val rootDirectories: List<File> by lazy {

        try {
            val mStorageManager = ApplicationLoader.applicationContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            (mStorageManager.javaClass.getMethod("getVolumePaths").invoke(mStorageManager) as Array<String>).map { File(it) }
        } catch (e:  Throwable) {
            AndroidUtilities.getRootDirs()
        }

    }

    @JvmStatic
    val availableDirectories
        get() = LinkedList<File>().apply {

            // 修改第33-34行：使用本地的 getDataDirFixed 方法
            add(File(getDataDirFixed(), "files/media"))
            add(File(getDataDirFixed(), "cache/media"))

            rootDirectories.forEach {

                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/files"))
                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/cache"))

            }

            if (Build.VERSION.SDK_INT < 30) {
                add(Environment.getExternalStoragePublicDirectory("Nagram"))
            }

        }.map { it.path }.toTypedArray()

    // This is the only media path of NekoX, don't use other!
    @JvmStatic
    fun getTelegramPath(): File {

        if (NekoConfig.cachePath.String() == "") {
            // https://github.com/NekoX-Dev/NekoX/issues/284
            NekoConfig.cachePath.setConfigString(availableDirectories[2]);
        }
        var telegramPath = File(NekoConfig.cachePath.String())
        if (telegramPath.isDirectory || telegramPath.mkdirs()) {
            return telegramPath
        } else {
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        }

        // fallback

        // 修改第66行：使用本地的 getDataDirFixed 方法
        telegramPath = ApplicationLoader.applicationContext.getExternalFilesDir(null) ?: File(getDataDirFixed(), "cache/files")

        if (telegramPath.isDirectory || telegramPath.mkdirs()) {

            return telegramPath

        }

        // 修改第74行：使用本地的 getDataDirFixed 方法
        telegramPath = File(getDataDirFixed(), "cache/files")

        if (!telegramPath.isDirectory) telegramPath.mkdirs();

        return telegramPath;

    }

    @JvmStatic
    fun getShareCachePath(): File {
        // fix SDK < A11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getTelegramPath()
        }
        return File(availableDirectories[3])
    }

    @JvmStatic
    fun doTest() {

        FileLog.d("rootDirectories: ${rootDirectories.size}")

        rootDirectories.forEach { FileLog.d(it.path) }

    }

}