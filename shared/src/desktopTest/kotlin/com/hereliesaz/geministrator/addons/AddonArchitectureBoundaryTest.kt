package com.hereliesaz.geministrator.addons

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.reflect.KClass

class AddonArchitectureBoundaryTest {

    private fun checkClassForMemory(kclass: Class<*>) {
        val nameHasMemory = kclass.simpleName?.contains("memory", ignoreCase = true) == true
        assertTrue(!nameHasMemory, "Type ${kclass.simpleName} must not expose memory.")

        for (prop in kclass.declaredFields) {
            val propHasMemory = prop.name.contains("memory", ignoreCase = true)
            assertTrue(!propHasMemory, "Property ${prop.name} in ${kclass.simpleName} must not expose memory.")
            val returnHasMemory = prop.type.toString().contains("memory", ignoreCase = true)
            assertTrue(!returnHasMemory, "Property ${prop.name} type in ${kclass.simpleName} must not expose memory.")
        }

        for (func in kclass.declaredMethods) {
            val funcHasMemory = func.name.contains("memory", ignoreCase = true)
            assertTrue(!funcHasMemory, "Method ${func.name} in ${kclass.simpleName} must not expose memory.")
            val returnHasMemory = func.returnType.toString().contains("memory", ignoreCase = true)
            assertTrue(!returnHasMemory, "Method ${func.name} return type in ${kclass.simpleName} must not expose memory.")
            for (param in func.parameters) {
                val paramHasMemory = param.name?.contains("memory", ignoreCase = true) == true
                assertTrue(!paramHasMemory, "Parameter ${param.name} in ${func.name} of ${kclass.simpleName} must not expose memory.")
                val paramTypeHasMemory = param.type.toString().contains("memory", ignoreCase = true)
                assertTrue(!paramTypeHasMemory, "Parameter ${param.name} type in ${func.name} of ${kclass.simpleName} must not expose memory.")
            }
        }
    }

    @Test
    fun testAddonApiDoesNotExposeMemory() {
        checkClassForMemory(HaiveAddonApi::class.java)
        checkClassForMemory(AddonAppApi::class.java)
        checkClassForMemory(AddonProjectApi::class.java)
        checkClassForMemory(AddonRepositoryApi::class.java)
        checkClassForMemory(AddonCompanyApi::class.java)
        checkClassForMemory(AddonWorkflowApi::class.java)
        checkClassForMemory(AddonRunApi::class.java)
        checkClassForMemory(AddonArtifactApi::class.java)
        checkClassForMemory(AddonApprovalApi::class.java)
        checkClassForMemory(AddonEventApi::class.java)
        checkClassForMemory(AddonProviderApi::class.java)
        checkClassForMemory(AddonExecutorApi::class.java)
        checkClassForMemory(AddonPackageApi::class.java)
        checkClassForMemory(AddonSettingsApi::class.java)
        checkClassForMemory(AddonUiApi::class.java)
        checkClassForMemory(HaiveAddonMediator::class.java)
        checkClassForMemory(AddonInstallation::class.java)
        checkClassForMemory(AzphaltPackageManifest::class.java)
    }
}
