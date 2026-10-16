package com.hereliesaz.aive.remote

/**
 * Wire contract of [AiveRemoteService], the bound service other apps signed with The Aive's key
 * (IDEaz) use to run prompts and launch workflows. Messages travel over [android.os.Messenger];
 * every field lives in the message's data Bundle. IDEaz keeps a copy of these constants
 * (`com.hereliesaz.ideaz.ai.aive.AiveRemoteProtocol`); change both together and bump [VERSION].
 * See docs/Remote-API.md.
 */
object AiveRemoteProtocol {
    /** 2 added [KEY_LAUNCH_ID] and [MSG_LAUNCH_OUTCOME]; version 1 clients keep working. */
    const val VERSION = 2

    /** Bind with this action and the target package set. */
    const val ACTION_BIND = "com.hereliesaz.aive.action.REMOTE"

    /**
     * Signature permission guarding the service; clients declare `uses-permission`. Both flavors
     * declare this same name, which Android allows only because both are signed with one key;
     * see docs/Remote-API.md before signing either flavor differently.
     */
    const val PERMISSION = "com.hereliesaz.aive.permission.REMOTE"

    // Requests (client -> Aive). Each carries KEY_REQUEST_ID and a replyTo Messenger.
    /** Run one prompt. Prompt text arrives through KEY_PROMPT_FD (a pipe), never the Bundle. */
    const val MSG_PROMPT = 1
    /**
     * Queue an orchestrated workflow; the client then opens Aive, which launches it. The RESULT
     * carries [KEY_LAUNCH_ID]; watchers later get a [MSG_LAUNCH_OUTCOME] for it.
     */
    const val MSG_LAUNCH_WORKFLOW = 2
    /** Stream the status of the workflow run Aive is showing until the client unbinds. */
    const val MSG_WATCH_RUN = 3

    // Replies (Aive -> client).
    const val MSG_RESULT = 101
    const val MSG_ERROR = 102
    const val MSG_RUN_STATUS = 103
    /** To watchers: a queued launch started ([KEY_LAUNCH_STARTED]) or failed ([KEY_ERROR]). */
    const val MSG_LAUNCH_OUTCOME = 104

    const val KEY_VERSION = "version"
    const val KEY_REQUEST_ID = "requestId"
    /** ParcelFileDescriptor: read end of a pipe carrying the UTF-8 prompt. */
    const val KEY_PROMPT_FD = "promptFd"
    /** Optional Aive provider id (e.g. "kilo"); absent picks the first linked, then keyless. */
    const val KEY_PROVIDER_ID = "providerId"
    const val KEY_MODEL = "model"
    const val KEY_TEXT = "text"
    const val KEY_ERROR = "error"
    const val KEY_PROJECT_NAME = "projectName"
    const val KEY_OBJECTIVE = "objective"
    /** Optional `https://github.com/owner/name` the workflow works on. */
    const val KEY_REPOSITORY_URL = "repositoryUrl"
    /** Long: Aive's id for a queued launch, echoed in its [MSG_LAUNCH_OUTCOME]. */
    const val KEY_LAUNCH_ID = "launchId"
    /** Boolean: true when the launch started; false with [KEY_ERROR] when it failed or expired. */
    const val KEY_LAUNCH_STARTED = "launchStarted"
    const val KEY_RUN_ID = "runId"
    /** A [com.hereliesaz.geministrator.domain.WorkflowRunStatus] name. */
    const val KEY_RUN_STATUS = "runStatus"
    const val KEY_TASKS_TOTAL = "tasksTotal"
    const val KEY_TASKS_DONE = "tasksDone"
    const val KEY_PROGRESS_MESSAGE = "progressMessage"

    /** Upper bound on a prompt read from the pipe. */
    const val MAX_PROMPT_BYTES = 4 * 1024 * 1024
}
