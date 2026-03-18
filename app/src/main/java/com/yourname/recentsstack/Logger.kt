package com.yourname.recentsstack

import android.os.Environment
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private val LOG_DIR = File(Environment.getExternalStorageDirectory(), "RecentsStack")
    private val LOG_FILE = File(LOG_DIR, "log.txt")
    private val FULL_FILE = File(LOG_DIR, "full_log.txt")

    init {
        try { if (!LOG_DIR.exists()) LOG_DIR.mkdirs() } catch (_: Throwable) {}
    }

    fun d(tag: String, en: String, zh: String? = null) {
        val line = buildLine(tag, en, zh)
        try { XposedBridge.log("[$tag] $en ${if (!zh.isNullOrBlank()) "/ $zh" else ""}") } catch (_: Throwable) {}
        writeToFile(LOG_FILE, line)
    }

    fun e(tag: String, en: String, zh: String? = null, t: Throwable? = null) {
        d(tag, "ERROR: $en", if (zh != null) "错误: $zh" else null)
        if (t != null) {
            val sw = StringBuilder()
            sw.append("EX: ").append(t.toString())
            t.stackTrace.forEach { sw.append("\n\tat ").append(it.toString()) }
            writeToFile(LOG_FILE, sw.toString())
            writeToFile(File("/data/local/tmp/RecentsStack_log.txt"), sw.toString())
        }
    }

    fun appendToFullLog(line: String) {
        writeToFile(FULL_FILE, line + "\n")
    }

    private fun buildLine(tag: String, en: String, zh: String?): String {
        val now = now()
        val sb = StringBuilder()
        sb.append("[$now] [$tag] EN: ").append(en)
        if (!zh.isNullOrBlank()) sb.append(" | ZH: ").append(zh)
        return sb.toString()
    }

    private fun writeToFile(file: File, line: String) {
        try {
            val fw = FileWriter(file, true)
            fw.append(line)
            fw.append("\n")
            fw.flush()
            fw.close()
            return
        } catch (e: Throwable) {
            try {
                val f2 = File("/data/local/tmp/${file.name}")
                if (!f2.parentFile.exists()) f2.parentFile.mkdirs()
                val fw2 = FileWriter(f2, true)
                fw2.append(line)
                fw2.append("\n")
                fw2.flush()
                fw2.close()
                return
            } catch (_: Throwable) {}
        }
    }

    fun now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
}
