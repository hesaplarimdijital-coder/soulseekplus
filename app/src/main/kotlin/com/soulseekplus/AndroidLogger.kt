package com.soulseekplus

import android.util.Log
import com.soulseekplus.core.api.Logger

/**
 * Çekirdek günlüğünü Logcat'e yönlendirir. Hata ayıklama günlükleri debug derlemede, sürüm derlemesinde ise yalnızca
 * `adb shell setprop log.tag.SlskPlus DEBUG` ile açıldığında yazılır (sorun giderme; varsayılan kapalı).
 */
object AndroidLogger : Logger {
    private const val PREFIX = "Slsk+/"
    private const val SWITCH_TAG = "SlskPlus"

    private val debugEnabled: Boolean
        get() = BuildConfig.DEBUG || Log.isLoggable(SWITCH_TAG, Log.DEBUG)

    override fun debug(tag: String, message: String) {
        if (debugEnabled) Log.d(PREFIX + tag, message)
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
