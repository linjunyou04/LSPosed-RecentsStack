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
            if (pkg != "com.android.systemui") return
            Logger.d(TAG, "Loaded package: $pkg", "已加载包：$pkg")

            // run quick diagnostics and dump to file
            try {
                dumpSystemUiDiagnostics(lpparam.classLoader)
            } catch (t: Throwable) {
                Logger.e(TAG, "dumpSystemUiDiagnostics failed", "系统诊断导出失败", t)
            }

            // Hook all activities onResume to catch Recents openings
            hookAllActivities(lpparam.classLoader)
        } catch (t: Throwable) {
            Logger.e(TAG, "handleLoadPackage error: ${t.message}", "加载包处理错误: ${t.message}", t)
        }
    }

    /** Write a snapshot of candidate classes, their fields and methods into a timestamped file on sdcard */
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
                        try {
                            fw.append("    field: ").append(f.name).append(" : ").append(f.type.simpleName).append("\n")
                        } catch (_: Throwable) {}
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
                    Logger.e(TAG, "error reading ${clsName}", "读取类出错：$clsName", t)
                }
            }

            // Write some basic system props using reflection when possible
            fw.append("\nSystem properties (getprop):\n")
            try {
                val getprop = Class.forName("android.os.SystemProperties")
                val g = getprop.getMethod("get", String::class.java)
                listOf("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint").forEach { key ->
                    try {
                        val v = g.invoke(null, key) as? String
                        fw.append("  $key = ${v ?: "<null>"}\n")
                    } catch (_: Throwable) { fw.append("  $key = <err>\n") }
                }
            } catch (_: Throwable) { /* ignore */ }

            fw.append("\nEnd of diagnostics\n")
            fw.flush()
            Logger.d(TAG, "Wrote systemui diagnostics to ${out.absolutePath}", "已写入系统诊断到 ${out.absolutePath}")
        } finally {
            try { fw.close() } catch (_: Throwable) {}
        }
    }

    private fun hookAllActivities(classLoader: ClassLoader) {
        try {
            val activityCls = Class.forName("android.app.Activity", false, classLoader)
            XposedBridge.hookAllMethods(activityCls, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        Logger.d(TAG, "Activity resumed: $name", "Activity 恢复: $name")
                        if (name.contains("recents", true) || name.contains("overview", true) || name.contains("Recent", true)) {
                            Logger.d(TAG, "HIT Recents Activity: $name", "检测到 Recents Activity: $name")
                            try {
                                injectStackView(activity, activity.classLoader)
                            } catch (t: Throwable) {
                                Logger.e(TAG, "injectStackView failed: ${t.message}", "注入视图失败: ${t.message}", t)
                            }
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

    // reuse your existing injectStackView logic (ensure it writes logs too)
    private fun injectStackView(activity: Activity, cl: ClassLoader) {
        try {
            Logger.d(TAG, "injectStackView into ${activity.javaClass.name}", "尝试注入到 ${activity.javaClass.name}")
            val modulePkg = this.javaClass.`package`?.name ?: "com.yourname.recentsstack"
            val moduleCtx = try {
                activity.createPackageContext(modulePkg, Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Throwable) { activity }
            val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as android.view.LayoutInflater
            // try inflate from module if possible
            try {
                val layoutId = moduleCtx.resources.getIdentifier("custom_recents", "layout", modulePkg)
                if (layoutId != 0) {
                    val root = inflater.inflate(layoutId, null) as android.widget.FrameLayout
                    val rvId = moduleCtx.resources.getIdentifier("stackRecycler", "id", modulePkg)
                    val rv = if (rvId != 0) root.findViewById<androidx.recyclerview.widget.RecyclerView>(rvId) else null
                    activity.runOnUiThread {
                        try {
                            val decor = activity.window?.decorView as? android.view.ViewGroup
                            if (decor != null) {
                                decor.addView(root, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                                Logger.d(TAG, "overlay added to decor", "已把 overlay 添加到 decor")
                            } else {
                                activity.setContentView(root)
                                Logger.d(TAG, "setContentView used", "使用 setContentView")
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "ui attach error: ${t.message}", "界面附加错误: ${t.message}", t)
                        }
                    }
                } else {
                    Logger.d(TAG, "module layout not found for injection", "模块布局未找到")
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "inflate/attach error: ${t.message}", "inflate/attach 错误: ${t.message}", t)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "injectStackView general error: ${t.message}", "注入总体错误: ${t.message}", t)
        }
    }

    // convert fallback fetch if needed (kept minimal to avoid crashes)
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
}
