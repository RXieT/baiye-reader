package io.github.rxiet.baiye

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rxiet.baiye.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CommunityViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as BaiyeApplication).account
    private val mutableAccount = MutableStateFlow(AccountState())
    val account = mutableAccount.asStateFlow()
    private val mutableTopics = MutableStateFlow(TopicsState())
    val topics = mutableTopics.asStateFlow()
    private val mutableThread = MutableStateFlow(ThreadState())
    val thread = mutableThread.asStateFlow()
    private var accountJob: Job? = null
    private var topicsJob: Job? = null
    private var threadJob: Job? = null
    private var topicsGeneration = 0L
    private var threadGeneration = 0L
    private var restored = false

    init { viewModelScope.launch { repository.user.collect { user -> mutableAccount.update { it.copy(user = user) } } } }

    fun restoreAccount() {
        if (accountJob?.isActive == true) return
        accountJob = viewModelScope.launch {
            mutableAccount.update { it.copy(loading = true, error = null) }
            try { repository.restore(); restored = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableAccount.update { it.copy(error = "账号暂时无法连接") } }
            finally { mutableAccount.update { it.copy(loading = false) } }
        }
    }

    fun login(name: String, password: String, email: Boolean) {
        if (accountJob?.isActive == true || name.isBlank() || password.isEmpty()) return
        accountJob = viewModelScope.launch {
            mutableAccount.update { it.copy(loading = true, error = null) }
            try { repository.login(name, password, email); restored = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableAccount.update { it.copy(error = failure.message ?: "登录未成功") } }
            finally { mutableAccount.update { it.copy(loading = false) } }
        }
    }

    fun logout() {
        if (accountJob?.isActive == true) return
        accountJob = viewModelScope.launch {
            mutableAccount.update { it.copy(loading = true, error = null) }
            try { repository.logout() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableAccount.update { it.copy(error = "已清除本机会话") } }
            finally { mutableAccount.update { it.copy(loading = false) } }
        }
    }

    fun showTopics(book: Book) {
        topicsGeneration++
        topicsJob?.cancel()
        mutableTopics.value = TopicsState(bookId = book.id, bookTitle = book.title)
        if (!restored) restoreAccount()
        loadTopics()
    }

    fun loadTopics(more: Boolean = false) {
        val before = mutableTopics.value
        if (before.bookId.isBlank() || before.loading || (more && !before.hasMore)) return
        topicsJob?.cancel()
        val token = topicsGeneration
        topicsJob = viewModelScope.launch {
            mutableTopics.update { it.copy(loading = true, error = null) }
            val page = if (more) before.page + 1 else 1
            try {
                val result = repository.topics(before.bookId, page)
                if (token != topicsGeneration) return@launch
                val combined = (if (more) before.topics + result.items else result.items).distinctBy { it.id }
                mutableTopics.update { it.copy(topics = combined, page = page,
                    hasMore = result.hasMore && (!more || combined.size > before.topics.size), loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == topicsGeneration) mutableTopics.update { it.copy(loading = false, error = "评论暂时无法加载") } }
        }
    }

    fun showThread(topic: ReviewTopic) {
        threadGeneration++
        threadJob?.cancel()
        mutableThread.value = ThreadState(topic = topic)
        loadReplies()
    }

    fun loadReplies(more: Boolean = false) {
        val before = mutableThread.value
        val topic = before.topic ?: return
        if (before.loading || (more && !before.hasMore)) return
        threadJob?.cancel()
        val token = threadGeneration
        threadJob = viewModelScope.launch {
            mutableThread.update { it.copy(loading = true, error = null) }
            val page = if (more) before.page + 1 else 1
            try {
                val result = repository.replies(topic.id, page)
                if (token != threadGeneration) return@launch
                val combined = if (more) before.posts + result.items else result.items
                mutableThread.update { it.copy(posts = combined, page = page, hasMore = result.hasMore, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == threadGeneration) mutableThread.update { it.copy(loading = false, error = "评论暂时无法加载") } }
        }
    }

    fun post(title: String, text: String) {
        val before = mutableTopics.value
        if (before.sending || before.bookId.isBlank()) return
        val token = topicsGeneration
        val actor = repository.user.value?.id
        mutableTopics.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                if (actor == null) throw java.io.IOException("请先登录")
                repository.post(before.bookId, title.trim(), text.trim(), actor)
                if (token != topicsGeneration) return@launch
                mutableTopics.update { it.copy(sending = false, sendRevision = it.sendRevision + 1, sendError = null) }
                topicsJob?.cancel()
                mutableTopics.update { it.copy(loading = false) }
                loadTopics()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == topicsGeneration) mutableTopics.update {
                    it.copy(sending = false, sendRevision = it.sendRevision + 1, sendError = failure.message ?: "发送失败")
                }
            }
        }
    }

    fun reply(text: String) {
        val before = mutableThread.value
        val topic = before.topic ?: return
        if (before.sending) return
        val token = threadGeneration
        val actor = repository.user.value?.id
        mutableThread.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                if (actor == null) throw java.io.IOException("请先登录")
                repository.reply(topic.id, text.trim(), actor)
                if (token != threadGeneration) return@launch
                mutableThread.update { it.copy(sending = false, sendRevision = it.sendRevision + 1, sendError = null) }
                threadJob?.cancel()
                mutableThread.update { it.copy(loading = false) }
                loadReplies()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == threadGeneration) mutableThread.update {
                    it.copy(sending = false, sendRevision = it.sendRevision + 1, sendError = failure.message ?: "发送失败")
                }
            }
        }
    }
}
