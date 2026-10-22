package com.soulseekplus.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.UploadQueueMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Motorun bilmediği, yalnızca uygulama katmanını ilgilendiren tercihler. */
data class AppPrefs(
    val notifyMessages: Boolean = true,
    val notifyWishlist: Boolean = true,
    val notifyTransfers: Boolean = true,
    /** Paylaşım taramasında ses özniteliklerini (bitrate, süre, örnekleme hızı) oku. */
    val readAudioAttributes: Boolean = true,
    /** Yeni arama sekmelerinin varsayılan gruplama kipi. */
    val defaultGroupMode: GroupMode = GroupMode.FOLDER,
    /** Gruplu sonuçlarda grupları varsayılan olarak aç. */
    val expandGroups: Boolean = false,
)

/**
 * Ayarların kalıcı deposu: `SharedPreferences("settings")`. Parola ayrı, uygulamaya özel bir dosyada ve Keystore ile
 * şifreli tutulur. Tüm yazmalar tek kilit altında ve [StateFlow] ile eşzamanlı yayımlanır.
 */
class SettingsRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val accountPrefs: SharedPreferences = appContext.getSharedPreferences(ACCOUNT_FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _client = MutableStateFlow(loadClient())

    /** Motorun okuduğu tüm ayarlar. */
    val clientSettings: StateFlow<ClientSettings> = _client.asStateFlow()

    private val _downloadRoot = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_ROOT, null)?.takeIf { it.isNotEmpty() })

    /** İndirme kökü (SAF ağaç URI'si, kalıcı izinli) ya da ayarlanmamışsa null. */
    val downloadRootUri: StateFlow<String?> = _downloadRoot.asStateFlow()

    private val _app = MutableStateFlow(loadApp())
    val appPrefs: StateFlow<AppPrefs> = _app.asStateFlow()

    /** Ayarları dönüştürür, doğrular, kaydeder ve yayımlar. */
    fun update(transform: (ClientSettings) -> ClientSettings) {
        synchronized(lock) {
            val old = _client.value
            val new = sanitize(transform(old))
            if (new == old) return
            saveClient(old, new)
            _client.value = new
        }
    }

    /** Hesap bilgilerini ayarlar. Kullanıcı adı kırpılır; sunucu/port boşsa varsayılana döner. */
    fun setAccount(username: String, password: String, host: String, port: Int) {
        update {
            it.copy(
                username = username.trim(),
                password = password,
                serverHost = host.trim().ifEmpty { ClientSettings.DEFAULT_SERVER_HOST },
                serverPort = if (port in 1..65535) port else ClientSettings.DEFAULT_SERVER_PORT,
            )
        }
    }

    /** Komut takma adını ayarlar; [expansion] null ise siler. */
    fun setAlias(name: String, expansion: String?) {
        update { settings ->
            val key = name.trim().lowercase()
            if (key.isEmpty()) {
                settings
            } else if (expansion == null) {
                settings.copy(commandAliases = settings.commandAliases - key)
            } else {
                settings.copy(commandAliases = settings.commandAliases + (key to expansion.trim()))
            }
        }
    }

    fun setDownloadRoot(uri: String?) {
        synchronized(lock) {
            val value = uri?.takeIf { it.isNotEmpty() }
            prefs.edit().putString(KEY_DOWNLOAD_ROOT, value).apply()
            _downloadRoot.value = value
        }
    }

    fun updateApp(transform: (AppPrefs) -> AppPrefs) {
        synchronized(lock) {
            val new = transform(_app.value)
            if (new == _app.value) return
            prefs.edit()
                .putBoolean(KEY_NOTIFY_MESSAGES, new.notifyMessages)
                .putBoolean(KEY_NOTIFY_WISHLIST, new.notifyWishlist)
                .putBoolean(KEY_NOTIFY_TRANSFERS, new.notifyTransfers)
                .putBoolean(KEY_READ_AUDIO_ATTRS, new.readAudioAttributes)
                .putString(KEY_DEFAULT_GROUP_MODE, new.defaultGroupMode.name)
                .putBoolean(KEY_EXPAND_GROUPS, new.expandGroups)
                .apply()
            _app.value = new
        }
    }

    // ------------------------------------------------------------------ yükleme / kaydetme

    private fun loadClient(): ClientSettings {
        val d = ClientSettings()
        return sanitize(
            ClientSettings(
                username = prefs.getString("username", d.username).orEmpty(),
                password = PasswordVault.open(accountPrefs.getString(KEY_PASSWORD, null)),
                serverHost = prefs.getString("serverHost", d.serverHost).orEmpty(),
                serverPort = prefs.getInt("serverPort", d.serverPort),
                autoConnect = prefs.getBoolean("autoConnect", d.autoConnect),
                listenPort = prefs.getInt("listenPort", d.listenPort),
                portMappingEnabled = prefs.getBoolean("portMappingEnabled", d.portMappingEnabled),
                transfersOnWifiOnly = prefs.getBoolean("transfersOnWifiOnly", d.transfersOnWifiOnly),
                uploadSlots = prefs.getInt("uploadSlots", d.uploadSlots),
                uploadQueueMode = runCatching {
                    UploadQueueMode.valueOf(prefs.getString("uploadQueueMode", d.uploadQueueMode.name).orEmpty())
                }.getOrDefault(d.uploadQueueMode),
                uploadSpeedLimitKiB = prefs.getInt("uploadSpeedLimitKiB", d.uploadSpeedLimitKiB),
                prioritizeBuddies = prefs.getBoolean("prioritizeBuddies", d.prioritizeBuddies),
                uploadToBuddiesOnly = prefs.getBoolean("uploadToBuddiesOnly", d.uploadToBuddiesOnly),
                maxQueuedFilesPerUser = prefs.getInt("maxQueuedFilesPerUser", d.maxQueuedFilesPerUser),
                maxQueuedMiBPerUser = prefs.getInt("maxQueuedMiBPerUser", d.maxQueuedMiBPerUser),
                downloadSpeedLimitKiB = prefs.getInt("downloadSpeedLimitKiB", d.downloadSpeedLimitKiB),
                autoRetryDownloads = prefs.getBoolean("autoRetryDownloads", d.autoRetryDownloads),
                maxActiveDownloads = prefs.getInt("maxActiveDownloads", d.maxActiveDownloads),
                downloadsIntoUserFolder = prefs.getBoolean("downloadsIntoUserFolder", d.downloadsIntoUserFolder),
                maxSearchResultsToSend = prefs.getInt("maxSearchResultsToSend", d.maxSearchResultsToSend),
                maxSearchResultsKept = prefs.getInt("maxSearchResultsKept", d.maxSearchResultsKept),
                respondToSearches = prefs.getBoolean("respondToSearches", d.respondToSearches),
                userDescription = prefs.getString("userDescription", d.userDescription).orEmpty(),
                userPictureUri = prefs.getString("userPictureUri", null)?.takeIf { it.isNotEmpty() },
                autoJoinRooms = decodeList(prefs.getString("autoJoinRooms", null)),
                commandAliases = decodeMap(prefs.getString("commandAliases", null)),
            ),
        )
    }

    private fun saveClient(old: ClientSettings, new: ClientSettings) {
        val e = prefs.edit()
        e.putString("username", new.username)
        e.putString("serverHost", new.serverHost)
        e.putInt("serverPort", new.serverPort)
        e.putBoolean("autoConnect", new.autoConnect)
        e.putInt("listenPort", new.listenPort)
        e.putBoolean("portMappingEnabled", new.portMappingEnabled)
        e.putBoolean("transfersOnWifiOnly", new.transfersOnWifiOnly)
        e.putInt("uploadSlots", new.uploadSlots)
        e.putString("uploadQueueMode", new.uploadQueueMode.name)
        e.putInt("uploadSpeedLimitKiB", new.uploadSpeedLimitKiB)
        e.putBoolean("prioritizeBuddies", new.prioritizeBuddies)
        e.putBoolean("uploadToBuddiesOnly", new.uploadToBuddiesOnly)
        e.putInt("maxQueuedFilesPerUser", new.maxQueuedFilesPerUser)
        e.putInt("maxQueuedMiBPerUser", new.maxQueuedMiBPerUser)
        e.putInt("downloadSpeedLimitKiB", new.downloadSpeedLimitKiB)
        e.putBoolean("autoRetryDownloads", new.autoRetryDownloads)
        e.putInt("maxActiveDownloads", new.maxActiveDownloads)
        e.putBoolean("downloadsIntoUserFolder", new.downloadsIntoUserFolder)
        e.putInt("maxSearchResultsToSend", new.maxSearchResultsToSend)
        e.putInt("maxSearchResultsKept", new.maxSearchResultsKept)
        e.putBoolean("respondToSearches", new.respondToSearches)
        e.putString("userDescription", new.userDescription)
        e.putString("userPictureUri", new.userPictureUri.orEmpty())
        e.putString("autoJoinRooms", encodeList(new.autoJoinRooms))
        e.putString("commandAliases", encodeMap(new.commandAliases))
        e.apply()
        if (old.password != new.password) {
            accountPrefs.edit().putString(KEY_PASSWORD, PasswordVault.seal(new.password)).apply()
        }
    }

    private fun loadApp(): AppPrefs {
        val d = AppPrefs()
        return AppPrefs(
            notifyMessages = prefs.getBoolean(KEY_NOTIFY_MESSAGES, d.notifyMessages),
            notifyWishlist = prefs.getBoolean(KEY_NOTIFY_WISHLIST, d.notifyWishlist),
            notifyTransfers = prefs.getBoolean(KEY_NOTIFY_TRANSFERS, d.notifyTransfers),
            readAudioAttributes = prefs.getBoolean(KEY_READ_AUDIO_ATTRS, d.readAudioAttributes),
            defaultGroupMode = runCatching { GroupMode.valueOf(prefs.getString(KEY_DEFAULT_GROUP_MODE, d.defaultGroupMode.name).orEmpty()) }
                .getOrDefault(d.defaultGroupMode),
            expandGroups = prefs.getBoolean(KEY_EXPAND_GROUPS, d.expandGroups),
        )
    }

    /** Aralık dışı ya da bozuk değerleri güvenli sınırlara çeker. */
    private fun sanitize(s: ClientSettings): ClientSettings = s.copy(
        username = s.username.trim(),
        serverHost = s.serverHost.trim().ifEmpty { ClientSettings.DEFAULT_SERVER_HOST },
        serverPort = if (s.serverPort in 1..65535) s.serverPort else ClientSettings.DEFAULT_SERVER_PORT,
        listenPort = if (s.listenPort in 1..65535) s.listenPort else ClientSettings.DEFAULT_LISTEN_PORT,
        uploadSlots = s.uploadSlots.coerceIn(1, 100),
        uploadSpeedLimitKiB = s.uploadSpeedLimitKiB.coerceIn(0, 10_000_000),
        maxQueuedFilesPerUser = s.maxQueuedFilesPerUser.coerceIn(0, 1_000_000),
        maxQueuedMiBPerUser = s.maxQueuedMiBPerUser.coerceIn(0, 100_000_000),
        downloadSpeedLimitKiB = s.downloadSpeedLimitKiB.coerceIn(0, 10_000_000),
        maxActiveDownloads = s.maxActiveDownloads.coerceIn(0, 200),
        maxSearchResultsToSend = s.maxSearchResultsToSend.coerceIn(0, 10_000),
        maxSearchResultsKept = s.maxSearchResultsKept.coerceIn(100, 100_000),
        autoJoinRooms = s.autoJoinRooms.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    )

    private fun encodeList(list: List<String>): String = JSONArray(list).toString()

    private fun decodeList(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun encodeMap(map: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }

    private fun decodeMap(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = obj.optString(key)
            }
            out
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    companion object {
        const val FILE = "settings"
        private const val ACCOUNT_FILE = "account"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOWNLOAD_ROOT = "downloadRootUri"
        private const val KEY_NOTIFY_MESSAGES = "notifyMessages"
        private const val KEY_NOTIFY_WISHLIST = "notifyWishlist"
        private const val KEY_NOTIFY_TRANSFERS = "notifyTransfers"
        private const val KEY_READ_AUDIO_ATTRS = "readAudioAttributes"
        private const val KEY_DEFAULT_GROUP_MODE = "defaultGroupMode"
        private const val KEY_EXPAND_GROUPS = "expandGroups"

        /** Bildirim servisleri gibi depo nesnesine ulaşamayanlar için tercihleri doğrudan okur. */
        fun readAppPrefs(context: Context): AppPrefs {
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val d = AppPrefs()
            return d.copy(
                notifyMessages = p.getBoolean(KEY_NOTIFY_MESSAGES, d.notifyMessages),
                notifyWishlist = p.getBoolean(KEY_NOTIFY_WISHLIST, d.notifyWishlist),
                notifyTransfers = p.getBoolean(KEY_NOTIFY_TRANSFERS, d.notifyTransfers),
            )
        }
    }
}
