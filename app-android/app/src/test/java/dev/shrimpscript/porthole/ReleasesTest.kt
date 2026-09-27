package dev.shrimpscript.porthole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** org.json is Android's, so this runs under Robolectric rather than on the bare JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleasesTest {
    private fun release(tag: String, vararg assets: Pair<String, String>, draft: Boolean = false, pre: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,
         "html_url":"https://github.com/ShrimpScript/porthole/releases/tag/$tag",
         "assets":[${assets.joinToString(",") { (name, url) -> """{"name":"$name","size":9000000,"browser_download_url":"$url"}""" }}]}
    """.trimIndent()

    private val apkUrl = "https://github.com/ShrimpScript/porthole/releases/download/v0.27.0/porthole-0.27.0.apk"

    @Test fun `the APK is found among the other assets`() {
        val r = Releases.parse(release("v0.27.0",
            "portholed-0.27.0-linux-amd64" to "https://github.com/ShrimpScript/porthole/releases/download/v0.27.0/portholed-0.27.0-linux-amd64",
            "porthole-0.27.0.apk" to apkUrl,
            "SHA256SUMS" to "https://github.com/ShrimpScript/porthole/releases/download/v0.27.0/SHA256SUMS"))
        assertEquals("0.27.0", r?.version)
        assertEquals(apkUrl, r?.apkUrl)
        assertEquals(9000000L, r?.apkSize)
    }

    @Test fun `the unversioned name the site links to is found too`() {
        val url = "https://github.com/ShrimpScript/porthole/releases/download/v0.27.0/porthole.apk"
        assertEquals(url, Releases.parse(release("v0.27.0", "porthole.apk" to url))?.apkUrl)
        assertNull(Releases.parse(release("v0.27.0", "porthole-debug.apk" to url)))
    }

    @Test fun `drafts, pre-releases, odd tags and releases without an APK offer nothing`() {
        assertNull(Releases.parse(release("v0.27.0", "porthole-0.27.0.apk" to apkUrl, draft = true)))
        assertNull(Releases.parse(release("v0.27.0", "porthole-0.27.0.apk" to apkUrl, pre = true)))
        assertNull(Releases.parse(release("nightly", "porthole-0.27.0.apk" to apkUrl)))
        assertNull(Releases.parse(release("v0.27.0", "portholed-0.27.0-linux-amd64" to apkUrl)))
        assertNull(Releases.parse("not json"))
        assertNull(Releases.parse("{}"))
    }

    @Test fun `a download is only ever GitHub over HTTPS`() {
        assertTrue(Releases.trusted(apkUrl))
        assertTrue(Releases.trusted("https://objects.githubusercontent.com/github-production-release-asset/1/2"))
        assertFalse(Releases.trusted("http://github.com/ShrimpScript/porthole/releases/download/v1/porthole-1.apk"))
        assertFalse(Releases.trusted("https://github.com.example.net/porthole-1.apk"))
        assertFalse(Releases.trusted("https://evilgithubusercontent.com/porthole-1.apk"))
        assertFalse(Releases.trusted("file:///sdcard/porthole-1.apk"))
        assertFalse(Releases.trusted("not a url"))
        // A release whose APK points elsewhere is not offered at all.
        assertNull(Releases.parse(release("v0.27.0", "porthole-0.27.0.apk" to "https://example.com/porthole-0.27.0.apk")))
    }

    @Test fun `a check is due daily, and after the clock goes backwards`() {
        val day = Releases.CHECK_EVERY_MS
        assertTrue(Releases.due(0L, day))
        assertFalse(Releases.due(1_000L, 1_000L + day - 1))
        assertTrue(Releases.due(1_000L, 1_000L + day))
        assertTrue(Releases.due(5 * day, day))
    }

    @Test fun `only a newer release is offered, as Porthole's own build`() {
        val r = Releases.Release("0.27.0", apkUrl, 9L, "")
        val offer = Releases.offer(r, "0.26.0")
        assertTrue(offer!!.isPorthole)
        assertTrue(offer.remote)
        assertEquals(apkUrl, offer.path)
        assertNull(Releases.offer(r, "0.27.0"))
        assertNull(Releases.offer(r, "0.28.1"))
        assertNull(Releases.offer(null, "0.26.0"))
    }

    @Test fun `the last answer survives a restart, and a bad one is not trusted back`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("releases-test", 0)
        val r = Releases.Release("0.27.0", apkUrl, 9L, "https://github.com/ShrimpScript/porthole/releases/tag/v0.27.0")
        Releases.remember(prefs, r, 42L)
        assertEquals(r, Releases.remembered(prefs))
        assertEquals(42L, Releases.checkedAt(prefs))
        Releases.remember(prefs, null, 43L)
        assertNull(Releases.remembered(prefs))
        prefs.edit().putString("release_found", "0.27.0\nhttp://example.com/x.apk\n9\n").apply()
        assertNull(Releases.remembered(prefs))
    }
}
