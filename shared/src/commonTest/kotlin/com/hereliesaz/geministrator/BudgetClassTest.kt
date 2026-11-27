package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BudgetClass
import com.hereliesaz.geministrator.domain.BudgetClassCodec
import com.hereliesaz.geministrator.domain.BudgetClassSettings
import com.hereliesaz.geministrator.domain.BudgetClassesInUse
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleStartingTemplates
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Budget classes: the defaults, how a role's class becomes a number, and how the keeper's numbers are kept. */
class BudgetClassTest {
    @AfterTest
    fun reset() {
        BudgetClassesInUse.current = BudgetClassSettings.Defaults
    }

    private val writer = RoleStartingTemplates.roles.first { it.id.value == "writer" }

    @Test
    fun theDefaultsGrowWithTheClass() {
        val d = BudgetClassSettings.Defaults
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, d.tokensFor(BudgetClass.Small))
        assertEquals(BudgetClassSettings.DEFAULT_MEDIUM, d.tokensFor(BudgetClass.Medium))
        assertEquals(BudgetClassSettings.DEFAULT_LARGE, d.tokensFor(BudgetClass.Large))
        assertTrue(d.small < d.medium && d.medium < d.large)
    }

    @Test
    fun aRoleNamesAClassAndGetsTheKeepersNumberForIt() {
        assertEquals(BudgetClassSettings.DEFAULT_MEDIUM, writer.effectiveTokenBudget())
        val mine = BudgetClassSettings.Defaults.with(BudgetClass.Medium, 123_000L)
        assertEquals(123_000L, writer.effectiveTokenBudget(mine))
        BudgetClassesInUse.current = mine
        assertEquals(123_000L, writer.effectiveTokenBudget())
    }

    @Test
    fun aNumberOnTheRoleAlwaysWinsOverItsClass() {
        val own = writer.copy(tokenBudget = 5_000L)
        assertEquals(5_000L, own.effectiveTokenBudget(BudgetClassSettings(1L, 2L, 3L)))
    }

    @Test
    fun geneticAllowanceIsStableAndExplicitOverrideWins() {
        val role = writer.copy(geneticTokenCap = 250L, tokenBudget = null)
        assertEquals(250L, role.effectiveTokenBudget(BudgetClassSettings(100L, 200L, 300L)))
        assertEquals(250L, role.effectiveTokenBudget(BudgetClassSettings(100L, 400L, 500L)))
        assertEquals(900L, role.copy(tokenBudget = 900L).effectiveTokenBudget())
        assertEquals(250L, role.copy(tokenBudget = 900L).copy(tokenBudget = null).effectiveTokenBudget())
    }

    @Test
    fun theBuiltInMirrorsNameTheirClassesByIdEvenWhenStoredWithoutOne() {
        assertEquals(BudgetClassSettings.DEFAULT_LARGE, BuiltInRoles.Antagonist.effectiveTokenBudget())
        assertEquals(BudgetClassSettings.DEFAULT_MEDIUM, BuiltInRoles.Planner.effectiveTokenBudget())
        assertEquals(BudgetClassSettings.DEFAULT_LARGE, BuiltInRoles.ImplementationEngineer.effectiveTokenBudget())
    }

    @Test
    fun aRoleWithNeitherAClassNorANumberHasNoBudget() {
        val custom = RoleDefinition(RoleDefinitionId("my-own-role"), "Mine", "d", "i")
        assertNull(custom.effectiveTokenBudget())
        assertEquals(10L, custom.copy(budgetClass = BudgetClass.Small).effectiveTokenBudget(BudgetClassSettings(10L, 20L, 30L)))
    }

    @Test
    fun settingAClassKeepsTheOthersAndANonPositiveNumberRestoresTheDefault() {
        val s = BudgetClassSettings.Defaults.with(BudgetClass.Small, 1_000L)
        assertEquals(1_000L, s.small)
        assertEquals(BudgetClassSettings.DEFAULT_MEDIUM, s.medium)
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, s.with(BudgetClass.Small, 0L).small)
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, s.with(BudgetClass.Small, -5L).small)
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, s.with(BudgetClass.Small, null).small)
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, s.with(BudgetClass.Small, BudgetClassSettings.MAX_TOKENS + 1).small)
    }

    @Test
    fun theCodecRoundTrips() {
        val s = BudgetClassSettings(small = 11L, medium = 22L, large = 33L)
        assertEquals(s, BudgetClassCodec.decode(BudgetClassCodec.encode(s)))
        assertEquals(BudgetClassSettings.Defaults, BudgetClassCodec.decode(BudgetClassCodec.encode(BudgetClassSettings.Defaults)))
    }

    @Test
    fun theCodecReadsBadOrMissingTextAsDefaultsAndNeverThrows() {
        assertEquals(BudgetClassSettings.Defaults, BudgetClassCodec.decode(null))
        assertEquals(BudgetClassSettings.Defaults, BudgetClassCodec.decode(""))
        assertEquals(BudgetClassSettings.Defaults, BudgetClassCodec.decode("not json {"))
        assertEquals(BudgetClassSettings.Defaults, BudgetClassCodec.decode("[1,2,3]"))
        val partial = BudgetClassCodec.decode("""{"medium": 77}""")
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, partial.small)
        assertEquals(77L, partial.medium)
        val bad = BudgetClassCodec.decode("""{"small": 0, "medium": -4, "large": 9, "extra": true}""")
        assertEquals(BudgetClassSettings.DEFAULT_SMALL, bad.small)
        assertEquals(BudgetClassSettings.DEFAULT_MEDIUM, bad.medium)
        assertEquals(9L, bad.large)
    }

    @Test
    fun theStoreKeepsTheKeepersNumbersAndPublishesThem() {
        val settings = MapSettings()
        val store = BudgetClassStore(settings)
        assertEquals(BudgetClassSettings.Defaults, store.classes)
        store.set(BudgetClass.Large, 4_000_000L)
        assertEquals(4_000_000L, BudgetClassesInUse.current.large)
        // A fresh store on the same settings reads it back, and publishes it as it is made.
        BudgetClassesInUse.current = BudgetClassSettings.Defaults
        val again = BudgetClassStore(settings)
        assertEquals(4_000_000L, again.classes.large)
        assertEquals(4_000_000L, BudgetClassesInUse.current.large)
        assertEquals(4_000_000L, BuiltInRoles.Antagonist.effectiveTokenBudget())
        // Putting every class back to its default leaves nothing stored.
        again.set(BudgetClass.Large, null)
        assertNull(settings.getStringOrNull(BudgetClassStore.KEY))
        assertEquals(BudgetClassSettings.Defaults, BudgetClassStore(settings).classes)
    }

    @Test
    fun whatTheKeeperTypesIsReadPlainly() {
        assertEquals(BudgetClassInput.Default, parseBudgetClassInput("  "))
        assertEquals(BudgetClassInput.Tokens(600_000L), parseBudgetClassInput("600000"))
        assertEquals(BudgetClassInput.Tokens(600_000L), parseBudgetClassInput(" 600,000 "))
        assertEquals(BudgetClassInput.Invalid, parseBudgetClassInput("lots"))
        assertEquals(BudgetClassInput.Invalid, parseBudgetClassInput("0"))
        assertEquals(BudgetClassInput.Invalid, parseBudgetClassInput("-3"))
        assertEquals(BudgetClassInput.Invalid, parseBudgetClassInput("1.5"))
    }
}
