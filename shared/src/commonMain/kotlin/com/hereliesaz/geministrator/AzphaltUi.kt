package com.hereliesaz.geministrator

import com.russhwolf.settings.Settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.azrienoch_vf
import org.jetbrains.compose.resources.Font
import com.hereliesaz.geministrator.resources.tab_position
import org.jetbrains.compose.resources.stringResource

/**
 * Marks text that changes while the screen is open (progress, status, errors) so screen readers
 * announce the new text without moving focus. Descendants are merged so a note or record reads as
 * one message.
 */
internal fun Modifier.azphaltLiveRegion(): Modifier =
    semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

internal object Azphalt {
    val Ink = Color(0xFF1E1A17)
    val Yellow = Color(0xFFF0D42A)
    val White = Color(0xFFFFFFFF)

    val hues = listOf(
        Color(0xFF6B4FBB), Color(0xFF2FA9C4), Color(0xFF1F9E86), Color(0xFF5AAE34),
        Color(0xFFD9A21C), Color(0xFFD9762A), Color(0xFFC6392F), Color(0xFFB03A6E),
        Color(0xFF8E4FA8), Color(0xFF2E6FB7), Color(0xFF8A9296), Color(0xFF4E7D6E),
        Color(0xFFC9A45C), Color(0xFF8C6E4E),
    )

    val caps = listOf(
        Color(0xFF4B3489), Color(0xFF1F7B90), Color(0xFF137060), Color(0xFF3E7D22),
        Color(0xFFA2760F), Color(0xFFA6551A), Color(0xFF93251D), Color(0xFF7F2A4D),
        Color(0xFF653578), Color(0xFF1E4E85), Color(0xFF616A6E), Color(0xFF365A4E),
        Color(0xFF96762F), Color(0xFF634B31),
    )

    data class Ground(
        val name: String,
        val page: Color,
        val foldLight: Color,
        val foldDark: Color,
        val weight: Int = 1,
    )

    val grounds = listOf(
        Ground("Mustard", Color(0xFFE8C81E), Color(0xFFF2D82C), Color(0xFFD9B615), weight = 6),
        Ground("Maroon", Color(0xFF8F1F34), Color(0xFFA22940), Color(0xFF7A1A2C)),
        Ground("Navy", Color(0xFF163A63), Color(0xFF204B7C), Color(0xFF0F2C4C)),
        Ground("Cerulean", Color(0xFF2D6EA8), Color(0xFF3C82C2), Color(0xFF215A8C)),
        Ground("Teal", Color(0xFF1D6B62), Color(0xFF267F74), Color(0xFF14554E)),
        Ground("Pink", Color(0xFFD4728F), Color(0xFFDD879F), Color(0xFFC15D7A)),
    )

    private val settings = Settings()
    private const val GROUND_STORAGE_KEY = "aive.appearance.ground.v1"

    var currentGround: Ground by mutableStateOf(
        groundNamed(settings.getStringOrNull(GROUND_STORAGE_KEY)) ?: grounds.first(),
    )
        private set

    /** The ground called [name] (as saved, for example "Teal"), or null when there is none. */
    fun groundNamed(name: String?): Ground? = grounds.firstOrNull { it.name == name }

    /**
     * Makes the ground called [name] current and remembers it on this device. An unknown name
     * changes nothing and returns false.
     */
    fun setGround(name: String): Boolean {
        val ground = groundNamed(name) ?: return false
        currentGround = ground
        settings.putString(GROUND_STORAGE_KEY, ground.name)
        return true
    }

    /** Re-reads the saved ground, after something other than [setGround] wrote it (a setup import). */
    fun reloadGround() {
        groundNamed(settings.getStringOrNull(GROUND_STORAGE_KEY))?.let { currentGround = it }
    }

    fun hueIndex(seed: String): Int = seed.hashCode().mod(hues.size)
    fun hue(seed: String): Color = hues[hueIndex(seed)]
    fun cap(seed: String): Color = caps[hueIndex(seed)]
}

