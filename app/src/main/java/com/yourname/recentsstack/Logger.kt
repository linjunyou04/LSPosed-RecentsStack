package com.yourname.recentsstack

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private var BASE_DIR: File = File("/sdcard/RecentsStack")
    private val LOG_FILE_NAME = "log.txt"
    private val FULL_FILE_NAME = "full_log.txt"
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun init(appContext: Context) {
        try {
            val d = appContext.getExternalFilesDir("RecentsStack")
            if (d != null) {
                if (!d.exists()) d.mkdirs()
                BASE_DIR = d
                d.mkdirs()
                d.setReadable(true, false)
                d.setWritable(true, false)
            }
        } catch (_: Throwable) {}
    }

    private fun ensureBaseExists() {
        try {
            if (!BASE_DIR.exists()) BASE_DIR.mkdirs()
        } catch (_: Throwable) {}
    }

    fun d(tag: String, en: String, zh: String? = null) {
        val line = buildLine(tag, en, zh)
        try { XposedBridge.log("[$tag] $en ${if (!zh.isNullOrBlank()) "/ $zh" else ""}") } catch (_: Throwable) {}
        writeToFile(File(BASE_DIR, LOG_FILE_NAME), line)
    }

    fun e(tag: String, en: String, zh: String? = null, t: Throwable? = null) {
        d(tag, "ERROR: $en", if (zh != null) "错误: $zh" else null)
        if (t != null) {
            val sw = StringBuilder()
            sw.append("EX: ").append(t.toString())
            t.stackTrace.forEach { sw.append("\n\tat ").append(it.toString()) }
            writeToFile(File(BASE_DIR, LOG_FILE_NAME), sw.toString())
            writeToFile(File("/data/local/tmp/RecentsStack_log.txt"), sw.toString())
        }
    }

    fun appendToFullLog(line: String) {
        writeToFile(File(BASE_DIR, FULL_FILE_NAME), line + "\n")
    }

    private fun buildLine(tag: String, en: String, zh: String?): String {
        val now = now()
        val sb = StringBuilder()
        sb.append("[$now] [$tag] EN: ").append(en)
        if (!zh.isNullOrBlank()) sb.append(" | ZH: ").append(zh)
        return sb.toString()
    }

    private fun writeToFile(file: File, content: String) {
        try {
            ensureBaseExists()
            val fw = FileWriter(file, true)
            fw.append(content)
            fw.append("\n")
            fw.flush()
            fw.close()
            return
        } catch (e: Throwable) {
            try {
                val fallback = File("/sdcard/RecentsStack")
                if (!fallback.exists()) fallback.mkdirs()
                val fw2 = FileWriter(File(fallback, file.name), true)
                fw2.append(content)
                fw2.append("\n")
                fw2.flush()
                fw2.close()
                return
            } catch (_: Throwable) {
                try {
                    val f2 = File("/data/local/tmp/${file.name}")
                    if (!f2.parentFile.exists()) f2.parentFile.mkdirs()
                    val fw3 = FileWriter(f2, true)
                    fw3.append(content)
                    fw3.append("\n")
                    fw3.flush()
                    fw3.close()
                } catch (_: Throwable) {}
            }
        }
    }

    fun now(): String = DATE_FMT.format(Date())
}