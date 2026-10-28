package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private fun version(text: String) = AppVersion.parse(text)!!

    @Test fun parsesReleaseTagsAndInstalledVersionNames() {
        assertEquals("0.5.11", version("v0.5.11").toString())
        assertEquals("0.5.11", version("0.5.11").toString())
        assertEquals("0.5.11", version("0.5.11-test").toString())
    }
    @Test fun rejectsTagsThatAreNotVersions() {
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse("v1.x"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("0.5.11-rc1"))
    }
    @Test fun comparesNumericallyNotAsText() {
        assertTrue(version("0.5.10") > version("0.5.9"))
        assertTrue(version("0.6.0") > version("0.5.99"))
        assertTrue(version("1.0") > version("0.9.9"))
    }
    @Test fun missingTrailingPartsCountAsZero() {
        assertEquals(0, version("0.5").compareTo(version("0.5.0")))
        assertTrue(version("0.5.1") > version("0.5"))
    }

    private val feed = """[
        {"tag_name":"v0.5.11","name":"Zf 传图 0.5.11","prerelease":true,"assets":[{"name":"ZfTransfer-0.5.11.apk"}]},
        {"tag_name":"v0.5.10","name":"Zf 传图 0.5.10","prerelease":true},
        {"tag_name":"nightly","prerelease":true},
        {"tag_name":"v0.5.7","prerelease":true}
    ]"""

    @Test fun newestVersionIsTheHighestNotTheFirstListed() {
        val shuffled = """[{"tag_name":"v0.5.7"},{"tag_name":"v0.5.11"},{"tag_name":"v0.5.9"}]"""
        assertEquals("0.5.11", ReleaseFeed.newest(shuffled).toString())
    }
    @Test fun preReleasesCountAsNewVersions() {
        assertEquals(UpdateResult.Available(version("0.5.11")), ReleaseFeed.compare(version("0.5.10"), feed))
    }
    @Test fun sameOrNewerInstalledVersionIsUpToDate() {
        assertEquals(UpdateResult.UpToDate, ReleaseFeed.compare(version("0.5.11"), feed))
        assertEquals(UpdateResult.UpToDate, ReleaseFeed.compare(version("0.6.0-test"), feed))
    }
    @Test fun aFeedWithoutAnyVersionTagFails() {
        assertTrue(ReleaseFeed.compare(version("0.5.11"), "[]") is UpdateResult.Failed)
        assertTrue(ReleaseFeed.compare(version("0.5.11"), """[{"tag_name":"nightly"}]""") is UpdateResult.Failed)
        assertTrue(ReleaseFeed.compare(version("0.5.11"), "not json") is UpdateResult.Failed)
    }
    @Test fun downloadPageLinksToTheTaggedRelease() {
        assertEquals("https://github.com/zhuuchaofan/ZfTransfer/releases/tag/v0.5.12", ReleaseFeed.pageFor(version("0.5.12")))
    }
}
