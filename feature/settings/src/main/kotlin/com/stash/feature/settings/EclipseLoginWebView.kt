package com.stash.feature.settings

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONTokener

/**
 * The two Eclipse Music sign-in entry points. Both finish with a 307 to the
 * provider, then a callback back to `https://eclipsemusic.app/`, where the
 * site stores its session in `localStorage.eclipse_token`.
 */
internal const val ECLIPSE_LOGIN_GOOGLE = "https://eclipsemusic.app/configure/auth/google"
internal const val ECLIPSE_LOGIN_DISCORD = "https://eclipsemusic.app/configure/auth/discord"

/** The site's own password-reset page, opened in the browser. */
internal const val ECLIPSE_WEB_LOGIN = "https://eclipsemusic.app/web/login"

private const val TAG = "EclipseLogin"
private const val ECLIPSE_ORIGIN = "https://eclipsemusic.app"
private const val ECLIPSE_HOST = "eclipsemusic.app"
private const val TOKEN_KEY = "eclipse_token"

/**
 * Mobile Chrome user-agent WITHOUT the `; wv` WebView marker. Google's OAuth
 * consent page refuses plain WebViews ("this browser or app may not be
 * secure"), so the sign-in has to look like the Chrome build the WebView is
 * actually running on.
 */
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S938B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/**
 * Full-screen Eclipse account sign-in, hosted in a WebView.
 *
 * Google and Discord both redirect back to the Eclipse site, which writes the
 * account token into `localStorage.eclipse_token`. When that callback page
 * finishes loading we read the key with [WebView.evaluateJavascript] and hand
 * the value to [onToken] — the app's encrypted store is the only place the
 * token is kept. The WebView's storage and cookies are then wiped so a copy
 * of the session doesn't stay behind in the browser profile.
 *
 * The token is never rendered or logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EclipseLoginWebView(
    url: String,
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }
    // The callback page loaded but the key stayed empty (site error, or the
    // user cancelled on the provider). Shown inside the sheet, not a toast.
    var loginFailed by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) webView.goBack() else onDismiss()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_eclipse_signin_title)) },
            navigationIcon = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            captured -> Text(
                text = stringResource(R.string.settings_login_successful_connecting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            loginFailed -> Text(
                text = stringResource(R.string.settings_eclipse_login_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    // A token left over from an earlier attempt must not be
                    // mistaken for this sign-in: start the origin clean.
                    runCatching { WebStorage.getInstance().deleteOrigin(ECLIPSE_ORIGIN) }
                        .onFailure { Log.w(TAG, "clearing the Eclipse origin failed", it) }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        flush()
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = MOBILE_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }

                        fun readToken(view: WebView?) {
                            if (view == null || captured) return
                            view.evaluateJavascript(
                                "(function(){try{return window.localStorage.getItem('$TOKEN_KEY');}" +
                                    "catch(e){return null;}})();",
                            ) { raw ->
                                if (captured) return@evaluateJavascript
                                val token = parseEclipseToken(raw) ?: return@evaluateJavascript
                                captured = true
                                loginFailed = false
                                onToken(token)
                                wipeEclipseWebSession(view)
                                onDismiss()
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                isLoading = true
                                loginFailed = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (view == null || !isEclipseCallback(url)) return
                                readToken(view)
                                // The site writes the key from its own JS on
                                // the callback page; that can land just after
                                // the load event, so retry a couple of times.
                                view.postDelayed({ readToken(view) }, 1_500)
                                view.postDelayed({ readToken(view) }, 3_000)
                                view.postDelayed({
                                    if (!captured && isEclipseCallback(view.url)) {
                                        loginFailed = true
                                    }
                                }, 5_000)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }

                        loadUrl(url)
                    }.also { webViewRef = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * True for the post-sign-in page: an eclipsemusic.app URL that isn't one of
 * the `/configure/auth/…` entry points. Keeping the entry pages out means a
 * leftover token can't be captured before the user actually signs in.
 */
private fun isEclipseCallback(url: String?): Boolean {
    if (url == null) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.removePrefix("www.") ?: return false
    if (host != ECLIPSE_HOST) return false
    return !uri.path.orEmpty().startsWith("/configure")
}

/**
 * `evaluateJavascript` returns JSON: the token arrives as a quoted, escaped
 * string. Decode it once; [removeSurrounding] then strips the extra quotes of
 * a value the site stored already JSON-encoded. "null"/"undefined"/empty mean
 * there is nothing to take yet.
 */
private fun parseEclipseToken(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "undefined") return null
    val decoded = runCatching { JSONTokener(text).nextValue() as? String }.getOrNull()
        ?: return null
    val token = decoded.trim().removeSurrounding("\"").trim()
    return token.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
}

/**
 * Drop the session the WebView just created: the token (via local storage)
 * and the provider cookies. The credential lives in the app's encrypted store;
 * anything left here is a second, unprotected copy. Best-effort — a WebView
 * without storage still lets the sign-in finish.
 */
private fun wipeEclipseWebSession(view: WebView) {
    runCatching {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
    }.onFailure { Log.w(TAG, "clearing the Eclipse web session failed", it) }
}
