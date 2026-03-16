package com.yourname.recentsstack

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Field

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
        const val MODULE_PKG = "com.yourname.recentsstack"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            if (pkg != "com.android.systemui" && pkg != "com.meizu.systemui") return
            Logger.d(TAG, "Loaded package: $pkg")

            for (clsName in RECENTS_CLASS_CANDIDATES) {
                try {
                    val cls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue
                    Logger.d(TAG, "Found candidate: $clsName")

                    val methodNames = listOf("onCreate", "init", "onStart", "start")
                    for (mname in methodNames) {
                        try {
                            XposedHelpers.findAndHookMethod(cls, mname, Bundle::class.java, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        val thisObj = param.thisObject
                                        if (thisObj is Activity) {
                                            injectStackView(thisObj, lpparam.classLoader)
                                        } else {
                                            val ctx = findContextFromObject(thisObj)
                                            if (ctx is Activity) injectStackView(ctx, lpparam.classLoader)
                                        }
                                    } catch (t: Throwable) { Logger.d(TAG, "afterHook error: ${t.message}") }
                                }
                            })
                            Logger.d(TAG, "Hooked $clsName::$mname")
                        } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    Logger.d(TAG, "hook candidate error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Logger.d(TAG, "handleLoadPackage error: ${t.message}")
        }
    }

    private fun injectStackView(activity: Activity, cl: ClassLoader) {
        try {
            Logger.d(TAG, "injecting into ${activity.javaClass.name}")
            val moduleCtx = activity.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
            val inflater = LayoutInflater.from(moduleCtx)
            val root = inflater.inflate(moduleCtx.resources.getIdentifier("custom_recents", "layout", MODULE_PKG), null) as FrameLayout
            val rv = root.findViewById<RecyclerView>(moduleCtx.resources.getIdentifier("stackRecycler", "id", MODULE_PKG))
            if (rv == null) { Logger.d(TAG, "stackRecycler not found in module layout"); return }

            activity.runOnUiThread {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    if (decor != null) decor.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    else activity.setContentView(root)
                } catch (t: Throwable) { Logger.d(TAG, "attach error: ${t.message}") }
            }

            rv.layoutManager = StackLayoutManager(activity)
            val tasks = fetchTasks(activity, cl)
            rv.adapter = RecentsStackAdapter(tasks)
            dumpTasks(tasks)
        } catch (t: Throwable) { Logger.d(TAG, "injectStackView err: ${t.message}") }
    }

    private fun dumpTasks(tasks: List<RecentTaskStub>) {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "RecentsStack")
            if (!dir.exists()) dir.mkdirs()
            val tf = File(dir, "tasks.json")
            val fw = FileWriter(tf, false)
            fw.write(Gson().toJson(tasks))
            fw.flush(); fw.close()
        } catch (t: Throwable) { Logger.d(TAG, "dumpTasks err: ${t.message}") }
    }

    private fun fetchTasks(ctx: Context, classLoader: ClassLoader): List<RecentTaskStub> {
        try {
            for (clsName in RECENTS_CLASS_CANDIDATES) {
                try {
                    val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
                    for (f in cls.declaredFields) {
                        try {
                            f.isAccessible = true
                            val valObj = f.get(null)
                            if (valObj is Collection<*>) {
                                Logger.d(TAG, "Found static collection ${f.name} in $clsName")
                                return convertToStub(valObj)
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) { Logger.d(TAG, "reflection strategy1 err: ${t.message}") }

        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val out = ArrayList<RecentTaskStub>()
            try {
                val appTasks = am.appTasks
                for (at in appTasks) {
                    try {
                        val info = at.taskInfo
                        val pkg = info.baseIntent?.component?.packageName ?: (info.topActivity?.packageName ?: "unknown")
                        val cls = info.baseIntent?.component?.className ?: (info.topActivity?.className ?: "")
                        out.add(RecentTaskStub(pkg, cls, pkg, null))
                    } catch (_: Throwable) {}
                }
                if (out.isNotEmpty()) { Logger.d(TAG, "ActivityManager returned ${out.size} tasks"); return out }
            } catch (t: Throwable) { Logger.d(TAG, "ActivityManager.appTasks err: ${t.message}") }
        } catch (t: Throwable) { Logger.d(TAG, "AM fetch err: ${t.message}") }

        return emptyList()
    }

    private fun convertToStub(col: Collection<*>?): List<RecentTaskStub> {
        val out = ArrayList<RecentTaskStub>()
        if (col == null) return out
        for (it in col) {
            if (it == null) continue
            try {
                var pkg = ""
                var title = ""
                try {
                    val pn = safeFindField(it.javaClass, "packageName", "pkg", "package")
                    if (pn != null) { val v = pn.get(it); if (v is String) pkg = v }
                } catch (_: Throwable) {}
                try {
                    val t = safeFindField(it.javaClass, "title", "label")
                    if (t != null) { val v = t.get(it); if (v is String) title = v }
                } catch (_: Throwable) {}
                out.add(RecentTaskStub(if (pkg.isNotBlank()) pkg else "unknown", "", if (title.isNotBlank()) title else pkg, null))
            } catch (_: Throwable) {}
        }
        return out
    }

    private fun safeFindField(cls: Class<*>, vararg names: String): Field? {
        for (n in names) {
            try {
                val f = cls.getDeclaredField(n)
                f.isAccessible = true
                return f
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findContextFromObject(obj: Any): Context? {
        try {
            for (f in obj.javaClass.declaredFields) {
                try { f.isAccessible = true; val v = f.get(obj); if (v is Context) return v } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }
}
