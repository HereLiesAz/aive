package com.hereliesaz.aive

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidCliAgentProviderTest {
    @Test
    fun aGitHubOrGitLabRepositoryIsClonedByItsAddress() {
        assertEquals("https://github.com/acme/app.git", cloneAddress(RepositoryRef("acme", "app", source = RepositorySource.GitHub)))
        assertEquals("https://gitlab.com/acme/app.git", cloneAddress(RepositoryRef("acme", "app", source = RepositorySource.GitLab)))
        assertEquals(
            "https://git.example.com/acme/app.git",
            cloneAddress(RepositoryRef("acme", "app", source = RepositorySource.GitHub, remoteUrl = "https://git.example.com/acme/app.git")),
        )
    }

    @Test
    fun aRepositoryThatExistsOnlyOnThePhoneIsNotReachableByTheRuntime() {
        assertNull(cloneAddress(RepositoryRef("me", "notes", source = RepositorySource.Local, localPath = "/data/user/0/x/files/notes")))
    }

    @Test
    fun anAddressWithAPasswordOrShellSyntaxIsRefused() {
        assertNull(cloneAddress(RepositoryRef("a", "b", source = RepositorySource.GitHub, remoteUrl = "https://user:secret@github.com/a/b.git")))
        assertNull(cloneAddress(RepositoryRef("a", "b", source = RepositorySource.GitHub, remoteUrl = "https://github.com/a/b.git; touch x")))
        assertNull(cloneAddress(RepositoryRef("a;id", "b", source = RepositorySource.GitHub)))
    }

    @Test
    fun aLabelCannotForgeAHeaderInThePrompt() {
        assertEquals("a b c", safePhoneLabel("a[b]c"))
        assertEquals("unlabelled", safePhoneLabel("   "))
    }
}
