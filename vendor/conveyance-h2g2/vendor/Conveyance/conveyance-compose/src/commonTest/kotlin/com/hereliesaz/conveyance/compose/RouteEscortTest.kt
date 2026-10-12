package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Gate
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Escort, once it knows the shape of the whole product rather than one gate.
 *
 * A prerequisite chain is the ordinary condition of real software — you cannot send until you have
 * chosen, and you cannot choose until you have signed in — and the usual answer to it is a sequence
 * of refusals, or a tutorial that narrates the sequence in advance. Both are instruction. This is
 * the third answer: the framework already knows the chain, because the acts describe it, so it
 * carries the person to the one thing that is possible right now and says nothing at all.
 */
@OptIn(ExperimentalTestApi::class)
class RouteEscortTest {

    private val avatar = ElementId("recipient.avatar")
    private val chooser = ElementId("recipient.chooser")
    private val account = ElementId("account")

    /**
     * Two hops. The thing you are missing is itself unavailable.
     *
     * The old behaviour — carry them to the first unmet gate — would deliver the person to a
     * chooser that refuses them in turn. Being moved and then refused is worse than being refused,
     * because now they have also lost their place.
     */
    @Test
    fun `an escort skips a blocked prerequisite for the one that can be done`() = runComposeUiTest {
        val registry = ElementRegistry()
        val signedIn = mutableStateOf(false)
        val chosen = mutableStateOf(false)

        val signIn = Act.alter("account.signIn", SubjectId("account"), "session", account) {
            signedIn.value = true
            com.hereliesaz.conveyance.Outcome.Done
        }
        val choose = Act.alter(
            id = "recipient.choose",
            subject = SubjectId("recipient"),
            property = "recipient",
            target = chooser,
            requires = listOf(Gate("signed in", livesAt = account) { signedIn.value }),
        )
        val send = Act.send(
            id = "invoice.send",
            subject = SubjectId("invoice.41"),
            to = avatar,
            requires = listOf(Gate("recipient chosen", livesAt = chooser) { chosen.value }),
        )

        var reaching: ActScope? = null
        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
            ) {
                Column {
                    Offer(send) { reaching = this; Box(Modifier.size(40.dp)) }
                    Offer(choose, element = chooser) { Box(Modifier.size(40.dp)) }
                    Offer(signIn, element = account) { Box(Modifier.size(40.dp)) }
                }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(reaching).engage() }
        waitForIdle()

        assertEquals(
            account,
            registry.articulating,
            "Reaching past two closed gates should arrive at the only open one, not at the nearer " +
                "gate that is equally shut.",
        )
    }

    /** One hop is the ordinary case, and it must not have moved. */
    @Test
    fun `a single unmet gate still escorts to that gate`() = runComposeUiTest {
        val registry = ElementRegistry()
        val chosen = mutableStateOf(false)
        val choose = Act.alter("recipient.choose", SubjectId("recipient"), "recipient", chooser)
        val send = Act.send(
            id = "invoice.send",
            subject = SubjectId("invoice.41"),
            to = avatar,
            requires = listOf(Gate("recipient chosen", livesAt = chooser) { chosen.value }),
        )

        var reaching: ActScope? = null
        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
            ) {
                Column {
                    Offer(send) { reaching = this; Box(Modifier.size(40.dp)) }
                    Offer(choose, element = chooser) { Box(Modifier.size(40.dp)) }
                }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(reaching).engage() }
        waitForIdle()

        assertEquals(chooser, registry.articulating)
    }

    /**
     * A gate with nothing behind it is a hole in the product, and the person is still carried to it.
     *
     * The framework does not invent a destination it cannot justify. Arriving at the place where
     * the missing thing ought to be is the honest answer, and the dead end is what the Conscience is
     * for.
     */
    @Test
    fun `a gate nothing offers still receives the person`() = runComposeUiTest {
        val registry = ElementRegistry()
        val send = Act.send(
            id = "invoice.send",
            subject = SubjectId("invoice.41"),
            to = avatar,
            requires = listOf(Gate("recipient chosen", livesAt = chooser) { false }),
        )

        var reaching: ActScope? = null
        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
            ) {
                Column {
                    Offer(send) { reaching = this; Box(Modifier.size(40.dp)) }
                    Box(Modifier.size(40.dp).element(chooser))
                }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(reaching).engage() }
        waitForIdle()

        assertEquals(chooser, registry.articulating)
    }
}
