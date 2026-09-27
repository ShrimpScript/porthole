package dev.shrimpscript.porthole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReporterTest {
    @Test
    fun writesTheCrashAndHandsItOnce() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CrashReporter.clear(app)
        assertNull(CrashReporter.pending(app))

        // The handler must not be the last word: whatever was there still runs, so the
        // process still dies and Android still records it.
        var delegated = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
        CrashReporter.install(app)
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("the daemon said something odd"))

        val report = CrashReporter.pending(app)
        assertTrue("nothing was written", report != null)
        assertTrue("the version is missing: $report", report!!.contains("Porthole "))
        assertTrue("the device is missing", report.contains("Android "))
        assertTrue("the cause is missing", report.contains("IllegalStateException"))
        assertTrue("the message is missing", report.contains("the daemon said something odd"))
        assertTrue("the previous handler was skipped", delegated)

        CrashReporter.clear(app)
        assertNull("a cleared report must not come back", CrashReporter.pending(app))
    }
}
