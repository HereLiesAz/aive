package com.hereliesaz.conveyance.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.compose.ElementRegistry
import com.hereliesaz.conveyance.compose.ConveyanceHost
import com.hereliesaz.conveyance.bacterium.Templates as BacteriumTemplates
import com.hereliesaz.conveyance.bacterium.ComposableRequest as BacteriumRequest
import com.hereliesaz.conveyance.expressive.Templates as ExpressiveTemplates
import com.hereliesaz.conveyance.expressive.ComposableRequest as ExpressiveRequest
import com.hereliesaz.conveyance.h2g2.Templates as H2g2Templates
import com.hereliesaz.conveyance.h2g2.ComposableRequest as H2g2Request
import com.hereliesaz.conveyance.liquid.Templates as LiquidTemplates
import com.hereliesaz.conveyance.liquid.ComposableRequest as LiquidRequest
import com.hereliesaz.conveyance.space.Templates as SpaceTemplates
import com.hereliesaz.conveyance.space.ComposableRequest as SpaceRequest

/**
 * Every registry entry from all five composable-set libraries, rendered live and wired to a real
 * (if minimal) [Act] each -- proof these actually link and run against this repo's own
 * conveyance-core/-compose, not just against each other's own isolated test suites.
 *
 * This is deliberately a second, separate screen from [Gallery] rather than chrome grafted onto
 * it: [Gallery]'s own docs are explicit that Conveyance can't be demonstrated in a world with no
 * stakes and no objects, and five unrelated visual styles borrowed as decoration for a photo-
 * sharing screen would be exactly that kind of object-less demonstration. Here the domain *is*
 * the composable sets themselves -- what's on screen is what's being conveyed.
 *
 * Each tile gets a plain [Act.reveal] rather than anything narratively meaningful, since revealing
 * more of what's already on this screen is the only consequence a template picker actually has.
 */
@Composable
fun StyleShowcase(
    modifier: Modifier = Modifier,
    /** Passed only by the audit harness, so this screen can be graded from outside, the same as [Gallery]. */
    registry: ElementRegistry? = null,
) {
    ConveyanceHost(modifier = modifier.background(Look.ground), registry = registry) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            section("h2g2") {
                H2g2Templates.registry.forEach { (key, render) ->
                    render(
                        H2g2Request(
                            act = revealAct(key),
                            hueSeed = key,
                            surface = "tile",
                            scale = "lead",
                            label = key.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                        ),
                    )
                }
            }
            section("expressive") {
                ExpressiveTemplates.registry.forEach { (key, render) ->
                    render(
                        ExpressiveRequest(
                            act = revealAct(key),
                            rank = listOf("primary", "secondary", "tertiary")[key.hashCode().mod(3)],
                            surface = "cookie9Sided",
                            scale = "titleSmall",
                            label = key.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                        ),
                    )
                }
            }
            section("liquid") {
                LiquidTemplates.registry.forEach { (key, render) ->
                    render(
                        LiquidRequest(
                            act = revealAct(key),
                            hue = "azure",
                            surface = "puddle",
                            scale = "body",
                            label = key.substringAfterLast('.'),
                        ),
                    )
                }
            }
            section("bacterium") {
                BacteriumTemplates.registry.forEach { (key, render) ->
                    render(
                        BacteriumRequest(
                            act = revealAct(key),
                            hue = "algal",
                            surface = "amoeba",
                            scale = "body",
                            label = key.substringAfterLast('.'),
                        ),
                    )
                }
            }
            section("space") {
                SpaceTemplates.registry.forEach { (key, render) ->
                    render(
                        SpaceRequest(
                            act = revealAct(key),
                            hue = "G",
                            rank = "primary",
                            scale = "body",
                            label = key.substringAfterLast('.'),
                        ),
                    )
                }
            }
        }
    }
}

private fun revealAct(templateKey: String): Act =
    Act.reveal(id = "showcase.$templateKey", target = ElementId("showcase.$templateKey"))

@Composable
private fun section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = Look.quiet, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
        }
    }
}
