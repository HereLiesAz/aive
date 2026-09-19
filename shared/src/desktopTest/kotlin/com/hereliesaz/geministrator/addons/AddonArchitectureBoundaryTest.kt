package com.hereliesaz.geministrator.addons

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertTrue

class AddonArchitectureBoundaryTest {
    private val visited = mutableSetOf<Class<*>>()

    private fun checkTypeForMemory(type: Type, context: String) {
        assertTrue(
            !type.typeName.contains("memory", ignoreCase = true),
            "$context must not expose memory through type ${type.typeName}.",
        )

        when (type) {
            is Class<*> -> {
                if (type.isArray) {
                    checkTypeForMemory(type.componentType, "$context array component")
                }
                if (
                    type.name.startsWith("com.hereliesaz.geministrator.addons.") &&
                    visited.add(type)
                ) {
                    checkClassForMemory(type)
                }
            }
            is ParameterizedType -> {
                checkTypeForMemory(type.rawType, "$context raw type")
                type.actualTypeArguments.forEachIndexed { index, argument ->
                    checkTypeForMemory(argument, "$context generic argument $index")
                }
            }
            is GenericArrayType ->
                checkTypeForMemory(type.genericComponentType, "$context array component")
            is WildcardType -> {
                type.upperBounds.forEach { checkTypeForMemory(it, "$context upper bound") }
                type.lowerBounds.forEach { checkTypeForMemory(it, "$context lower bound") }
            }
        }
    }

    private fun checkClassForMemory(kclass: Class<*>) {
        assertTrue(
            !kclass.name.contains("memory", ignoreCase = true),
            "Type ${kclass.name} must not expose memory.",
        )

        kclass.declaredFields.forEach { field ->
            assertTrue(
                !field.name.contains("memory", ignoreCase = true),
                "Property ${field.name} in ${kclass.simpleName} must not expose memory.",
            )
            checkTypeForMemory(field.genericType, "Property ${field.name} in ${kclass.simpleName}")
        }

        kclass.declaredMethods.forEach { method ->
            assertTrue(
                !method.name.contains("memory", ignoreCase = true),
                "Method ${method.name} in ${kclass.simpleName} must not expose memory.",
            )
            checkTypeForMemory(method.genericReturnType, "Return type of ${method.name} in ${kclass.simpleName}")
            method.genericParameterTypes.forEachIndexed { index, type ->
                checkTypeForMemory(type, "Parameter $index of ${method.name} in ${kclass.simpleName}")
            }
        }
    }

    @Test
    fun addonApiObjectGraphDoesNotExposeMemory() {
        visited.clear()
        checkClassForMemory(HaiveAddonApi::class.java)
        checkClassForMemory(HaiveAddonMediator::class.java)
        checkClassForMemory(AddonInstallation::class.java)
        checkClassForMemory(AzphaltPackageManifest::class.java)
    }
}