/**
 * Accent for text and glyphs drawn directly on the page ground. Content is always the exact
 * opposite of its surface ([oppositeOf]); Yellow is only kept where it clears 3:1 on the page, so
 * it is never used as a foreground on Mustard (1.11:1).
 */
internal val Azphalt.Ground.accent: Color
    get() = if (contrastRatio(Azphalt.Yellow, page) >= AZPHALT_MIN_LARGE_CONTRAST) Azphalt.Yellow else onPage

/** The exact opposite of the ground's page colour: what text on the bare page is drawn in. */
internal val Azphalt.Ground.onPage: Color
    get() = page.onOpposite()

/** The app-wide Material scheme: every role derived from the current ground's page colour. */
internal val GeministratorColors
    get() = azphaltColorScheme(Azphalt.currentGround.page)

/** Spacing scale in dp. Prefer these over new literals in the screens that touch them. */
internal object AzphaltSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** Shape tokens shared by Azphalt surfaces. */
internal object AzphaltShapes {
    val dialogCorner: Dp = 28.dp
}

/** Smallest touch target (dp) for anything clickable. */
internal val AzphaltMinTouchTarget: Dp = 48.dp

/**
 * The app's two typefaces, bundled under composeResources/font (licences in THIRD_PARTY_NOTICES.md):
 * Archivo Black for titles, DM Sans for everything else. They start as the platform sans-serif and
 * become the bundled faces once [LoadAzphaltFonts] has run at the root of a window; [AzphaltType]
 * reads them on every access, so text recomposes when they arrive.
 */
internal object AzphaltFonts {
    var display: FontFamily by mutableStateOf(FontFamily.SansSerif)
        private set
    var text: FontFamily by mutableStateOf(FontFamily.SansSerif)
        private set

    internal fun use(display: FontFamily, text: FontFamily) {
        if (this.display != display) this.display = display
        if (this.text != text) this.text = text
    }
}

@Composable
private fun azrienoch(weight: FontWeight): androidx.compose.ui.text.font.Font =
    Font(Res.font.azrienoch_vf, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Loads the bundled faces into [AzphaltFonts]. Call once near the root of each window. */
@Composable
internal fun LoadAzphaltFonts() {
    // Azrienoch is one variable font (wght 100-900; width, serif and grade stay at their defaults), so each
    // weight the type scale names is an instance of the same file rather than a separate face.
    val display = FontFamily(azrienoch(FontWeight.Black))
    val text = FontFamily(
        azrienoch(FontWeight.Normal),
        azrienoch(FontWeight.Medium),
        azrienoch(FontWeight.SemiBold),
        azrienoch(FontWeight.Bold),
        azrienoch(FontWeight.ExtraBold),
        azrienoch(FontWeight.Black),
    )
    SideEffect { AzphaltFonts.use(display, text) }
}

internal object AzphaltType {
    /** A page's big title (Azrienoch Black). */
    val hero get() = TextStyle(
        fontFamily = AzphaltFonts.display,
        fontWeight = FontWeight.Black,
        fontSize = 46.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.045).em,
    )
    val section get() = TextStyle(
        fontFamily = AzphaltFonts.display,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.035).em,
    )
    val lead get() = TextStyle(
        fontFamily = AzphaltFonts.text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    )
    val body get() = TextStyle(
        fontFamily = AzphaltFonts.text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )
    val capsule get() = TextStyle(
        fontFamily = AzphaltFonts.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.09.em,
    )
    val eyebrow get() = TextStyle(
        fontFamily = AzphaltFonts.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.22.em,
    )
    val endCap get() = TextStyle(
        fontFamily = AzphaltFonts.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.10.em,
    )
}

/**
 * What a pill is for, which is all its colour says (docs/BRANDING.md, Controls). Hues belong to
 * living things (creatures, hives); controls are calm.
 */
internal enum class PillTone {
    /** Most controls: filled with the pill's own hue, from its seed. */
    Quiet,
    /** The one main action in view: filled. */
    Primary,
    /** Removes, deletes, rejects or stops something: red. */
    Danger,
}

