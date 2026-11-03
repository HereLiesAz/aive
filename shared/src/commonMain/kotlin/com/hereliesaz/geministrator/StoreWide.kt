package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary
import com.hereliesaz.geministrator.azphalt.isDirectLlmPackage
import com.hereliesaz.geministrator.azphalt.isModelAssetPackage
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.store_card_inspect
import com.hereliesaz.geministrator.resources.store_hive_from_store
import com.hereliesaz.geministrator.resources.store_kind_agent
import com.hereliesaz.geministrator.resources.store_kind_hive
import com.hereliesaz.geministrator.resources.store_llm
import com.hereliesaz.geministrator.resources.store_model
import com.hereliesaz.geministrator.resources.store_view_hive
import org.jetbrains.compose.resources.stringResource

/*
 * The Store on wide screens (820dp and up; the Figma Make design): the tabs on an Ink capsule, a
 * hive from the Store leading, and every package as a card. Phones keep the list (mockup B5).
 */

/** The Store's tabs: on wide screens an Ink capsule, as in the Figma design; on phones bare. */
@Composable
internal fun StoreTabsRow(wide: Boolean, tabs: @Composable () -> Unit) {
    if (!wide) {
        tabs()
        return
    }
    Box(
        Modifier
            .widthIn(max = 960.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Azphalt.Ink)
            .padding(5.dp),
    ) {
        AzphaltThemedSurface(Azphalt.Ink) { tabs() }
    }
}

/** What a package is, in the hive's words, then who made it. */
@Composable
internal fun storeCardEyebrow(item: AzphaltPackageSummary): String {
    val kind = when {
        item.isModelAssetPackage() -> stringResource(Res.string.store_model)
        item.isDirectLlmPackage() -> stringResource(Res.string.store_llm)
        item.kind == "workflow" -> stringResource(Res.string.store_kind_hive)
        item.kind == "role" -> stringResource(Res.string.store_kind_agent)
        else -> item.kind
    }
    return listOfNotNull(kind, item.author?.takeIf(String::isNotBlank)).joinToString(" · ")
}

/**
 * A hive from the Store, leading the page: its name large, what it does, and View hive, which opens
 * its record (and from there its install). The first hive the Store lists; nothing ranks it.
 */
@Composable
internal fun FeaturedStoreHive(hive: AzphaltPackageSummary, onView: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 245.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Azphalt.Ink)
            .padding(29.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AzphaltThemedSurface(Azphalt.Ink) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(Res.string.store_hive_from_store).uppercase(), style = AzphaltType.eyebrow, color = Azphalt.Yellow)
                Text(hive.name, style = AzphaltType.section, color = azphaltOnSurface())
                hive.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = AzphaltType.body, color = azphaltMutedOnSurface(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.padding(top = 10.dp)) {
                    AzphaltPill(
                        label = stringResource(Res.string.store_view_hive),
                        seed = "store-featured-${hive.id}",
                        tone = PillTone.Primary,
                        onClick = onView,
                    )
                }
            }
            // Three of its creatures at rest: a hive reads as its swarm.
            BoxWithConstraints(Modifier.weight(1f).height(200.dp).clipToBounds()) {
                val size = minOf(150, (maxWidth.value / 2.4f).toInt())
                listOf(0f to 20, 0.31f to -12, 0.62f to 31).forEachIndexed { i, (left, top) ->
                    StableCreature(edgeCreatureFor("store:${hive.id}:$i"), size, Modifier.offset(x = maxWidth * left, y = top.dp + 20.dp))
                }
            }
        }
    }
}

/** Every package as a card, [columns] across. */
@Composable
internal fun StoreCardGrid(
    packages: List<AzphaltPackageSummary>,
    columns: Int,
    selectedId: String?,
    footerOf: @Composable (AzphaltPackageSummary) -> String,
    onInspect: (AzphaltPackageSummary) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        packages.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    StoreCard(
                        item = item,
                        footer = footerOf(item),
                        selected = item.id == selectedId,
                        onInspect = { onInspect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One package: its creature on an art panel of its own hue, what it is, its name, a line, and Inspect. */
@Composable
private fun StoreCard(
    item: AzphaltPackageSummary,
    footer: String,
    selected: Boolean,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Azphalt.Ink)
            .then(if (selected) Modifier.border(2.dp, Azphalt.Yellow, shape) else Modifier),
    ) {
        Box(
            Modifier.fillMaxWidth().height(130.dp).background(Azphalt.hue("store-${item.id}")),
            contentAlignment = Alignment.Center,
        ) {
            StableCreature(edgeCreatureFor("store:${item.id}"), 110)
        }
        AzphaltThemedSurface(Azphalt.Ink) {
            Column(Modifier.padding(horizontal = 15.dp).padding(top = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(storeCardEyebrow(item).uppercase(), style = AzphaltType.eyebrow, color = Azphalt.Yellow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.name, style = AzphaltType.lead.copy(fontWeight = FontWeight.Black), color = azphaltOnSurface(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.description?.takeIf(String::isNotBlank) ?: item.id,
                    style = EdgeLine,
                    color = azphaltMutedOnSurface(),
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(AzphaltLiftedInk))
            Row(
                Modifier.fillMaxWidth().padding(start = 15.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(footer.uppercase(), style = AzphaltType.endCap, color = azphaltMutedOnSurface(), modifier = Modifier.weight(1f), maxLines = 1)
                val inspect = stringResource(Res.string.store_card_inspect)
                Box(
                    Modifier
                        .defaultMinSize(minWidth = AzphaltMinTouchTarget, minHeight = AzphaltMinTouchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .semantics { contentDescription = "$inspect ${item.name}" }
                        .clickable(role = Role.Button, onClick = onInspect)
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(inspect.uppercase(), style = AzphaltType.capsule, color = Azphalt.Yellow)
                }
            }
        }
    }
}
