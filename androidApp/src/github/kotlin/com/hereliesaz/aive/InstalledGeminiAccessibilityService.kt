package com.hereliesaz.aive

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Drives only the currently active installed-Gemini handoff.
 *
 * The service ignores every other package and every event while Haive has no pending bridge run.
 */
class InstalledGeminiAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""
    private var stableJob: Runnable? = null
    private var inputJob: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!InstalledGeminiBridge.waiting) return
        // Enabled in Android settings is not enough: the in-app opt-in must also be on.
        if (!InstalledGeminiPreference.isEnabled(this)) return
        val target = InstalledGeminiBridge.targetPackage ?: return
        if (event?.packageName?.toString() != target) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        when (InstalledGeminiBridge.phase) {
            InstalledGeminiBridge.Phase.Input -> scheduleInput()
            InstalledGeminiBridge.Phase.AwaitResponse -> scheduleCapture()
            InstalledGeminiBridge.Phase.Idle -> Unit
        }
    }

    private fun scheduleInput() {
        inputJob?.let(handler::removeCallbacks)
        inputJob = Runnable { submitPrompt() }.also { handler.postDelayed(it, INPUT_STABLE_MS) }
    }

    private fun submitPrompt() {
        if (!InstalledGeminiBridge.waiting ||
            InstalledGeminiBridge.phase != InstalledGeminiBridge.Phase.Input ||
            InstalledGeminiBridge.promptSubmitted
        ) return

        val root = targetRoot() ?: return
        val field = findInputField(root) ?: return
        val prompt = InstalledGeminiBridge.pendingPrompt.orEmpty()
        if (prompt.isBlank()) return

        if (field.text?.toString() != prompt) {
            setInputText(field, prompt)
            return
        }

        val send = findClickable(root, InstalledGeminiBridgeHeuristics::isSendHint) ?: return
        if (!send.isEnabled) return

        InstalledGeminiBridge.baselineCopyActions = countClickable(
            root,
            InstalledGeminiBridgeHeuristics::isCopyHint,
        )
        InstalledGeminiBridge.baselineSnapshot = collectText(root).trim()
        InstalledGeminiBridge.observedGenerating = false

        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            InstalledGeminiBridge.promptSubmitted = true
            InstalledGeminiBridge.phase = InstalledGeminiBridge.Phase.AwaitResponse
            lastSnapshot = ""
        }
    }

    private fun scheduleCapture() {
        val root = targetRoot() ?: return
        val snapshot = collectText(root).trim()
        val generating = containsHint(root, InstalledGeminiBridgeHeuristics::isGeneratingHint)
        if (generating) {
            InstalledGeminiBridge.observedGenerating = true
            stableJob?.let(handler::removeCallbacks)
            lastSnapshot = ""
            return
        }
        if (!isCompletedResponse(root, snapshot) || snapshot == lastSnapshot) return

        lastSnapshot = snapshot
        stableJob?.let(handler::removeCallbacks)
        stableJob = Runnable { captureStableResponse(snapshot) }.also {
            handler.postDelayed(it, RESPONSE_STABLE_MS)
        }
    }

    private fun captureStableResponse(candidateSnapshot: String) {
        if (!InstalledGeminiBridge.waiting ||
            InstalledGeminiBridge.phase != InstalledGeminiBridge.Phase.AwaitResponse
        ) return

        val root = targetRoot() ?: return
        val currentSnapshot = collectText(root).trim()
        if (currentSnapshot != candidateSnapshot || !isCompletedResponse(root, currentSnapshot)) {
            lastSnapshot = ""
            scheduleCapture()
            return
        }

        val copy = findClickable(root, InstalledGeminiBridgeHeuristics::isCopyHint)
        if (copy != null && copy.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ deliverClipboardOrScrape(currentSnapshot) }, CLIPBOARD_DELAY_MS)
        } else {
            deliverScraped(currentSnapshot)
        }
    }

    private fun isCompletedResponse(root: AccessibilityNodeInfo, snapshot: String): Boolean {
        if (snapshot.isBlank() || snapshot == InstalledGeminiBridge.baselineSnapshot) return false
        if (containsHint(root, InstalledGeminiBridgeHeuristics::isGeneratingHint)) return false
        val copyActions = countClickable(root, InstalledGeminiBridgeHeuristics::isCopyHint)
        return copyActions > InstalledGeminiBridge.baselineCopyActions ||
            (InstalledGeminiBridge.observedGenerating && copyActions > 0)
    }

    private fun deliverClipboardOrScrape(snapshot: String) {
        if (!InstalledGeminiBridge.waiting) return
        val text = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
        }.getOrNull()

        if (!text.isNullOrBlank() && text != InstalledGeminiBridge.pendingPrompt) {
            finish(text)
        } else {
            deliverScraped(snapshot)
        }
    }

    private fun deliverScraped(snapshot: String) {
        val response = InstalledGeminiBridgeHeuristics.extractResponse(
            snapshot,
            InstalledGeminiBridge.pendingPrompt.orEmpty(),
        )
        if (response.isNotBlank()) finish(response)
    }

    private fun finish(response: String) {
        InstalledGeminiBridge.waiting = false
        InstalledGeminiBridge.phase = InstalledGeminiBridge.Phase.Idle
        InstalledGeminiBridge.responses.trySend(response)
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var hinted: AccessibilityNodeInfo? = null
        var fallback: AccessibilityNodeInfo? = null
        walk(root) { node ->
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (editable) {
                fallback = node
                val hint = node.hintText?.toString()
                    ?: node.contentDescription?.toString()
                    ?: node.text?.toString()
                if (InstalledGeminiBridgeHeuristics.isInputHint(hint)) hinted = node
            }
        }
        return hinted ?: fallback
    }

    private fun findClickable(
        root: AccessibilityNodeInfo,
        matcher: (String?) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (node.isClickable && matcher(nodeHint(node))) found = node
        }
        return found
    }

    private fun countClickable(
        root: AccessibilityNodeInfo,
        matcher: (String?) -> Boolean,
    ): Int {
        var count = 0
        walk(root) { node ->
            if (node.isClickable && matcher(nodeHint(node))) count++
        }
        return count
    }

    private fun containsHint(
        root: AccessibilityNodeInfo,
        matcher: (String?) -> Boolean,
    ): Boolean {
        var found = false
        walk(root) { node -> if (!found && matcher(nodeHint(node))) found = true }
        return found
    }

    private fun nodeHint(node: AccessibilityNodeInfo): String? =
        node.contentDescription?.toString()
            ?: node.text?.toString()
            ?: node.hintText?.toString()

    private fun setInputText(field: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Haive Gemini prompt", text))
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private fun targetRoot(): AccessibilityNodeInfo? {
        val target = InstalledGeminiBridge.targetPackage ?: return null
        rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == target }
            ?.let { return it }

        return runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == target }
        }.getOrNull()
    }

    private fun walk(root: AccessibilityNodeInfo?, block: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        block(root)
        for (index in 0 until root.childCount) walk(root.getChild(index), block)
    }

    private fun collectText(
        node: AccessibilityNodeInfo?,
        out: StringBuilder = StringBuilder(),
    ): String {
        if (node == null) return out.toString()
        node.text?.toString()?.takeIf(String::isNotBlank)?.let {
            if (out.isNotEmpty()) out.append('\n')
            out.append(it)
        }
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let {
            if (node.text?.toString() != it) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(it)
            }
        }
        for (index in 0 until node.childCount) collectText(node.getChild(index), out)
        return out.toString()
    }

    override fun onInterrupt() {
        stableJob?.let(handler::removeCallbacks)
        inputJob?.let(handler::removeCallbacks)
        stableJob = null
        inputJob = null
        lastSnapshot = ""
    }

    private companion object {
        const val INPUT_STABLE_MS = 500L
        const val RESPONSE_STABLE_MS = 1_250L
        const val CLIPBOARD_DELAY_MS = 300L
    }
}
