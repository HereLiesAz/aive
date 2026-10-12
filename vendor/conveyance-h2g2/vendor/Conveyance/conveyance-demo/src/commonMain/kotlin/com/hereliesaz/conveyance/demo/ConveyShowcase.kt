package com.hereliesaz.conveyance.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.conveyance.ConveyGrammar
import compose.conveyance.ConveyLife
import compose.conveyance.ConveySystem
import compose.conveyance.ConveyWeight
import compose.conveyance.LocalConveyWeightRegistry
import compose.conveyance.conveyInert
import compose.conveyance.conveyLongPress
import compose.conveyance.conveyPress
import compose.conveyance.conveyRipple
import compose.conveyance.conveySwipe
import compose.conveyance.conveyWeight
import compose.conveyance.ConveyKineticSentence
import compose.conveyance.ConveyKineticText
import compose.conveyance.ConveySvoScene
import compose.conveyance.foundation.ConveyAttentionGrid
import compose.conveyance.foundation.ConveyConstruct
import compose.conveyance.foundation.ConveyConstructRegistry
import compose.conveyance.foundation.ConveyFab
import compose.conveyance.foundation.ConveyFabAction
import compose.conveyance.foundation.ConveyMorphControl
import compose.conveyance.foundation.ConveyOutcome
import compose.conveyance.foundation.ConveySubmitButton
import compose.conveyance.foundation.ConveyTopographicalLayout
import compose.conveyance.foundation.LocalConveyConstructRegistry
import compose.conveyance.tokens.ConveyColor
import compose.conveyance.tokens.ConveyShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A live demonstration of `conveyance-convey` -- a second, entirely separate library built on
 * this repo's own Manifesto, but as a standalone Compose Multiplatform design system rather than
 * an extension of [com.hereliesaz.conveyance.Act]/[com.hereliesaz.conveyance.Gate]. It has no
 * dependency on conveyance-core or conveyance-compose, so unlike [StyleShowcase] this screen wires
 * to Convey's own primitives directly: [ConveySystem] as the root, [ConveyWeight] for hierarchy,
 * [ConveySubmitButton]/[ConveyFab] for persistent-identity morphing, and the interaction
 * modifiers ([compose.conveyance.conveyPress] et al.) for touch feedback.
 *
 * The weight-hierarchy and construct audits below are not decorative text -- they read
 * [compose.conveyance.ConveyWeightRegistry.snapshot] and [ConveyConstructRegistry.audit] live off
 * the same registries the tiles above are actually registered into, proving the library's central
 * claim (that a screen can be understood from its own self-report) against this screen itself.
 */
@Composable
fun ConveyShowcase(modifier: Modifier = Modifier) {
    val constructRegistry = remember { ConveyConstructRegistry() }

    ConveySystem(grammar = ConveyGrammar.Default, maxPrimaryWeight = 2) {
        CompositionLocalProvider(LocalConveyConstructRegistry provides constructRegistry) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(Look.ground)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                WeightSection()
                AuditSection(constructRegistry)
                MorphSection()
                FabSection()
                InteractionSection()
                KineticTextSection()
                SvoSceneSection()
                TopographicalSection()
                AttentionGridSection()
                MorphControlSection()
            }
        }
    }
}

@Composable
private fun WeightSection() {
    section("weight hierarchy") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WeightTile("Checkout", ConveyWeight.Hero, ConveyOutcome.Navigate("checkout/payment"))
            WeightTile("Save", ConveyWeight.Primary, ConveyOutcome.StateChange("draft saved"))
            WeightTile("Share", ConveyWeight.Secondary, ConveyOutcome.Unspecified)
            WeightTile("Divider", ConveyWeight.Ghost, ConveyOutcome.Inert("decorative rule"))
        }
    }
}

