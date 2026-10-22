package com.soulseekplus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.soulseekplus.AndroidLogger
import com.soulseekplus.R
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Ön plan servisi (`dataSync`): ekran kapalıyken de bağlantı ve transferlerin sürmesini sağlar.
 *
 * - Bildirim (kanal "service", düşük önem): bağlantı durumu, aktif transfer sayısı/hızı ve toplam indirme ilerlemesi.
 *   Eylemler: Bağlantıyı kes / Bağlan ve Aç.
 * - [WifiManager.WifiLock]: bağlıyken (ve transfer sürerken) Wi-Fi güç tasarrufuna girip soketleri kesmesin.
 * - Kısmi [PowerManager.WakeLock]: yalnızca aktif transfer sürerken; zaman aşımlıdır, transfer bitince bırakılır.
 * - Varsayılan ağ geri çağrısı: ağ değişince `client.onNetworkChanged(isMetered)`.
 * - Kullanıcı bağlantıyı kesmiş ve aktif transfer kalmamışsa kısa bir bekleyişten sonra kendini durdurur.
 *
 * Tüm durum ana iş parçacığında (lifecycleScope) yürür; motor çağrıları iş parçacığı güvenlidir ve hemen döner.
 */
class SoulseekService : LifecycleService() {

    private enum class Kind { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED }

    /** Bildirimi ve kilitleri belirleyen, karşılaştırılabilir durum özeti. */
    private data class Snapshot(
        val kind: Kind,
        val username: String?,
        val attempt: Int,
        val activeDownloads: Int,
        val activeUploads: Int,
        val waitingDownloads: Int,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        /** Bitmemiş indirmelerin toplam ilerlemesi (0..100); boyut bilinmiyorsa -1. */
        val progressPercent: Int,
    ) {
        val anyActive: Boolean get() = activeDownloads + activeUploads > 0
    }

    private lateinit var client: SoulseekClient
    private lateinit var notificationManager: NotificationManager

    private var monitorStarted = false
    private var lastPosted: Snapshot? = null
    private var latest: Snapshot? = null
    private var stopJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val openAppIntent: PendingIntent by lazy { buildOpenAppIntent() }
    private val disconnectIntent: PendingIntent by lazy { buildActionIntent(ACTION_DISCONNECT, 1) }
    private val connectIntent: PendingIntent by lazy { buildActionIntent(ACTION_CONNECT, 2) }

    // ---------------------------------------------------------------------------------------- yaşam döngüsü

