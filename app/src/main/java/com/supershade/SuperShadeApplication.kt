package com.supershade

import android.app.Application
import com.supershade.di.appModule
import com.supershade.overlay.CustomStatusBarOverlay
import com.supershade.shizuku.StatusBarGovernor
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass

class SuperShadeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.setHiddenApiExemptions("")
        startKoin {
            androidContext(this@SuperShadeApplication)
            modules(appModule)
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                getKoin().getOrNull<CustomStatusBarOverlay>()?.detach()
            } catch (_: Throwable) {}
            try {
                getKoin().getOrNull<StatusBarGovernor>()?.restoreSystemStatusBarBlocking()
            } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
