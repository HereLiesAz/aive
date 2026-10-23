package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidUpdateUiTest {
    @Test
    fun semanticVersionComparisonOnlyAcceptsNewerVersions() {
        assertTrue(isNewerVersion("0.9.6", "0.9.5"))
        assertTrue(isNewerVersion("0.10.0", "0.9.99"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertFalse(isNewerVersion("0.9.5", "0.9.5"))
        assertFalse(isNewerVersion("0.9.4", "0.9.5"))
        assertFalse(isNewerVersion("v0.9.5", "0.9.5"))
    }

    @Test
    fun onlyAPlayInstallerCountsAsAPlayInstall() {
        assertEquals(InstallSource.Play, installSourceOf("com.android.vending"))
        assertEquals(InstallSource.Other, installSourceOf(null))
        assertEquals(InstallSource.Other, installSourceOf("com.android.packageinstaller"))
        assertEquals(InstallSource.Other, installSourceOf("com.amazon.venezia"))
    }

    @Test
    fun releaseFeedPicksTheNewestNonDraftReleaseWithAnAndroidApk() {
        val feed = """
            [
              {"draft": true, "html_url": "https://github.com/HereLiesAz/aive/releases/tag/0.9.9.1",
               "assets": [{"name": "TheAive-0.9.9.1-android.apk"}]},
              {"draft": false, "html_url": "https://github.com/HereLiesAz/aive/releases/tag/0.9.6.2180",
               "assets": [{"name": "TheAive-0.9.6.2180.deb"}, {"name": "TheAive-0.9.6.2180-android.apk"}]},
              {"draft": false, "html_url": "https://github.com/HereLiesAz/aive/releases/tag/0.9.6.2171",
               "assets": [{"name": "TheAive-0.9.6.2171-android.apk"}]},
              {"draft": false, "html_url": "https://github.com/HereLiesAz/aive/releases/tag/0.9.6.2190",
               "assets": [{"name": "TheAive-0.9.6.2190.msi"}]}
            ]
        """.trimIndent()
        val release = GithubReleaseFeed.parseLatest(feed)
        assertEquals("0.9.6.2180", release?.version)
        assertEquals("https://github.com/HereLiesAz/aive/releases/tag/0.9.6.2180", release?.pageUrl)
        assertNull(GithubReleaseFeed.parseLatest("[]"))
    }

    @Test
    fun aReleasePageOutsideGithubFallsBackToTheReleasesList() {
        val feed = """
            [{"draft": false, "html_url": "https://evil.example/x", "assets": [{"name": "TheAive-1.0.0.0-android.apk"}]}]
        """.trimIndent()
        assertEquals(GithubReleaseFeed.RELEASES_PAGE_URL, GithubReleaseFeed.parseLatest(feed)?.pageUrl)
    }

    @Test
    fun theNewestApkInOneReleaseWinsNotTheFirstListed() {
        // The rolling release holds one APK per build, oldest first in the API's asset order.
        val assets = listOf("0.9.6.1725", "0.9.6.2200", "0.9.6.1999")
            .joinToString(",") { """{"name": "TheAive-$it-android.apk"}""" }
        val feed = """
            [{"draft": false, "html_url": "https://github.com/HereLiesAz/aive/releases/tag/0.9.6", "assets": [$assets]}]
        """.trimIndent()
        assertEquals("0.9.6.2200", GithubReleaseFeed.parseLatest(feed)?.version)
    }

    @Test
    fun aFinalReleaseIsNewerThanItsPrereleaseNeverTheOtherWayRound() {
        assertTrue(isNewerVersion("1.0.0", "1.0.0-rc1"))
        assertFalse(isNewerVersion("1.0.0-rc1", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0-rc2", "1.0.0-rc1"))
        assertTrue(isNewerVersion("1.0.1-rc1", "1.0.0"))
        assertTrue(isNewerVersion("0.9.6.10", "0.9.6.9"))
        assertFalse(isNewerVersion("0.9.6.2147483648", "0.9.6.2147483649"))
    }

    @Test
    fun eachBuildOnlyUpdatesFromItsOwnChannel() {
        assertEquals(UpdateChannel.Play, updateChannel(isPlayBuild = true, source = InstallSource.Play))
        // A Play build installed any other way has no update source; the GitHub APK is a different package.
        assertEquals(UpdateChannel.None, updateChannel(isPlayBuild = true, source = InstallSource.Other))
        assertEquals(UpdateChannel.Github, updateChannel(isPlayBuild = false, source = InstallSource.Other))
        assertEquals(UpdateChannel.Github, updateChannel(isPlayBuild = false, source = InstallSource.Play))
    }
}
