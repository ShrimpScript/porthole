package dev.shrimpscript.porthole

import android.content.SharedPreferences
import dev.shrimpscript.porthole.net.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Porthole's own new versions, read from the project's GitHub releases.
 *
 * Builds published on the computer are for the apps being worked on there; Porthole
 * itself updates from the public release, so every install gets the same signed APK
 * and nobody's phone depends on what happens to be in someone's build folder.
 *
 * The check is one unauthenticated GET to api.github.com, at most once a day and when
 * asked from Settings. It sends nothing about the person, the phone or the computer
 * beyond what any web request carries, and it can be turned off. A build made for a
 * store has it compiled out: store policy is that an app updates through the store.
 */
object Releases {
    /** What a GitHub release offers: its version and where its APK is. */
    data class Release(val version: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

    const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

    private const val KEY_ON = "check_updates"
    private const val KEY_AT = "release_checked_at"
    private const val KEY_FOUND = "release_found"

    /** "porthole.apk" (what the site links to) or a versioned "porthole-1.2.3.apk". */
    private val APK = Regex("""porthole(-\d+(\.\d+)*)?\.apk""")
    private val VERSION = Regex("""\d+(\.\d+){1,3}""")

    /**
     * GitHub's "latest release" answer, or null when it offers nothing installable: a
     * draft, a pre-release, a tag that is not a version, or no APK attached.
     */
    fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").trim().removePrefix("v")
        if (!VERSION.matches(version)) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            if (APK.matches(a.optString("name")) && trusted(url)) {
                return Release(version, url, a.optLong("size"), o.optString("html_url"))
            }
        }
        null
    }.getOrNull()

    /**
     * Only GitHub over HTTPS. Android refuses an update signed with a different key
     * whatever the source, so this is not the integrity check - it keeps a malformed or
     * hostile answer from pointing the download anywhere else.
     */
    fun trusted(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    fun enabled(prefs: SharedPreferences): Boolean =
        BuildConfig.SELF_UPDATE && prefs.getBoolean(KEY_ON, true)

    fun setEnabled(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
    }

    fun checkedAt(prefs: SharedPreferences): Long = prefs.getLong(KEY_AT, 0L)

    /** A clock moved backwards counts as due, so a wrong date never stops the checks. */
    fun due(lastCheck: Long, now: Long): Boolean = lastCheck > now || now - lastCheck >= CHECK_EVERY_MS

    /** The newest release seen by the last check, kept so the banner survives a restart offline. */
    fun remembered(prefs: SharedPreferences): Release? {
        val parts = prefs.getString(KEY_FOUND, null)?.split('\n') ?: return null
        if (parts.size != 4) return null
        return Release(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L, parts[3]).takeIf { trusted(it.apkUrl) }
    }

    fun remember(prefs: SharedPreferences, r: Release?, at: Long) {
        val edit = prefs.edit().putLong(KEY_AT, at)
        if (r == null) edit.remove(KEY_FOUND)
        else edit.putString(KEY_FOUND, listOf(r.version, r.apkUrl, r.apkSize.toString(), r.pageUrl).joinToString("\n"))
        edit.apply()
    }

    /** The release as the banner's build, when it is newer than what is installed. */
    fun offer(r: Release?, installed: String): BuildInfo? =
        r?.takeIf { Updater.isNewer(it.version, installed) }?.let {
            BuildInfo(app = "porthole", version = it.version, path = it.apkUrl, size = it.apkSize)
        }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    /** Asks GitHub for the latest release. No release at all is null, not an error. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BuildConfig.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Porthole/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { res ->
            when {
                res.code == 404 -> null
                !res.isSuccessful -> error("GitHub answered ${res.code}")
                else -> parse(res.body?.string().orEmpty())
            }
        }
    }
}
