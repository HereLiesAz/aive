package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.stable_added
import com.hereliesaz.geministrator.resources.stable_another_agent
import com.hereliesaz.geministrator.resources.stable_bred_name
import com.hereliesaz.geministrator.resources.stable_choose
import com.hereliesaz.geministrator.resources.stable_choose_hint
import com.hereliesaz.geministrator.resources.stable_creature_count
import com.hereliesaz.geministrator.resources.stable_cross
import com.hereliesaz.geministrator.resources.stable_filter_all
import com.hereliesaz.geministrator.resources.stable_filter_hatched
import com.hereliesaz.geministrator.resources.stable_gen_chip
import com.hereliesaz.geministrator.resources.stable_generation
import com.hereliesaz.geministrator.resources.stable_hatched
import com.hereliesaz.geministrator.resources.stable_hatched_nothing
import com.hereliesaz.geministrator.resources.stable_in_agent
import com.hereliesaz.geministrator.resources.stable_its_creature
import com.hereliesaz.geministrator.resources.stable_its_creature_hint
import com.hereliesaz.geministrator.resources.stable_keep
import com.hereliesaz.geministrator.resources.stable_left
import com.hereliesaz.geministrator.resources.stable_living_in
import com.hereliesaz.geministrator.resources.stable_progress
import com.hereliesaz.geministrator.resources.stable_seen
import com.hereliesaz.geministrator.resources.stable_stable
import com.hereliesaz.geministrator.resources.stable_starter
import com.hereliesaz.geministrator.resources.stable_starter_unlimited
import com.hereliesaz.geministrator.resources.stable_stranger
import com.hereliesaz.geministrator.resources.stable_stranger_added
import com.hereliesaz.geministrator.resources.stable_unlimited
import org.jetbrains.compose.resources.pluralStringResource
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

// The stable's type (mockups B1-B4), from AzphaltType at the mockups' weights and sizes.
private val HeadingType = AzphaltType.lead.copy(fontWeight = FontWeight.ExtraBold)
private val TitleType = AzphaltType.body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
private val NameType = AzphaltType.body.copy(fontWeight = FontWeight.Bold)
private val SmallNameType = AzphaltType.body.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
private val CaptionType = AzphaltType.body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
private val NoteType = AzphaltType.body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
private val CountType = AzphaltType.lead.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)

/** The widest a stable view grows on a wide screen, so its grid and rows keep the phone's proportions. */
private val StableMaxWidth = 560.dp

/**
 * The stable (mockups B1, B2, B4), wherever it is shown: the first creature to pick; then the last
 * hatch until it is seen; then what the keeper owns and the next generation's progress. Breeds show
 * only their name and rarity; how they breed is never shown.
 */
@Composable
internal fun StableSection(modifier: Modifier = Modifier) {
    val host = LocalStableHost.current ?: return
    val catalog = host.catalog
    val stable = host.stable
    if (catalog.breeds.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val hatching = stable.lastHatching
        // From the shell's wide breakpoint up, the stable is the Figma desktop card grid; the pick and
        // the hatch keep their phone shape there too, at the phone's width.
        val wide = maxWidth >= ControlRoomBreakpoints.Wide && stable.starter != null && hatching == null
        val columns = if (maxWidth > WIDE_FULL) 3 else 2
        Column(
            modifier = if (wide) Modifier.fillMaxWidth() else Modifier.widthIn(max = StableMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                stable.starter == null -> StarterPick(catalog, onKeep = host::chooseStarter)
                hatching != null -> HatchView(hatching, stable, catalog, onSeen = host::dismissHatching)
                wide -> WideStable(stable, catalog, columns = columns)
                else -> OwnedStable(stable, catalog)
            }
        }
    }
}

/** Figma's full-rail breakpoint: above it the stable grid takes three columns, below it two. */
private val WIDE_FULL = 1080.dp

/**
 * The stable on a wide screen (the Figma desktop Stable): the generation's progress, a filter row
 * by what is really known of each creature (its rarity, or hatched here), and a grid of cards, each
 * an art panel with the creature large and, for a hatched one, its generation; its rarity, name
 * and how many below. No stats: breeds differ only in rarity.
 */
