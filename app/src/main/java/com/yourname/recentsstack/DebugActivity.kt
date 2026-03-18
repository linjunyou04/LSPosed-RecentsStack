package com.yourname.recentsstack

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.util.*

class DebugActivity : Activity() {
    private lateinit var infoTv: TextView
    private lateinit var rv: RecyclerView
    private lateinit var exportBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var genFullBtn: Button
    private val REQ_READ_EXT = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        infoTv = findViewById(R.id.info_tv)
        rv = findViewById(R.id.debug_rv)
        exportBtn = findViewById(R.id.export_btn)
        refreshBtn = findViewById(R.id.refresh_btn)
        genFullBtn = findViewById(R.id.genfull_btn)

        rv.layoutManager = LinearLayoutManager(this)

        refreshBtn.setOnClickListener { loadTasks() }
        exportBtn.setOnClickListener { exportLogs() }
        genFullBtn.setOnClickListener { generateFullLogAndShare() }

        // runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_EXT)
            }
        }
        loadTasks()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_READ_EXT) {
            loadTasks()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun loadTasks() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val tf = File(dir, "tasks.json")
        if (!tf.exists()) {
            infoTv.text = "未检测到 tasks.json（模块可能未注入）。\nNo tasks.json found (module may not be injected)."
            rv.adapter = RecentsStackAdapter(emptyList())
            return
        }
        try {
            val text = tf.readText()
            val arr = try {
                Gson().fromJson(text, Array<RecentTaskStub>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                infoTv.text = "解析 tasks.json 错误: ${e.message}\nParse error: ${e.message}"
                rv.adapter = RecentsStackAdapter(emptyList())
                return
            }
            infoTv.text = "已加载 ${arr.size} 个任务（Loaded ${arr.size} tasks）"
            rv.adapter = RecentsStackAdapter(arr)
        } catch (e: Exception) {
            infoTv.text = "读取 tasks.json 错误: ${e.message}"
            rv.adapter = RecentsStackAdapter(emptyList())
        }
    }

    private fun exportLogs() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val lf = File(dir, "log.txt")
        if (!lf.exists()) {
            infoTv.text = "日志文件 log.txt 未找到\nlog.txt not found"
            return
        }
        try {
            val authority = "${applicationContext.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(this, authority, lf)
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_STREAM, uri)
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "分享日志 / Share logs"))
        } catch (e: Exception) {
            infoTv.text = "导出失败: ${e.message}\nExport failed: ${e.message}"
        }
    }

    /** Generate a full diagnostic bundle: read any systemui_dump_*.txt, log.txt, stack traces, system props, and write a combined file */
    private fun generateFullLogAndShare() {
        val stamp = System.currentTimeMillis()
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "full_log_$stamp.txt")
        try {
            val fw = FileWriter(out, false)
            fw.append("RecentsStack FULL LOG / 完整日志\n")
            fw.append("Time: ${Date()}\n\n")

            // 1) systemui dumps (most recent)
            fw.append("== SystemUI dumps (files) ==\n")
            val dumps = dir.listFiles { f -> f.name.startsWith("systemui_dump_") }?.sortedByDescending { it.name } ?: emptyList()
            if (dumps.isEmpty()) fw.append("no systemui_dump_* files found\n")
            for (f in dumps.take(3)) {
                fw.append("--- file: ${f.name} ---\n")
                try { fw.append(f.readText()); fw.append("\n") } catch (e: Exception) { fw.append("read error: ${e.message}\n") }
            }

            // 2) main log
            fw.append("\n== Main log (log.txt) ==\n")
            val mainlog = File(dir, "log.txt")
            if (mainlog.exists()) fw.append(mainlog.readText()) else fw.append("no log.txt found\n")

            // 3) stack traces of current process (DebugActivity) - helpful for local crashes
            fw.append("\n== Stack traces (current process) ==\n")
            val traces = Thread.getAllStackTraces()
            for ((t, st) in traces) {
                fw.append("Thread: ${t.name} (id=${t.id})\n")
                st.forEach { fw.append("\t at $it\n") }
            }

            // 4) system properties
            fw.append("\n== System properties ==\n")
            try {
                val getprop = Class.forName("android.os.SystemProperties")
                val g = getprop.getMethod("get", String::class.java)
                listOf("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint").forEach { key ->
                    try {
                        val v = g.invoke(null, key) as? String
                        fw.append("$key = ${v ?: "<null>"}\n")
                    } catch (_: Throwable) { fw.append("$key = <err>\n") }
                }
            } catch (_: Throwable) { fw.append("SystemProperties unavailable\n") }

            fw.append("\n== End of FULL LOG ==\n")
            fw.flush()
            fw.close()

            // also append a short entry into Logger.full_log
            Logger.appendToFullLog("Full log created: ${out.absolutePath}")

            // share file via FileProvider
            val authority = "${applicationContext.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(this, authority, out)
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_STREAM, uri)
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "分享完整日志 / Share full log"))
        } catch (e: Exception) {
            infoTv.text = "生成完整日志失败: ${e.message}\nGenerate full log failed: ${e.message}"
            Logger.e("DebugActivity", "generateFullLog failed: ${e.message}", "生成完整日志失败：${e.message}", e)
        }
    }
}
