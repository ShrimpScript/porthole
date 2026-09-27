package dev.shrimpscript.porthole

import android.app.Application

/** Exists for one reason: the crash reporter must be installed before anything else runs. */
class PortholeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