/** The filled colour for a selected or primary control on the current surface: Ink, or Yellow on dark. */
@Composable
internal fun azphaltStrong(): Color = if (azphaltOnSurface() == Color.White) Azphalt.Yellow else Azphalt.Ink

internal val AzphaltDanger = Color(0xFFC6392F)

@Composable
internal fun AzphaltPill(
    label: String,
    seed: String,
    selected: Boolean = false,
    endCap: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    stateDescription: String? = null,
    tone: PillTone = PillTone.Quiet,
    /** False while the action cannot be taken (a decision is already running): the pill is dimmed, announced as disabled and does nothing. */
    enabled: Boolean = true,
    /** What a screen reader says in place of the label (and end cap), when the label alone does not say what it acts on. */
    description: String? = null,
) {
    // Every pill wears its own colour: a hue from its seed, with an end cap in the same family.
    // Selected sits on Ink (Yellow when its surface is Ink, so it never vanishes); the one main
    // action fills with the surface's strong colour; danger fills red.
    val onInk = azphaltSurface() == Azphalt.Ink
    val fill = when {
        selected -> if (onInk) Azphalt.Yellow else Azphalt.Ink
        tone == PillTone.Danger -> AzphaltDanger
        tone == PillTone.Primary -> azphaltStrong()
        else -> Azphalt.hue(seed)
    }
    val content = fill.onOpposite()
    val cap = when {
        selected -> if (onInk) Azphalt.Ink else Azphalt.Yellow
        tone == PillTone.Quiet -> Azphalt.cap(seed)
        else -> content
    }
    val pillRole = role
    Row(
        modifier = modifier
            .semantics {
                this.role = pillRole
                this.selected = selected
                contentDescription = description ?: if (endCap != null) "$label: $endCap" else label
                if (stateDescription != null) this.stateDescription = stateDescription
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else 0.5f)
            .azphaltSelectedTransform(selected)
            .defaultMinSize(minHeight = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, top = 9.dp, bottom = 9.dp, end = if (endCap == null) 16.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule, color = content)
        if (endCap != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(cap)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(endCap.uppercase(), style = AzphaltType.endCap, color = cap.onOpposite())
            }
        }
    }
}

/** One tab of an [AzphaltTabs] row. [key] must be unique within the row. */
internal data class AzphaltTabItem(
    val key: String,
    val label: String,
    val endCap: String? = null,
)

/**
 * A single row of tabs built from [AzphaltPill], so tabs keep the pill colours, type and 48dp
 * targets. Each tab reports `Role.Tab`, its selected state and its position ("Tab 2 of 5") to
 * screen readers, and the row is one selectable group that scrolls sideways when it overflows.
 */
@Composable
internal fun AzphaltTabs(
    tabs: List<AzphaltTabItem>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    seedPrefix: String,
    modifier: Modifier = Modifier,
    /** A control that rides at the row's start (a way back, say) and is not itself a tab. */
    leading: (@Composable () -> Unit)? = null,
    /** Scrolls the selected tab into view when it changes, so a row that overflows never hides where you are. */
    keepSelectedInView: Boolean = false,
    /** Wraps onto more lines instead of scrolling sideways, so no tab is ever off the edge. */
    wrap: Boolean = false,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(0) }
    // Each tab's span along the row, so a selection that overflows can be scrolled into view.
    val spans = remember { mutableStateMapOf<String, IntRange>() }
    if (keepSelectedInView) {
        val span = spans[selectedKey]
        LaunchedEffect(selectedKey, span, viewport) {
            span ?: return@LaunchedEffect
            val edge = 16
            when {
                span.last + edge > scroll.value + viewport -> scroll.animateScrollTo(span.last + edge - viewport)
                span.first - edge < scroll.value -> scroll.animateScrollTo((span.first - edge).coerceAtLeast(0))
            }
        }
    }
    val tabContent: @Composable () -> Unit = {
        leading?.invoke()
        tabs.forEachIndexed { index, tab ->
            AzphaltPill(
                label = tab.label,
                seed = "$seedPrefix-${tab.key}",
                selected = tab.key == selectedKey,
                endCap = tab.endCap,
                onClick = { onSelected(tab.key) },
                modifier = if (keepSelectedInView && !wrap) {
                    Modifier.onPlaced { spans[tab.key] = it.positionInParent().x.toInt().let { x -> x..(x + it.size.width) } }
                } else {
                    Modifier
                },
                role = Role.Tab,
                stateDescription = stringResource(Res.string.tab_position, index + 1, tabs.size),
            )
        }
    }
    if (wrap) {
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
            verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
        ) { tabContent() }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { viewport = it.width }
                .horizontalScroll(scroll)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
        ) { tabContent() }
    }
}

