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
        try { Logger.init(this) } catch (_: Throwable) {}

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_EXT)
            }
        }
        loadTasks()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_READ_EXT) loadTasks()
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun loadTasks() {
        val candidates = arrayListOf<File>()
        val appDir = getExternalFilesDir("RecentsStack")
        if (appDir != null) candidates.add(File(appDir, "tasks.json"))
        candidates.add(File(Environment.getExternalStorageDirectory(), "RecentsStack/tasks.json"))
        candidates.add(File("/data/local/tmp/tasks.json"))

        var tf: File? = null
        for (f in candidates) { if (f.exists()) { tf = f; break } }

        if (tf == null) {
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
        val appDir = getExternalFilesDir("RecentsStack")
        val candidates = ArrayList<File>()
        if (appDir != null) {
            candidates.add(File(appDir, "log.txt"))
            candidates.add(File(appDir, "full_log.txt"))
        }
        candidates.add(File(Environment.getExternalStorageDirectory(), "RecentsStack/log.txt"))
        candidates.add(File("/data/local/tmp/log.txt"))
        candidates.add(File("/data/local/tmp/full_log.txt"))

        var lf: File? = null
        for (f in candidates) { if (f.exists()) { lf = f; break } }

        if (lf == null) {
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

    private fun generateFullLogAndShare() {
        val stamp = System.currentTimeMillis()
        val dir = getExternalFilesDir("RecentsStack") ?: run {
            val f = File(Environment.getExternalStorageDirectory(), "RecentsStack")
            if (!f.exists()) f.mkdirs()
            f
        }
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "full_log_$stamp.txt")

        try {
            val fw = FileWriter(out, false)
            fw.append("RecentsStack FULL LOG / 完整日志\n")
            fw.append("Time: ${Date()}\n\n")

            val dumps = ArrayList<File>()
            getExternalFilesDir("RecentsStack")?.let { d -> dumps.addAll(d.listFiles { f -> f.name.startsWith("systemui_dump_") }?.sortedByDescending { it.name } ?: emptyList()) }
            dumps.addAll(File(Environment.getExternalStorageDirectory(), "RecentsStack").listFiles { f -> f.name.startsWith("systemui_dump_") }?.sortedByDescending { it.name } ?: emptyList())
            dumps.addAll(File("/data/local/tmp").listFiles { f -> f.name.startsWith("systemui_dump_") }?.sortedByDescending { it.name } ?: emptyList())

            if (dumps.isEmpty()) fw.append("no systemui_dump_* files found\n") else {
                for (f in dumps.take(10)) {
                    fw.append("--- file: ${f.name} ---\n")
                    try { fw.append(f.readText()); fw.append("\n") } catch (e: Exception) { fw.append("read error: ${e.message}\n") }
                }
            }

            fw.append("\n== Main log (log.txt) ==\n")
            val mainCandidates = arrayListOf<File>()
            getExternalFilesDir("RecentsStack")?.let { mainCandidates.add(File(it, "log.txt")) }
            mainCandidates.add(File(Environment.getExternalStorageDirectory(), "RecentsStack/log.txt"))
            mainCandidates.add(File("/data/local/tmp/log.txt"))
            var mainFound = false
            for (mf in mainCandidates) {
                if (mf.exists()) {
                    fw.append(mf.readText()); mainFound = true; break
                }
            }
            if (!mainFound) fw.append("no log.txt found\n")

            fw.append("\n== Full log (full_log.txt) ==\n")
            val fullCandidates = arrayListOf<File>()
            getExternalFilesDir("RecentsStack")?.let { fullCandidates.add(File(it, "full_log.txt")) }
            fullCandidates.add(File(Environment.getExternalStorageDirectory(), "RecentsStack/full_log.txt"))
            fullCandidates.add(File("/data/local/tmp/full_log.txt"))
            var fullFound = false
            for (ff in fullCandidates) {
                if (ff.exists()) {
                    fw.append(ff.readText()); fullFound = true; break
                }
            }
            if (!fullFound) fw.append("no full_log.txt found\n")

            fw.append("\n== tasks.json ==\n")
            val tasksCandidates = arrayListOf<File>()
            getExternalFilesDir("RecentsStack")?.let { tasksCandidates.add(File(it, "tasks.json")) }
            tasksCandidates.add(File(Environment.getExternalStorageDirectory(), "RecentsStack/tasks.json"))
            tasksCandidates.add(File("/data/local/tmp/tasks.json"))
            var tasksFound = false
            for (tf in tasksCandidates) {
                if (tf.exists()) {
                    fw.append(tf.readText()); tasksFound = true; break
                }
            }
            if (!tasksFound) fw.append("no tasks.json found\n")

            fw.append("\n== End of FULL LOG ==\n")
            fw.flush(); fw.close()

            Logger.appendToFullLog("Full log created: ${out.absolutePath}")

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