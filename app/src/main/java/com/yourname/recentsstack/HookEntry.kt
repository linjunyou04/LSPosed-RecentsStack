package com.yourname.recentsstack

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileWriter
import java.util.*

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "RecentsStack"
        val RECENTS_CLASS_CANDIDATES = listOf(
            "com.android.systemui.recents.RecentsImplementation",
            "com.android.systemui.recents.OverviewProxyRecentsImpl",
            "com.android.systemui.recents.LauncherProxyService",
            "com.android.systemui.recents.OverviewProxyPolicy",
            "com.android.systemui.recents.RecentsActivity",
            "com.android.systemui.recents.OverviewActivity"
        )
    }

    // Throttle and limits
    private val lastDumpForActivity = mutableMapOf<String, Long>()
    private val DUMP_MIN_INTERVAL_MS = 1500L
    private val MAX_FULL_LOG_BYTES = 3 * 1024 * 1024 // 3MB

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            if (pkg != "com.android.systemui" && pkg != "com.meizu.systemui") return
            Logger.d(TAG, "Loaded package: $pkg", "已加载包：$pkg")

            // dump diagnostics to sdcard to help analysis
            try { dumpSystemUiDiagnostics(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "dumpSystemUiDiagnostics failed: ${t.message}", "系统诊断导出失败: ${t.message}", t) }

            // 1) Install global capture hooks (Activity, View, WindowManager)
            try { installGlobalCapture(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "installGlobalCapture failed: ${t.message}", "安装全局捕获失败: ${t.message}", t) }

            // 2) Robust method hooks with pattern matching
            try { addRobustMethodHooks(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "addRobustMethodHooks failed: ${t.message}", "添加稳健方法钩子失败: ${t.message}", t) }

            // 3) Keep legacy hooks for compatibility
            try { addHooksForRecentsMethods(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "addHooksForRecentsMethods failed: ${t.message}", "添加 recents 方法钩子失败: ${t.message}", t) }

        } catch (t: Throwable) {
            Logger.e(TAG, "handleLoadPackage error: ${t.message}", "加载包处理错误: ${t.message}", t)
        }
    }

    // ====== Global Capture Hooks ======
    private fun installGlobalCapture(classLoader: ClassLoader) {
        try {
            Logger.d(TAG, "installGlobalCapture start", "开始安装全局捕获")

            // Activity.onResume hook
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
                            } else {
                                Logger.d(TAG, "ACTIVITY_RESUME_THROTTLE", "Throttled dump for $name")
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "onResume hook err: ${t.message}", "onResume 钩子错误: ${t.message}", t)
                        }
                    }
                })
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook Activity failed: ${t.message}", "安装 Activity 钩子失败: ${t.message}", t)
            }

            // WindowManagerGlobal.addView hook
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
                                        Logger.d(TAG, "ADD_VIEW", "WindowManagerGlobal.addView: $viewClass")
                                        appendFullLogSafe("=== addView: $viewClass ===\n")
                                    } catch (_: Throwable) {}
                                }
                            })
                        } catch (t: Throwable) {
                            Logger.e(TAG, "hook addView method failed: ${t.message}", "钩子 addView 失败: ${t.message}", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook addView failed: ${t.message}", "安装 addView 钩子失败: ${t.message}", t)
            }

            // View.onAttachedToWindow hook
            try {
                val viewCls = Class.forName("android.view.View", false, classLoader)
                XposedBridge.hookAllMethods(viewCls, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            val clsName = v.javaClass.name
                            if (clsName.contains("recents", true) || clsName.contains("overview", true) || clsName.contains("task", true)) {
                                Logger.d(TAG, "RECENTS_VIEW_ATTACH", "View attached: $clsName")
                                appendFullLogSafe("=== RecentsViewAttached: $clsName ===\n")
                                val ctx = findContextFromObject(v)
                                if (ctx is Activity) dumpViewTree(ctx)
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "onAttachedToWindow hook err: ${t.message}", "onAttachedToWindow 钩子错误: ${t.message}", t)
                        }
                    }
                })
            } catch (t: Throwable) {
                Logger.e(TAG, "installGlobalCapture hook View failed: ${t.message}", "View 钩子安装失败: ${t.message}", t)
            }

            Logger.d(TAG, "installGlobalCapture done", "全局捕获安装完成")
        } catch (t: Throwable) {
            Logger.e(TAG, "installGlobalCapture general error: ${t.message}", "全局捕获总体错误: ${t.message}", t)
        }
    }

    // ====== Robust Method Hooks with Pattern Matching ======
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
                                                if (ctx is Activity) injectStackView(ctx, classLoader)
                                            } catch (t: Throwable) {
                                                Logger.e(TAG, "method-hook-after err: ${t.message}", "方法钩子后处理错误: ${t.message}", t)
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
                    Logger.e(TAG, "candidate loop error: ${t.message}", "循环候选出错: ${t.message}", t)
                }
            }

            Logger.d(TAG, "method-hook-summary", "hooked methods approx: $hooked")
            appendFullLogSafe("HOOK SUMMARY: hooked methods approx: $hooked\n")
        } catch (t: Throwable) {
            Logger.e(TAG, "addRobustMethodHooks failed: ${t.message}", "添加稳健方法钩子失败: ${t.message}", t)
        }
    }

    // ====== Legacy Method Hooks ======
    private fun addHooksForRecentsMethods(classLoader: ClassLoader) {
        val boolType = java.lang.Boolean.TYPE
        val candidates = listOf(
            "com.android.systemui.recents.RecentsImplementation",
            "com.android.systemui.recents.OverviewProxyRecentsImpl",
            "com.android.systemui.recents.LauncherProxyService"
        )

        for (clsName in candidates) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, classLoader)
                if (cls == null) {
                    Logger.d(TAG, "Candidate not found for method-hooks: $clsName", "候选类未找到（方法钩子）: $clsName")
                    continue
                }
                Logger.d(TAG, "Adding method hooks for $clsName", "为 $clsName 添加方法钩子")

                // showRecentApps(boolean)
                try {
                    XposedHelpers.findAndHookMethod(cls, "showRecentApps", boolType, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logger.d(TAG, "$clsName.showRecentApps() called", "$clsName.showRecentApps() 被调用")
                            try {
                                val ctx = findContextFromObject(param.thisObject) ?: (param.thisObject as? Activity)
                                if (ctx is Activity) injectStackView(ctx, classLoader)
                            } catch (t: Throwable) { Logger.e(TAG, "after showRecentApps hook error: ${t.message}", "showRecentApps 钩子后处理错误: ${t.message}", t) }
                        }
                    })
                } catch (_: Throwable) {}

                // toggleRecentApps()
                try {
                    XposedHelpers.findAndHookMethod(cls, "toggleRecentApps", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logger.d(TAG, "$clsName.toggleRecentApps() called", "$clsName.toggleRecentApps() 被调用")
                            try {
                                val ctx = findContextFromObject(param.thisObject) ?: (param.thisObject as? Activity)
                                if (ctx is Activity) injectStackView(ctx, classLoader)
                            } catch (t: Throwable) { Logger.e(TAG, "after toggleRecentApps hook error: ${t.message}", "toggleRecentApps 钩子后处理错误: ${t.message}", t) }
                        }
                    })
                } catch (_: Throwable) {}

            } catch (t: Throwable) {
                Logger.e(TAG, "addHooksForRecentsMethods error for $clsName: ${t.message}", "添加方法钩子错误: $clsName ${t.message}", t)
            }
        }
        Logger.d(TAG, "Completed adding method hooks for recents candidates", "已完成为 recents 候选添加方法钩子")
    }

    // ====== View Tree Dump ======
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
            Logger.e(TAG, "dumpViewTree err: ${t.message}", "View树dump失败: ${t.message}", t)
        }
    }

    // ====== Safe Log Append with Rotation ======
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

    // ====== Diagnostics Dump ======
    private fun dumpSystemUiDiagnostics(classLoader: ClassLoader) {
        val stamp = System.currentTimeMillis()
        val dir = File("/sdcard/RecentsStack")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "systemui_dump_${stamp}.txt")
        val fw = FileWriter(out, false)
        try {
            fw.append("RecentsStack SystemUI Diagnostics\n")
            fw.append("Time: ${Date()}\n")
            fw.append("Packages / classes tested:\n")
            for (clsName in RECENTS_CLASS_CANDIDATES) {
                fw.append("---- Class: ").append(clsName).append("\n")
                try {
                    val cls = XposedHelpers.findClassIfExists(clsName, classLoader)
                    if (cls == null) {
                        fw.append("  -> NOT FOUND\n")
                        continue
                    }
                    fw.append("  -> FOUND\n")
                    fw.append("  Declared fields:\n")
                    for (f in cls.declaredFields) {
                        try { fw.append("    field: ").append(f.name).append(" : ").append(f.type.simpleName).append("\n") } catch (_: Throwable) {}
                    }
                    fw.append("  Declared methods:\n")
                    for (m in cls.declaredMethods) {
                        try {
                            fw.append("    method: ").append(m.name).append("(")
                            m.parameterTypes.forEachIndexed { i, p -> if (i > 0) fw.append(","); fw.append(p.simpleName) }
                            fw.append(")\n")
                        } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    fw.append("  -> error reading class: ${t.message}\n")
                }
            }
            fw.append("\nSystem properties (getprop):\n")
            try {
                val getprop = Class.forName("android.os.SystemProperties")
                val g = getprop.getMethod("get", String::class.java)
                listOf("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint").forEach { key ->
                    try { val v = g.invoke(null, key) as? String; fw.append("  $key = ${v ?: "<null>"}\n") } catch (_: Throwable) { fw.append("  $key = <err>\n") }
                }
            } catch (_: Throwable) {}
            fw.append("\nEnd of diagnostics\n")
            fw.flush()
            Logger.d(TAG, "Wrote systemui diagnostics to ${out.absolutePath}", "已写入系统诊断到 ${out.absolutePath}")
        } finally {
            try { fw.close() } catch (_: Throwable) {}
        }
    }

    // ====== Inject Overlay with Duplicate Prevention ======
    private fun injectStackView(activity: Activity, cl: ClassLoader) {
        try {
            Logger.d(TAG, "injectStackView into ${activity.javaClass.name}", "尝试注入到 ${activity.javaClass.name}")
            val modulePkg = this.javaClass.`package`?.name ?: "com.yourname.recentsstack"
            val moduleCtx = try { activity.createPackageContext(modulePkg, Context.CONTEXT_IGNORE_SECURITY) } catch (_: Throwable) { activity }
            val inflater = activity.layoutInflater
            val layoutId = moduleCtx.resources.getIdentifier("custom_recents", "layout", modulePkg)
            if (layoutId == 0) {
                Logger.d(TAG, "module layout not found for injection", "模块布局未找到")
                return
            }
            val root = inflater.inflate(layoutId, null) as android.widget.FrameLayout
            val rvId = moduleCtx.resources.getIdentifier("stackRecycler", "id", modulePkg)
            val rv = if (rvId != 0) root.findViewById<androidx.recyclerview.widget.RecyclerView>(rvId) else null

            // Use safe overlay attach
            attachOverlaySafely(activity, root)

            try {
                rv?.layoutManager = StackLayoutManager(activity)
                val tasks = fetchTasksFallback(activity)
                rv?.adapter = RecentsStackAdapter(tasks)
            } catch (t: Throwable) {
                Logger.e(TAG, "set adapter/manager failed: ${t.message}", "设置 Adapter/Manager 失败: ${t.message}", t)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "injectStackView general error: ${t.message}", "注入总体错误: ${t.message}", t)
        }
    }

    // ====== Safe Overlay Attach (prevents duplicates) ======
    private fun attachOverlaySafely(activity: Activity, root: View) {
        try {
            activity.runOnUiThread {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    if (decor != null) {
                        var existing: View? = null
                        for (i in 0 until decor.childCount) {
                            val c = decor.getChildAt(i)
                            try {
                                val t = c.tag
                                if (t is String && t == "RecentsStackOverlay") {
                                    existing = c; break
                                }
                            } catch (_: Throwable) {}
                        }
                        if (existing != null) {
                            Logger.d(TAG, "overlay-exists", "Overlay already present, skipping add")
                        } else {
                            try {
                                root.tag = "RecentsStackOverlay"
                                val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                decor.addView(root, lp)
                                Logger.d(TAG, "overlay-added", "Overlay added to decor")
                            } catch (t: Throwable) {
                                Logger.e(TAG, "decor.addView failed: ${t.message}", "decor.addView 失败: ${t.message}", t)
                                try { activity.setContentView(root); Logger.d(TAG, "setContentView used as fallback", "回退使用 setContentView") } catch (_: Throwable) {}
                            }
                        }
                    } else {
                        try { activity.setContentView(root); Logger.d(TAG, "setContentView used", "使用 setContentView") } catch (t: Throwable) { Logger.e(TAG, "setContentView failed: ${t.message}", "setContentView 失败: ${t.message}", t) }
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "ui attach error: ${t.message}", "界面附加错误: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "attachOverlaySafely outer error: ${t.message}", "attachOverlaySafely 总体错误: ${t.message}", t)
        }
    }

    // ====== Fallback Task Fetch ======
    private fun fetchTasksFallback(ctx: Context): List<RecentTaskStub> {
        val out = ArrayList<RecentTaskStub>()
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = am.appTasks
            for (at in appTasks) {
                try {
                    val info = at.taskInfo
                    val pkg = info.baseIntent?.component?.packageName ?: (info.topActivity?.packageName ?: "unknown")
                    val cls = info.baseIntent?.component?.className ?: (info.topActivity?.className ?: "")
                    out.add(RecentTaskStub(pkg, cls, pkg, null))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return out
    }

    // ====== Helper: Find Context in Object ======
    private fun findContextFromObject(obj: Any?): Context? {
        try {
            if (obj == null) return null
            val fields = obj.javaClass.declaredFields
            for (f in fields) {
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v is Context) return v
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }
}
