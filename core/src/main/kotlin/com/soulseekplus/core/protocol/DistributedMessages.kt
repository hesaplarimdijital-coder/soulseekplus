package com.soulseekplus.core.protocol

/**
 * Dağıtık ("D") ağ mesajları (bkz. docs/slsk-protocol.md §6). Çerçeve: uint32 uzunluk + uint8 kod + yük.
 */
sealed interface DistributedMessage {
    val code: Int
    fun writePayload(w: MessageWriter)

    fun encode(): ByteArray {
        val w = MessageWriter()
        writePayload(w)
        return Frame.code8(code, w.toByteArray())
    }
}

data object DistribPing : DistributedMessage {
    override val code get() = DistribCode.PING
    override fun writePayload(w: MessageWriter) = Unit
}

data class DistribSearch(val username: String, val token: Int, val query: String) : DistributedMessage {
    override val code get() = DistribCode.SEARCH
    override fun writePayload(w: MessageWriter) {
        w.uint32(DistribCode.SEARCH_IDENTIFIER.toLong()).string(username).int32(token).string(query)
    }
}

data class DistribBranchLevel(val level: Int) : DistributedMessage {
    override val code get() = DistribCode.BRANCH_LEVEL
    override fun writePayload(w: MessageWriter) {
        w.int32(level)
    }
}

data class DistribBranchRoot(val root: String) : DistributedMessage {
    override val code get() = DistribCode.BRANCH_ROOT
    override fun writePayload(w: MessageWriter) {
        w.string(root)
    }
}

data class DistribChildDepth(val depth: Long) : DistributedMessage {
    override val code get() = DistribCode.CHILD_DEPTH
    override fun writePayload(w: MessageWriter) {
        w.uint32(depth)
    }
}

/** Başka bir dağıtık mesajı saran (kullanımdan kalkmış) zarf. [body] sarılan mesajın gövdesidir. */
class DistribEmbedded(val distribCode: Int, val body: ByteArray) : DistributedMessage {
    override val code get() = DistribCode.EMBEDDED_MESSAGE
    override fun writePayload(w: MessageWriter) {
        w.uint8(distribCode).raw(body)
    }
}

object DistributedMessages {

    /**
     * Gelen dağıtık mesajı çözer. [payload] kod baytından sonrasıdır. Bilinmeyen kod için null; geçersiz içerik için
     * [ProtocolException] (çağıran bağlantıyı kapatır).
     */
    fun decode(code: Int, payload: ByteArray): DistributedMessage? {
        return when (code) {
            DistribCode.PING -> DistribPing
            DistribCode.SEARCH -> decodeSearch(payload)
            DistribCode.BRANCH_LEVEL -> {
                val level = MessageReader(payload).int32()
                if (level < 0) throw ProtocolException("Negatif dal seviyesi")
                DistribBranchLevel(level)
            }
            DistribCode.BRANCH_ROOT -> {
                val root = MessageReader(payload).string()
                if (!isValidUsername(root)) throw ProtocolException("Geçersiz dal kökü")
                DistribBranchRoot(root)
            }
            DistribCode.CHILD_DEPTH -> DistribChildDepth(MessageReader(payload).uint32())
            DistribCode.EMBEDDED_MESSAGE -> {
                val r = MessageReader(payload)
                // Eski SoulseekQt kodu uint32 gönderir: ilk 3 bayt sıfırsa atla.
                if (payload.size >= 4 && payload[0].toInt() == 0 && payload[1].toInt() == 0 && payload[2].toInt() == 0) r.skip(3)
                val inner = r.uint8()
                DistribEmbedded(inner, r.rest())
            }
            else -> null
        }
    }

    /** DistribSearch gövdesi (sunucu EmbeddedMessage'ı ile aynı biçim). */
    fun decodeSearch(body: ByteArray): DistribSearch {
        val r = MessageReader(body)
        val identifier = r.uint32()
        if (identifier != DistribCode.SEARCH_IDENTIFIER.toLong()) throw ProtocolException("Bilinmeyen arama tanımlayıcısı $identifier")
        return DistribSearch(r.string(), r.int32(), r.string())
    }

    /** Alınan bir DistribSearch gövdesini çocuklara olduğu gibi iletmek için çerçeveler. */
    fun searchFrame(rawBody: ByteArray): ByteArray = Frame.code8(DistribCode.SEARCH, rawBody)

    /** Kullanıcı adı doğrulaması (Nicotine+): 1..256 bayt, "server" değil, yazdırılabilir. */
    fun isValidUsername(name: String): Boolean {
        if (name.isEmpty() || name == "server") return false
        if (name.toByteArray(Charsets.UTF_8).size > 256) return false
        return name.none { it.isISOControl() }
    }
}
