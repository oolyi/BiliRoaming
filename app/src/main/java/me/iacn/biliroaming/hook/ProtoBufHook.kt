package me.iacn.biliroaming.hook

import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.content.DialogInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.Constant
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.utils.*
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ProtoBufHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        fun Any.removeCmdDms() {
            callMethod("clearActivityMeta")
            runCatchingOrNull {
                callMethod("clearCommand")
            }
            setObjectField("unknownFields", instance.unknownFieldSetLiteInstance)
        }
    }

    private val mainListReplyClass by Weak { "com.bapis.bilibili.main.community.reply.v1.MainListReply" from mClassLoader }
    private val emptyPageV1Class by Weak { "com.bapis.bilibili.main.community.reply.v1.EmptyPage" from mClassLoader }
    private val textV1Class by Weak { "com.bapis.bilibili.main.community.reply.v1.EmptyPage\$Text" from mClassLoader }
    private val textStyleV1Class by Weak { "com.bapis.bilibili.main.community.reply.v1.TextStyle" from mClassLoader }
    private val textV2Class by Weak { "com.bapis.bilibili.main.community.reply.v2.EmptyPage\$Text" from mClassLoader }
    private val textStyleV2Class by Weak { "com.bapis.bilibili.main.community.reply.v2.TextStyle" from mClassLoader }
    private val videoGuideClass by Weak { "com.bapis.bilibili.app.view.v1.VideoGuide" from mClassLoader }


    private val unlockPlayLimit = sPrefs.getBoolean("play_arc_conf", false)
    private val blockCommentGuide = sPrefs.getBoolean("block_comment_guide", false)
    private val disableMainPageStory = sPrefs.getBoolean("disable_main_page_story", false)

    // 以下为隐藏功能配置
    private val hidden = sPrefs.getBoolean("hidden", false)

    private val removeHonor = hidden && sPrefs.getBoolean("remove_video_honor", false)
    private val removeUgcSeason = hidden && sPrefs.getBoolean("remove_video_UgcSeason", false)
    private val removeCmdDms = hidden && sPrefs.getBoolean("remove_video_cmd_dms", false)
    private val removeUpVipLabel = hidden && sPrefs.getBoolean("remove_up_vip_label", false)

    // 搜索过滤
    private val searchFilterUid = run {
        sPrefs.getStringSet("search_filter_keyword_uid", null)
            ?.mapNotNull { it.toLongOrNull() }.orEmpty()
    }
    private val searchFilterContents = run {
        sPrefs.getStringSet("search_filter_keyword_content", null).orEmpty()
    }
    private val searchFilterContentRegexes by lazy { searchFilterContents.map { it.toRegex() } }
    private val searchFilterContentRegexMode = sPrefs.getBoolean("search_filter_content_regex_mode", false)
    private val searchFilterUpNames = run {
        sPrefs.getStringSet("search_filter_keyword_upname", null).orEmpty()
    }
    private val searchRemoveRelatePromote = sPrefs.getBoolean("search_filter_remove_relate_promote", false)

    // 评论过滤
    private val commentFilterBlockAtComment = sPrefs.getBoolean("comment_filter_block_at_comment", false)
    private val commentFilterAtUid = run {
        sPrefs.getStringSet("comment_filter_keyword_at_uid", null)
            ?.mapNotNull { it.toLongOrNull() }.orEmpty()
    }
    private val commentFilterContents = run {
        sPrefs.getStringSet("comment_filter_keyword_content", null).orEmpty()
    }
    private val commentFilterContentRegexes by lazy { commentFilterContents.map { it.toRegex() } }
    private val commentFilterContentRegexMode = sPrefs.getBoolean("comment_filter_content_regex_mode", false)
    private val commentFilterAtUpNames = run {
        sPrefs.getStringSet("comment_filter_keyword_at_upname", null).orEmpty()
    }
    private val targetCommentAuthorLevel = sPrefs.getLong("target_comment_author_level", 0L)

    private val purifySearch = hidden && sPrefs.getBoolean("purify_search", false)
    private val purifyCity = hidden && sPrefs.getBoolean("purify_city", false)
    private val purifyCampus = hidden && sPrefs.getBoolean("purify_campus", false)

    private val blockLiveOrder = hidden && sPrefs.getBoolean("block_live_order", false)
    private val blockWordSearch = hidden && sPrefs.getBoolean("block_word_search", false)
    private val blockModules = hidden && sPrefs.getBoolean("block_modules", false)
    private val blockUpperRecommendAd = hidden && sPrefs.getBoolean("block_upper_recommend_ad", false)
    private val blockVideoComment = hidden && sPrefs.getBoolean("block_video_comment", false)
    private val blockViewPageAds = hidden && sPrefs.getBoolean("block_view_page_ads", false)

    private data class CommentIpContext(
        val oid: Long,
        val type: Long,
        val rpid: Long,
        val root: Long,
        val dialog: Long
    )

    private val commentIpContextMap = ConcurrentHashMap<Long, CommentIpContext>()

    private val commentIpCache: MutableMap<Long, String> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean {
                return size > 300
            }
        }
    )

    private val commentIpMenuTitle = "显示 IP 属地"


    override fun startHook() {

        hookMossView()
        hookCommentIpContext()
        hookCommentLongPressMenu()

        if (hidden && (purifyCity || purifyCampus)) {
            listOf(
                "com.bapis.bilibili.app.dynamic.v1.DynTabReply",
                "com.bapis.bilibili.app.dynamic.v2.DynTabReply"
            ).forEach { clazz ->
                clazz.hookAfterMethod(
                    mClassLoader,
                    "getDynTabList"
                ) { param ->
                    param.result = (param.result as List<*>).filterNot {
                        purifyCity && it?.callMethodAs<Long>("getCityId") != 0L
                                || purifyCampus && it?.callMethodAs<String>("getAnchor") == "campus"
                    }
                }
            }
        }

        if (hidden && removeCmdDms) {
            instance.viewMossClass?.hookAfterMethod(
                if (instance.useNewMossFunc) "executeViewProgress" else "viewProgress",
                "com.bapis.bilibili.app.view.v1.ViewProgressReq"
            ) { param ->
                param.result?.callMethod("setVideoGuide", videoGuideClass?.new())
            }
            instance.viewUniteMossClass?.hookAfterMethod(
                if (instance.useNewMossFunc) "executeViewProgress" else "viewProgress",
                "com.bapis.bilibili.app.viewunite.v1.ViewProgressReq"
            ) { param ->
                param.result?.run {
                    callMethod("clearDm")
                    callMethod("getVideoGuide")?.callMethod("clearContractCard")
                }
            }
            instance.viewMossClass?.replaceMethod(
                if (instance.useNewMossFunc) "executeTFInfo" else "tFInfo",
                "com.bapis.bilibili.app.view.v1.TFInfoReq"
            ) { null }
            instance.dmMossClass?.hookAfterMethod(
                if (instance.useNewMossFunc) "executeDmView" else "dmView",
                instance.dmViewReqClass,
            ) { it.result?.removeCmdDms() }
        }
        if (hidden && purifySearch) {
            "com.bapis.bilibili.app.interfaces.v1.SearchMoss".hookAfterMethod(
                mClassLoader,
                if (instance.useNewMossFunc) "executeDefaultWords" else "defaultWords",
                "com.bapis.bilibili.app.interfaces.v1.DefaultWordsReq"
            ) { param ->
                param.result = null
            }
        }
        if (hidden && blockWordSearch) {
            "com.bapis.bilibili.main.community.reply.v1.Content".hookAfterMethod(
                mClassLoader,
                "internalGetUrls"
            ) { param ->
                (param.result as LinkedHashMap<*, *>?)?.let { m ->
                    val iterator = m.iterator()
                    while (iterator.hasNext()) {
                        iterator.next().value.callMethodAs<String?>("getAppUrlSchema")
                            ?.takeIf {
                                it.startsWith("bilibili://search?from=appcommentline_search")
                            }?.run {
                                iterator.remove()
                            }
                    }
                }
            }
        }
        if (hidden && blockModules) {
            "com.bapis.bilibili.app.resource.v1.ModuleMoss".hookAfterMethod(
                mClassLoader,
                if (instance.useNewMossFunc) "executeList" else "list",
                "com.bapis.bilibili.app.resource.v1.ListReq"
            ) {
                it.result?.callMethod("clearPools")
            }
        }
        if (hidden && blockUpperRecommendAd) {
            "com.bapis.bilibili.ad.v1.SourceContentDto".from(mClassLoader)
                ?.replaceMethod("getAdContent") { null }
        }
        if (disableMainPageStory) {
            "com.bapis.bilibili.app.distribution.setting.experimental.MultipleTusConfig"
                .from(mClassLoader)?.hookAfterMethod("getTopLeft") { param ->
                    param.result?.runCatchingOrNull {
                        callMethod("clearBadge")
                        callMethod("clearListenBackgroundImage")
                        callMethod("clearListenForegroundImage")
                        callMethod("clearStoryBackgroundImage")
                        callMethod("clearStoryForegroundImage")
                        val tabUrl = "bilibili://root?tab_name=我的"
                        callMethod("getUrl")?.callMethod("setValue", tabUrl)
                        callMethod("getUrlV2")?.callMethod("setValue", tabUrl)
                        callMethod("getGoto")?.callMethod("setValue", "1")
                        callMethod("getGotoV2")?.callMethod("setValue", 1)
                    }
                }
        }
        if (blockCommentGuide || (hidden && blockVideoComment)) {
            "com.bapis.bilibili.main.community.reply.v1.ReplyMoss".hookBeforeMethod(
                mClassLoader,
                if (instance.useNewMossFunc) "executeMainList" else "mainList",
                "com.bapis.bilibili.main.community.reply.v1.MainListReq",
            ) { param ->
                val type = param.args[0].callMethodAs<Long>("getType")
                if (hidden && blockVideoComment && type == 1L) {
                    val reply = mainListReplyClass?.new()?.apply {
                        val subjectControl = callMethod("getSubjectControl")
                        val emptyPage = emptyPageV1Class?.new()?.also {
                            subjectControl?.callMethod("setEmptyPage", it)
                        }
                        emptyPage?.callMethod(
                            "setImageUrl",
                            "https://i0.hdslb.com/bfs/app-res/android/img_holder_forbid_style1.webp"
                        )
                        textV1Class?.new()?.apply {
                            callMethod("setRaw", "评论区已由漫游屏蔽")
                            textStyleV1Class?.new()?.apply {
                                callMethod("setFontSize", 14)
                                callMethod("setTextDayColor", "#FF61666D")
                                callMethod("setTextNightColor", "#FFA2A7AE")
                            }?.let {
                                callMethod("setStyle", it)
                            }
                        }?.let {
                            emptyPage?.callMethod("addTexts", it)
                        }
                    }
                    param.result = reply
                    return@hookBeforeMethod
                }
                if (!blockCommentGuide) return@hookBeforeMethod
                param.result.runCatchingOrNull {
                    callMethod("getSubjectControl")?.run {
                        callMethod("clearEmptyBackgroundTextPlain")
                        callMethod("clearEmptyBackgroundTextHighlight")
                        callMethod("clearEmptyBackgroundUri")
                        callMethod("getEmptyPage")?.let { page ->
                            page.callMethod("clearLeftButton")
                            page.callMethod("clearRightButton")
                            page.callMethodAs<List<Any>>("getTextsList").takeIf { it.size > 1 }
                                ?.let {
                                    page.callMethod("clearTexts")
                                    page.callMethod("addTexts", it.first().apply {
                                        callMethod("setRaw", "还没有评论哦")
                                    })
                                }
                        }
                    }
                }
            }
            "com.bapis.bilibili.main.community.reply.v2.ReplyMoss".from(mClassLoader)
                ?.hookBeforeMethod(
                    if (instance.useNewMossFunc) "executeSubjectDescription" else "subjectDescription",
                    "com.bapis.bilibili.main.community.reply.v2.SubjectDescriptionReq"
                ) { param ->
                    val defaultText = textV2Class?.new()?.apply {
                        val tipStr = if (hidden && blockVideoComment) {
                            "评论区已由漫游屏蔽"
                        } else {
                            "还没有评论哦"
                        }
                        callMethod("setRaw", tipStr)
                        textStyleV2Class?.new()?.apply {
                            callMethod("setFontSize", 14)
                            callMethod("setTextDayColor", "#FF61666D")
                            callMethod("setTextNightColor", "#FFA2A7AE")
                        }?.let {
                            callMethod("setStyle", it)
                        }
                    } ?: return@hookBeforeMethod
                    param.result.runCatchingOrNull {
                        callMethod("getEmptyPage")?.run {
                            callMethod("clearLeftButton")
                            callMethod("clearRightButton")
                            callMethod("ensureTextsIsMutable")
                            callMethodAs<MutableList<Any>>("getTextsList").run {
                                clear()
                                add(defaultText)
                            }
                            if (!(hidden && blockVideoComment)) return@run
                            callMethod(
                                "setImageUrl",
                                "https://i0.hdslb.com/bfs/app-res/android/img_holder_forbid_style1.webp"
                            )
                        }
                    }
                }
        }
        val needSearchFilter = hidden and (searchFilterContents.isNotEmpty() or searchFilterUid.isNotEmpty() or searchFilterUpNames.isNotEmpty()) or searchRemoveRelatePromote
        if (needSearchFilter) {
            instance.searchAllResponseClass?.hookAfterMethod("getItemList") { p ->
                val items = p.result as? List<Any?> ?: return@hookAfterMethod
                p.result = items.filter { item ->
                    val videoCard = item?.getObjectField("cardItem_") ?: return@filter true
                    if (instance.searchVideoCardClass?.isInstance(videoCard) == false) {
                        if (searchRemoveRelatePromote) {
                            if (item.callMethodAs<Boolean>("hasCm")) return@filter false
                            if (item.callMethodAs<Boolean>("hasSpecial")) return@filter false
                        }
                        return@filter true
                    }
                    if (videoCard.getLongField("mid_") in searchFilterUid) return@filter false
                    if (videoCard.getObjectFieldAs<String>("author_") in searchFilterUpNames) return@filter false
                    if (searchFilterContentRegexMode) {
                        if (searchFilterContentRegexes.any { it.containsMatchIn(videoCard.getObjectFieldAs<String>("title_")) })
                            return@filter false
                    } else {
                        if (searchFilterContents.any { videoCard.getObjectFieldAs<String>("title_").contains(it) }) return@filter false
                    }
                    true
                }
            }
        }

        val needCommentFilter =
            hidden && (commentFilterBlockAtComment || commentFilterContents.isNotEmpty() || commentFilterAtUid.isNotEmpty() || commentFilterAtUpNames.isNotEmpty() || targetCommentAuthorLevel != 0L)
        if (needCommentFilter) {
            val blockAtCommentSplitRegex = Regex("\\s+")

            fun Any.validCommentAuthorLevel(): Boolean {
                if (targetCommentAuthorLevel == 0L) return true
                val authorLevel = getObjectField("member_")?.getObjectFieldAs<Long>("level_") ?: 6L
                return authorLevel >= targetCommentAuthorLevel
            }

            fun Any.validCommentContent(): Boolean {
                val content = getObjectField("content_") ?: return true
                val commentMessage = content.getObjectFieldAs<String>("message_")

                val contentIsToBlock = commentFilterContents.isNotEmpty() && if (commentFilterContentRegexMode) {
                    commentFilterContentRegexes.any { commentMessage.contains(it) }
                } else {
                    commentFilterContents.any { commentMessage.contains(it) }
                }
                if (contentIsToBlock) return false

                if (commentFilterBlockAtComment && commentMessage.trim()
                        .split(blockAtCommentSplitRegex).all { it.startsWith("@") }
                ) return false

                if (commentFilterAtUpNames.isEmpty() && commentFilterAtUid.isEmpty()) return true
                val atNameToMid = content.getObjectFieldAs<Map<String, Long>>("atNameToMid_")
                return !(atNameToMid.keys.any { it in commentFilterAtUpNames } || atNameToMid.values.any { it in commentFilterAtUid })
            }

            fun Any.filterComment() = validCommentAuthorLevel() && validCommentContent()

            "com.bapis.bilibili.main.community.reply.v1.MainListReply".from(mClassLoader)
                ?.hookAfterMethod("getRepliesList") { p ->
                    val l = p.result as? List<*> ?: return@hookAfterMethod
                    p.result = l.filter { it?.filterComment() ?: true }
                }
            "com.bapis.bilibili.main.community.reply.v1.ReplyInfo".from(mClassLoader)
                ?.hookAfterMethod("getRepliesList") { p ->
                    val l = p.result as? List<*> ?: return@hookAfterMethod
                    p.result = l.filter { it?.filterComment() ?: true }
                }
        }

        if (!sPrefs.getBoolean("replace_story_video", false)) return
        val disableBooleanValue = "com.bapis.bilibili.app.distribution.BoolValue".from(mClassLoader)?.callStaticMethod("getDefaultInstance") ?: return
        "com.bapis.bilibili.app.distribution.setting.play.PlayConfig".from(mClassLoader)?.run {
            replaceAllMethods("getLandscapeAutoStory") { disableBooleanValue }
            replaceAllMethods("getShouldAutoStory") { disableBooleanValue }
        }
    }

    private fun hookMossView() {
        instance.viewUniteMossClass?.hookAfterMethod(
            "executeView",
            instance.viewUniteReqClass
        ) { param ->
            param.result?.let {
                handleViewReply(it, true)
            }
        }

        instance.viewMossClass?.hookAfterMethod(
            if (instance.useNewMossFunc) "executeView" else "view",
            instance.viewReqClass
        ) { param ->
            param.result?.let {
                handleViewReply(it, false)
            }
        }
    }

    private fun isPlayPackage(): Boolean {
        return AndroidAppHelper.currentPackageName() == Constant.PLAY_PACKAGE_NAME
    }

    private fun ipLog(msg: String) {
        Log.e("[CommentIPLocation] $msg")
    }

    private fun normalizeIpLocation(location: String?): String? {
        val raw = location
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return if (raw.startsWith("IP属地")) raw else "IP属地：$raw"
    }

    private fun Any.longValue(methodName: String, fieldName: String): Long {
        return runCatchingOrNull {
            callMethodOrNullAs<Long>(methodName)
        } ?: runCatchingOrNull {
            getObjectFieldAs<Long>(fieldName)
        } ?: 0L
    }

    private fun Any.collectCommentIpContext(oidFromReq: Long, typeFromReq: Long) {
        val rpid = longValue("getRpid", "rpid_")
        if (rpid <= 0L) return

        val rootValue = longValue("getRoot", "root_")
        val dialog = longValue("getDialog", "dialog_")
        val root = if (rootValue > 0L) rootValue else rpid

        commentIpContextMap[rpid] = CommentIpContext(
            oid = oidFromReq,
            type = typeFromReq,
            rpid = rpid,
            root = root,
            dialog = dialog
        )

        runCatchingOrNull {
            callMethodOrNullAs<List<Any>>("getRepliesList")
                ?.forEach { it.collectCommentIpContext(oidFromReq, typeFromReq) }
        }
    }

    private fun hookCommentIpContext() {
        if (!isPlayPackage()) return

        ipLog("context hook init, package=${AndroidAppHelper.currentPackageName()}")

        listOf("v1", "v2").forEach { version ->
            val replyMossClass = "com.bapis.bilibili.main.community.reply.$version.ReplyMoss"
                .from(mClassLoader)

            if (replyMossClass == null) {
                ipLog("ReplyMoss class not found: $version")
                return@forEach
            }

            fun hookReplyMossList(
                methodName: String,
                reqClassName: String,
                logName: String
            ) {
                runCatchingOrNull {
                    replyMossClass.hookAfterMethod(methodName, reqClassName) { param ->
                        val req = param.args[0]
                        val oid = req.callMethodOrNullAs<Long>("getOid") ?: return@hookAfterMethod
                        val type = req.callMethodOrNullAs<Long>("getType") ?: return@hookAfterMethod

                        param.result?.callMethodOrNullAs<Any>("getRoot")
                            ?.collectCommentIpContext(oid, type)

                        val replies = param.result
                            ?.callMethodOrNullAs<List<Any>>("getRepliesList")
                            .orEmpty()

                        replies.forEach { it.collectCommentIpContext(oid, type) }
                        ipLog("cached $logName context: version=$version, oid=$oid, type=$type, size=${replies.size}, total=${commentIpContextMap.size}")
                    }
                    ipLog("hook $logName context: $version")
                } ?: ipLog("hook $logName context failed: $version")
            }

            hookReplyMossList(
                if (instance.useNewMossFunc) "executeMainList" else "mainList",
                "com.bapis.bilibili.main.community.reply.$version.MainListReq",
                "main list"
            )
            hookReplyMossList(
                if (instance.useNewMossFunc) "executeDetailList" else "detailList",
                "com.bapis.bilibili.main.community.reply.$version.DetailListReq",
                "detail list"
            )
            hookReplyMossList(
                if (instance.useNewMossFunc) "executeDialogList" else "dialogList",
                "com.bapis.bilibili.main.community.reply.$version.DialogListReq",
                "dialog list"
            )
        }
    }

    private fun hookCommentLongPressMenu() {
        if (!isPlayPackage()) return

        fun Any?.findReplyInfo(depth: Int = 0, visited: MutableSet<Int> = mutableSetOf()): Any? {
            if (this == null || depth > 5) return null

            val identity = System.identityHashCode(this)
            if (!visited.add(identity)) return null

            val name = javaClass.name
            if (name == "com.bapis.bilibili.main.community.reply.v1.ReplyInfo" ||
                name == "com.bapis.bilibili.main.community.reply.v2.ReplyInfo"
            ) {
                return this
            }

            if (this is String || this is Number || this is Boolean || this is CharSequence) return null
            if (name.startsWith("java.") || name.startsWith("android.")) return null

            return runCatchingOrNull {
                for (field in javaClass.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val value = runCatchingOrNull {
                        field.isAccessible = true
                        field.get(this)
                    } ?: continue
                    val found = value.findReplyInfo(depth + 1, visited)
                    if (found != null) return@runCatchingOrNull found
                }
                null
            }
        }

        fun Any.replyRpid(): Long {
            return longValue("getRpid", "rpid_")
        }

        fun hookBuilder(builderClass: Class<*>, name: String) {
            runCatchingOrNull {
                builderClass.hookBeforeMethod(
                    "setItems",
                    Array<CharSequence>::class.java,
                    DialogInterface.OnClickListener::class.java
                ) { param ->
                    val items = param.args[0] as? Array<CharSequence> ?: return@hookBeforeMethod
                    if (items.any { it.toString() == commentIpMenuTitle }) return@hookBeforeMethod

                    val oldListener = param.args[1] as? DialogInterface.OnClickListener
                        ?: return@hookBeforeMethod
                    val replyInfo = oldListener.findReplyInfo() ?: return@hookBeforeMethod
                    val rpid = replyInfo.replyRpid()
                    if (rpid <= 0L) return@hookBeforeMethod

                    val context = commentIpContextMap[rpid]
                    if (context == null) {
                        ipLog("long press matched reply but context missing: rpid=$rpid")
                        return@hookBeforeMethod
                    }

                    val newItems = items + commentIpMenuTitle
                    val newListener = DialogInterface.OnClickListener { dialog, which ->
                        if (which == items.size) {
                            showCommentIpLocation(rpid)
                        } else {
                            oldListener.onClick(dialog, which)
                        }
                    }

                    param.args[0] = newItems
                    param.args[1] = newListener
                    ipLog("inject long press menu: builder=$name, rpid=$rpid, oid=${context.oid}, type=${context.type}")
                }
                ipLog("hook dialog builder: $name")
            } ?: ipLog("hook dialog builder failed: $name")
        }

        hookBuilder(AlertDialog.Builder::class.java, "android.app.AlertDialog.Builder")

        "androidx.appcompat.app.AlertDialog\$Builder"
            .from(mClassLoader)
            ?.let { hookBuilder(it, "androidx.appcompat.app.AlertDialog.Builder") }
    }

    private fun showCommentIpLocation(rpid: Long) {
        val cached = synchronized(commentIpCache) {
            commentIpCache[rpid]
        }

        if (!cached.isNullOrBlank()) {
            showCommentIpToast(cached)
            return
        }

        val context = commentIpContextMap[rpid]
        if (context == null) {
            showCommentIpToast("未找到评论上下文")
            return
        }

        showCommentIpToast("正在获取 IP 属地...")

        kotlin.concurrent.thread(name = "BiliRoamingCommentIp") {
            val location = runCatchingOrNull {
                fetchCommentIpLocation(context)
            }.getOrNull()

            if (location.isNullOrBlank()) {
                showCommentIpToast("未获取到 IP 属地")
            } else {
                synchronized(commentIpCache) {
                    commentIpCache[rpid] = location
                }
                showCommentIpToast(location)
            }
        }
    }

    private fun showCommentIpToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            val context = runCatchingOrNull { currentContext }
                ?: AndroidAppHelper.currentApplication()
                ?: return@post
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchCommentIpLocation(context: CommentIpContext): String? {
        fun findLocationInReply(reply: JSONObject?, targetRpid: Long): String? {
            if (reply == null) return null

            if (reply.optLong("rpid", 0L) == targetRpid) {
                val location = normalizeIpLocation(
                    reply.optJSONObject("reply_control")
                        ?.optString("location")
                )
                if (!location.isNullOrBlank()) return location
            }

            val replies = reply.optJSONArray("replies") ?: return null
            for (i in 0 until replies.length()) {
                val found = findLocationInReply(replies.optJSONObject(i), targetRpid)
                if (!found.isNullOrBlank()) return found
            }

            return null
        }

        fun request(url: String): JSONObject? {
            ipLog("request: $url")
            val content = BiliRoamingApi.getContent(url, "android") ?: return null
            val json = JSONObject(content)
            val code = json.optInt("code", -1)
            if (code != 0) {
                ipLog("request failed: code=$code, message=${json.optString("message")}")
                return null
            }
            return json
        }

        val infoUrl = Uri.Builder()
            .scheme("https")
            .encodedAuthority("api.bilibili.com")
            .encodedPath("/x/v2/reply/info")
            .appendQueryParameter("type", context.type.toString())
            .appendQueryParameter("oid", context.oid.toString())
            .appendQueryParameter("rpid", context.rpid.toString())
            .build()
            .toString()

        request(infoUrl)
            ?.optJSONObject("data")
            ?.optJSONObject("reply")
            ?.let { reply ->
                val location = findLocationInReply(reply, context.rpid)
                if (!location.isNullOrBlank()) return location
            }

        val root = if (context.root > 0L) context.root else context.rpid
        val detailUrl = Uri.Builder()
            .scheme("https")
            .encodedAuthority("api.bilibili.com")
            .encodedPath("/x/v2/reply/detail")
            .appendQueryParameter("type", context.type.toString())
            .appendQueryParameter("oid", context.oid.toString())
            .appendQueryParameter("root", root.toString())
            .appendQueryParameter("ps", "20")
            .appendQueryParameter("next", "0")
            .build()
            .toString()

        request(detailUrl)
            ?.optJSONObject("data")
            ?.let { data ->
                val locationFromRoot = findLocationInReply(data.optJSONObject("root"), context.rpid)
                if (!locationFromRoot.isNullOrBlank()) return locationFromRoot

                val replies = data.optJSONArray("replies")
                if (replies != null) {
                    for (i in 0 until replies.length()) {
                        val found = findLocationInReply(replies.optJSONObject(i), context.rpid)
                        if (!found.isNullOrBlank()) return found
                    }
                }
            }

        return null
    }

    private fun handleViewReply(viewReply: Any, isUnite: Boolean) {
        val aid = viewReply.callMethod("getArc")
            ?.callMethodAs("getAid") ?: -1L
        val like = viewReply.callMethod("getReqUser")
            ?.callMethodAs("getLike") ?: -1

        if (aid > 0 && like != -1) {
            AutoLikeHook.detail = aid to like
        }

        BangumiPlayUrlHook.qnApplied.set(false)

        // 视频详情页荣誉卡片
        fun Any.isHonor() = removeHonor && callMethodAs("hasHonor")

        // 视频详情页活动卡片(含会员购等), 区分于视频下方推荐处的广告卡片
        fun Any.isActivityEntranceModule() = blockViewPageAds && callMethodAs("hasActivityEntranceModule")

        // 视频详情页直播预约卡片
        fun Any.isLiveOrder() = blockLiveOrder && callMethodAs("hasLiveOrder")

        // 视频下方分集列表
        fun Any.isUgcSeason() = removeUgcSeason && callMethodAs("hasUgcSeason")

        fun Any.isLikeComment() = blockCommentGuide && callMethodAs("hasLikeComment")

        fun MutableList<Any>.filter() = removeAll {
            it.isActivityEntranceModule() || it.isHonor() || it.isLiveOrder() || it.isUgcSeason() || it.isLikeComment()
        }

        if (isUnite) {
            if (blockViewPageAds) {
                viewReply.callMethod("clearCm")
            }

            if (hidden && removeUpVipLabel) {
                viewReply.callMethod("getOwner")?.callMethod("getVip")?.callMethod("clearLabel")
            }

            viewReply.callMethod("getTab")?.run {
                callMethod("ensureTabModuleIsMutable")
                val tabModuleList = callMethodAs<MutableList<Any>>("getTabModuleList")
                if (blockVideoComment) {
                    tabModuleList.removeAll {
                        it.callMethodAs("hasReply")
                    }
                }
                if (!(blockCommentGuide || blockViewPageAds || removeHonor || removeUgcSeason)) return@run
                tabModuleList.firstOrNull { it.callMethod("hasIntroduction") == true }?.let {
                    it.callMethodAs<Any>("getIntroduction").run {
                        callMethod("ensureModulesIsMutable")
                        callMethodAs<MutableList<Any>>("getModulesList").filter()
                    }
                }
                if (blockCommentGuide) {
                    tabModuleList.firstOrNull { it.callMethod("hasReply") == true }?.let { tabModule ->
                        tabModule.callMethod("getReply")?.callMethod("getReplyStyle")
                            ?.callMethod("clearBadgeType")
                    }
                }
            }

            return
        }

        if (unlockPlayLimit)
            viewReply.callMethod("getConfig")?.callMethod("setShowListenButton", true)
        if (blockCommentGuide) {
            viewReply.runCatchingOrNull {
                callMethod("getLikeCustom")
                    ?.callMethod("clearLikeComment")
                callMethod("getReplyPreface")
                    ?.callMethod("clearBadgeType")
            }
        }

        if (hidden && removeHonor) {
            viewReply.callMethod("clearHonor")
        }
        if (hidden && removeUgcSeason) {
            viewReply.callMethod("clearUgcSeason")
        }
        if (hidden && blockLiveOrder) {
            viewReply.callMethod("clearLiveOrderInfo")
        }
        if (hidden && removeUpVipLabel) {
            viewReply.callMethod("getOwnerExt")?.callMethod("getVip")?.callMethod("clearLabel")
        }
    }
}
