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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.io.File

class DebugActivity : Activity() {
    private lateinit var infoTv: TextView
    private lateinit var rv: RecyclerView
    private lateinit var exportBtn: Button
    private lateinit var refreshBtn: Button
    private val REQ_READ_EXT = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        infoTv = findViewById(R.id.info_tv)
        rv = findViewById(R.id.debug_rv)
        exportBtn = findViewById(R.id.export_btn)
        refreshBtn = findViewById(R.id.refresh_btn)

        rv.layoutManager = LinearLayoutManager(this)

        refreshBtn.setOnClickListener { loadTasks() }
        exportBtn.setOnClickListener { exportLogs() }

        // Request permission if needed (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_EXT)
            }
        }

        loadTasks()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_EXT) {
            loadTasks()
        }
    }

    private fun loadTasks() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val tf = File(dir, "tasks.json")
        if (!tf.exists()) {
            infoTv.text = "tasks.json not found.\nMake sure module injected and has permission."
            rv.adapter = RecentsStackAdapter(emptyList())
            return
        }

        try {
            val text = tf.readText()
            val arr = try {
                Gson().fromJson(text, Array<RecentTaskStub>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                infoTv.text = "tasks.json parse error: ${e.message}"
                rv.adapter = RecentsStackAdapter(emptyList())
                return
            }

            infoTv.text = "Loaded ${arr.size} tasks from tasks.json"
            rv.adapter = RecentsStackAdapter(arr)
        } catch (e: Exception) {
            infoTv.text = "Load error: ${e.message}"
            rv.adapter = RecentsStackAdapter(emptyList())
        }
    }

    private fun exportLogs() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val lf = File(dir, "log.txt")
        if (!lf.exists()) {
            infoTv.text = "log.txt not found"
            return
        }
        try {
            val authority = "${applicationContext.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(this, authority, lf)
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_STREAM, uri)
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "Share logs"))
        } catch (e: Exception) {
            infoTv.text = "Export failed: ${e.message}"
        }
    }
}
