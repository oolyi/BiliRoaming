package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.utils.*
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class CopyHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        private val DYNAMIC_COPYABLE_IDS = arrayOf(
            "dy_card_text",
            "dy_opus_paragraph_desc",
            "dy_opus_paragraph_title",
            "dy_opus_copy_right_id",
            "dy_opus_paragraph_text",
        )
    }

    private data class ReplyIpKey(
        val oid: String,
        val type: String,
        val rpid: String?
    ) {
        fun cacheKey(): String = "$type:$oid:${rpid.orEmpty()}"
    }

    private val enhanceLongClickCopy = sPrefs.getBoolean("comment_copy_enhance", false)

    /**
     * value 为 "" 表示已经请求过但失败了，避免同一条评论反复请求。
     */
    private val replyIpCache = ConcurrentHashMap<String, String>()

    override fun startHook() {
        if (!sPrefs.getBoolean("comment_copy", false)) return

        instance.descCopyView().zip(instance.descCopy()).forEach { p ->
            val clazz = p.first ?: return@forEach
            val method = p.second ?: return@forEach
            clazz.replaceMethod(
                method,
                View::class.java,
                ClickableSpan::class.java
            ) { param ->
                if (!enhanceLongClickCopy) return@replaceMethod Unit

                param.thisObject.getFirstFieldByExactTypeOrNull<SpannableStringBuilder>()?.let {
                    val view = param.args[0] as View
                    showCopyDialog(view.context, it, param)
                } ?: (param.args[0] as? TextView)?.let { tv ->
                    showCopyDialog(tv.context, tv.text, param)
                }
            }
        }

        instance.dynamicDescHolderListeners().forEach { c ->
            c?.replaceMethod("onLongClick", View::class.java) { param ->
                if (!enhanceLongClickCopy)
                    return@replaceMethod true
                val itemView = param.args[0] as? View
                DYNAMIC_COPYABLE_IDS.asSequence().firstNotNullOfOrNull { n ->
                    getId(n).takeIf { it != 0 }?.let { itemView?.findViewById<TextView>(it) }
                }?.let { v ->
                    (if (instance.ellipsizingTextViewClass?.isInstance(v) == true) {
                        v.getFirstFieldByExactTypeOrNull()
                    } else v.text)?.also { text ->
                        showCopyDialog(v.context, text, param)
                    }
                } ?: Log.toast("找不到动态内容", true)
                true
            }
        }

        val commentCopyHook = fun(param: MethodHookParam, idName: String): Any? {
            if (!enhanceLongClickCopy) return true
            if (param.args[0] is FrameLayout) return param.invokeOriginalMethod()
            (param.args[0] as? View)?.findViewById<View>(getId(idName))?.let {
                if (instance.commentSpanTextViewClass?.isInstance(it) == true ||
                    instance.commentSpanEllipsisTextViewClass?.isInstance(it) == true
                ) it else null
            }?.let { view ->
                view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                    showCopyDialog(
                        context = view.context,
                        text = text,
                        param = param,
                        replyIpKey = param.extractReplyIpKey()
                    )
                }
            } ?: Log.toast("找不到评论内容", true)
            return true
        }

        instance.commentCopyClass?.replaceMethod("onLongClick", View::class.java) {
            commentCopyHook(it, "message")
        }

        instance.commentCopyNewClass?.replaceMethod("onLongClick", View::class.java) {
            commentCopyHook(it, "comment_message")
        }

        instance.comment3CopyClass?.let { c ->
            instance.comment3Copy()?.let { m ->
                instance.comment3ViewIndex().let { i ->
                    c.replaceAllMethods(m) { param ->
                        if (!enhanceLongClickCopy) return@replaceAllMethods true
                        val view = param.args[i] as View
                        view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                            showCopyDialog(
                                context = view.context,
                                text = text,
                                param = param,
                                replyIpKey = param.extractReplyIpKey()
                            )
                        }
                        return@replaceAllMethods true
                    }
                }
            }
        }

        if (!enhanceLongClickCopy) return

        val onClickName = instance.onOperateClick() ?: return
        val contentStringName = instance.getContentString() ?: return
        val hostClassName = instance.operateClickHostClass() ?: return
        val hostClass = hostClassName.from(mClassLoader) ?: return
        val hookMethod = hostClass.declaredMethods.find { it.name == onClickName } ?: return

        fun parseContentText(json: String): String? = runCatchingOrNull { json.toJSONObject() }?.run {
            optString("content").ifEmpty {
                buildString {
                    appendLine(optString("title").trim())
                    appendLine(optString("text").trim())
                    optJSONArray("modules")?.run {
                        asSequence<JSONObject>().map {
                            it.optString("title") + "：" + it.optString("detail")
                        }.joinToString("\n").run { append(this) }
                    }
                }.run { removeSuffix("\n") }
            }.ifEmpty { null }
        }

        hookMethod.hookBeforeMethod { param ->
            // Repost guard: last arg == first arg
            if (param.args.size >= 2 && param.args.last() != param.args.first()) return@hookBeforeMethod

            val hostClass = param.thisObject.javaClass

            // Activity: try this first, then search for captured field
            val activity = (param.thisObject as? Activity)
                ?: hostClass.declaredFields.find {
                    Activity::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.get(param.thisObject) as? Activity
                ?: return@hookBeforeMethod

            // Typed message: try args[1] first, then search for field with getContentString
            val typedMsg = param.args.getOrNull(1)
                ?: hostClass.declaredFields.find {
                    runCatching { it.type.getMethod(contentStringName) }.isSuccess
                }?.apply { isAccessible = true }?.get(param.thisObject)
                ?: return@hookBeforeMethod

            val json = typedMsg.callMethodOrNullAs<String>(contentStringName) ?: return@hookBeforeMethod
            val text = parseContentText(json) ?: return@hookBeforeMethod
            showCopyDialog(activity, text, param)

            // Dismiss popup: try args[6] first, then search for PopupWindow field
            (param.args.getOrNull(6)
                ?: hostClass.declaredFields.find {
                    PopupWindow::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.get(param.thisObject))
                ?.callMethodOrNull("dismiss")

            param.result = null
        }
    }

    private fun showCopyDialog(
        context: Context,
        text: CharSequence,
        param: MethodHookParam,
        replyIpKey: ReplyIpKey? = null
    ) {
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")

        if (replyIpKey == null) {
            AlertDialog.Builder(context, appDialogTheme).run {
                setTitle("自由复制内容")
                setMessage(text)
                setPositiveButton("分享") { _, _ ->
                    shareText(context, text)
                }
                setNeutralButton("复制全部") { _, _ ->
                    param.invokeOriginalMethod()
                }
                setNegativeButton(android.R.string.cancel, null)
                show()
            }.apply {
                findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
            return
        }

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

        val contentLayout = LinearLayout(context).apply {
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

        AlertDialog.Builder(context, appDialogTheme).run {
            setTitle("自由复制内容")
            setView(contentLayout)
            setPositiveButton("分享") { _, _ ->
                shareText(context, text)
            }
            setNeutralButton("复制全部") { _, _ ->
                param.invokeOriginalMethod()
            }
            setNegativeButton(android.R.string.cancel, null)
            show()
        }.also {
            loadReplyIpOnce(replyIpKey) { location ->
                ipTextView.text = location ?: "IP属地：获取失败"
            }
        }
    }

    private fun shareText(context: Context, text: CharSequence) {
        context.startActivity(
            Intent.createChooser(
                Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                },
                "分享评论内容"
            )
        )
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
            val location = runCatching {
                BiliRoamingApi.getCommentIpLocation(
                    oid = key.oid,
                    type = key.type,
                    rpid = key.rpid
                )
            }.getOrNull()

            replyIpCache[cacheKey] = location.orEmpty()

            Handler(Looper.getMainLooper()).post {
                callback(location)
            }
        }.start()
    }

    private fun MethodHookParam.extractReplyIpKey(): ReplyIpKey? {
        val roots = buildList {
            add(this@extractReplyIpKey.thisObject)
            this@extractReplyIpKey.args.forEach { add(it) }
        }.filterNotNull()

        val oid = roots.firstNotNullOfOrNull {
            it.findFieldValueAsString(
                names = setOf(
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

        val rpid = roots.firstNotNullOfOrNull {
            it.findFieldValueAsString(
                names = setOf(
                    "rpid",
                    "rpidStr",
                    "rpid_str",
                    "replyId",
                    "reply_id",
                    "commentId",
                    "comment_id"
                )
            )
        } ?: roots.firstNotNullOfOrNull {
            it.findFieldValueAsString(
                names = setOf("id"),
                maxDepth = 2
            )
        }

        val type = roots.firstNotNullOfOrNull {
            it.findFieldValueAsString(
                names = setOf(
                    "type",
                    "replyType",
                    "reply_type",
                    "commentType",
                    "comment_type"
                )
            )
        } ?: "1"

        if (oid.isNullOrBlank()) return null

        return ReplyIpKey(
            oid = oid,
            type = type,
            rpid = rpid
        )
    }

    private fun Any.findFieldValueAsString(
        names: Set<String>,
        maxDepth: Int = 3
    ): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun dfs(obj: Any?, depth: Int): String? {
            if (obj == null || depth < 0) return null
            if (obj is CharSequence || obj is Number || obj is Boolean) return null
            if (!visited.add(obj)) return null

            if (obj is Iterable<*>) {
                obj.take(20).forEach { item ->
                    dfs(item, depth - 1)?.let { return it }
                }
                return null
            }

            val clazz = obj.javaClass

            if (clazz.isArray) {
                val length = java.lang.reflect.Array.getLength(obj).coerceAtMost(20)
                for (i in 0 until length) {
                    dfs(java.lang.reflect.Array.get(obj, i), depth - 1)?.let { return it }
                }
                return null
            }

            if (clazz.shouldSkipScan()) return null

            val fields = generateSequence(clazz) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filterNot { Modifier.isStatic(it.modifiers) }
                .toList()

            fields.firstOrNull { field ->
                names.any { it.equals(field.name, ignoreCase = true) }
            }?.let { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(obj)
                }.getOrNull()

                value.asNonZeroString()?.let { return it }
            }

            if (depth == 0) return null

            fields.forEach { field ->
                if (field.type.shouldSkipScan()) return@forEach
                val value = runCatching {
                    field.isAccessible = true
                    field.get(obj)
                }.getOrNull()

                dfs(value, depth - 1)?.let { return it }
            }

            return null
        }

        return dfs(this, maxDepth)
    }

    private fun Class<*>.shouldSkipScan(): Boolean {
        if (isPrimitive) return true
        if (View::class.java.isAssignableFrom(this)) return true
        if (Context::class.java.isAssignableFrom(this)) return true
        if (Activity::class.java.isAssignableFrom(this)) return true
        if (CharSequence::class.java.isAssignableFrom(this)) return true
        if (Number::class.java.isAssignableFrom(this)) return true
        if (Boolean::class.java.isAssignableFrom(this)) return true

        val name = name
        return name.startsWith("android.") ||
            name.startsWith("androidx.") ||
            name.startsWith("java.") ||
            name.startsWith("kotlin.")
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
                value.takeIf { it.isNotBlank() && it != "0" }
            }
            else -> {
                val value = toString().trim()
                value.takeIf {
                    it.isNotBlank() &&
                        it != "0" &&
                        it != "null"
                }
            }
        }
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
