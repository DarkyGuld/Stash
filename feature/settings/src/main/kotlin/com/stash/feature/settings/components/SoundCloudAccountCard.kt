package com.stash.feature.settings.components

import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.ui.R as UiR
import com.stash.feature.settings.R
import com.stash.feature.settings.SoundCloudAccountViewModel
import kotlinx.coroutines.delay

private const val TAG = "SoundCloudLogin"

@Composable
fun SoundCloudAccountCard(viewModel: SoundCloudAccountViewModel = hiltViewModel()) {
    val account by viewModel.session.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var profile by rememberSaveable { mutableStateOf("") }
    // Never save the access token in the saved-state bundle.
    var token by remember { mutableStateOf("") }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    AccountConnectionCard(serviceName = "SoundCloud", icon = ImageVector.vectorResource(UiR.drawable.ic_brand_soundcloud),
        accentColor = Color(0xFFFF5500),
        authState = if (ui.busy) AuthState.Connecting else account?.let {
            AuthState.Connected(UserInfo(it.userId, it.displayName, it.avatarUrl))
        } ?: AuthState.NotConnected,
        onConnect = viewModel::open, onDisconnect = { disconnect = true })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text(stringResource(R.string.soundcloud_disconnect)) },
        text = { Text(stringResource(R.string.soundcloud_disconnect_body)) },
        confirmButton = { TextButton(onClick = { disconnect = false; viewModel.disconnect() }) {
            Text(stringResource(R.string.soundcloud_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.soundcloud_cancel)) } })
    if (ui.open && !ui.web) AlertDialog(onDismissRequest = viewModel::close,
        title = { Text("SoundCloud") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())) {
                Button(onClick = viewModel::web, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.soundcloud_connect))
                }
                Text(stringResource(R.string.soundcloud_public_profile))
                OutlinedTextField(profile, { profile = it }, singleLine = true, enabled = !ui.busy,
                    label = { Text(stringResource(R.string.soundcloud_profile_url)) })
                Text("Or paste an OAuth token")
                OutlinedTextField(token, { token = it }, singleLine = true, enabled = !ui.busy,
                    label = { Text("OAuth token") },
                    visualTransformation = PasswordVisualTransformation())
                TextButton(onClick = { viewModel.connectToken(token) }, enabled = !ui.busy && token.isNotBlank()) {
                    Text("Use token")
                }
                if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (ui.failed) Text(stringResource(R.string.soundcloud_connect_failed), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { viewModel.connectProfile(profile) }, enabled = !ui.busy && profile.isNotBlank()) {
            Text(stringResource(R.string.soundcloud_use_public_likes)) } },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.soundcloud_cancel)) } })
    if (ui.web) SoundCloudLogin(onToken = viewModel::connectToken, onClose = viewModel::closeWeb)
}

/** No Javascript bridge, file access, exported callback or token saved in Compose state. */
@Composable
private fun SoundCloudLogin(onToken: (String) -> Unit, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var container by remember { mutableStateOf<FrameLayout?>(null) }
    var delivered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            container?.let(::destroyWebViews)
            container = null
            browser = null
            popup = null
        }
    }
    LaunchedEffect(browser) {
        while (!delivered) {
            val cookie = SoundCloudLoginCookies.token(browser?.url, CookieManager.getInstance()::getCookie)
            if (cookie != null) { delivered = true; onToken(cookie) }
            delay(500)
        }
    }
    BackHandler {
        val currentPopup = popup
        when {
            currentPopup != null && currentPopup.canGoBack() -> currentPopup.goBack()
            currentPopup != null -> { destroyWebView(currentPopup); popup = null }
            browser?.canGoBack() == true -> browser?.goBack()
            else -> onClose()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.soundcloud_cancel)) }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    val frame = FrameLayout(context)
                    container = frame
                    val main = WebView(context)
                    main.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    main.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = userAgentString.replace("; wv", "")
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(main, true)
                    main.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && !isAllowedSoundCloudLoginUrl(request.url.toString())
                        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                            Log.w(TAG, "renderer gone (crashed=${detail.didCrash()})")
                            destroyWebView(v)
                            onClose()
                            return true
                        }
                    }
                    lateinit var chrome: WebChromeClient
                    chrome = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                            resultMsg: Message): Boolean {
                            // SoundCloud opens Google/Apple/Facebook in a popup and posts the
                            // result back through window.opener. Without a real popup WebView
                            // the consent page cannot finish.
                            val child = WebView(view.context)
                            child.layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                            child.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                allowFileAccess = false
                                allowContentAccess = false
                                userAgentString = view.settings.userAgentString
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
                            child.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    return request.isForMainFrame && url != "about:blank" &&
                                        !isAllowedSoundCloudLoginUrl(url)
                                }
                                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    Log.w(TAG, "popup renderer gone (crashed=${detail.didCrash()})")
                                    destroyWebView(v)
                                    onClose()
                                    return true
                                }
                            }
                            child.webChromeClient = chrome
                            popup = child
                            frame.addView(child)
                            val transport = resultMsg.obj as WebView.WebViewTransport
                            transport.webView = child
                            resultMsg.sendToTarget()
                            return true
                        }

                        override fun onCloseWindow(window: WebView) {
                            destroyWebView(window)
                            if (popup === window) popup = null
                        }
                    }
                    main.webChromeClient = chrome
                    frame.addView(main)
                    browser = main
                    val cookies = CookieManager.getInstance()
                    fun clearCookie(index: Int) {
                        if (index == SoundCloudLoginCookies.removals.size) {
                            main.post { if (browser === main) main.loadUrl("https://soundcloud.com/signin") }
                            return
                        }
                        val (origin, header) = SoundCloudLoginCookies.removals[index]
                        cookies.setCookie(origin, header) { clearCookie(index + 1) }
                    }
                    clearCookie(0)
                    frame
                })
            }
        }
    }
}

private fun destroyWebView(view: WebView) {
    (view.parent as? ViewGroup)?.removeView(view)
    view.stopLoading()
    view.destroy()
}

private fun destroyWebViews(container: FrameLayout) {
    for (i in 0 until container.childCount) {
        (container.getChildAt(i) as? WebView)?.let { view ->
            view.stopLoading()
            view.destroy()
        }
    }
    container.removeAllViews()
}
