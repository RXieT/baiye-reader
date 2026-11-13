package io.github.rxiet.baiye.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rxiet.baiye.data.AccountState
import io.github.rxiet.baiye.data.ReviewTopic
import io.github.rxiet.baiye.data.ThreadState
import io.github.rxiet.baiye.data.TopicsState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun AccountScreen(
    state: AccountState,
    onLogin: (String, String, Boolean) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var emailAddress by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(false) }
    // Deliberately memory-only: never put a password in saved state.
    var password by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val user = state.user
    LaunchedEffect(user) {
        if (user != null) password = ""
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("账号", onBack)
        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (user != null) {
                Text(user.name.ifBlank { user.username }, fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = Ink)
                if (user.username.isNotBlank()) {
                    Text(user.username, fontSize = 16.sp, color = Muted)
                }
                if (user.rank.isNotBlank()) {
                    Text("等级 ${user.rank}", fontSize = 16.sp, color = Ink)
                }
                if (user.score.isNotBlank()) {
                    Text("积分 ${user.score}", fontSize = 16.sp, color = Ink)
                }
                state.error?.let { Text(it, fontSize = 16.sp, lineHeight = 26.sp, color = Accent) }
                ActionButton(if (state.loading) "处理中" else "退出登录", {
                    if (!state.loading) onLogout()
                }, Modifier.fillMaxWidth(), enabled = !state.loading)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("用户名登录", { email = false },
                        Modifier.weight(1f).semantics { selected = !email },
                        primary = !email, enabled = !state.loading)
                    ActionButton("邮箱登录", { email = true },
                        Modifier.weight(1f).semantics { selected = email },
                        primary = email, enabled = !state.loading)
                }
                TextField(
                    value = if (email) emailAddress else username,
                    onValueChange = { if (email) emailAddress = it else username = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    label = if (email) "邮箱" else "用户名",
                    singleLine = true,
                    enabled = !state.loading,
                    textStyle = communityInputStyle(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (email) KeyboardType.Email else KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    label = "密码",
                    singleLine = true,
                    enabled = !state.loading,
                    textStyle = communityInputStyle(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                state.error?.let {
                    Text(it, fontSize = 16.sp, lineHeight = 26.sp, color = Accent)
                    ActionButton("重试连接", { if (!state.loading) onRetry() },
                        Modifier.fillMaxWidth(), enabled = !state.loading)
                }
                val name = if (email) emailAddress else username
                ActionButton(if (state.loading) "登录中" else "登录", {
                    if (!state.loading && name.isNotBlank() && password.isNotEmpty()) {
                        keyboard?.hide()
                        onLogin(name.trim(), password, email)
                    }
                }, Modifier.fillMaxWidth(), primary = true,
                    enabled = !state.loading && name.isNotBlank() && password.isNotEmpty())
            }
            if (state.loading) CommunityLoading()
            ActionButton("隐私", { privacy = true }, Modifier.fillMaxWidth())
        }
    }
    if (privacy) {
        CommunityDialog(onDismiss = { privacy = false }) {
            Text("隐私", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("请求通过文库原有的 HTTPS 中继发送。客户端只保存加密会话，不保存密码。",
                fontSize = 16.sp, lineHeight = 26.sp, color = Ink)
            ActionButton("关闭", { privacy = false }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun TopicsScreen(
    state: TopicsState,
    loggedIn: Boolean,
    onTopic: (ReviewTopic) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onPost: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var composing by remember(state.bookId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("评论", onBack)
        if (state.bookTitle.isNotBlank()) {
            Text(state.bookTitle, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.topics, key = { "topic:${it.id}" }) { topic ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onTopic(topic) }.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(topic.title, fontSize = 17.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium, color = Ink)
                    if (topic.author.isNotBlank()) {
                        Text(topic.author, fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
                    }
                    Text("${topic.replies} 条回复", fontSize = 14.sp, color = Muted)
                    val date = communityDate(topic.updatedAt.ifBlank { topic.postedAt })
                    if (date.isNotBlank()) Text(date, fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
                    CommunityRule()
                }
            }
            if (state.topics.isEmpty() && !state.loading && state.error == null) {
                item(key = "empty") {
                    Text("无评论", Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        fontSize = 16.sp, color = Muted)
                }
            }
            item(key = "pagination") {
                CommunityPagination(
                    loading = state.loading,
                    sending = state.sending,
                    hasMore = state.hasMore && state.topics.isNotEmpty(),
                    error = state.error,
                    onMore = onMore,
                    onRetry = onRetry,
                )
            }
        }
        ActionButton(if (loggedIn) "发表评论" else "登录", {
            if (!state.sending) {
                if (loggedIn) composing = true else onLogin()
            }
        }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            primary = true, enabled = !state.sending && state.bookId.isNotBlank())
    }
    CommunityComposer(
        contextKey = state.bookId,
        visible = composing,
        withTitle = true,
        loggedIn = loggedIn,
        sending = state.sending,
        sendRevision = state.sendRevision,
        sendError = state.sendError,
        onDismiss = { composing = false },
        onLogin = onLogin,
        onSend = onPost,
    )
}

@Composable
fun ThreadScreen(
    state: ThreadState,
    loggedIn: Boolean,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onReply: (String) -> Unit,
    onBack: () -> Unit,
) {
    var composing by remember(state.topic?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("评论详情", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.topic?.let { topic ->
                item(key = "heading:${topic.id}") {
                    SelectionContainer {
                        Text(topic.title, fontSize = 20.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                }
            }
            items(state.posts, key = { "post:${it.id}" }) { post ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (post.author.isNotBlank()) {
                        Text(post.author, fontSize = 14.sp, lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium, color = Ink)
                    }
                    if (post.date.isNotBlank()) {
                        Text(communityDate(post.date), fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
                    }
                    SelectionContainer {
                        Text(post.content, fontSize = 16.sp, lineHeight = 26.sp, color = Ink)
                    }
                    CommunityRule()
                }
            }
            if (state.posts.isEmpty() && !state.loading && state.error == null) {
                item(key = "empty") {
                    Text("暂无回复", Modifier.padding(vertical = 24.dp), fontSize = 16.sp, color = Muted)
                }
            }
            item(key = "pagination") {
                CommunityPagination(
                    loading = state.loading,
                    sending = state.sending,
                    hasMore = state.hasMore && state.posts.isNotEmpty(),
                    error = state.error,
                    onMore = onMore,
                    onRetry = onRetry,
                )
            }
        }
        ActionButton(if (loggedIn) "回复" else "登录", {
            if (!state.sending) {
                if (loggedIn) composing = true else onLogin()
            }
        }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            primary = true, enabled = !state.sending && state.topic != null)
    }
    CommunityComposer(
        contextKey = state.topic?.id.orEmpty(),
        visible = composing,
        withTitle = false,
        loggedIn = loggedIn,
        sending = state.sending,
        sendRevision = state.sendRevision,
        sendError = state.sendError,
        onDismiss = { composing = false },
        onLogin = onLogin,
        onSend = { _, content -> onReply(content) },
    )
}

@Composable
private fun CommunityComposer(
    contextKey: String,
    visible: Boolean,
    withTitle: Boolean,
    loggedIn: Boolean,
    sending: Boolean,
    sendRevision: Long,
    sendError: String?,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    // Save drafts across navigation; submission tracking stays memory-only.
    var title by rememberSaveable(contextKey) { mutableStateOf("") }
    var content by rememberSaveable(contextKey) { mutableStateOf("") }
    var submitted by remember(contextKey) { mutableStateOf(false) }
    var submittedRevision by remember(contextKey) { mutableStateOf(sendRevision) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(contextKey, sendRevision) {
        if (submitted && sendRevision != submittedRevision) {
            submitted = false
            if (sendError == null) {
                title = ""
                content = ""
                keyboard?.hide()
                onDismiss()
            }
        }
    }
    val busy = sending || submitted
    if (visible) {
        CommunityDialog(onDismiss = { if (!sending && !submitted) onDismiss() }) {
            Text(if (withTitle) "发表评论" else "回复", fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold, color = Ink)
            if (withTitle) {
                TextField(
                    value = title,
                    onValueChange = { if (it.length <= 100) title = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    label = "标题",
                    singleLine = true,
                    enabled = !busy,
                    textStyle = communityInputStyle(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
            }
            TextField(
                value = content,
                onValueChange = { if (it.length <= 5000) content = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp),
                label = "正文",
                enabled = !busy,
                textStyle = communityInputStyle(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
            )
            sendError?.let { Text(it, fontSize = 16.sp, lineHeight = 26.sp, color = Accent) }
            if (loggedIn) {
                val valid = content.isNotBlank() && content.length <= 5000 &&
                    (!withTitle || title.isNotBlank() && title.length <= 100)
                ActionButton(if (busy) "发送中" else "发送", {
                    if (!sending && !submitted && valid) {
                        submittedRevision = sendRevision
                        submitted = true
                        keyboard?.hide()
                        onSend(title.trim(), content.trim())
                    }
                }, Modifier.fillMaxWidth(), primary = true, enabled = !busy && valid)
            } else {
                ActionButton("登录", { if (!sending && !submitted) onLogin() }, Modifier.fillMaxWidth(),
                    primary = true, enabled = !busy)
            }
            ActionButton("取消", { if (!sending && !submitted) onDismiss() },
                Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

private fun communityDate(value: String): String {
    if (value.length != 14 || !value.all { it in '0'..'9' }) return value
    return "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)} " +
        "${value.substring(8, 10)}:${value.substring(10, 12)}"
}

@Composable
private fun CommunityDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().imePadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) { content() }
        }
    }
}

@Composable
private fun CommunityPagination(
    loading: Boolean,
    sending: Boolean,
    hasMore: Boolean,
    error: String?,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) CommunityLoading()
        if (error != null) {
            FailureState(error, { if (!loading && !sending) onRetry() })
        }
        if (hasMore && error == null) {
            ActionButton(if (loading) "加载中" else "更多", {
                if (!loading && !sending) onMore()
            }, Modifier.fillMaxWidth(), enabled = !loading && !sending)
        }
    }
}

@Composable
private fun CommunityLoading() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(size = 24.dp)
    }
}

@Composable
private fun communityInputStyle() = TextStyle(
    fontSize = 16.sp,
    lineHeight = 26.sp,
    color = Ink,
    fontFamily = LocalAppFont.current,
)

@Composable
private fun CommunityRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}
