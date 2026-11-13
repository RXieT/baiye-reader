package io.github.rxiet.baiye.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Passwords exist only for the user-initiated HTTPS request; only the session is persisted. */
class AccountRepository(context: Context, baseClient: OkHttpClient) {
    private val session = PrivateSession(context)
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val identityLock = Mutex()
    private val mutableUser = MutableStateFlow<AccountUser?>(null)
    val user = mutableUser.asStateFlow()

    suspend fun restore(): AccountUser? = withContext(Dispatchers.IO) {
        identityLock.withLock {
            if (session.get().isBlank()) { mutableUser.value = null; return@withLock null }
            val result = userInfo()
            val active = result?.takeIf { session.get().isNotBlank() }
            mutableUser.value = active
            if (active == null) session.clear()
            active
        }
    }

    suspend fun login(name: String, password: String, email: Boolean): AccountUser = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && password.isNotEmpty())
        identityLock.withLock {
            session.clear()
            mutableUser.value = null
            call("action=${if (email) "loginemail" else "login"}&username=${encode(name.trim(), "UTF-8")}&password=${encode(password, "UTF-8")}")
            val profile = userInfo()?.takeIf { session.get().isNotBlank() }
                ?: run { session.clear(); throw IOException("未能登录，请检查账号信息") }
            mutableUser.value = profile
            profile
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        identityLock.withLock {
            try { if (session.get().isNotBlank()) call("action=logout") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IOException) { /* Local logout still succeeds offline. */ }
            finally { session.clear(); mutableUser.value = null }
        }
    }

    private suspend fun userInfo(): AccountUser? {
        val doc = Jsoup.parse(call("action=userinfo"), "", Parser.xmlParser())
        fun field(name: String): String = doc.select("item").find { it.attr("name") == name }
            ?.let { it.attr("value").ifBlank { it.text() } }.orEmpty()
        val id = field("uid")
        if (!id.matches(Regex("[1-9][0-9]*"))) return null
        return AccountUser(id, field("uname"), field("nickname").ifBlank { field("uname") }, field("rank"), field("score"))
    }

    suspend fun topics(bookId: String, page: Int): ReviewPage<ReviewTopic> = withContext(Dispatchers.IO) {
        requireId(bookId); require(page > 0)
        val doc = xml(call("action=review&do=list&aid=$bookId&page=$page&t=0"))
        val rows = doc.select("item[rid]").map { item ->
            val author = item.selectFirst("user")
            ReviewTopic(item.attr("rid"), item.selectFirst("content")?.text().orEmpty(), author?.text().orEmpty(),
                author?.attr("uid").orEmpty(), item.attr("replies").toIntOrNull() ?: 0,
                item.attr("posttime"), item.attr("replytime"))
        }
        ReviewPage(rows, page < (doc.selectFirst("page")?.attr("num")?.toIntOrNull() ?: page))
    }

    suspend fun replies(topicId: String, page: Int): ReviewPage<ReviewPost> = withContext(Dispatchers.IO) {
        requireId(topicId); require(page > 0)
        val doc = xml(call("action=review&do=show&rid=$topicId&page=$page&t=0"))
        val rows = doc.select("item").mapIndexed { index, item ->
            val author = item.selectFirst("user")
            val content = item.selectFirst("content")?.text().orEmpty()
            ReviewPost("$page:$index:${item.attr("timestamp")}", author?.text().orEmpty(), author?.attr("uid").orEmpty(),
                if (content.contains('<')) Jsoup.parse(content).wholeText().trim() else content,
                item.attr("timestamp"))
        }
        ReviewPage(rows, page < (doc.selectFirst("page")?.attr("num")?.toIntOrNull() ?: page))
    }

    suspend fun post(bookId: String, title: String, content: String, expectedUserId: String) = withContext(Dispatchers.IO) {
        requireId(bookId)
        require(title.isNotBlank() && title.length <= 100 && content.isNotBlank() && content.length <= 5000)
        val request = "action=review&do=post&aid=$bookId&title=${reviewValue(title)}&content=${reviewValue(content)}"
        identityLock.withLock {
            authenticated(expectedUserId)
            if (submit(request) != "1") throw IOException("评论未发送，请稍后重试")
        }
    }

    suspend fun reply(topicId: String, content: String, expectedUserId: String) = withContext(Dispatchers.IO) {
        requireId(topicId); require(content.isNotBlank() && content.length <= 5000)
        val request = "action=review&do=reply&rid=$topicId&content=${reviewValue(content)}"
        identityLock.withLock {
            authenticated(expectedUserId)
            val result = submit(request)
            if (result == "11") throw IOException("此评论已锁定")
            if (result != "1") throw IOException("回复未发送，请稍后重试")
        }
    }

    private suspend fun submit(request: String): String = try { call(request).trim() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: IOException) { throw IOException("发送结果未确认，请刷新评论后再试", failure) }

    private fun requireId(value: String) { require(value.matches(Regex("[1-9][0-9]{0,10}"))) }
    private fun authenticated(expectedUserId: String) {
        if (session.get().isBlank()) { mutableUser.value = null; throw IOException("请先登录") }
        if (mutableUser.value?.id != expectedUserId) throw IOException("账号已变更，请重新发送")
    }
    private fun reviewValue(value: String): String {
        if (!Charset.forName("GBK").newEncoder().canEncode(value)) throw IOException("评论包含暂不支持的字符")
        // Observed contract: GBK form escaping, then UTF-8 Base64, inside the outer request envelope.
        return Base64.getEncoder().encodeToString(encode(value, "GBK").toByteArray(Charsets.UTF_8))
    }
    private fun encode(value: String, charset: String) = URLEncoder.encode(value, charset)
    private fun xml(raw: String): org.jsoup.nodes.Document {
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        if (doc.selectFirst("result, page, item") == null) throw IOException("评论暂时无法加载")
        return doc
    }

    private suspend fun call(inner: String): String {
        val body = FormBody.Builder().add("appver", WenkuVersion.value())
            .add("request", Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8)))
            .add("timetoken", System.currentTimeMillis().toString()).build()
        val (epoch, cookie) = session.snapshot()
        val acceptSession = cookie.isNotBlank() || inner.startsWith("action=login&") || inner.startsWith("action=loginemail&")
        val request = Request.Builder().url("https://wenku8-relay.mewx.org/").post(body).header("User-Agent", "Baiye/1.3 Android")
        if (cookie.isNotBlank()) request.header("Cookie", "PHPSESSID=$cookie")
        return suspendCancellableCoroutine { continuation ->
            val active = client.newCall(request.build())
            continuation.invokeOnCancellation { active.cancel() }
            active.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("连接失败", e)) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) throw IOException("服务暂不可用")
                            if (acceptSession) it.headers.values("Set-Cookie").mapNotNull { header ->
                                Regex("(?:^|;\\s*)PHPSESSID=([^;]*)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim('"')
                            }.lastOrNull()?.let { value -> session.save(value, epoch) }
                            val source = it.body?.source() ?: throw IOException("响应为空")
                            if (source.request(2L * 1024 * 1024 + 1)) throw IOException("响应过大")
                            val bytes = source.buffer.readByteArray()
                            String(bytes, Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            })
        }
    }
}