    override fun onCreate() {
        super.onCreate()
        client = SoulseekApp.container(this).client
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // startForegroundService() çağrısı birkaç saniye içinde startForeground ile yanıtlanmalıdır; her komutta yenilenir.
        if (!enterForeground(buildNotification(snapshot()))) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_DISCONNECT -> client.disconnect()
            ACTION_CONNECT -> client.connect()
        }
        if (!monitorStarted) {
            monitorStarted = true
            registerNetworkCallback()
            lifecycleScope.launch { monitor() }
            lifecycleScope.launch { renewLocksPeriodically() }
        }
        return START_STICKY
    }

    /** Android 15: `dataSync` ön plan servisinin süresi (6 saat) dolunca çağrılır; birkaç saniye içinde durmak zorundadır. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AndroidLogger.warn(TAG, "Foreground service time limit reached (type=$fgsType); stopping until the app is opened again")
        stopSelf()
    }

    override fun onDestroy() {
        stopJob?.cancel()
        unregisterNetworkCallback()
        releaseWakeLock()
        releaseWifiLock()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "stopForeground failed", e)
        }
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // Android 12+ arka plandan başlatma yasağı (ForegroundServiceStartNotAllowedException), eksik izin vb.
        AndroidLogger.warn(TAG, "Could not enter foreground", e)
        false
    }

    // ---------------------------------------------------------------------------------------- izleme döngüsü

    /**
     * Bağlantı/transfer akışlarını birleştirir; bildirimi en çok saniyede bir yeniler (conflate + gecikme),
     * kilitleri ve durma kararını her özette değerlendirir.
     */
    private suspend fun monitor() {
        combine(client.connection, client.transferStats, client.transfers) { _, _, _ -> Unit }
            .conflate()
            .collect {
                val snap = snapshot()
                latest = snap
                applyLocks(snap)
                evaluateStop(snap)
                if (snap != lastPosted) {
                    lastPosted = snap
                    postNotification(snap)
                }
                delay(MIN_UPDATE_INTERVAL_MILLIS)
            }
    }

    /** Akışlar sessizken de wake lock zaman aşımı yenilensin diye dakikada bir kilitleri yeniden değerlendirir. */
    private suspend fun renewLocksPeriodically() {
        while (true) {
            delay(LOCK_RENEW_CHECK_MILLIS)
            latest?.let { applyLocks(it) }
        }
    }

    private fun snapshot(): Snapshot {
        val connection = client.connection.value
        val stats = client.transferStats.value
        var activeDown = 0
        var activeUp = 0
        var waiting = 0
        var pendingBytes = 0L
        var pendingSize = 0L
        for (t in client.transfers.value) {
            if (t.direction == TransferDirection.UPLOAD) {
                if (t.state.isActive) activeUp++
            } else {
                when (t.state) {
                    TransferState.CONNECTING, TransferState.TRANSFERRING -> activeDown++
                    TransferState.QUEUED, TransferState.QUEUED_REMOTE -> waiting++
                    else -> Unit
                }
                if (t.size > 0 && (t.state.isActive || t.state == TransferState.QUEUED || t.state == TransferState.QUEUED_REMOTE)) {
                    pendingSize += t.size
                    pendingBytes += t.bytesTransferred.coerceIn(0L, t.size)
                }
            }
        }
        val progress = if (pendingSize > 0) ((pendingBytes * 100) / pendingSize).toInt().coerceIn(0, 100) else -1
        val kind: Kind
        var username: String? = null
        var attempt = 0
        when (connection) {
            is ConnectionState.LoggedIn -> {
                kind = Kind.CONNECTED
                username = connection.username
            }
            is ConnectionState.Connecting -> kind = Kind.CONNECTING
            is ConnectionState.Reconnecting -> {
                kind = Kind.RECONNECTING
                attempt = connection.attempt
            }
            is ConnectionState.Disconnected -> kind = Kind.DISCONNECTED
        }
        return Snapshot(
            kind = kind,
            username = username,
            attempt = attempt,
            activeDownloads = activeDown,
            activeUploads = activeUp,
            waitingDownloads = waiting,
            downloadSpeed = if (activeDown > 0) stats.downloadSpeed.coerceAtLeast(0L) else 0L,
            uploadSpeed = if (activeUp > 0) stats.uploadSpeed.coerceAtLeast(0L) else 0L,
            progressPercent = if (activeDown > 0 || waiting > 0) progress else -1,
        )
    }

    /** Bağlantı kullanıcı tarafından kesilmiş (motor yeniden bağlanmayacak) ve transfer yoksa kısa bekleyişle durur. */
    private fun evaluateStop(snap: Snapshot) {
        if (snap.kind != Kind.DISCONNECTED || snap.anyActive) {
            stopJob?.cancel()
            stopJob = null
            return
        }
        if (stopJob?.isActive == true) return
        stopJob = lifecycleScope.launch {
            // Motorun ilk bağlanma girişimi (start → Connecting) birkaç milisaniye sürebilir; hemen durmayız.
            delay(STOP_GRACE_MILLIS)
            val now = snapshot()
            if (now.kind == Kind.DISCONNECTED && !now.anyActive) {
                AndroidLogger.info(TAG, "Disconnected with no active transfers; stopping service")
                stopSelf()
            }
        }
    }

    // ---------------------------------------------------------------------------------------- kilitler

    private fun applyLocks(snap: Snapshot) {
        val wantWifi = snap.kind == Kind.CONNECTED || snap.kind == Kind.CONNECTING || snap.anyActive
        if (wantWifi) acquireWifiLock() else releaseWifiLock()
        if (snap.anyActive) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWifiLock() {
        try {
            val lock = wifiLock ?: run {
                val wifi = ContextCompat.getSystemService(applicationContext, WifiManager::class.java) ?: return
                @Suppress("DEPRECATION")
                val created = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                created.setReferenceCounted(false)
                wifiLock = created
                created
            }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "WifiLock could not be acquired", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "WifiLock could not be released", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: run {
                val power = ContextCompat.getSystemService(applicationContext, PowerManager::class.java) ?: return
                val created = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                created.setReferenceCounted(false)
                wakeLock = created
                created
            }
            val now = SystemClock.elapsedRealtime()
            // Zaman aşımlı alınır (sızıntıya karşı güvence); yarı ömründe yenilenir.
            if (!lock.isHeld || now - wakeLockAcquiredAt >= WAKE_LOCK_RENEW_MILLIS) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                wakeLockAcquiredAt = now
            }
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "WakeLock could not be acquired", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "WakeLock could not be released", e)
        }
        wakeLockAcquiredAt = 0L
    }

    // ---------------------------------------------------------------------------------------- ağ geri çağrısı

    private fun registerNetworkCallback() {
        try {
            val cm = ContextCompat.getSystemService(applicationContext, ConnectivityManager::class.java) ?: return
            val callback = NetworkWatcher()
            cm.registerDefaultNetworkCallback(callback)
            connectivityManager = cm
            networkCallback = callback
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "Network callback could not be registered", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "Network callback could not be unregistered", e)
        }
    }

    /**
     * Varsayılan ağ değişince (farklı ağ ya da ölçülü/ölçüsüz geçişi) motoru uyarır. Sinyal gücü gibi sık gelen
     * yetenek güncellemeleri motoru tetiklemez. Geri çağrılar tek bir bağlantı iş parçacığında sırayla gelir.
     */
    private inner class NetworkWatcher : ConnectivityManager.NetworkCallback() {
        private var initialized = false
        private var lastNetwork: Network? = null
        private var lastMetered = false

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val changed = initialized && (network != lastNetwork || metered != lastMetered)
            val firstOnMetered = !initialized && metered
            initialized = true
            lastNetwork = network
            lastMetered = metered
            if (changed || firstOnMetered) {
                try {
                    client.onNetworkChanged(metered)
                } catch (e: Exception) {
                    AndroidLogger.warn(TAG, "onNetworkChanged failed", e)
                }
            }
        }

        override fun onLost(network: Network) {
            if (network == lastNetwork) lastNetwork = null
        }
    }

    // ---------------------------------------------------------------------------------------- bildirim

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun postNotification(snap: Snapshot) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(snap))
        } catch (e: Exception) {
            AndroidLogger.warn(TAG, "Notification could not be posted", e)
        }
    }

    private fun buildNotification(snap: Snapshot): Notification {
        val title = when (snap.kind) {
            Kind.CONNECTED -> getString(R.string.service_title_connected, snap.username.orEmpty())
            Kind.CONNECTING -> getString(R.string.service_title_connecting)
            Kind.RECONNECTING -> getString(R.string.service_title_reconnecting, snap.attempt.coerceAtLeast(1))
            Kind.DISCONNECTED -> getString(R.string.service_title_disconnected)
        }
        val lines = ArrayList<String>(3)
        if (snap.activeDownloads > 0) {
            lines += resources.getQuantityString(
                R.plurals.service_downloading, snap.activeDownloads,
                snap.activeDownloads, Formatter.formatFileSize(this, snap.downloadSpeed),
            )
        }
        if (snap.activeUploads > 0) {
            lines += resources.getQuantityString(
                R.plurals.service_uploading, snap.activeUploads,
                snap.activeUploads, Formatter.formatFileSize(this, snap.uploadSpeed),
            )
        }
        if (snap.waitingDownloads > 0) {
            lines += resources.getQuantityString(R.plurals.service_downloads_waiting, snap.waitingDownloads, snap.waitingDownloads)
        }
        if (lines.isEmpty()) lines += getString(R.string.service_text_idle)
        val text = lines.joinToString(" · ")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_soulseek)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent)
        if (snap.progressPercent in 0..100) builder.setProgress(100, snap.progressPercent, false)
        if (snap.kind == Kind.DISCONNECTED) {
            builder.addAction(0, getString(R.string.service_action_connect), connectIntent)
        } else {
            builder.addAction(0, getString(R.string.service_action_disconnect), disconnectIntent)
        }
        builder.addAction(0, getString(R.string.service_action_open), openAppIntent)
        return builder.build()
    }

    private fun buildOpenAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, MAIN_ACTIVITY_CLASS)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SoulseekService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---------------------------------------------------------------------------------------- dışa açık API

    companion object {
        private const val TAG = "Service"
        private const val CHANNEL_ID = "service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_DISCONNECT = "com.soulseekplus.action.DISCONNECT"
        private const val ACTION_CONNECT = "com.soulseekplus.action.CONNECT"
        private const val MAIN_ACTIVITY_CLASS = "com.soulseekplus.ui.MainActivity"
        private const val WIFI_LOCK_TAG = "SoulseekPlus:wifi"
        private const val WAKE_LOCK_TAG = "SoulseekPlus:transfers"

        private const val MIN_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val STOP_GRACE_MILLIS = 8_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
        private const val WAKE_LOCK_RENEW_MILLIS = 5 * 60_000L
        private const val LOCK_RENEW_CHECK_MILLIS = 60_000L

        /** Ön plan servisini başlatır. Android 12+ arka plandan başlatmayı reddedebilir; istisnalar yutulur ve günlüğe yazılır. */
        fun start(context: Context) {
            try {
                val app = context.applicationContext
                ContextCompat.startForegroundService(app, Intent(app, SoulseekService::class.java))
            } catch (e: Exception) {
                AndroidLogger.warn(TAG, "Service could not be started", e)
            }
        }

        /** Servisi durdurur (kilitler ve bildirim kaldırılır). */
        fun stop(context: Context) {
            try {
                val app = context.applicationContext
                app.stopService(Intent(app, SoulseekService::class.java))
            } catch (e: Exception) {
                AndroidLogger.warn(TAG, "Service could not be stopped", e)
            }
        }
    }
}
