package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.control.ControlRedaction

/**
 * Scrubs credential-shaped text from provider error bodies before they become exception messages.
 *
 * Failure messages are persisted as `TaskFailed.reason` and leave the device in run and project
 * exports, so an endpoint that echoes request headers or form fields must not carry a key with them.
 * There is one redactor for the whole app: this delegates to [ControlRedaction].
 */
internal fun String.redactSecrets(): String = ControlRedaction.scrub(this)
