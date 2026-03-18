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

    init {
        try { if (!LOG_DIR.exists()) LOG_DIR.mkdirs() } catch (_: Throwable) {}
    }

    fun d(tag: String, msg: String) {
        try { XposedBridge.log("[$tag] $msg") } catch (_: Throwable) {}
        try { writeToFile("[${now()}] [$tag] $msg\n") } catch (_: Throwable) {}
    }

    private fun writeToFile(line: String) {
        try {
            val fw = FileWriter(LOG_FILE, true)
            fw.append(line)
            fw.flush()
            fw.close()
            return
        } catch (e: Throwable) {
            // try fallback to /data/local/tmp (accessible for debugging via adb)
            try {
                val f2 = File("/data/local/tmp/RecentsStack_log.txt")
                if (!f2.parentFile.exists()) f2.parentFile.mkdirs()
                val fw2 = FileWriter(f2, true)
                fw2.append(line)
                fw2.flush()
                fw2.close()
                return
            } catch (_: Throwable) {}
        }
    }

    private fun now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
}
