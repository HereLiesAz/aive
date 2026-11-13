package com.hereliesaz.geministrator

import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RuntimeScopeTest {
    @Test
    fun theRuntimeRunsOffTheCallersDispatcherWhenOneIsGiven() {
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scope = runtimeScopeOf(ui, SupervisorJob(), Dispatchers.Default)
        assertSame(Dispatchers.Default, scope.coroutineContext[ContinuationInterceptor])
    }

    @Test
    fun withoutOneItKeepsTheCallersDispatcherSoATestStaysDeterministic() {
        val test = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scope = runtimeScopeOf(test, SupervisorJob(), null)
        assertEquals(Dispatchers.Unconfined, scope.coroutineContext[ContinuationInterceptor])
    }
}