@Composable
private fun WeightTile(label: String, weight: ConveyWeight, produces: ConveyOutcome) {
    val shape = when (weight) {
        ConveyWeight.Hero -> ConveyShape.Large
        ConveyWeight.Primary -> ConveyShape.Medium
        ConveyWeight.Secondary -> ConveyShape.Small
        ConveyWeight.Ghost -> ConveyShape.None
    }
    ConveyConstruct(purpose = "$label ($weight)", weight = weight, produces = produces) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .conveyWeight(weight)
                .clip(shape)
                .background(ConveyColor.containerFor(weight))
                .let { if (weight == ConveyWeight.Ghost) it.conveyInert("decorative") else it.conveyPress() },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = ConveyColor.contentFor(weight), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AuditSection(constructRegistry: ConveyConstructRegistry) {
    val weightRegistry = LocalConveyWeightRegistry.current
    section("self-report") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = weightRegistry.snapshot(), color = Look.quiet, fontSize = 11.sp)
            Text(text = constructRegistry.audit(), color = Look.quiet, fontSize = 11.sp)
        }
    }
}

private enum class SubmitDemoState { Idle, Loading, Success }

@Composable
private fun MorphSection() {
    var state by remember { mutableStateOf(SubmitDemoState.Idle) }
    val scope = rememberCoroutineScope()

    section("morph") {
        ConveySubmitButton(
            onClick = {
                scope.launch {
                    state = SubmitDemoState.Loading
                    delay(1000)
                    state = SubmitDemoState.Success
                    delay(900)
                    state = SubmitDemoState.Idle
                }
            },
            isLoading = state == SubmitDemoState.Loading,
            isSuccess = state == SubmitDemoState.Success,
            idleColor = ConveyColor.Primary,
            idleContent = { Text("Submit", color = ConveyColor.OnPrimary, fontSize = 13.sp) },
            loadingContent = { Text("…", color = ConveyColor.OnPrimary, fontSize = 13.sp) },
            successContent = { Text("✓", color = ConveyColor.OnPrimary, fontSize = 16.sp) },
        )
    }
}

@Composable
private fun FabSection() {
    var expanded by remember { mutableStateOf(false) }
    section("fab") {
        ConveyFab(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            collapsedIcon = { Text("+", color = ConveyColor.OnPrimary, fontSize = 18.sp) },
            actions = listOf(
                ConveyFabAction("New photo") {},
                ConveyFabAction("New style") {},
            ),
        )
    }
}

