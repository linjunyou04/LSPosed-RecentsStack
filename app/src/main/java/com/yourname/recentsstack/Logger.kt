package com.yourname.recentsstack

import android.os.Environment
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Logger: bilingual log output (zh / en), writes to sdcard and falls back to /data/local/tmp.
 * Files:
 *   /sdcard/RecentsStack/log.txt              - main bilingual log (app+hook)
 *   /sdcard/RecentsStack/full_log.txt         - aggregated full dump (created by DebugActivity)
 * Fallback:
 *   /data/local/tmp/RecentsStack_log.txt
 *   /data/local/tmp/RecentsStack_full_log.txt
 */
object Logger {
    private val LOG_DIR = File(Environment.getExternalStorageDirectory(), "RecentsStack")
    private val LOG_FILE = File(LOG_DIR, "log.txt")
    private val FULL_FILE = File(LOG_DIR, "full_log.txt")

    init {
        try { if (!LOG_DIR.exists()) LOG_DIR.mkdirs() } catch (_: Throwable) {}
    }

    /** Write a bilingual entry. zh may be null. */
    fun d(tag: String, en: String, zh: String? = null) {
        val line = buildLine(tag, en, zh)
        try { XposedBridge.log("[$tag] $en ${if (!zh.isNullOrBlank()) "/ $zh" else ""}") } catch (_: Throwable) {}
        writeToFile(LOG_FILE, line)
    }

    /** Append arbitrary lines to the full dump */
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
            // fallback: /data/local/tmp
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

    /** helper: write exception stack trace to logs */
    fun e(tag: String, en: String, zh: String? = null, t: Throwable? = null) {
        d(tag, en, zh)
        if (t != null) {
            val sw = StringBuilder()
            sw.append("EX: ").append(t.toString())
            t.stackTrace.forEach { sw.append("\n\tat ").append(it.toString()) }
            writeToFile(LOG_FILE, sw.toString())
            writeToFile(File("/data/local/tmp/RecentsStack_log.txt"), sw.toString())
        }
    }
}
