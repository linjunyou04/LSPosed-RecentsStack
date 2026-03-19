package com.yourname.recentsstack

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "RecentsStack"
    }

    // throttles & limits
    private val lastDumpForActivity = HashMap<String, Long>()
    private val DUMP_MIN_INTERVAL_MS = 1200L
    private val MAX_FULL_LOG_BYTES = 3 * 1024 * 1024L // 3MB

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            if (pkg != "com.android.systemui" && pkg != "com.meizu.systemui") return

            Logger.d(TAG, "HOOK", "SystemUI injected", "SystemUI 注入")

            // ensure folder & marker (guarantee visible to DebugActivity)
            try {
                val dir = File("/sdcard/RecentsStack")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "injected.flag").writeText("ok")
            } catch (_: Throwable) {}

            try { dumpSystemUiDiagnostics(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "dump diagnostics failed: ${t.message}", "诊断导出失败: ${t.message}", t) }

            try { forceWriteTasksJson(lpparam.classLoader) } catch (_: Throwable) {}

            installGlobalCapture(lpparam.classLoader)
            addRobustMethodHooks(lpparam.classLoader)

        } catch (t: Throwable) {
            Logger.e(TAG, "handleLoadPackage error: ${t.message}", "加载包错误: ${t.message}", t)
        }
    }

    private fun forceWriteTasksJson(classLoader: ClassLoader) {
        try {
            val dir = File("/sdcard/RecentsStack")
            if (!dir.exists()) dir.mkdirs()
            val tf = File(dir, "tasks.json")

            val stubs = LinkedHashSet<RecentTaskStub>()

            // Strategy: try to use ActivityManagerService via reflection (best-effort)
            try {
                val amClass = Class.forName("android.app.ActivityManager", false, classLoader)
                val service = try { XposedHelpers.callStaticMethod(amClass, "getService") } catch (_: Throwable) { null }
                if (service != null) {
                    try {
                        val getRecent = service.javaClass.getMethod("getRecentTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val list = getRecent.invoke(service, 50, 0) as? List<*>
                        if (!list.isNullOrEmpty()) {
                            for (item in list) {
                                try {
                                    var pkg = "unknown"; var cls = ""
                                    try {
                                        val baseIntent = try { XposedHelpers.getObjectField(item, "baseIntent") } catch (_: Throwable) { null }
                                        if (baseIntent != null) {
                                            val comp = try { baseIntent.javaClass.getMethod("getComponent").invoke(baseIntent) } catch (_: Throwable) { null }
                                            if (comp != null) {
                                                pkg = try { comp.javaClass.getMethod("getPackageName").invoke(comp) as? String ?: "unknown" } catch (_: Throwable) { "unknown" }
                                                cls = try { comp.javaClass.getMethod("getClassName").invoke(comp) as? String ?: "" } catch (_: Throwable) { "" }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                    try {
                                        val topActivity = try { XposedHelpers.getObjectField(item, "topActivity") } catch (_: Throwable) { null }
                                        if (topActivity != null && (pkg == "unknown" || cls.isBlank())) {
                                            pkg = try { topActivity.javaClass.getMethod("getPackageName").invoke(topActivity) as? String ?: pkg } catch (_: Throwable) { pkg }
                                            cls = try { topActivity.javaClass.getMethod("getClassName").invoke(topActivity) as? String ?: cls } catch (_: Throwable) { cls }
                                        }
                                    } catch (_: Throwable) {}
                                    stubs.add(RecentTaskStub(pkg, cls, pkg, null))
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            // Strategy fallback: nothing more guaranteed here; we will always write an (possibly empty) tasks.json
            val gson = Gson()
            val listJson = gson.toJson(stubs.toList())

            try {
                tf.writeText(listJson)
            } catch (t: Throwable) {
                try {
                    val f2 = File("/data/local/tmp/tasks.json")
                    if (!f2.parentFile.exists()) f2.parentFile.mkdirs()
                    f2.writeText(listJson)
                } catch (_: Throwable) {}
            }

            Logger.d(TAG, "FORCE_TASK_WRITE", "Wrote tasks.json (items=${stubs.size})", "写 tasks.json 成功 (数量=${stubs.size})")
            appendFullLogSafe("FORCE_TASK_WRITE: wrote ${stubs.size} entries\n")

        } catch (t: Throwable) {
            Logger.e(TAG, "forceWriteTasksJson failed: ${t.message}", "强制写 tasks.json 失败: ${t.message}", t)
        }
    }

    private fun installGlobalCapture(classLoader: ClassLoader) {
        try {
            Logger.d(TAG, "installGlobalCapture start", "开始安装全局捕获")

            // Activity.onResume
            try {
                val activityCls = Class.forName("android.app.Activity", false, classLoader)
                XposedBridge.hookAllMethods(activityCls, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val act = param.thisObject as? Activity ?: return
                            val name = act.javaClass.name
                            Logger.d(TAG, "ACTIVITY_RESUME", "Activity resumed: $name", "Activity 恢复: $name")

                            val now = System.currentTimeMillis()
                            val last = lastDumpForActivity[name] ?: 0L
                            if (now - last >= DUMP_MIN_INTERVAL_MS) {
                                lastDumpForActivity[name] = now
                                appendFullLogSafe("\n=== Activity Resume: $name @ $now ===\n")
                                dumpViewTree(act)
                                tryDumpTasks(act)
                            } else {
                                Logger.d(TAG, "ACTIVITY_RESUME_THROTTLE", "Throttled dump for $name")
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "onResume hook err: ${t.message}", "onResume 错误: ${t.message}", t)
                        }
                    }
                })
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook Activity failed: ${t.message}", "安装 Activity 钩子失败: ${t.message}", t)
            }

            // WindowManagerGlobal.addView
            try {
                val wmgCls = Class.forName("android.view.WindowManagerGlobal", false, classLoader)
                for (m in wmgCls.declaredMethods) {
                    if (m.name == "addView") {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val viewObj = param.args?.getOrNull(0)
                                        val viewClass = viewObj?.javaClass?.name ?: "<null>"
                                        Logger.d(TAG, "ADD_VIEW", "WindowManagerGlobal.addView: $viewClass", "添加顶层 View: $viewClass")
                                        appendFullLogSafe("=== addView: $viewClass ===\n")
                                    } catch (_: Throwable) {}
                                }
                            })
                        } catch (t: Throwable) {
                            Logger.e(TAG, "hook addView method failed: ${t.message}", "addView 钩子失败: ${t.message}", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook addView failed: ${t.message}", "安装 addView 钩子失败: ${t.message}", t)
            }

            // View.onAttachedToWindow
            try {
                val viewCls = Class.forName("android.view.View", false, classLoader)
                XposedBridge.hookAllMethods(viewCls, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            val clsName = v.javaClass.name
                            if (clsName.contains("recents", true) || clsName.contains("overview", true) || clsName.contains("task", true)) {
                                Logger.d(TAG, "RECENTS_VIEW_ATTACH", "View attached: $clsName", "相关视图挂载: $clsName")
                                appendFullLogSafe("=== RecentsViewAttached: $clsName ===\n")
                                val ctx = findContextFromObject(v)
                                if (ctx is Activity) {
                                    dumpViewTree(ctx)
                                    tryDumpTasks(ctx)
                                }
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "onAttachedToWindow hook err: ${t.message}", "onAttachedToWindow 错误: ${t.message}", t)
                        }
                    }
                })
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook View failed: ${t.message}", "安装 View 钩子失败: ${t.message}", t)
            }

            Logger.d(TAG, "installGlobalCapture done", "全局捕获完成")
        } catch (t: Throwable) {
            Logger.e(TAG, "installGlobalCapture general error: ${t.message}", "全局捕获总体错误: ${t.message}", t)
        }
    }

    private fun addRobustMethodHooks(classLoader: ClassLoader) {
        try {
            val candidates = listOf(
                "com.android.systemui.recents.RecentsImplementation",
                "com.android.systemui.recents.OverviewProxyRecentsImpl",
                "com.android.systemui.recents.LauncherProxyService",
                "com.android.systemui.statusbar.CommandQueue"
            )
            val pattern = Regex("(?i).*(show|toggle|recent|overview|notify|preload|notifyToggle).*")
            var hooked = 0

            for (clsName in candidates) {
                try {
                    val cls = XposedHelpers.findClassIfExists(clsName, classLoader)
                    if (cls == null) {
                        Logger.d(TAG, "candidate-missing", "Candidate not found: $clsName")
                        continue
                    }
                    Logger.d(TAG, "candidate-found", "Found candidate: $clsName")

                    for (m in cls.declaredMethods) {
                        try {
                            val name = m.name
                            if (pattern.matches(name)) {
                                try {
                                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            try {
                                                Logger.d(TAG, "method-before", "$clsName.$name BEFORE")
                                                appendFullLogSafe("BEFORE METHOD: $clsName.$name this=${param.thisObject?.javaClass?.name}\n")
                                            } catch (_: Throwable) {}
                                        }
                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            try {
                                                Logger.d(TAG, "method-after", "$clsName.$name AFTER")
                                                appendFullLogSafe("AFTER METHOD: $clsName.$name this=${param.thisObject?.javaClass?.name}\n")
                                                val ctx = findContextFromObject(param.thisObject) ?: (param.thisObject as? Activity)
                                                if (ctx is Activity) {
                                                    tryDumpTasks(ctx)
                                                    injectStackView(ctx, classLoader)
                                                }
                                            } catch (t: Throwable) {
                                                Logger.e(TAG, "method-hook-after err: ${t.message}", "方法钩子后错误: ${t.message}", t)
                                            }
                                        }
                                    })
                                    hooked++
                                } catch (t: Throwable) {
                                    Logger.e(TAG, "hookMethod failed: ${t.message}", "hookMethod 失败: ${t.message}", t)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "candidate loop error: ${t.message}", "候选循环错误: ${t.message}", t)
                }
            }
            Logger.d(TAG, "method-hook-summary", "hooked methods approx: $hooked")
            appendFullLogSafe("HOOK SUMMARY: hooked methods approx: $hooked\n")
        } catch (t: Throwable) {
            Logger.e(TAG, "addRobustMethodHooks failed: ${t.message}", "添加方法钩子失败: ${t.message}", t)
        }
    }

    private fun tryDumpTasks(ctx: Context?) {
        try {
            val tasks = fetchTasksBestEffort(ctx)
            val dir = File("/sdcard/RecentsStack")
            if (!dir.exists()) dir.mkdirs()
            val tf = File(dir, "tasks.json")
            val gson = Gson()
            tf.writeText(gson.toJson(tasks))
            Logger.d(TAG, "TASK_DUMP", "tasks.json written: ${tasks.size}", "写入 tasks.json 条目: ${tasks.size}")
            appendFullLogSafe("TASKS_WRITTEN: ${tasks.size}\n")
        } catch (t: Throwable) {
            Logger.e(TAG, "tryDumpTasks failed: ${t.message}", "写 tasks.json 失败: ${t.message}", t)
        }
    }

    private fun fetchTasksBestEffort(ctx: Context?): List<RecentTaskStub> {
        val out = ArrayList<RecentTaskStub>()

        try {
            if (ctx is Activity) {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val appTasks = try { am.appTasks } catch (_: Throwable) { null }
                if (appTasks != null && appTasks.isNotEmpty()) {
                    for (at in appTasks) {
                        try {
                            val info = at.taskInfo
                            val pkg = info.baseIntent?.component?.packageName ?: (info.topActivity?.packageName ?: "unknown")
                            val cls = info.baseIntent?.component?.className ?: (info.topActivity?.className ?: "")
                            out.add(RecentTaskStub(pkg, cls, pkg, null))
                        } catch (_: Throwable) {}
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        } catch (_: Throwable) {}

        try {
            val amClass = Class.forName("android.app.ActivityManager", false, this::class.java.classLoader)
            val service = try { XposedHelpers.callStaticMethod(amClass, "getService") } catch (_: Throwable) { null }
            if (service != null) {
                try {
                    val getRecent = service.javaClass.getMethod("getRecentTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    val list = getRecent.invoke(service, 50, 0) as? List<*>
                    if (!list.isNullOrEmpty()) {
                        for (item in list) {
                            try {
                                var pkg = "unknown"; var cls = ""
                                try {
                                    val baseIntent = try { XposedHelpers.getObjectField(item, "baseIntent") } catch (_: Throwable) { null }
                                    if (baseIntent != null) {
                                        val comp = try { baseIntent.javaClass.getMethod("getComponent").invoke(baseIntent) } catch (_: Throwable) { null }
                                        if (comp != null) {
                                            pkg = try { comp.javaClass.getMethod("getPackageName").invoke(comp) as? String ?: "unknown" } catch (_: Throwable) { "unknown" }
                                            cls = try { comp.javaClass.getMethod("getClassName").invoke(comp) as? String ?: "" } catch (_: Throwable) { "" }
                                        }
                                    }
                                } catch (_: Throwable) {}
                                try {
                                    val topActivity = try { XposedHelpers.getObjectField(item, "topActivity") } catch (_: Throwable) { null }
                                    if (topActivity != null && (pkg == "unknown" || cls.isBlank())) {
                                        pkg = try { topActivity.javaClass.getMethod("getPackageName").invoke(topActivity) as? String ?: pkg } catch (_: Throwable) { pkg }
                                        cls = try { topActivity.javaClass.getMethod("getClassName").invoke(topActivity) as? String ?: cls } catch (_: Throwable) { cls }
                                    }
                                } catch (_: Throwable) {}
                                out.add(RecentTaskStub(pkg, cls, pkg, null))
                            } catch (_: Throwable) {}
                        }
                        if (out.isNotEmpty()) return out
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        try {
            if (ctx != null) {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val recent = try { am.getRunningTasks(50) } catch (_: Throwable) { null }
                if (!recent.isNullOrEmpty()) {
                    for (t in recent) {
                        try {
                            val pkg = t.topActivity?.packageName ?: "unknown"
                            val cls = t.topActivity?.className ?: ""
                            out.add(RecentTaskStub(pkg, cls, pkg, null))
                        } catch (_: Throwable) {}
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        } catch (_: Throwable) {}

        return out
    }

    private fun dumpViewTree(activity: Activity) {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val sb = StringBuilder()
            sb.append("\n=== ViewTree for ").append(activity.javaClass.name).append(" ===\n")
            fun dfs(v: View, depth: Int) {
                repeat(depth) { sb.append("  ") }
                sb.append(v.javaClass.name)
                val idName = try {
                    if (v.id != View.NO_ID) activity.resources.getResourceEntryName(v.id) else null
                } catch (_: Throwable) { null }
                if (!idName.isNullOrBlank()) sb.append(" #").append(idName)
                sb.append("\n")
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        try { dfs(v.getChildAt(i), depth + 1) } catch (_: Throwable) {}
                    }
                }
            }
            dfs(decor, 0)
            sb.append("=== End ViewTree ===\n")
            appendFullLogSafe(sb.toString())
        } catch (t: Throwable) {
            Logger.e(TAG, "dumpViewTree err: ${t.message}", "View树 dump 错误: ${t.message}", t)
        }
    }

    private fun appendFullLogSafe(text: String) {
        try {
            val dir = File("/sdcard/RecentsStack")
            if (!dir.exists()) dir.mkdirs()
            val full = File(dir, "full_log.txt")
            if (full.exists() && full.length() > MAX_FULL_LOG_BYTES) {
                try {
                    val rotated = File(dir, "full_log_${System.currentTimeMillis()}.txt")
                    full.renameTo(rotated)
                    full.writeText("")
                } catch (_: Throwable) {}
            }
            full.appendText(text)
        } catch (e: Throwable) {
            try {
                val f2 = File("/data/local/tmp/full_log.txt")
                if (!f2.parentFile.exists()) f2.parentFile.mkdirs()
                f2.appendText(text)
            } catch (_: Throwable) {}
        }
    }

    private fun findContextFromObject(obj: Any?): Context? {
        if (obj == null) return null
        try {
            if (obj is Context) return obj
            var cls: Class<*>? = obj.javaClass
            var depth = 0
            while (cls != null && depth++ < 4) {
                for (f in cls.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is Context) return v
                        if (v != null && v.javaClass.name.contains("Context", true)) return v as? Context
                    } catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun injectStackView(activity: Activity, cl: ClassLoader) {
        try {
            Logger.d(TAG, "injectStackView", "Attempt inject overlay into ${activity.javaClass.name}", "尝试注入 overlay 到 ${activity.javaClass.name}")
            val modulePkg = this.javaClass.packageName
            val moduleCtx = try { activity.createPackageContext(modulePkg, Context.CONTEXT_IGNORE_SECURITY) } catch (_: Throwable) { activity }
            val inflater = activity.layoutInflater
            val layoutId = moduleCtx.resources.getIdentifier("custom_recents", "layout", modulePkg)
            if (layoutId == 0) {
                Logger.d(TAG, "module layout not found", "模块布局未找到")
                return
            }
            val root = inflater.inflate(layoutId, null) as android.widget.FrameLayout
            try {
                val rvId = moduleCtx.resources.getIdentifier("stackRecycler", "id", modulePkg)
                val rv = if (rvId != 0) root.findViewById<androidx.recyclerview.widget.RecyclerView>(rvId) else null
                rv?.layoutManager = StackLayoutManager(activity)
                val tasks = fetchTasksBestEffort(activity)
                rv?.adapter = RecentsStackAdapter(tasks)
            } catch (_: Throwable) {}

            attachOverlaySafely(activity, root)
        } catch (t: Throwable) {
            Logger.e(TAG, "injectStackView error: ${t.message}", "注入错误: ${t.message}", t)
        }
    }

    private fun attachOverlaySafely(activity: Activity, root: android.view.View) {
        try {
            activity.runOnUiThread {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    if (decor != null) {
                        var existing: android.view.View? = null
                        for (i in 0 until decor.childCount) {
                            val c = decor.getChildAt(i)
                            try {
                                val t = c.tag
                                if (t is String && t == "RecentsStackOverlay") { existing = c; break }
                            } catch (_: Throwable) {}
                        }
                        if (existing != null) {
                            Logger.d(TAG, "overlay-exists", "Overlay exists", "Overlay 已存在")
                        } else {
                            try {
                                root.tag = "RecentsStackOverlay"
                                val lp = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                decor.addView(root, lp)
                                Logger.d(TAG, "overlay-added", "Overlay added", "Overlay 已添加")
                            } catch (t: Throwable) {
                                Logger.e(TAG, "decor.addView failed: ${t.message}", "decor.addView 失败: ${t.message}", t)
                                try { activity.setContentView(root); Logger.d(TAG, "setContentView used", "回退 setContentView") } catch (_: Throwable) {}
                            }
                        }
                    } else {
                        try { activity.setContentView(root); Logger.d(TAG, "setContentView used", "setContentView") } catch (t: Throwable) { Logger.e(TAG, "setContentView failed: ${t.message}", "setContentView 失败: ${t.message}", t) }
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "ui attach error: ${t.message}", "UI 附加错误: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "attachOverlaySafely outer error: ${t.message}", "attachOverlaySafely 总体错误: ${t.message}", t)
        }
    }

    private fun dumpSystemUiDiagnostics(classLoader: ClassLoader) {
        try {
            val candidates = listOf(
                "com.android.systemui.recents.RecentsImplementation",
                "com.android.systemui.recents.OverviewProxyRecentsImpl",
                "com.android.systemui.recents.LauncherProxyService",
                "com.android.systemui.recents.OverviewProxyPolicy"
            )
            val dir = File("/sdcard/RecentsStack")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "systemui_dump_${System.currentTimeMillis()}.txt")
            val fw = FileWriter(out, false)
            fw.append("RecentsStack SystemUI Diagnostics\nTime: ${Date()}\n")
            for (n in candidates) {
                fw.append("---- Class: $n\n")
                try {
                    val cls = XposedHelpers.findClassIfExists(n, classLoader)
                    if (cls == null) {
                        fw.append("  -> NOT FOUND\n")
                        continue
                    }
                    fw.append("  -> FOUND\n  Declared fields:\n")
                    for (f in cls.declaredFields) {
                        try { fw.append("    field: ${f.name} : ${f.type.simpleName}\n") } catch (_: Throwable) {}
                    }
                    fw.append("  Declared methods:\n")
                    for (m in cls.declaredMethods) {
                        try {
                            fw.append("    method: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})\n")
                        } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    fw.append("  -> error: ${t.message}\n")
                }
            }
            fw.append("\nSystem properties:\n")
            try {
                val getprop = Class.forName("android.os.SystemProperties")
                val gm = getprop.getMethod("get", String::class.java)
                listOf("ro.product.model", "ro.build.version.release", "ro.build.version.sdk").forEach { k ->
                    try { fw.append("  $k = ${gm.invoke(null, k) as? String}\n") } catch (_: Throwable) { fw.append("  $k = <err>\n") }
                }
            } catch (_: Throwable) {}
            fw.flush()
            fw.close()
            Logger.d(TAG, "Wrote systemui diagnostics to ${out.absolutePath}", "已写入诊断到 ${out.absolutePath}")
        } catch (t: Throwable) {
            Logger.e(TAG, "dumpSystemUiDiagnostics err: ${t.message}", "诊断导出错误: ${t.message}", t)
        }
    }
}