@Composable
private fun ColumnScope.WideStable(stable: Stable, catalog: BreedCatalog, columns: Int) {
    StableHeading(stringResource(Res.string.stable_stable))
    GenerationCard(stable.generation, stable.progress, stable.need)
    val all = stringResource(Res.string.stable_filter_all)
    val hatchedLabel = stringResource(Res.string.stable_filter_hatched)
    val filters = stableFilters(stable, catalog)
    var filter by remember { mutableStateOf(STABLE_FILTER_ALL) }
    if (filter !in filters) filter = STABLE_FILTER_ALL
    val shown = stable.keys.filter { stableFilterOf(stable, catalog, it) == filter || filter == STABLE_FILTER_ALL }
    AzphaltSurface(Azphalt.Ink) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 49.dp).clip(RoundedCornerShape(25.dp)).background(Azphalt.Ink).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AzphaltTabs(
                tabs = filters.map { key ->
                    AzphaltTabItem(
                        key = key,
                        label = when (key) {
                            STABLE_FILTER_ALL -> all
                            STABLE_FILTER_HATCHED -> hatchedLabel
                            else -> key
                        },
                    )
                },
                selectedKey = filter,
                onSelected = { filter = it },
                seedPrefix = "stable-filter",
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(Res.plurals.stable_creature_count, shown.size, shown.size).uppercase(),
                style = AzphaltType.eyebrow,
                color = azphaltMutedOnSurface(),
                modifier = Modifier.padding(start = 8.dp, end = 14.dp).azphaltLiveRegion(),
            )
        }
    }
    shown.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { key ->
                val starter = key == stable.starter
                StableGridCard(
                    rig = stable.rigOf(key),
                    title = creatureName(stable, catalog, key),
                    eyebrow = if (starter) stringResource(Res.string.stable_starter) else catalog[stable.bodyOf(key)]?.rarity.orEmpty(),
                    count = if (starter) stringResource(Res.string.stable_unlimited) else (stable.owned[key] ?: 1).toString(),
                    generation = stable.bred.firstOrNull { it.id == key }?.generation,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

internal const val STABLE_FILTER_ALL = "all"
internal const val STABLE_FILTER_HATCHED = "hatched"

/** What a creature is filtered by on the wide stable: hatched here, else its body's rarity. */
internal fun stableFilterOf(stable: Stable, catalog: BreedCatalog, key: String): String =
    if (stable.bred.any { it.id == key }) STABLE_FILTER_HATCHED else catalog[stable.bodyOf(key)]?.rarity.orEmpty()

/** The filters worth offering: All, then each rarity the keeper owns (rarest last), then Hatched. */
internal fun stableFilters(stable: Stable, catalog: BreedCatalog): List<String> {
    val present = stable.keys.map { stableFilterOf(stable, catalog, it) }.filter(String::isNotBlank).toSet()
    val rarities = RARITY_ORDER.filter { it in present } + (present - RARITY_ORDER.toSet() - STABLE_FILTER_HATCHED).sorted()
    return listOf(STABLE_FILTER_ALL) + rarities + listOfNotNull(STABLE_FILTER_HATCHED.takeIf { it in present })
}

private val RARITY_ORDER = listOf("Common", "Uncommon", "Rare", "Very rare", "Mythic")

/** One card of the wide stable grid: the art panel in the creature's own hue, then its copy. */
@Composable
private fun StableGridCard(rig: String, title: String, eyebrow: String, count: String, generation: Int?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(19.dp)
    val art = Azphalt.hue(rig)
    Column(modifier.heightIn(min = 300.dp).clip(shape).background(Azphalt.Ink).semantics(mergeDescendants = true) {}) {
        Box(Modifier.fillMaxWidth().height(184.dp).background(art), contentAlignment = Alignment.Center) {
            StableCreature(rig, 150)
            generation?.let { gen ->
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Azphalt.Ink)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                ) {
                    Text(stringResource(Res.string.stable_gen_chip, gen).uppercase(), style = AzphaltType.endCap, color = Azphalt.Ink.onOpposite())
                }
            }
        }
        AzphaltSurface(Azphalt.Ink) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = azphaltStrong())
                    Text(title, style = TitleType.copy(fontWeight = FontWeight.Black), color = azphaltOnSurface())
                }
                Text(count, style = CountType, color = azphaltStrong())
            }
        }
    }
}

