package com.hereliesaz.aive

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.hereliesaz.geministrator.providers.llm.AiModeBrowser
import com.hereliesaz.geministrator.providers.llm.AiModePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The browser view behind Google AI Mode on Android: a WebView that is never shown, on the keeper's own connection. It loads the search
 * page, lets the caller read the page text as it renders, and is destroyed when the caller is done. No cookies are kept: each question
 * opens a clean session, and the web storage is cleared with the view.
 *
 * It can also attach a document to the page's question box, the way a person attaches a file, for a request too long for the address
 * ([AiModePage.attachDocument]). The file is written to this app's private cache for that one page and deleted when the view closes.
 * The steps that find Google's attach and question controls read the page's own labels and are NOT verified against the live page
 * (no device here); where one is not found they report false and the request fails over to another source.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidAiModeBrowser(context: Context) : AiModeBrowser {
    private val appContext = context.applicationContext

    override suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T = withContext(Dispatchers.Main) {
        val view = WebView(appContext)
        val chooser = FileChooser()
        val folder = File(appContext.cacheDir, "ai-mode-" + UUID.randomUUID())
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webChromeClient = chooser.client
            view.loadUrl(url)
            read(WebPage(view, chooser, folder))
        } finally {
            view.stopLoading()
            view.clearCache(true)
            view.destroy()
            // The attached document goes as soon as the view does.
            folder.deleteRecursively()
        }
    }

    /** Answers the page's file chooser with the one document this ask attached, and with nothing for any other. */
    private class FileChooser {
        @Volatile
        var file: File? = null
        val offered = CompletableDeferred<Unit>()

        val client = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val ours = file
                if (ours == null) {
                    filePathCallback?.onReceiveValue(null)
                } else {
                    filePathCallback?.onReceiveValue(arrayOf(Uri.fromFile(ours)))
                    offered.complete(Unit)
                }
                return true
            }
        }
    }

    private class WebPage(private val view: WebView, private val chooser: FileChooser, private val folder: File) : AiModePage {
        override suspend fun text(): String = evaluate("(function(){return document.body ? document.body.innerText : ''})()")

        /** Evaluates [script] and returns the string it gives back ("" for anything else). */
        private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { raw ->
                // The script's string comes back JSON-quoted ("null" before a page exists).
                val value = runCatching { Json.decodeFromString<String>(raw.orEmpty()) }.getOrDefault("")
                if (continuation.isActive) continuation.resume(value)
            }
        }

        override suspend fun attachDocument(fileName: String, content: String): Boolean {
            folder.mkdirs()
            val document = File(folder, fileName).also { it.writeText(content) }
            chooser.file = document
            // Wait for the page to be there to attach to, then open its file chooser the way a person would.
            if (!waitFor { evaluate(FIND_QUESTION_BOX) == "found" }) return false
            if (evaluate(OPEN_ATTACH) == "none") return false
            val offered = withTimeoutOrNull(CHOOSER_WAIT_MILLIS) { chooser.offered.await() } != null
            if (!offered) {
                // A menu may need its upload entry chosen before the chooser opens.
                if (evaluate(CHOOSE_UPLOAD) == "none") return false
                if (withTimeoutOrNull(CHOOSER_WAIT_MILLIS) { chooser.offered.await() } == null) return false
            }
            // Taken once its name shows on the page.
            return waitFor { fileName in text() }
        }

        override suspend fun typeAndSend(question: String): Boolean {
            // A JSON string literal is a valid script literal, with every quote and line break escaped.
            val literal = kotlinx.serialization.json.JsonPrimitive(question).toString()
            if (evaluate(TYPE_QUESTION.replace("__QUESTION__", literal)) != "typed") return false
            delay(300)
            return evaluate(SEND_QUESTION) == "sent"
        }

        private suspend fun waitFor(check: suspend () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + PAGE_WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (check()) return true
                delay(POLL_MILLIS)
            }
            return false
        }
    }

    private companion object {
        const val PAGE_WAIT_MILLIS = 20_000L
        const val CHOOSER_WAIT_MILLIS = 6_000L
        const val POLL_MILLIS = 500L

        /** The page's question box: a text area, a text box role or an editable element. */
        const val FIND_QUESTION_BOX =
            "(function(){return document.querySelector('textarea, [role=textbox], [contenteditable=true]') ? 'found' : ''})()"

        /** Clicks the control that attaches a file: a file input if the page has one, else a button labelled for upload or add. */
        const val OPEN_ATTACH = """(function(){
            var input = document.querySelector('input[type=file]');
            if (input) { input.click(); return 'input'; }
            var words = /(upload|attach|add (a )?(file|image|photo)|add to search|^\+$)/i;
            var all = document.querySelectorAll('button, [role=button]');
            for (var i = 0; i < all.length; i++) {
              var label = (all[i].getAttribute('aria-label') || '') + ' ' + (all[i].innerText || '');
              if (words.test(label.trim())) { all[i].click(); return 'button'; }
            }
            return 'none';
        })()"""

        /** Chooses the upload entry of an attach menu that opened. */
        const val CHOOSE_UPLOAD = """(function(){
            var words = /(upload (a )?(file|image|photo)|from (this )?(device|computer|phone)|upload)/i;
            var all = document.querySelectorAll('[role=menuitem], [role=option], li, button, [role=button]');
            for (var i = 0; i < all.length; i++) {
              var label = (all[i].getAttribute('aria-label') || '') + ' ' + (all[i].innerText || '');
              if (words.test(label.trim())) { all[i].click(); return 'clicked'; }
            }
            return 'none';
        })()"""

        /** Types the question (a JSON string literal) into the question box. */
        const val TYPE_QUESTION = """(function(){
            var box = document.querySelector('textarea, [role=textbox], [contenteditable=true]');
            if (!box) return '';
            box.focus();
            var text = __QUESTION__;
            if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {
              var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(box), 'value').set;
              setter.call(box, text);
              box.dispatchEvent(new Event('input', { bubbles: true }));
            } else {
              document.execCommand('insertText', false, text);
            }
            return 'typed';
        })()"""

        /** Sends it: the send button if there is one, else Enter in the box. */
        const val SEND_QUESTION = """(function(){
            var all = document.querySelectorAll('button, [role=button]');
            for (var i = 0; i < all.length; i++) {
              var label = (all[i].getAttribute('aria-label') || '').trim();
              if (/^(send|submit|search|ask)/i.test(label) && !all[i].disabled) { all[i].click(); return 'sent'; }
            }
            var box = document.querySelector('textarea, [role=textbox], [contenteditable=true]');
            if (!box) return '';
            ['keydown', 'keypress', 'keyup'].forEach(function(type){
              box.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
            });
            return 'sent';
        })()"""
    }
}
