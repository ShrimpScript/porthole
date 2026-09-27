package dev.shrimpscript.porthole

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.shrimpscript.porthole.net.PortholeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Getting an APK onto the phone and handing it to Android's installer: a build of a
 * project, from the computer over the tailnet (the same gate as everything else), or a
 * Porthole release from GitHub (see [Releases]).
 */
object Updater {
    /** True when [latest] is a higher dotted version than [current]. */
    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /**
     * Android installs by versionCode, so when the computer could read the APK's code
     * that decides; otherwise the dotted name does. A build with the same code as this
     * one would be refused by the installer, so it is not offered.
     */
    fun isNewerBuild(b: dev.shrimpscript.porthole.net.BuildInfo, currentName: String, currentCode: Int): Boolean =
        if (b.versionCode > 0) b.versionCode > currentCode else isNewer(b.version, currentName)

    fun compare(a: String, b: String): Int {
        val x = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    fun url(host: String, path: String): String =
        "http://" + PortholeClient.hostPort(PortholeClient.normalizeHost(host)) + path

    /** Android asks once per app before it may hand APKs to the installer. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallSetting(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /**
     * For release downloads: a redirect may move hosts within GitHub, never down to plain
     * HTTP and never off GitHub, because every hop passes [GitHubOnly].
     */
    internal val https: OkHttpClient by lazy {
        http.newBuilder().followSslRedirects(false).addNetworkInterceptor(GitHubOnly()).build()
    }

    /**
     * A network interceptor, so it sees every hop of a redirect chain, not just the first
     * request. A hop that fails [trusted] is refused before anything is sent, and a redirect
     * pointing at one is refused before it is followed, so the next host is never contacted.
     */
    internal class GitHubOnly(private val trusted: (String) -> Boolean = Releases::trusted) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!trusted(url.toString())) throw IOException("refused a download from ${url.host}: not GitHub")
            val res = chain.proceed(chain.request())
            val next = if (res.isRedirect) res.header("Location")?.let { url.resolve(it) } else null
            if (next != null && !trusted(next.toString())) {
                res.close()
                throw IOException("refused a redirect to ${next.host}: not GitHub")
            }
            return res
        }
    }

    /** Where a build downloads from: a release carries its own HTTPS URL, a project build a path on the computer. */
    fun source(host: String, b: dev.shrimpscript.porthole.net.BuildInfo): String =
        if (b.remote) b.path else url(host, b.path)

    /** Streams the APK to [dest]; [onProgress] gets bytes so far and the total (or -1). */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val remote = url.startsWith("https://")
        if (remote && !Releases.trusted(url)) error("not a GitHub download")
        val res = (if (remote) https else http).newCall(Request.Builder().url(url).build()).execute()
        res.use { r ->
            if (!r.isSuccessful) error((if (remote) "GitHub" else "the computer") + " answered ${r.code}")
            val body = r.body ?: error("empty download")
            val total = body.contentLength()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) error("could not keep the download")
        }
        dest
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
