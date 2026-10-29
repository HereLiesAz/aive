package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.stable_bred_name
import com.hereliesaz.geministrator.resources.stable_choose
import com.hereliesaz.geministrator.resources.stable_choose_hint
import com.hereliesaz.geministrator.resources.stable_generation
import com.hereliesaz.geministrator.resources.stable_hatched
import com.hereliesaz.geministrator.resources.stable_hatched_nothing
import com.hereliesaz.geministrator.resources.stable_its_creature
import com.hereliesaz.geministrator.resources.stable_its_creature_hint
import com.hereliesaz.geministrator.resources.stable_keep
import com.hereliesaz.geministrator.resources.stable_left
import com.hereliesaz.geministrator.resources.stable_seen
import com.hereliesaz.geministrator.resources.stable_stable
import com.hereliesaz.geministrator.resources.stable_stranger
import com.hereliesaz.geministrator.resources.stable_unlimited
import org.jetbrains.compose.resources.stringResource

/** A creature of the stable, drawn by its own rig, at rest. Never by an agent's role. */
@Composable
internal fun StableCreature(rig: String, size: Int, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalCreatureRig provides { rig }) {
        NodeMascotSurface(
            roleLabel = rig,
            hueSeed = rig,
            state = H2g2WorkflowState.Pending,
            motionPhase = { 0f },
            // The rig leaves a margin for the field's name pills; out here the whole creature is
            // drawn larger, tendrils and tail with it, into that margin.
            modifier = modifier.size(size.dp).graphicsLayer {
                scaleX = STABLE_CREATURE_SCALE
                scaleY = STABLE_CREATURE_SCALE
            },
        )
    }
}

private const val STABLE_CREATURE_SCALE = 1.35f

/** A creature key's name: its breed's, or its parents' for a bred one. Never its stats. */
@Composable
internal fun creatureName(stable: Stable, catalog: BreedCatalog, key: String): String {
    val bred = stable.bred.firstOrNull { it.id == key }
        ?: return catalog[key]?.name ?: key
    val names = bred.parents.map { catalog[it]?.name ?: it }
    return if (names.size < 2) names.firstOrNull() ?: key else stringResource(Res.string.stable_bred_name, names[0], names[1])
}

