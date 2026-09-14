package com.hereliesaz.geministrator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryRefsTest {
    @Test
    fun parsesGitHubUrl() {
        val repository = parseRepositoryRef(
            source = RepositorySource.GitHub,
            locator = "https://github.com/HereLiesAz/haive.git",
            defaultBranch = " main ",
        )

        assertEquals(RepositorySource.GitHub, repository.source)
        assertEquals("HereLiesAz", repository.owner)
        assertEquals("haive", repository.name)
        assertEquals("main", repository.defaultBranch)
        assertEquals("https://github.com/HereLiesAz/haive.git", repository.remoteUrl)
        assertEquals("https://github.com/HereLiesAz/haive", repository.remoteBrowserUrl())
        assertNull(repository.localPath)
    }

    @Test
    fun parsesNestedGitLabGroup() {
        val repository = parseRepositoryRef(
            source = RepositorySource.GitLab,
            locator = "git@gitlab.com:team/platform/haive.git",
            defaultBranch = "develop",
        )

        assertEquals(RepositorySource.GitLab, repository.source)
        assertEquals("team/platform", repository.owner)
        assertEquals("haive", repository.name)
        assertEquals("develop", repository.defaultBranch)
        assertEquals("git@gitlab.com:team/platform/haive.git", repository.remoteUrl)
        assertEquals("https://gitlab.com/team/platform/haive", repository.remoteBrowserUrl())
    }

    @Test
    fun selfManagedGitLabSshRemoteRetainsHostForBrowserLink() {
        val repository = parseRepositoryRef(
            source = RepositorySource.GitLab,
            locator = "ssh://git@gitlab.example.test:2222/team/platform/haive.git",
            defaultBranch = "main",
        )

        assertEquals("team/platform", repository.owner)
        assertEquals("haive", repository.name)
        assertEquals("https://gitlab.example.test/team/platform/haive", repository.remoteBrowserUrl())
    }

    @Test
    fun linksLocalGitFolder() {
        val repository = parseRepositoryRef(
            source = RepositorySource.Local,
            locator = "C:\\work\\haive\\",
            defaultBranch = "main",
        )

        assertEquals(RepositorySource.Local, repository.source)
        assertEquals("", repository.owner)
        assertEquals("haive", repository.name)
        assertEquals("C:\\work\\haive\\", repository.localPath)
        assertEquals("main", repository.defaultBranch)
        assertNull(repository.remoteUrl)
    }

    @Test
    fun legacyRepositoryRefDefaultsToGitHub() {
        val repository = RepositoryRef("HereLiesAz", "haive", "main")

        assertEquals(RepositorySource.GitHub, repository.source)
        assertEquals("HereLiesAz/haive", repository.displayName())
        assertEquals("https://github.com/HereLiesAz/haive", repository.remoteBrowserUrl())
    }
}
