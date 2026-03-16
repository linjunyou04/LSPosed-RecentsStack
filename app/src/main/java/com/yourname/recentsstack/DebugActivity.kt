package com.yourname.recentsstack

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.io.File

class DebugActivity : Activity() {
    private lateinit var infoTv: TextView
    private lateinit var rv: RecyclerView
    private lateinit var exportBtn: Button
    private lateinit var refreshBtn: Button

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

        loadTasks()
    }

    private fun loadTasks() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val tf = File(dir, "tasks.json")
        if (!tf.exists()) {
            infoTv.text = "tasks.json not found. Make sure module injected and has permission."
            rv.adapter = RecentsStackAdapter(emptyList())
            return
        }
        val text = tf.readText()
        val arr = Gson().fromJson(text, Array<RecentTaskStub>::class.java).toList()
        infoTv.text = "Loaded ${arr.size} tasks from tasks.json"
        rv.adapter = RecentsStackAdapter(arr)
    }

    private fun exportLogs() {
        val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
        val lf = File(dir, "log.txt")
        if (!lf.exists()) {
            infoTv.text = "log.txt not found"
            return
        }
        val uri = Uri.fromFile(lf)
        val share = Intent(Intent.ACTION_SEND)
        share.type = "text/plain"
        share.putExtra(Intent.EXTRA_STREAM, uri)
        startActivity(Intent.createChooser(share, "Share logs"))
    }
}
