package com.soulseekplus

import android.app.Application
import android.content.Context

/** Uygulama: kompozisyon kökünü bir kez kurar ve motoru başlatır. */
class SoulseekApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    companion object {
        /** Herhangi bir Context'ten kapsayıcıya erişim (Activity, Service, Composable içinde LocalContext). */
        fun container(context: Context): AppContainer = (context.applicationContext as SoulseekApp).container
    }
}
