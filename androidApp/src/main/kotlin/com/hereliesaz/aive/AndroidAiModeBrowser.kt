package com.hereliesaz.aive

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.hereliesaz.geministrator.providers.llm.AiModeBrowser
import com.hereliesaz.geministrator.providers.llm.AiModePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * The browser view behind Google AI Mode on Android: a WebView that is never shown, on the keeper's own connection. It loads the search
 * page, lets the caller read the page text as it renders, and is destroyed when the caller is done. No cookies are kept: each question
 * opens a clean session, and the web storage is cleared with the view.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidAiModeBrowser(context: Context) : AiModeBrowser {
    private val appContext = context.applicationContext

    override suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T = withContext(Dispatchers.Main) {
        val view = WebView(appContext)
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.loadUrl(url)
            read(WebPage(view))
        } finally {
            view.stopLoading()
            view.clearCache(true)
            view.destroy()
        }
    }

    private class WebPage(private val view: WebView) : AiModePage {
        override suspend fun text(): String = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { raw ->
                // The script's string comes back JSON-quoted ("null" before a page exists).
                val text = runCatching { Json.decodeFromString<String>(raw.orEmpty()) }.getOrDefault("")
                if (continuation.isActive) continuation.resume(text)
            }
        }
    }
}
