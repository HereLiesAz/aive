package com.hereliesaz.conveyance

/**
 * Meanings the reference binding knows how to talk about.
 *
 * A visual channel does not arrive with one eternal meaning attached to it. Meaning is learned from
 * repeated use inside a product. The values below are reference semantics, not a constitution that
 * every product must obey. A product may use hue for identity, shape for state, scale for emphasis,
 * or choose another coherent grammar entirely; what matters is that repeated use teaches rather
 * than contradicts itself.
 */
enum class Meaning {
    OriginAndRelation,
    MomentaryImportance,
    State,
    VisualIdentity,
    Heat,
    Reversibility,
    TransitionOnly,
    ReadingOrder,
    Relatedness,
    MotionGrammar,
    ConsequenceMagnitude,
    ExpressiveEmphasis,
}

/**
 * The reference channel assignment used by the core examples and audits.
 *
 * This is deliberately not a universal style law. It is one coherent mapping a binding can use,
 * override, or replace with another declared product grammar. In particular, hue is available for
 * stable visual identity; Conveyance does not require monochrome hierarchy or semantic rank colours.
 * Critical state and safety information must remain legible without colour alone.
 */
enum class Channel(val carries: Meaning) {
    Position(Meaning.OriginAndRelation),
    Size(Meaning.MomentaryImportance),
    Shape(Meaning.State),
    Hue(Meaning.VisualIdentity),
    Chroma(Meaning.Heat),
    Elevation(Meaning.Reversibility),
    Opacity(Meaning.TransitionOnly),
    TypeScale(Meaning.ReadingOrder),
    Density(Meaning.Relatedness),
    Motion(Meaning.MotionGrammar),
    Haptics(Meaning.ConsequenceMagnitude),
    Sound(Meaning.ExpressiveEmphasis);

    companion object {
        fun carrying(meaning: Meaning): Channel = entries.first { it.carries == meaning }
    }
}

/**
 * The reference haptic vocabulary. Products may extend it when another distinction is genuinely
 * learnable on the hardware in question; semantic consistency matters more than an arbitrary count.
 */
enum class HapticVoice {
    Commit,
    ModeChange;

    fun intensity(weight: Weight): Weight = weight
}
