package me.iacn.biliroaming.hook

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.utils.currentContext
import me.iacn.biliroaming.utils.getResId
import me.iacn.biliroaming.utils.sPrefs
import java.lang.reflect.Array
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class CopyCommentHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    private data class ReplyIpKey(
        val oid: String,
        val type: String,
        val rpid: String?
    ) {
        fun cacheKey(): String = "$type:$oid:${rpid.orEmpty()}"
    }

    /**
     * value 为空字符串表示已经请求过但失败了，避免同一条评论重复请求。
     */
    private val replyIpCache = ConcurrentHashMap<String, String>()

    override fun startHook() {
        if (!sPrefs.getBoolean("copy_comment", false)) return

        XposedHelpers.findAndHookMethod(
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder",
            mClassLoader,
            "y3",
            "kotlin.jvm.functions.Function1",
            "com.bilibili.app.comment3.data.model.CommentItem\$MenuItem",
            "android.view.View",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)

                    val menu = param.args[1]
                    val view = param.args[2] as View

                    if (!menu.toString().contains("COPY")) return

                    val clipboard =
                        currentContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val txt = clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?: ""

                    showCopyDialog(
                        context = view.context,
                        text = txt,
                        replyIpKey = extractReplyIpKey(param, view)
                    )
                }
            }
        )
    }

    private fun showCopyDialog(
        context: Context,
        text: CharSequence,
        replyIpKey: ReplyIpKey?
    ) {
        val contentTextView = TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
        }

        val ipTextView = TextView(context).apply {
            this.text = "IP属地：获取中..."
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, context.dp(12), 0, 0)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(24),
                context.dp(8),
                context.dp(24),
                0
            )
            addView(contentTextView)
            addView(ipTextView)
        }

        val dialog = AlertDialog.Builder(context, getResId("AppTheme.Dialog.Alert", "style"))
            .setTitle("自由复制内容")
            .setView(layout)
            .setPositiveButton("完成") { _, _ -> }
            .setNegativeButton("复制全部") { _, _ -> }
            .show()

        dialog.setOnShowListener {
            val key = replyIpKey

            if (key == null) {
                ipTextView.text = "IP属地：未获取到评论参数"
                return@setOnShowListener
            }

            loadReplyIpOnce(key) { location ->
                ipTextView.text = location ?: "IP属地：获取失败"
            }
        }
    }

    private fun loadReplyIpOnce(
        key: ReplyIpKey,
        callback: (String?) -> Unit
    ) {
        val cacheKey = key.cacheKey()

        replyIpCache[cacheKey]?.let {
            callback(it.ifBlank { null })
            return
        }

        Thread {
            val location = try {
                BiliRoamingApi.getCommentIpLocation(
                    oid = key.oid,
                    type = key.type,
                    rpid = key.rpid
                )
            } catch (e: Throwable) {
                null
            }

            replyIpCache[cacheKey] = location.orEmpty()

            Handler(Looper.getMainLooper()).post {
                callback(location)
            }
        }.start()
    }

    private fun extractReplyIpKey(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ): ReplyIpKey? {
        val roots = ArrayList<Any>()

        param.thisObject?.let { roots.add(it) }

        for (arg in param.args) {
            if (arg != null) roots.add(arg)
        }

        view.tag?.let { roots.add(it) }

        var oid: String? = null
        var rpid: String? = null
        var type: String? = null

        for (root in roots) {
            if (oid == null) {
                oid = root.findFieldValueAsString(
                    arrayOf(
                        "oid",
                        "oidStr",
                        "oid_str",
                        "subjectId",
                        "subject_id",
                        "resourceId",
                        "resource_id",
                        "aid",
                        "avid"
                    )
                )
            }

            if (rpid == null) {
                rpid = root.findFieldValueAsString(
                    arrayOf(
                        "rpid",
                        "rpidStr",
                        "rpid_str",
                        "replyId",
                        "reply_id",
                        "commentId",
                        "comment_id"
                    )
                )
            }

            if (type == null) {
                type = root.findFieldValueAsString(
                    arrayOf(
                        "type",
                        "replyType",
                        "reply_type",
                        "commentType",
                        "comment_type"
                    )
                )
            }

            if (oid != null && rpid != null && type != null) break
        }

        /**
         * 如果字段名被混淆，尝试从 toString() 中解析。
         * 例如：
         * oid=123456
         * rpid=987654
         * type=1
         */
        for (root in roots) {
            val text = root.toString()

            if (oid == null) {
                oid = findValueFromText(
                    text,
                    arrayOf(
                        "oid",
                        "oidStr",
                        "subjectId",
                        "subject_id",
                        "resourceId",
                        "resource_id",
                        "aid",
                        "avid"
                    )
                )
            }

            if (rpid == null) {
                rpid = findValueFromText(
                    text,
                    arrayOf(
                        "rpid",
                        "rpidStr",
                        "replyId",
                        "reply_id",
                        "commentId",
                        "comment_id"
                    )
                )
            }

            if (type == null) {
                type = findValueFromText(
                    text,
                    arrayOf(
                        "type",
                        "replyType",
                        "reply_type",
                        "commentType",
                        "comment_type"
                    )
                )
            }

            if (oid != null && rpid != null && type != null) break
        }

        if (oid.isNullOrBlank()) return null

        return ReplyIpKey(
            oid = oid!!,
            type = if (type.isNullOrBlank()) "1" else type!!,
            rpid = rpid
        )
    }

    private fun findValueFromText(
        text: String,
        names: Array<String>
    ): String? {
        for (name in names) {
            val regex = Regex("""$name\s*=\s*["']?(\d+)["']?""")
            val match = regex.find(text)
            val value = match?.groupValues?.getOrNull(1)

            if (!value.isNullOrBlank() && value != "0") {
                return value
            }
        }

        return null
    }

    private fun Any.findFieldValueAsString(
        names: Array<String>,
        maxDepth: Int = 4
    ): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun dfs(obj: Any?, depth: Int): String? {
            if (obj == null || depth < 0) return null
            if (obj is CharSequence || obj is Number || obj is Boolean) return null
            if (!visited.add(obj)) return null

            if (obj is Iterable<*>) {
                var count = 0
                val iterator = obj.iterator()
                while (iterator.hasNext() && count < 20) {
                    val item = iterator.next()
                    val result = dfs(item, depth - 1)
                    if (result != null) return result
                    count++
                }
                return null
            }

            val clazz = obj.javaClass

            if (clazz.isArray) {
                val length = Array.getLength(obj)
                val maxLength = if (length > 20) 20 else length

                for (i in 0 until maxLength) {
                    val result = dfs(Array.get(obj, i), depth - 1)
                    if (result != null) return result
                }

                return null
            }

            if (clazz.shouldSkipScan()) return null

            val fields = ArrayList<java.lang.reflect.Field>()
            var currentClass: Class<*>? = clazz

            while (currentClass != null) {
                for (field in currentClass.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers)) {
                        fields.add(field)
                    }
                }
                currentClass = currentClass.superclass
            }

            for (field in fields) {
                var matched = false

                for (name in names) {
                    if (field.name.equals(name, ignoreCase = true)) {
                        matched = true
                        break
                    }
                }

                if (!matched) continue

                val value = try {
                    field.isAccessible = true
                    field.get(obj)
                } catch (e: Throwable) {
                    null
                }

                val result = value.asNonZeroString()
                if (result != null) return result
            }

            if (depth == 0) return null

            for (field in fields) {
                if (field.type.shouldSkipScan()) continue

                val value = try {
                    field.isAccessible = true
                    field.get(obj)
                } catch (e: Throwable) {
                    null
                }

                val result = dfs(value, depth - 1)
                if (result != null) return result
            }

            return null
        }

        return dfs(this, maxDepth)
    }

    private fun Class<*>.shouldSkipScan(): Boolean {
        if (isPrimitive) return true
        if (View::class.java.isAssignableFrom(this)) return true
        if (Context::class.java.isAssignableFrom(this)) return true
        if (CharSequence::class.java.isAssignableFrom(this)) return true
        if (Number::class.java.isAssignableFrom(this)) return true
        if (Boolean::class.java.isAssignableFrom(this)) return true

        val className = name

        return className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("java.") ||
            className.startsWith("kotlin.")
    }

    private fun Any?.asNonZeroString(): String? {
        return when (this) {
            null -> null

            is Number -> {
                val value = toLong()
                if (value == 0L) null else value.toString()
            }

            is CharSequence -> {
                val value = toString().trim()
                if (value.isBlank() || value == "0") null else value
            }

            else -> {
                val value = toString().trim()
                if (value.isBlank() || value == "0" || value == "null") null else value
            }
        }
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