/** Platform Keystore AES-GCM protects the cookie; no username/password is written. */
private class PrivateSession(context: Context) {
    private val preferences = context.getSharedPreferences("account-session", Context.MODE_PRIVATE)
    private var cached: String? = null
    private var generation = 0L
    @Synchronized fun snapshot(): Pair<Long, String> = generation to get()
    @Synchronized fun get(): String {
        cached?.let { return it }
        val encoded = preferences.getString("session", null) ?: return "".also { cached = it }
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrElse { preferences.edit().remove("session").apply(); "" }.also { cached = it }
    }
    @Synchronized fun save(value: String, expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        if (value.isBlank() || value == "deleted") { clear(); return }
        if (!value.matches(Regex("[A-Za-z0-9,-]{8,256}"))) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("session", Base64.getEncoder().encodeToString(bytes)).apply()
        cached = value
    }
    @Synchronized fun clear() { generation++; cached = ""; preferences.edit().remove("session").apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("baiye-account", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("baiye-account", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

data class AccountUser(val id: String, val username: String, val name: String, val rank: String, val score: String)
data class AccountState(val user: AccountUser? = null, val loading: Boolean = false, val error: String? = null)
data class ReviewTopic(val id: String, val title: String, val author: String, val userId: String, val replies: Int, val postedAt: String, val updatedAt: String)
data class ReviewPost(val id: String, val author: String, val userId: String, val content: String, val date: String)
data class ReviewPage<T>(val items: List<T>, val hasMore: Boolean)
data class TopicsState(val bookId: String = "", val bookTitle: String = "", val topics: List<ReviewTopic> = emptyList(),
    val page: Int = 0, val hasMore: Boolean = true, val loading: Boolean = false, val sending: Boolean = false, val error: String? = null,
    val sendRevision: Long = 0, val sendError: String? = null)
data class ThreadState(val topic: ReviewTopic? = null, val posts: List<ReviewPost> = emptyList(),
    val page: Int = 0, val hasMore: Boolean = true, val loading: Boolean = false, val sending: Boolean = false, val error: String? = null,
    val sendRevision: Long = 0, val sendError: String? = null)
