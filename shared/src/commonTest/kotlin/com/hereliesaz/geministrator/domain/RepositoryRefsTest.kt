package com.hereliesaz.geministrator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryRefsTest {
    @Test
    fun parsesGitHubUrl() {
        val repository = parseRepositoryRef(
            source = RepositorySource.GitHub,
            locator = "https://github.com/HereLiesAz/aive.git",
            defaultBranch = " main ",
        )

        assertEquals(RepositorySource.GitHub, repository.source)
        assertEquals("HereLiesAz", repository.owner)
        assertEquals("aive", repository.name)
        assertEquals("main", repository.defaultBranch)
        assertEquals("https://github.com/HereLiesAz/aive", repository.remoteUrl)
        assertEquals("https://github.com/HereLiesAz/aive", repository.remoteBrowserUrl())
        assertNull(repository.localPath)
    }

    @Test
    fun parsesNestedGitLabGroup() {
        val repository = parseRepositoryRef(
            source = RepositorySource.GitLab,
            locator = "git@gitlab.com:team/platform/aive.git",
            defaultBranch = "develop",
        )

        assertEquals(RepositorySource.GitLab, repository.source)
        assertEquals("team/platform", repository.owner)
        assertEquals("aive", repository.name)
        assertEquals("develop", repository.defaultBranch)
        assertEquals("https://gitlab.com/team/platform/aive", repository.remoteUrl)
        assertEquals("https://gitlab.com/team/platform/aive", repository.remoteBrowserUrl())
    }

    @Test
    fun selfManagedGitLabRemoteIsRejectedBeforeCredentialsCanBeRouted() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseRepositoryRef(
                source = RepositorySource.GitLab,
                locator = "ssh://git@gitlab.example.test:2222/team/platform/aive.git",
                defaultBranch = "main",
            )
        }
    }

    @Test
    fun linksLocalGitFolder() {
        val repository = parseRepositoryRef(
            source = RepositorySource.Local,
            locator = "C:\\work\\aive\\",
            defaultBranch = "main",
        )

        assertEquals(RepositorySource.Local, repository.source)
        assertEquals("", repository.owner)
        assertEquals("aive", repository.name)
        assertEquals("C:\\work\\aive\\", repository.localPath)
        assertEquals("main", repository.defaultBranch)
        assertNull(repository.remoteUrl)
    }

    @Test
    fun legacyRepositoryRefDefaultsToGitHub() {
        val repository = RepositoryRef("HereLiesAz", "aive", "main")

        assertEquals(RepositorySource.GitHub, repository.source)
        assertEquals("HereLiesAz/aive", repository.displayName())
        assertEquals("https://github.com/HereLiesAz/aive", repository.remoteBrowserUrl())
    }
}