@Composable
private fun InteractionSection() {
    var swipedAway by remember { mutableStateOf(false) }
    section("interaction") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(ConveyShape.Medium)
                    .background(ConveyColor.SecondaryContainer)
                    .conveyRipple(),
                contentAlignment = Alignment.Center,
            ) {
                Text("ripple", color = ConveyColor.OnSecondaryContainer, fontSize = 11.sp)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(ConveyShape.Medium)
                    .background(ConveyColor.SecondaryContainer)
                    .conveyLongPress { },
                contentAlignment = Alignment.Center,
            ) {
                Text("hold", color = ConveyColor.OnSecondaryContainer, fontSize = 11.sp)
            }
            if (!swipedAway) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(ConveyShape.Medium)
                        .background(ConveyColor.SecondaryContainer)
                        .conveySwipe { swipedAway = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("swipe", color = ConveyColor.OnSecondaryContainer, fontSize = 11.sp)
                }
            } else {
                Text("swiped away", color = Look.quiet, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun KineticTextSection() {
    var struck by remember { mutableStateOf(0) }
    section("kinetic text") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConveyKineticText(
                text = "CONVEY",
                idle = ConveyLife.Wobble(period = 4500L),
                triggerKey = struck,
                onClick = { struck++ },
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 22.sp),
            )
            // Each word's idle motion is chosen by its own verb -- classified live against real
            // WordNet/VerbNet data, not styled by hand. "fell" resolves to a still, settled
            // profile (no per-glyph idle fits a spatial-path verb); the sentence as a whole
            // gets a real one-shot slide-in for exactly that reason (see ConveyKineticSentence).
            ConveyKineticSentence(
                text = "The leaves fell to the ground",
                style = TextStyle(color = Look.quiet, fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun SvoSceneSection() {
    section("SVO scene") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // ConveyNounLexicon (WordNet-backed animacy/countability) + ConveyVerbLexicon's own
            // event-timeline extension drive a pure-Kotlin force simulation: "cheetah" is animate
            // (real hypernym-chain-to-animal.n.01 data) so it gets the gait-wobble approximation
            // while it closes on "gazelle"; "hunt" resolves to a contact-terminating timeline, so
            // the object gets a squash-and-stretch impulse on collision. No word-as-image morphing
            // (that needs a live diffusion model this demo doesn't have) -- only position, scale,
            // and rotation are driven by the simulation; see ConveySvoScene's own doc comment.
            ConveySvoScene(
                sentence = "The cheetah hunts the gazelle",
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 20.sp),
            )
            // A mass noun ("clay", real WordNet noun.substance data) keeps visibly wobbling after
            // contact instead of settling instantly -- the underdamped spring [ConveySvoScene]
            // gives count nouns doesn't fire. "Pokes" -- unlike an earlier draft's "squishes",
            // which Simplified Lesk resolves to WordNet's unrelated "walk through mud" sense here,
            // producing no contact at all -- reliably classifies Contact/contactAtEnd, so this
            // sentence actually reaches the collision branch it's meant to demonstrate.
            ConveySvoScene(
                sentence = "The boy pokes the clay",
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 20.sp),
            )
        }
    }
}

@Composable
private fun TopographicalSection() {
    section("topographical layout") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Each sentence's own verb picks its own static shape -- ConveyVerbLexicon
            // resolves the category, ConveyTopographicalLayout renders it. A sentence with no
            // matching verb (not shown here) would render as ordinary flowing text instead.
            ConveyTopographicalLayout(
                text = "The leaves fell to the ground",
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 13.sp),
            )
            ConveyTopographicalLayout(
                text = "The balloon rose into the sky",
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 13.sp),
            )
            ConveyTopographicalLayout(
                text = "The wolves surrounded the camp",
                style = TextStyle(color = ConveyColor.OnSurface, fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun AttentionGridSection() {
    val tiles = remember { listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta") }
    section("attention grid") {
        ConveyAttentionGrid(
            items = tiles,
            columns = 3,
            modifier = Modifier.size(width = 280.dp, height = 200.dp),
            content = { tile, isAttended, expand ->
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .conveyWeight(if (isAttended) ConveyWeight.Primary else ConveyWeight.Secondary)
                        .clip(ConveyShape.Medium)
                        .background(if (isAttended) ConveyColor.PrimaryContainer else ConveyColor.SurfaceContainer)
                        .conveyPress(onClick = expand),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tile, color = ConveyColor.OnSurface, fontSize = 12.sp)
                }
            },
            expandedContent = { tile, collapse ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .conveyPress(onClick = collapse),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tile, color = ConveyColor.OnSurface, fontSize = 32.sp)
                }
            },
        )
    }
}

@Composable
private fun MorphControlSection() {
    var expanded by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(50f) }
    section("morph control") {
        ConveyMorphControl(
            expanded = expanded,
            onToggle = { expanded = true },
            onCollapse = { expanded = false },
            value = volume,
            onValueChange = { volume = it },
            color = ConveyColor.Primary,
            contentColor = ConveyColor.OnPrimary,
            collapsedLabel = { Text("Set volume", color = ConveyColor.OnPrimary, fontSize = 12.sp) },
            valueBadge = { v -> Text("${v.toInt()}%", color = ConveyColor.OnPrimary, fontSize = 12.sp) },
        )
    }
}

@Composable
private fun section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = Look.quiet, fontSize = 13.sp)
        content()
    }
}