/**
 * The stable on the whole hive (mockups B1, B2, B4): the first creature to pick, then what the
 * keeper owns and the next generation's progress, and the last hatch until seen. Breeds show only
 * their name and rarity; how they breed is never shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StableSection(modifier: Modifier = Modifier) {
    val host = LocalStableHost.current ?: return
    val catalog = host.catalog
    val stable = host.stable
    if (catalog.breeds.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stable.starter == null) {
            var picked by remember { mutableStateOf(catalog.starters.firstOrNull()?.id) }
            Text(stringResource(Res.string.stable_choose), style = AzphaltType.section, color = azphaltOnSurface())
            Text(stringResource(Res.string.stable_choose_hint), style = AzphaltType.body, color = azphaltOnSurface())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                catalog.starters.forEach { breed ->
                    StableTile(
                        rig = breed.id,
                        title = breed.name,
                        caption = breed.rarity,
                        selected = picked == breed.id,
                        onClick = { picked = breed.id },
                    )
                }
            }
            picked?.let { id ->
                AzphaltPill(
                    label = stringResource(Res.string.stable_keep, catalog[id]?.name ?: id),
                    seed = "stable-keep",
                    onClick = { host.chooseStarter(id) },
                )
            }
            return@Column
        }
        stable.lastHatching?.let { hatching -> HatchingCard(hatching, stable, catalog, onSeen = host::dismissHatching) }
        Text(stringResource(Res.string.stable_stable), style = AzphaltType.section, color = azphaltOnSurface())
        Text(
            stringResource(Res.string.stable_generation, stable.generation, stable.progress, stable.need),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stable.keys.forEach { key ->
                StableTile(
                    rig = stable.rigOf(key),
                    title = creatureName(stable, catalog, key),
                    caption = catalog[stable.bodyOf(key)]?.rarity.orEmpty(),
                    count = if (key == stable.starter) stringResource(Res.string.stable_unlimited) else (stable.owned[key] ?: 1).toString(),
                )
            }
        }
    }
}

/**
 * The first hive's creature (docs/Keepers-Room.md, stage 2): the keeper's starter, large; before one
 * is kept, the starters to pick from, [picked] filled. Feeding the hive keeps the picked one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FirstCreature(picked: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val host = LocalStableHost.current ?: return
    val catalog = host.catalog
    val starter = host.stable.starter
    if (starter != null) {
        Box(modifier.fillMaxWidth().height(240.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            StableCreature(host.stable.rigOf(starter), 180)
        }
        return
    }
    if (catalog.starters.isEmpty()) return
    // One row, scrolled sideways, so the step that feeds the hive stays in view.
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        catalog.starters.forEach { breed ->
            StableTile(
                rig = breed.id,
                title = breed.name,
                caption = breed.rarity,
                selected = picked == breed.id,
                onClick = { onPick(breed.id) },
            )
        }
    }
}

/** One creature of the stable: the body large, its name and rarity under it, and how many. */
@Composable
private fun StableTile(
    rig: String,
    title: String,
    caption: String,
    count: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val ground = if (selected) Azphalt.Yellow else androidx.compose.ui.graphics.Color(0xFF2A231E)
    val ink = if (selected) Azphalt.Ink else Azphalt.White
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 156.dp, height = 236.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(ground)
            .let { if (onClick != null) it.clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick) else it }
            .padding(8.dp),
    ) {
        Box(Modifier.size(width = 140.dp, height = 168.dp), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
            StableCreature(rig, 120, Modifier.align(androidx.compose.ui.Alignment.Center))
            count?.let { Text(it, style = AzphaltType.section, color = if (selected) Azphalt.Ink else Azphalt.Yellow) }
        }
        Text(title, style = AzphaltType.endCap, color = ink, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(caption, style = AzphaltType.endCap, color = ink.copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun HatchingCard(hatching: Hatching, stable: Stable, catalog: BreedCatalog, onSeen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.stable_hatched, hatching.generation), style = AzphaltType.section, color = azphaltOnSurface())
        val child = hatching.child
        if (child == null) {
            Text(stringResource(Res.string.stable_hatched_nothing), style = AzphaltType.body, color = azphaltOnSurface())
        } else {
            Box(Modifier.size(200.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { StableCreature(stable.rigOf(child), 140) }
            Text(creatureName(stable, catalog, child), style = AzphaltType.body, color = azphaltOnSurface())
        }
        hatching.stranger?.let { stranger ->
            Text(stringResource(Res.string.stable_stranger, catalog[stranger]?.name ?: stranger), style = AzphaltType.body, color = Azphalt.Yellow)
            StableTile(rig = stranger, title = catalog[stranger]?.name ?: stranger, caption = catalog[stranger]?.rarity.orEmpty())
        }
        AzphaltPill(label = stringResource(Res.string.stable_seen), seed = "hatch-seen", onClick = onSeen)
    }
}

/** Giving an agent a creature (mockup B3): any the keeper owns; each lives in one agent at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreaturePicker(role: RoleDefinitionId, modifier: Modifier = Modifier) {
    val host = LocalStableHost.current ?: return
    val stable = host.stable
    if (stable.starter == null) return
    val current = stable.creatureOf(role)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.stable_its_creature), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(stringResource(Res.string.stable_its_creature_hint), style = AzphaltType.body, color = azphaltOnSurface())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stable.keys.forEach { key ->
                val left = stable.available(key, except = role)
                AzphaltPill(
                    label = creatureName(stable, host.catalog, key),
                    seed = "creature-$key",
                    selected = key == current,
                    endCap = when (left) {
                        null -> stringResource(Res.string.stable_unlimited)
                        else -> stringResource(Res.string.stable_left, left)
                    },
                    // Assigning checks the count, so one already living elsewhere stays put.
                    onClick = { host.assign(role, key) },
                )
            }
        }
    }
}

/** A breed for sale (mockup B5): the creature, its name and rarity, and its price on the buy button. */
@Composable
internal fun BreedRow(rig: String, title: String, caption: String, price: String, onBuy: (() -> Unit)?) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF2A231E))
            .padding(8.dp),
    ) {
        StableCreature(rig, 80)
        Column(Modifier.weight(1f)) {
            Text(title, style = AzphaltType.body, color = Azphalt.White)
            Text(caption, style = AzphaltType.endCap, color = Azphalt.White.copy(alpha = 0.7f))
        }
        // Without a way to pay on this platform, the price still shows; the button does nothing.
        AzphaltPill(label = price, seed = "buy-$rig", onClick = { onBuy?.invoke() })
    }
}
