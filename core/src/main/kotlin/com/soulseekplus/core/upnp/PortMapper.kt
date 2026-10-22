package com.soulseekplus.core.upnp

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.PortMappingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Yönlendiricide dinleme portunu açar: önce UPnP IGD, olmazsa NAT-PMP. Kira dolmadan yeniler, ağ değişince yeniden eşler,
 * kapatırken eşlemeyi kaldırır. Tüm ağ işleri [scope] içinde (IO) çalışır.
 *
 * NAT-PMP ağ geçidi: `hints` (ör. Android LinkProperties), okunabiliyorsa `/proc/net/route` varsayılan rotası, son olarak
 * her yerel IPv4 /24 ağının `.1` adresi denenir (bkz. [LocalNetwork.gatewayCandidates]).
 */
class PortMapper(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val gatewayHints: () -> List<String> = { emptyList() },
    private val discover: (Inet4Address?) -> List<String> = { local -> Ssdp.discover(local, logger) },
    private val natPmpPort: Int = NatPmp.PORT,
    private val renewIntervalMillis: Long = 7_200_000,
    private val retryIntervalMillis: Long = 900_000,
    private val localAddress: () -> Inet4Address? = { LocalNetwork.localIpv4() },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Active {
        val port: Int

        class Upnp(val service: IgdService, override val port: Int) : Active
        class Pmp(val gateway: InetAddress, val bind: InetAddress?, override val port: Int) : Active
    }

    private val mutex = Mutex()
    private var job: Job? = null
    private var active: Active? = null
    private var wantedPort = 0

    private val _state = MutableStateFlow<PortMappingState>(PortMappingState.Disabled)
    val state: StateFlow<PortMappingState> = _state

    /** [port] için eşlemeyi başlatır (önceki varsa kaldırılır). */
    fun start(port: Int) {
        job?.cancel()
        wantedPort = port
        job = scope.launch {
            mutex.withLock {
                removeLocked()
                _state.value = PortMappingState.Discovering
            }
            loop(port)
        }
    }

    /** Ağ değişti: aynı port için yeniden keşfet ve eşle. */
    fun refresh() {
        val port = wantedPort
        if (port in 1..65535 && job != null) start(port)
    }

    /** Eşlemeyi kaldırır (arka planda) ve durumu [PortMappingState.Disabled] yapar. */
    fun stop() {
        job?.cancel()
        job = null
        wantedPort = 0
        scope.launch {
            mutex.withLock { removeLocked() }
            _state.value = PortMappingState.Disabled
        }
    }

    /** [stop] gibi, ama en çok [timeoutMillis] bekler (kapatma için). */
    suspend fun stopAndWait(timeoutMillis: Long) {
        job?.cancel()
        job = null
        wantedPort = 0
        withTimeoutOrNull(timeoutMillis) { mutex.withLock { removeLocked() } }
        _state.value = PortMappingState.Disabled
    }

    private suspend fun loop(port: Int) {
        while (true) {
            var delayMillis = retryIntervalMillis
            try {
                mutex.withLock {
                    val result = withContext(Dispatchers.IO) { mapOnce(port) }
                    if (result != null) {
                        _state.value = result
                        delayMillis = renewIntervalMillis
                    } else {
                        _state.value = PortMappingState.Failed("No UPnP or NAT-PMP gateway found")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Port eşleme hatası", e)
                _state.value = PortMappingState.Failed(e.message ?: "Port mapping failed")
            }
            delay(delayMillis)
        }
    }

    /** Engelleyici: bir kez UPnP sonra NAT-PMP dener. */
    private fun mapOnce(port: Int): PortMappingState.Mapped? {
        val local = localAddress()
        if (local == null) {
            logger.info(TAG, "Yerel IPv4 adresi yok, port eşlenemedi")
            return null
        }
        // 1) Daha önce işe yarayan yöntemi önce dene (yenileme).
        val previous = active
        // 2) UPnP
        try {
            val service = if (previous is Active.Upnp) previous.service else UpnpIgd.pickService(discover(local), logger)
            if (service != null) {
                val lease = UpnpIgd.addPortMapping(service, port, local.hostAddress.orEmpty())
                val ip = try {
                    UpnpIgd.externalIp(service)
                } catch (_: IOException) {
                    null
                }
                active = Active.Upnp(service, port)
                logger.info(TAG, "UPnP ile port $port açıldı (kira ${lease}s)")
                return Mapped("UPnP", ip, port, lease)
            }
        } catch (e: IOException) {
            logger.info(TAG, "UPnP başarısız: ${e.message}")
            if (previous is Active.Upnp) active = null
        }
        // 3) NAT-PMP
        for (candidate in LocalNetwork.gatewayCandidates(gatewayHints())) {
            try {
                val gateway = InetAddress.getByName(candidate)
                val lifetime = NatPmp.mapPort(gateway, port, NatPmp.LEASE_SECONDS.toLong(), local, natPmpPort)
                val ip = NatPmp.externalAddress(gateway, local, natPmpPort)
                active = Active.Pmp(gateway, local, port)
                logger.info(TAG, "NAT-PMP ile port $port açıldı (ağ geçidi $candidate)")
                return Mapped("NAT-PMP", ip, port, lifetime.toInt())
            } catch (e: IOException) {
                logger.debug(TAG, "NAT-PMP $candidate: ${e.message}")
            }
        }
        active = null
        return null
    }

    private fun Mapped(method: String, ip: String?, port: Int, leaseSeconds: Int): PortMappingState.Mapped =
        PortMappingState.Mapped(
            method = method,
            externalIp = ip,
            externalPort = port,
            internalPort = port,
            leaseExpiresAtMillis = if (leaseSeconds > 0) clockMillis() + leaseSeconds * 1000L else null,
        )

    /** Mutex tutulurken çağrılır. */
    private suspend fun removeLocked() {
        val a = active ?: return
        active = null
        withContext(Dispatchers.IO) {
            try {
                when (a) {
                    is Active.Upnp -> UpnpIgd.deletePortMapping(a.service, a.port)
                    is Active.Pmp -> NatPmp.unmapPort(a.gateway, a.port, a.bind, natPmpPort)
                }
                logger.info(TAG, "Port eşlemesi kaldırıldı (${a.port})")
            } catch (e: IOException) {
                logger.debug(TAG, "Port eşlemesi kaldırılamadı: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PortMapper"
    }
}