/** B1: one starter to keep, unlimited. */
@Composable
private fun ColumnScope.StarterPick(catalog: BreedCatalog, onKeep: (String) -> Unit) {
    var picked by remember { mutableStateOf(catalog.starters.firstOrNull()?.id) }
    StableHeading(stringResource(Res.string.stable_choose))
    StableCard { Text(stringResource(Res.string.stable_choose_hint), style = AzphaltType.body, color = azphaltOnSurface()) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.starters.chunked(STARTER_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { breed ->
                    StableTile(
                        rig = breed.id,
                        title = breed.name,
                        caption = breed.rarity.lowercase(),
                        selected = picked == breed.id,
                        onClick = { picked = breed.id },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(STARTER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val breed = picked?.let(catalog::get) ?: return
    // The picked one again, larger, with its name and rarity.
    StableCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CreatureBox(breed.id, 64)
            Column(Modifier.weight(1f)) {
                Text(breed.name, style = NameType.copy(fontSize = 17.sp), color = azphaltOnSurface())
                Text(breed.rarity, style = NoteType, color = azphaltMutedOnSurface())
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
        AzphaltPill(
            label = stringResource(Res.string.stable_keep, breed.name),
            seed = "stable-keep",
            tone = PillTone.Primary,
            onClick = { onKeep(breed.id) },
            modifier = Modifier.heightIn(min = 56.dp),
        )
    }
}

private const val STARTER_COLUMNS = 3

/** B2: the next generation's progress, and every creature owned with how many. */
@Composable
private fun ColumnScope.OwnedStable(stable: Stable, catalog: BreedCatalog) {
    StableHeading(stringResource(Res.string.stable_stable))
    GenerationCard(stable.generation, stable.progress, stable.need)
    StableCard {
        stable.keys.forEach { key ->
            val starter = key == stable.starter
            StableRow(
                rig = stable.rigOf(key),
                title = creatureName(stable, catalog, key),
                caption = if (starter) stringResource(Res.string.stable_starter) else catalog[stable.bodyOf(key)]?.rarity.orEmpty().lowercase(),
                count = if (starter) stringResource(Res.string.stable_unlimited) else (stable.owned[key] ?: 1).toString(),
            )
        }
    }
}

/** "Generation N", "k of n workflows", and n segments with k filled. */
@Composable
private fun GenerationCard(generation: Int, progress: Int, need: Int) {
    val title = stringResource(Res.string.stable_generation, generation)
    val tally = stringResource(Res.string.stable_progress, progress, need)
    StableCard(Modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TitleType, color = azphaltOnSurface(), modifier = Modifier.weight(1f))
            Text(tally, style = NoteType, color = azphaltMutedOnSurface())
        }
        val segments = need.coerceIn(1, MAX_SEGMENTS)
        val filled = if (need <= MAX_SEGMENTS) progress else progress * segments / need
        val track = stableRowFill()
        val fill = azphaltStrong()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.toFloat(), 0f..need.toFloat().coerceAtLeast(1f)) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(segments) { index ->
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (index < filled) fill else track))
            }
        }
    }
}

/** Past this many workflows the bar stays this many segments, filled in proportion. */
private const val MAX_SEGMENTS = 21

