package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable

@Serializable
data class AddonScreen(
    val title: String,
    val sections: List<AddonScreenSection> = emptyList()
)

@Serializable
sealed class AddonScreenSection {
    @Serializable
    data class Group(val title: String, val items: List<AddonScreenItem>) : AddonScreenSection()
}

@Serializable
sealed class AddonScreenItem {
    @Serializable
    data class Text(val content: String) : AddonScreenItem()
    @Serializable
    data class Record(val key: String, val value: String) : AddonScreenItem()
    @Serializable
    data class Status(val label: String, val status: String) : AddonScreenItem()
    @Serializable
    data class Button(val label: String, val actionId: String) : AddonScreenItem()
    @Serializable
    data class TextInput(val label: String, val bindingId: String) : AddonScreenItem()
    @Serializable
    data class Select(val label: String, val options: List<String>, val bindingId: String) : AddonScreenItem()
    @Serializable
    data class Toggle(val label: String, val bindingId: String) : AddonScreenItem()
}
