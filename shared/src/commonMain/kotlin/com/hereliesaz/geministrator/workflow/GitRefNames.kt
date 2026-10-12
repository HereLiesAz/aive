package com.hereliesaz.geministrator.workflow

/**
 * Validates a branch or ref name that comes from a workflow definition before it reaches git or a
 * repository API. Workflow definitions can be model-authored, so a name must stay a single ref:
 * the rules of `git check-ref-format`, plus no leading `-` (option injection) and no `+` (force
 * refspec). A `:` is rejected with them, so `src:dst` cannot redirect a push to another branch.
 */
object GitRefNames {
    fun requireSafe(raw: String, kind: String = "Branch name"): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "$kind is required" }
        require(!name.startsWith('-')) { "$kind may not begin with '-'" }
        require(!name.startsWith('+')) { "$kind may not begin with '+'" }
        require(name.none { it.code < 0x20 || it.code == 0x7f || it in FORBIDDEN_CHARS }) {
            "$kind contains a character git does not allow in a ref (space, control, ~ ^ : ? * [ \\)"
        }
        require(".." !in name && "@{" !in name && "//" !in name && name != "@") {
            "$kind contains a sequence git does not allow in a ref"
        }
        require(!name.startsWith('/') && !name.endsWith('/') && !name.endsWith('.')) {
            "$kind may not begin or end with '/' or end with '.'"
        }
        require(name.split('/').none { it.startsWith('.') || it.endsWith(".lock") }) {
            "$kind has a component that begins with '.' or ends with '.lock'"
        }
        return name
    }

    private const val FORBIDDEN_CHARS = " ~^:?*[\\"
}
