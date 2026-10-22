package com.soulseekplus.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.soulseekplus.SoulseekApp

/**
 * Tek etkinlik. Yeniden oluşturma durumu ViewModel'lerde ve kabuk kaydetmelerinde korunur; DeX'te pencere
 * boyutlanınca etkinlik zaten yeniden oluşturulmaz (manifest configChanges). Kısayollar `dispatchKeyEvent` içinde,
 * odak nerede olursa olsun eşleşir.
 */
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    /** Kompozisyonun sağladığı kısayol köprüsü; kompozisyon yokken null. */
    private var shortcutHost: ShortcutHost? = null

    /**
     * Kısayol olarak tüketilen son tuşun kodu: eşleşme yalnızca KeyDown'da yapılır; aynı tuşun KeyUp'ı odaktaki
     * düğmeye ulaşırsa (Space/Enter tıklamayı KeyUp'ta tetikler) eylem iki kez çalışırdı.
     */
    private var consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Reddedilse de uygulama bildirimsiz çalışır; sonuç kullanılmaz.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) appViewModel.handleIntent(intent)
        if (savedInstanceState == null && SoulseekApp.container(this).settings.clientSettings.value.isConfigured) {
            requestNotificationPermission()
        }
        setContent {
            SoulseekRoot(
                appViewModel = appViewModel,
                onHostChanged = { shortcutHost = it },
                onAccountSaved = ::requestNotificationPermission,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Android 12+ arka plandan ön plan servisi başlatmayı yasaklar: yalnızca görünür etkinlikten başlatılır.
        SoulseekApp.container(this).ensureServiceRunning()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedKeyCode) {
            consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        val host = shortcutHost
        if (host != null && event.action == KeyEvent.ACTION_DOWN) {
            val shortcut = AppShortcuts.registry.matchNative(event, host.activeScopes())
            if (shortcut != null) {
                // Tuş basılı tutulunca tekrar eden olaylar tüketilir ama yeniden çalıştırılmaz.
                if (event.repeatCount > 0) return true
                if (host.perform(shortcut.id)) {
                    consumedKeyCode = event.keyCode
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Meta+/ (ya da sistemin kısayol ekranı) aynı tabloyu listeler. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(AppShortcuts.registry.keyboardShortcutGroups(resources))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
