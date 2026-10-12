package com.hereliesaz.geministrator.persistence

/** The web build is a preview for 1.0 and keeps Settings (localStorage) persistence. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence =
    SettingsWorkflowPersistence.createDefault()
