package com.soulseekplus.core.protocol

/*
 * Soulseek mesaj kodları (bkz. docs/slsk-protocol.md §3-§6). Sunucu ve "P" kodları uint32, başlangıç ve "D" kodları uint8'dir.
 */

/** Sunucu ("S") mesaj kodları. */
object ServerCode {
    const val LOGIN = 1
    const val SET_WAIT_PORT = 2
    const val GET_PEER_ADDRESS = 3
    const val WATCH_USER = 5
    const val UNWATCH_USER = 6
    const val GET_USER_STATUS = 7
    const val SAY_CHATROOM = 13
    const val JOIN_ROOM = 14
    const val LEAVE_ROOM = 15
    const val USER_JOINED_ROOM = 16
    const val USER_LEFT_ROOM = 17
    const val CONNECT_TO_PEER = 18
    const val MESSAGE_USER = 22
    const val MESSAGE_ACKED = 23
    const val FILE_SEARCH = 26
    const val SET_STATUS = 28
    const val SERVER_PING = 32
    const val SHARED_FOLDERS_FILES = 35
    const val GET_USER_STATS = 36
    const val RELOGGED = 41
    const val USER_SEARCH = 42
    const val ROOM_LIST = 64
    const val ADMIN_MESSAGE = 66
    const val PRIVILEGED_USERS = 69
    const val HAVE_NO_PARENT = 71
    const val PARENT_MIN_SPEED = 83
    const val PARENT_SPEED_RATIO = 84
    const val CHECK_PRIVILEGES = 92
    const val EMBEDDED_MESSAGE = 93
    const val ACCEPT_CHILDREN = 100
    const val POSSIBLE_PARENTS = 102
    const val WISHLIST_SEARCH = 103
    const val WISHLIST_INTERVAL = 104
    const val ROOM_TICKER_STATE = 113
    const val ROOM_TICKER_ADD = 114
    const val ROOM_TICKER_REMOVE = 115
    const val ROOM_TICKER_SET = 116
    const val ROOM_SEARCH = 120
    const val SEND_UPLOAD_SPEED = 121
    const val BRANCH_LEVEL = 126
    const val BRANCH_ROOT = 127
    const val CHILD_DEPTH = 129
    const val RESET_DISTRIBUTED = 130
    const val EXCLUDED_SEARCH_PHRASES = 160
    const val CANT_CONNECT_TO_PEER = 1001
    const val CANT_CREATE_ROOM = 1003
}

/** Eş ("P") mesaj kodları. */
object PeerCode {
    const val SHARED_FILE_LIST_REQUEST = 4
    const val SHARED_FILE_LIST_RESPONSE = 5
    const val FILE_SEARCH_REQUEST = 8
    const val FILE_SEARCH_RESPONSE = 9
    const val USER_INFO_REQUEST = 15
    const val USER_INFO_RESPONSE = 16
    const val FOLDER_CONTENTS_REQUEST = 36
    const val FOLDER_CONTENTS_RESPONSE = 37
    const val TRANSFER_REQUEST = 40
    const val TRANSFER_RESPONSE = 41
    const val PLACEHOLD_UPLOAD = 42
    const val QUEUE_UPLOAD = 43
    const val PLACE_IN_QUEUE_RESPONSE = 44
    const val UPLOAD_FAILED = 46
    const val UPLOAD_DENIED = 50
    const val PLACE_IN_QUEUE_REQUEST = 51
    const val UPLOAD_QUEUE_NOTIFICATION = 52
}

/** Bağlantı başlangıç mesajı kodları (uint8). */
object InitCode {
    const val PIERCE_FIREWALL = 0
    const val PEER_INIT = 1
}

/** Dağıtık ("D") mesaj kodları (uint8). */
object DistribCode {
    const val PING = 0
    const val SEARCH = 3
    const val BRANCH_LEVEL = 4
    const val BRANCH_ROOT = 5
    const val CHILD_DEPTH = 7
    const val EMBEDDED_MESSAGE = 93

    /** DistribSearch gövdesindeki sabit tanımlayıcı (ASCII '1'). */
    const val SEARCH_IDENTIFIER = 49
}

/** Eş bağlantı türleri. */
object ConnType {
    const val PEER = "P"
    const val FILE = "F"
    const val DISTRIBUTED = "D"

    fun isValid(type: String): Boolean = type == PEER || type == FILE || type == DISTRIBUTED
}

/** Transfer reddi nedenleri (protokolde serbest metin; bunlar Nicotine+ sabitleridir). */
object TransferReason {
    const val QUEUED = "Queued"
    const val COMPLETE = "Complete"
    const val CANCELLED = "Cancelled"
    const val FILE_READ_ERROR = "File read error."
    const val FILE_NOT_SHARED = "File not shared."
    const val BANNED = "Banned"
    const val PENDING_SHUTDOWN = "Pending shutdown."
    const val TOO_MANY_FILES = "Too many files"
    const val TOO_MANY_MEGABYTES = "Too many megabytes"
    const val DISALLOWED_EXTENSION = "Disallowed extension"
    const val USER_LIMIT_PREFIX = "User limit of"

    /** Nicotine+'ın iç durum metinleri; bunlar uzak taraftan gelirse "Cancelled" sayılır. */
    val INTERNAL_STATUSES: Set<String> = setOf(
        "Queued", "Getting status", "Transferring", "Paused", "Cancelled", "Filtered", "Finished",
        "User logged off", "Connection closed", "Connection timeout", "Download folder error", "Local file error",
    )
}

/** Giriş reddi nedenleri. */
object LoginFailure {
    const val INVALID_USERNAME = "INVALIDUSERNAME"
    const val EMPTY_PASSWORD = "EMPTYPASSWORD"
    const val INVALID_PASSWORD = "INVALIDPASS"
    const val INVALID_VERSION = "INVALIDVERSION"
    const val SERVER_FULL = "SVRFULL"
    const val SERVER_PRIVATE = "SVRPRIVATE"
}