@Composable
internal fun AzphaltRecord(
    seed: String,
    eyebrow: String,
    title: String,
    body: String? = null,
    endCap: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    well: (@Composable () -> Unit)? = null,
    /** A styled body (an agent's Markdown, read), shown instead of [body]. */
    richBody: androidx.compose.ui.text.AnnotatedString? = null,
) {
    // A card, not a creature: Ink (lifted Ink on a dark surface), and Yellow when selected.
    val hue = recordFill(selected)
    val content = hue.onOpposite()
    // Selected Yellow can sit on a yellow ground: outline it in the ground's content colour.
    val outline = azphaltOnSurface()
    val radius = animatedRecordRadius(selected)
    val description = buildString {
        append(title)
        if (eyebrow.isNotBlank()) append(", $eyebrow")
        endCap?.takeIf(String::isNotBlank)?.let { append(", $it") }
    }
    val semanticsMod = if (onClick != null) {
        modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            this.selected = selected
            contentDescription = description
        }
    } else {
        modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        }
    }
    val interactive = if (onClick == null) {
        semanticsMod
    } else {
        semanticsMod.defaultMinSize(minHeight = AzphaltMinTouchTarget).clickable(onClick = onClick)
    }
    Column(
        modifier = interactive
            .azphaltSelectedTransform(selected)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(hue)
            .then(if (selected) Modifier.border(2.dp, outline, RoundedCornerShape(radius)) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AzphaltSurface(hue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = content)
                if (endCap != null) {
                    val cap = content
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(cap).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(endCap.uppercase(), style = AzphaltType.endCap, color = cap.onOpposite())
                    }
                }
            }
        }
        // Body text is the full opposite of the record, not a faded copy of it: a translucent
        // tint of the content colour would drop below 4.5:1 on the mid-tone hues.
        Text(title, style = AzphaltType.lead, color = content)
        if (richBody != null) {
            Text(richBody, style = AzphaltType.body, color = content)
        } else if (body != null) {
            Text(body, style = AzphaltType.body, color = content)
        }
        AzphaltChildBand(visible = well != null) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(wellFill(hue)).padding(14.dp),
            ) {
                // The well is its own dark surface: everything in it takes the opposite of the
                // well, never the page ground's text colour (dark-on-dark on Mustard and Pink).
                AzphaltThemedSurface(wellFill(hue)) { well?.invoke() }
            }
        }
    }
}

/** Ink one step lighter: a card on an Ink surface, and the well inside an Ink card. */
internal val AzphaltLiftedInk = Color(0xFF2E2723)

@Composable
private fun recordFill(selected: Boolean): Color {
    val surface = azphaltSurface()
    return when {
        selected -> Azphalt.Yellow
        surface == Azphalt.Ink || surface == AzphaltLiftedInk -> AzphaltLiftedInk
        else -> Azphalt.Ink
    }
}

/** The dark panel a record's [AzphaltRecord] `well` sits on: set apart from the card around it. */
private fun wellFill(card: Color): Color = if (card == Azphalt.Ink) AzphaltLiftedInk else Azphalt.Ink

@Composable
internal fun AzphaltNote(
    seed: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** Set, the note is a link: it takes the keeper to where what it says can be acted on. */
    onClick: (() -> Unit)? = null,
) {
    val hue = recordFill(selected = false)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(hue)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = AzphaltType.eyebrow, color = hue.onOpposite())
        Text(value, style = AzphaltType.body, color = hue.onOpposite())
    }
}
