package com.yourname.recentsstack

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            if (pkg != "com.android.systemui" && pkg != "com.meizu.systemui") return
            Logger.d(TAG, "Loaded package: $pkg", "已加载包：$pkg")

            // dump diagnostics to sdcard to help analysis
            try { dumpSystemUiDiagnostics(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "dumpSystemUiDiagnostics failed: ${t.message}", "系统诊断导出失败: ${t.message}", t) }

            // 1) keep activity hook (some ROMs use Activity)
            try { hookAllActivities(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "hookAllActivities failed: ${t.message}", "安装 Activity 钩子失败: ${t.message}", t) }

            // 2) key: hook recents methods (more reliable)
            try { addHooksForRecentsMethods(lpparam.classLoader) } catch (t: Throwable) { Logger.e(TAG, "addHooksForRecentsMethods failed: ${t.message}", "添加 recents 方法钩子失败: ${t.message}", t) }

        } catch (t: Throwable) {
            Logger.e(TAG, "handleLoadPackage error: ${t.message}", "加载包处理错误: ${t.message}", t)
        }
    }

    // Hook all Activity.onResume (keeps compatibility)
    private fun hookAllActivities(classLoader: ClassLoader) {
        try {
            val activityClass = Class.forName("android.app.Activity", false, classLoader)
            XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        Logger.d(TAG, "Activity resumed: $name", "Activity 恢复: $name")
                        if (name.contains("recents", true) || name.contains("overview", true) || name.contains("Recent", true)) {
                            Logger.d(TAG, "HIT Recents Activity: $name", "检测到 Recents Activity: $name")
                            try { injectStackView(activity, activity.classLoader) } catch (t: Throwable) { Logger.e(TAG, "injectStackView failed: ${t.message}", "注入视图失败: ${t.message}", t) }
                        }
                    } catch (t: Throwable) {
                        Logger.e(TAG, "onResume hook error: ${t.message}", "onResume 钩子错误: ${t.message}", t)
                    }
                }
            })
            Logger.d(TAG, "hookAllActivities installed", "已安装 Activity onResume 钩子")
        } catch (t: Throwable) {
            Logger.e(TAG, "hookAllActivities error: ${t.message}", "安装 Activity 钩子失败: ${t.message}", t)
        }
    }

    // Add method-level hooks on Recents controller classes
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

                // preloadRecentApps()
                try {
                    XposedHelpers.findAndHookMethod(cls, "preloadRecentApps", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logger.d(TAG, "$clsName.preloadRecentApps() called", "$clsName.preloadRecentApps() 被调用")
                        }
                    })
                } catch (_: Throwable) {}

                // hideRecentApps(bool,bool) or hideRecentApps(bool)
                try {
                    XposedHelpers.findAndHookMethod(cls, "hideRecentApps", boolType, boolType, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logger.d(TAG, "$clsName.hideRecentApps(bool,bool) called", "$clsName.hideRecentApps 被调用")
                        }
                    })
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.findAndHookMethod(cls, "hideRecentApps", boolType, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                Logger.d(TAG, "$clsName.hideRecentApps(bool) called", "$clsName.hideRecentApps 被调用")
                            }
                        })
                    } catch (_: Throwable) {}
                }

                if (clsName.contains("LauncherProxyService")) {
                    try {
                        XposedHelpers.findAndHookMethod(cls, "notifyToggleRecentApps", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                Logger.d(TAG, "LauncherProxyService.notifyToggleRecentApps() called", "LauncherProxyService.notifyToggleRecentApps 被调用")
                                try {
                                    val ctx = findContextFromObject(param.thisObject) ?: (param.thisObject as? Activity)
                                    if (ctx is Activity) injectStackView(ctx, classLoader)
                                } catch (t: Throwable) { Logger.e(TAG, "after notifyToggleRecentApps error: ${t.message}", "notifyToggleRecentApps 后处理错误: ${t.message}", t) }
                            }
                        })
                    } catch (_: Throwable) {}
                }

            } catch (t: Throwable) {
                Logger.e(TAG, "addHooksForRecentsMethods error for $clsName: ${t.message}", "添加方法钩子错误: $clsName ${t.message}", t)
            }
        }

        Logger.d(TAG, "Completed adding method hooks for recents candidates", "已完成为 recents 候选添加方法钩子")
    }

    // dump some diagnostics to /sdcard/RecentsStack/systemui_dump_TIMESTAMP.txt
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
                        Logger.d(TAG, "Candidate not found: $clsName", "候选类未找到：$clsName")
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
                    Logger.e(TAG, "error reading $clsName", "读取类出错：$clsName", t)
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

    // inject overlay (RecyclerView stack) - safe overlay add
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
            activity.runOnUiThread {
                try {
                    val decor = activity.window?.decorView as? android.view.ViewGroup
                    if (decor != null) {
                        val lp = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        decor.addView(root, lp)
                        Logger.d(TAG, "overlay added to decor", "已把 overlay 添加到 decor")
                    } else {
                        activity.setContentView(root)
                        Logger.d(TAG, "setContentView used", "使用 setContentView")
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "ui attach error: ${t.message}", "界面附加错误: ${t.message}", t)
                }
            }
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

    // fallback to ActivityManager appTasks - safe
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

    // try to find Context inside an object (helper)
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