/** B4: the parents, the child large, its generation, and a stranger if one came. Nothing says why. */
@Composable
private fun ColumnScope.HatchView(hatching: Hatching, stable: Stable, catalog: BreedCatalog, onSeen: () -> Unit) {
    StableHeading(stringResource(Res.string.stable_hatched))
    if (hatching.parents.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hatching.parents.forEachIndexed { index, parent ->
                if (index > 0) {
                    Text(stringResource(Res.string.stable_cross), style = CountType, color = azphaltStrong(), modifier = Modifier.clearAndSetSemantics {})
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val name = catalog[parent]?.name ?: parent
                    CreatureBox(parent, 70, description = name)
                    Text(name, style = CaptionType, color = azphaltMutedOnSurface(), modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
    val child = hatching.child
    if (child == null) {
        Text(stringResource(Res.string.stable_hatched_nothing), style = AzphaltType.body, color = azphaltOnSurface())
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CreatureBox(stable.rigOf(child), 150, description = creatureName(stable, catalog, child))
        }
        StableCard(Modifier.semantics(mergeDescendants = true) {}) {
            Text(stringResource(Res.string.stable_generation, hatching.generation), style = NameType.copy(fontSize = 17.sp), color = azphaltOnSurface())
            Text(stringResource(Res.string.stable_added), style = NoteType, color = azphaltMutedOnSurface())
        }
    }
    hatching.stranger?.let { stranger ->
        val breed = catalog[stranger]
        StableCard(Modifier.semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreatureBox(stranger, 56)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.stable_stranger, breed?.name ?: stranger),
                        style = NameType.copy(fontSize = 16.sp),
                        color = azphaltStrong(),
                    )
                    Text(stringResource(Res.string.stable_stranger_added, breed?.rarity ?: ""), style = NoteType, color = azphaltMutedOnSurface())
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
        AzphaltPill(label = stringResource(Res.string.stable_seen), seed = "hatch-seen", onClick = onSeen)
    }
}

/**
 * The first hive's creature (docs/Keepers-Room.md, stage 2): the keeper's starter, large; before one
 * is kept, the starters to pick from, [picked] filled. Feeding the hive keeps the picked one.
 */
@Composable
internal fun FirstCreature(picked: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val host = LocalStableHost.current ?: return
    val catalog = host.catalog
    val starter = host.stable.starter
    if (starter != null) {
        Box(modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            StableCreature(host.stable.rigOf(starter), 180)
        }
        return
    }
    if (catalog.starters.isEmpty()) return
    // One row, scrolled sideways, so the step that feeds the hive stays in view.
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        catalog.starters.forEach { breed ->
            StableTile(
                rig = breed.id,
                title = breed.name,
                caption = breed.rarity.lowercase(),
                selected = picked == breed.id,
                onClick = { onPick(breed.id) },
                modifier = Modifier.width(116.dp),
            )
        }
    }
}

/** A stable view's heading: a pill in the surface's strong colour, as the shell's own header. */
@Composable
private fun StableHeading(text: String) {
    val fill = azphaltStrong()
    Box(
        Modifier
            .semantics { heading() }
            .heightIn(min = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(24.dp))
            .background(fill)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text.uppercase(), style = HeadingType, color = fill.onOpposite())
    }
}

/** A dark card: Ink, or lifted Ink where the surface is already Ink. Its content takes its opposite. */
@Composable
private fun StableCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val surface = azphaltSurface()
    val fill = if (surface == Azphalt.Ink || surface == AzphaltLiftedInk) AzphaltLiftedInk else Azphalt.Ink
    AzphaltSurface(fill) {
        Column(
            modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(AzphaltShapes.dialogCorner)).background(fill).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** A row or tile set apart from the surface it sits on: lifted Ink on Ink, Ink elsewhere. */
@Composable
private fun stableRowFill(): Color = if (azphaltSurface() == Azphalt.Ink) AzphaltLiftedInk else Azphalt.Ink

/** A creature at [size], named for screen readers when [description] is given. */
@Composable
private fun CreatureBox(rig: String, size: Int, description: String? = null) {
    Box(
        Modifier.size(size.dp).then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        StableCreature(rig, (size * CREATURE_IN_BOX).toInt())
    }
}

/** How much of its box a creature's rig takes before [STABLE_CREATURE_SCALE] grows it into the margin. */
private const val CREATURE_IN_BOX = 0.8f

/** One starter to pick (B1): the creature, its name and rarity; Yellow, outlined, when picked. */
@Composable
private fun StableTile(
    rig: String,
    title: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    // Records (docs/BRANDING.md): dark, and Yellow outlined in the surface's content colour when
    // selected, so the chosen one stands off the mustard ground rather than melting into it.
    val fill = if (selected) Azphalt.Yellow else stableRowFill()
    val outline = azphaltOnSurface()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(if (selected) Modifier.border(3.dp, outline, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        AzphaltSurface(fill) {
            CreatureBox(rig, 86)
            Text(title, style = SmallNameType, color = azphaltOnSurface(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = CaptionType.copy(fontSize = 11.sp), color = azphaltMutedOnSurface(), maxLines = 1)
        }
    }
}

/** One creature owned (B2): the creature, its name, rarity or starter, and how many on the right. */
@Composable
private fun StableRow(rig: String, title: String, caption: String, count: String) {
    val fill = stableRowFill()
    AzphaltSurface(fill) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clip(RoundedCornerShape(26.dp))
                .background(fill)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp),
        ) {
            CreatureBox(rig, 52)
            Column(Modifier.weight(1f)) {
                Text(title, style = NameType, color = azphaltOnSurface())
                Text(caption, style = CaptionType, color = azphaltMutedOnSurface())
            }
            Text(count, style = CountType, color = azphaltStrong())
        }
    }
}

