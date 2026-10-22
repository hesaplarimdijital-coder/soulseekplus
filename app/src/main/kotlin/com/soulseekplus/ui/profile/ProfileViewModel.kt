package com.soulseekplus.ui.profile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.model.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProfileState {
    data object Idle : ProfileState
    data class Loading(val username: String) : ProfileState
    data class Failed(val username: String, val message: String?) : ProfileState
    data class Loaded(val username: String, val info: UserInfo, val picture: Bitmap?, val address: Pair<String, Int>?) : ProfileState
}

/** Kullanıcı bilgisi ekranı: profil (açıklama, resim, yuva sayıları), durum ve adres. */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)

    var usernameInput by mutableStateOf("")
    var state by mutableStateOf<ProfileState>(ProfileState.Idle)
        private set

    private var job: Job? = null
    private var watched: String? = null

    fun open(username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        usernameInput = name
        job?.cancel()
        watched?.let { if (it != name) container.client.unwatchUser(it) }
        watched = name
        container.client.watchUser(name)
        state = ProfileState.Loading(name)
        job = viewModelScope.launch {
            try {
                val info = container.client.getUserInfo(name)
                val picture = info.picture?.let { bytes -> withContext(Dispatchers.Default) { decode(bytes) } }
                val address = try {
                    container.client.getUserAddress(name)
                } catch (e: SoulseekException) {
                    null
                }
                state = ProfileState.Loaded(name, info, picture, address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SoulseekException) {
                state = ProfileState.Failed(name, e.message)
            }
        }
    }

    fun reload() = open(usernameInput)

    override fun onCleared() {
        watched?.let { container.client.unwatchUser(it) }
        super.onCleared()
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_SIDE = 512
    }
}
