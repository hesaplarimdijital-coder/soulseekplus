package com.soulseekplus.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.soulseekplus.R
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.chat.ChatEvent
import com.soulseekplus.core.chat.ChatRepository
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.search.WishlistHitEvent
import com.soulseekplus.core.search.WishlistRepository
import com.soulseekplus.data.settings.SettingsRepository
import com.soulseekplus.ui.LaunchTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sistem bildirimleri: özel mesajlar, oda anmaları, istek listesi isabetleri ve biten indirmeler.
 *
 * - Hangi kategorinin gösterileceği ayarlardan (`AppPrefs.notify*`) her olayda okunur; değişiklik hemen etkili olur.
 * - Bildirim izni (Android 13+) yoksa ya da kullanıcı bildirimleri kapattıysa hiçbir şey gösterilmez.
 * - Açık olan sohbet için bildirim gösterilmez; sohbet okununca ilgili bildirim kaldırılır.
 * - Dokununca [LaunchTargets] ile `MainActivity` ilgili ekranda açılır.
 */
class AppNotifications(
    context: Context,
    private val client: SoulseekClient,
    private val chat: ChatRepository,
    private val wishlist: WishlistRepository,
    private val scope: CoroutineScope,
) {
    private val context: Context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Kullanıcı başına gösterilen son mesajlar (MessagingStyle geçmişi); erişim `synchronized(conversations)` altındadır. */
    private val conversations = HashMap<String, ArrayDeque<ConversationLine>>()

    private data class ConversationLine(val text: String, val timestampMillis: Long)

    private val finishedSeen = HashSet<String>()
    private var finishedBaselineTaken = false
    private val finishedBatch = ArrayList<Transfer>()
    private var finishedJob: Job? = null

    fun start() {
        createChannels()
        scope.launch { chat.events.collect(::onChatEvent) }
        scope.launch { wishlist.events.collect(::onWishlistEvent) }
        // Okunmuş sohbetlerin bildirimini kaldır.
        scope.launch {
            chat.privateChats
                .map { chats -> chats.filterValues { it.unread == 0 }.keys }
                .distinctUntilChanged()
                .collect { read -> read.forEach(::dismissConversation) }
        }
        scope.launch {
            client.transfers
                .map { list -> list.filter { it.direction == TransferDirection.DOWNLOAD && it.state == TransferState.FINISHED } }
                .distinctUntilChanged { old, new -> old.size == new.size && old.map { it.id } == new.map { it.id } }
                .collect(::onFinishedDownloads)
        }
    }

    // ------------------------------------------------------------------ kanallar

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        fun channel(id: String, name: Int, description: Int, importance: Int) {
            val channel = NotificationChannel(id, context.getString(name), importance)
            channel.description = context.getString(description)
            nm.createNotificationChannel(channel)
        }
        channel(CHANNEL_MESSAGES, R.string.appnotif_channel_messages, R.string.appnotif_channel_messages_desc, NotificationManager.IMPORTANCE_HIGH)
        channel(CHANNEL_WISHLIST, R.string.appnotif_channel_wishlist, R.string.appnotif_channel_wishlist_desc, NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_TRANSFERS, R.string.appnotif_channel_transfers, R.string.appnotif_channel_transfers_desc, NotificationManager.IMPORTANCE_LOW)
    }

    // ------------------------------------------------------------------ olaylar

    private fun onChatEvent(event: ChatEvent) {
        if (!SettingsRepository.readAppPrefs(context).notifyMessages) return
        when (event) {
            is ChatEvent.PrivateMessageReceived -> if (!event.conversationOpen) showPrivateMessage(event)
            is ChatEvent.RoomMention -> if (!event.roomOpen) showMention(event)
            is ChatEvent.RoomJoinFailed -> Unit
        }
    }

    private fun showPrivateMessage(event: ChatEvent.PrivateMessageReceived) {
        if (!canNotify()) return
        val lines = synchronized(conversations) {
            val deque = conversations.getOrPut(event.username) { ArrayDeque() }
            deque.addLast(ConversationLine(event.message.text, event.message.timestampMillis))
            while (deque.size > MAX_LINES) deque.removeFirst()
            deque.toList()
        }

        val sender = Person.Builder().setName(event.username).setKey(event.username).build()
        val me = Person.Builder().setName(context.getString(R.string.appnotif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
        for (line in lines) style.addMessage(line.text, line.timestampMillis, sender)

        val id = idFor(ID_PRIVATE, event.username)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_soulseek)
            .setContentTitle(event.username)
            .setContentText(event.message.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open(id, LaunchTargets.TARGET_PRIVATE_CHAT, user = event.username))
            .build()
        post(id, notification)
    }

    private fun showMention(event: ChatEvent.RoomMention) {
        if (!canNotify()) return
        val id = idFor(ID_ROOM, event.room)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_soulseek)
            .setContentTitle(context.getString(R.string.appnotif_mention_title, event.message.username, event.room))
            .setContentText(event.message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open(id, LaunchTargets.TARGET_ROOM, room = event.room))
            .build()
        post(id, notification)
    }

    private fun onWishlistEvent(event: WishlistHitEvent) {
        if (!SettingsRepository.readAppPrefs(context).notifyWishlist) return
        // Uygulama ekrandayken rozet zaten görünür.
        if (isForeground() || !canNotify()) return
        val samples = event.sample.joinToString("\n") { "${it.file.name} — ${it.username}" }
        val id = idFor(ID_WISHLIST, event.query)
        val notification = NotificationCompat.Builder(context, CHANNEL_WISHLIST)
            .setSmallIcon(R.drawable.ic_stat_soulseek)
            .setContentTitle(context.getString(R.string.appnotif_wishlist_title, event.query))
            .setContentText(context.getString(R.string.appnotif_wishlist_text, event.newCount))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.appnotif_wishlist_text, event.newCount) + "\n" + samples))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(open(id, LaunchTargets.TARGET_WISHLIST))
            .build()
        post(id, notification)
    }

    private fun onFinishedDownloads(finished: List<Transfer>) {
        if (!finishedBaselineTaken) {
            // Açılışta zaten bitmiş olanlar için bildirim gösterilmez.
            finishedBaselineTaken = true
            finishedSeen.addAll(finished.map { it.id })
            return
        }
        val fresh = finished.filter { finishedSeen.add(it.id) }
        finishedSeen.retainAll(finished.mapTo(HashSet()) { it.id })
        if (fresh.isEmpty()) return
        if (!SettingsRepository.readAppPrefs(context).notifyTransfers) return
        synchronized(finishedBatch) { finishedBatch.addAll(fresh) }
        // Art arda biten dosyalar tek bildirimde toplanır.
        finishedJob?.cancel()
        finishedJob = scope.launch {
            delay(BATCH_MILLIS)
            flushFinished()
        }
    }

    private fun flushFinished() {
        val batch = synchronized(finishedBatch) { finishedBatch.toList().also { finishedBatch.clear() } }
        if (batch.isEmpty() || isForeground() || !canNotify()) return
        val title: String
        val text: String
        if (batch.size == 1) {
            title = context.getString(R.string.appnotif_download_done)
            text = batch[0].fileName
        } else {
            title = context.getString(R.string.appnotif_downloads_done)
            text = context.getString(R.string.appnotif_downloads_done_count, batch.size)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_soulseek)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(batch.joinToString("\n", limit = 6) { it.fileName }))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open(ID_TRANSFERS, LaunchTargets.TARGET_TRANSFERS))
            .build()
        post(ID_TRANSFERS, notification)
    }

    private fun dismissConversation(username: String) {
        val had = synchronized(conversations) { conversations.remove(username) != null }
        if (had) manager.cancel(idFor(ID_PRIVATE, username))
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun open(requestCode: Int, target: String, user: String? = null, room: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            LaunchTargets.intent(context, target, user, room),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(id: Int, notification: android.app.Notification) {
        if (!canNotify()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // İzin yarış durumunda geri alınmış olabilir; bildirimsiz devam.
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled()
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Anahtardan kararlı bildirim kimliği (aynı sohbet aynı bildirimi günceller). */
    private fun idFor(base: Int, key: String): Int = base + (key.hashCode() and 0xFFFF)

    private companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_WISHLIST = "wishlist"
        const val CHANNEL_TRANSFERS = "transfers"

        const val ID_PRIVATE = 0x10000
        const val ID_ROOM = 0x20000
        const val ID_WISHLIST = 0x30000
        const val ID_TRANSFERS = 0x40000

        const val MAX_LINES = 6
        const val BATCH_MILLIS = 3_000L
    }
}