/**
 * Giving an agent a creature (mockup B3): any the keeper owns, with what is left of each; each
 * lives in one agent at a time, so one already living in another agent is dimmed and says where.
 * [agentName] names an agent by its role id, or null when it is not in this hive.
 */
@Composable
internal fun CreaturePicker(role: RoleDefinitionId, modifier: Modifier = Modifier, agentName: (String) -> String? = { null }) {
    val host = LocalStableHost.current ?: return
    val stable = host.stable
    if (stable.starter == null) return
    val current = stable.creatureOf(role)
    val another = stringResource(Res.string.stable_another_agent)
    Column(modifier = modifier.widthIn(max = StableMaxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.stable_its_creature), style = TitleType.copy(fontSize = 20.sp), color = azphaltOnSurface())
        Text(stringResource(Res.string.stable_its_creature_hint), style = NoteType, color = azphaltMutedOnSurface())
        val elsewhere = mutableListOf<Pair<String, String>>()
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stable.keys.forEach { key ->
                val name = creatureName(stable, host.catalog, key)
                val left = stable.available(key, except = role)
                val selected = key == current
                // Every one of it already lives in another agent: say which.
                val livingIn = if (left == 0 && !selected) {
                    stable.assignments.filter { (agent, creature) -> creature == key && agent != role.value }
                        .keys.map { agentName(it) ?: another }.distinct().joinToString(", ")
                } else {
                    null
                }
                if (livingIn != null) elsewhere += name to livingIn
                PickerRow(
                    rig = stable.rigOf(key),
                    title = name,
                    caption = when {
                        left == null -> stringResource(Res.string.stable_starter_unlimited)
                        livingIn != null -> stringResource(Res.string.stable_in_agent, livingIn)
                        else -> stringResource(Res.string.stable_left, left)
                    },
                    selected = selected,
                    enabled = livingIn == null,
                    onClick = { host.assign(role, key) },
                )
            }
        }
        elsewhere.forEach { (name, agent) ->
            Text(stringResource(Res.string.stable_living_in, name, agent), style = CaptionType, color = azphaltMutedOnSurface())
        }
    }
}

@Composable
private fun PickerRow(rig: String, title: String, caption: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fill = if (selected) azphaltStrong() else stableRowFill()
    AzphaltSurface(fill) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else DIMMED)
                .heightIn(min = AzphaltMinTouchTarget)
                .clip(RoundedCornerShape(24.dp))
                .background(fill)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 14.dp),
        ) {
            CreatureBox(rig, 44)
            Text(title, style = NameType.copy(fontSize = 14.sp), color = azphaltOnSurface(), modifier = Modifier.weight(1f))
            Text(caption, style = CaptionType, color = azphaltOnSurface())
        }
    }
}

/** How far a creature living in another agent is dimmed in the picker. */
private const val DIMMED = 0.45f

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
