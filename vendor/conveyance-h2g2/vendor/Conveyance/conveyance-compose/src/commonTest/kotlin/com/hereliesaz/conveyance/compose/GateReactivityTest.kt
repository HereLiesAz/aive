package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ActState
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Gate
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A gate whose condition is backed by something Compose cannot see change.
 *
 * `Gate.condition` is `() -> Boolean` on purpose -- a product should be free to answer "is this
 * satisfied" from wherever the truth actually lives, and plenty of real truth lives in a plain
 * `var` or a `StateFlow.value` rather than a `mutableStateOf`. Reading such a thing directly in a
 * composable's body does not subscribe to anything; the world can change and the element never
 * hears about it. This is the case `rememberLive` exists for.
 */
@OptIn(ExperimentalTestApi::class)
class GateReactivityTest {

    private val avatar = ElementId("recipient.avatar")
    private val field = ElementId("recipient.field")

    /** The world's actual truth, held the way a lot of real applications hold it: a plain field. */
    private class PlainFlag {
        @Volatile var open: Boolean = false
    }

    @Test
    fun `a gate backed by a plain var still unblocks, once polled`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val flag = PlainFlag()
        val gate = Gate("recipient.chosen", livesAt = field) { flag.open }
        val send = Act.send("invoice.send", SubjectId("invoice.41"), avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides ElementRegistry(), LocalPractice provides Practice()) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()
        assertIs<ActState.Blocked>(requireNotNull(scope).state, "The world has not said yes yet.")

        // Nothing Compose can see changes here -- there is no snapshot write, no recomposition
        // trigger, nothing but a plain field being set the way ordinary application code sets one.
        flag.open = true

        // The poll interval has to actually elapse; this is the whole point of the test.
        mainClock.advanceTimeBy(REACTIVITY_POLL_MS_FOR_TEST)
        waitForIdle()

        assertIs<ActState.Ready>(requireNotNull(scope).state, "A plain var changing should still be seen.")
    }

    /** The ordinary case must not have regressed: a mutableStateOf still unblocks immediately. */
    @Test
    fun `a gate backed by snapshot state still unblocks without waiting for a poll`() = runComposeUiTest {
        val chosen = androidx.compose.runtime.mutableStateOf(false)
        val gate = Gate("recipient.chosen", livesAt = field) { chosen.value }
        val send = Act.send("invoice.send", SubjectId("invoice.41"), avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides ElementRegistry(), LocalPractice provides Practice()) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()
        chosen.value = true
        waitForIdle()

        assertIs<ActState.Ready>(requireNotNull(scope).state)
    }
}

/** One poll interval plus margin, named for the test rather than exposed by the framework. */
private const val REACTIVITY_POLL_MS_FOR_TEST = REACTIVITY_POLL_MS * 2 + 100L
