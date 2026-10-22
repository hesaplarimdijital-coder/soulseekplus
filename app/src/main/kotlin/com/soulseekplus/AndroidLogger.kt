package com.soulseekplus

import android.util.Log
import com.soulseekplus.core.api.Logger

/** Çekirdek günlüğünü Logcat'e yönlendirir. Hata ayıklama günlükleri yalnızca debug derlemede yazılır. */
object AndroidLogger : Logger {
    private const val PREFIX = "Slsk+/"

    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w(PREFIX + tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(PREFIX + tag, message, error)
    }
}
