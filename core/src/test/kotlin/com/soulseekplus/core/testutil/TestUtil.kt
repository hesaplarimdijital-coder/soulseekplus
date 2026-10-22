package com.soulseekplus.core.testutil

import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SharedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Eşzamanlı (Unconfined) test kapsamı. */
fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

fun CoroutineScope.close() = cancel()

/** [condition] doğru olana dek en çok [timeoutMillis] bekler. */
fun awaitUntil(timeoutMillis: Long = 3000, message: String = "condition not met", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    if (!condition()) throw AssertionError(message)
}

fun file(
    path: String,
    size: Long = 1_000_000,
    bitrate: Int? = null,
    sampleRate: Int? = null,
    bitDepth: Int? = null,
    duration: Int? = null,
) = SharedFile(path = path, size = size, attributes = FileAttributes(bitrate = bitrate, durationSeconds = duration, sampleRate = sampleRate, bitDepth = bitDepth))

fun hit(
    user: String = "alice",
    path: String = "Music\\Artist\\Album\\01 Song.mp3",
    size: Long = 5_000_000,
    bitrate: Int? = 320,
    locked: Boolean = false,
    free: Boolean = true,
    speed: Int = 100_000,
    queue: Long = 0,
    country: String? = "TR",
    sampleRate: Int? = null,
    bitDepth: Int? = null,
) = SearchHit(user, file(path, size, bitrate, sampleRate, bitDepth), locked, free, speed, queue, country)

fun response(
    user: String,
    token: Int,
    files: List<SharedFile>,
    locked: List<SharedFile> = emptyList(),
    free: Boolean = true,
    speed: Int = 50_000,
    queue: Long = 0,
    country: String? = "DE",
) = SearchResponse(user, token, files, locked, free, speed, queue, country)
