package com.soulseekplus.core.protocol

import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.UserStats
import com.soulseekplus.core.model.UserStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class ProtocolMessagesTest {

    // ------------------------------------------------------------------------------------------------- yardımcılar

    private fun serverRoundTrip(message: ServerMessage): ServerMessage? {
        val frame = message.encode()
        val r = MessageReader(frame)
        val length = r.uint32()
        val code = r.int32()
        assertEquals("uzunluk alanı", (frame.size - 4).toLong(), length)
        assertEquals(message.code, code)
        return ServerMessages.decode(code, r.rest())
    }

    private fun peerRoundTrip(message: PeerMessage): PeerMessage? {
        val frame = message.encode()
        val r = MessageReader(frame)
        val length = r.uint32()
        val code = r.int32()
        assertEquals((frame.size - 4).toLong(), length)
        assertEquals(message.code, code)
        return PeerMessages.decode(code, r.rest())
    }

    private fun payloadOf(message: ServerMessage): ByteArray = MessageReader(message.encode()).also { it.skip(8) }.rest()

    private fun assertServerRoundTrip(message: ServerMessage) {
        assertEquals(message, serverRoundTrip(message))
    }

    // ------------------------------------------------------------------------------------------------- istemci → sunucu

    @Test
    fun loginRequestLayout() {
        val frame = LoginRequest("alice", "secret").encode()
        val r = MessageReader(frame)
        r.uint32()
        assertEquals(ServerCode.LOGIN, r.int32())
        assertEquals("alice", r.string())
        assertEquals("secret", r.string())
        assertEquals(160L, r.uint32())
        val expected = MessageDigest.getInstance("MD5").digest("alicesecret".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, r.string())
        assertEquals(3L, r.uint32())
        assertEquals(0, r.remaining)
    }

    @Test
    fun clientRequestsHaveExpectedCodesAndPayloads() {
        assertEquals(ServerCode.SET_WAIT_PORT, SetWaitPort(2234).code)
        assertArrayEquals(byteArrayOf(0xBA.toByte(), 0x08, 0, 0), payloadOf(SetWaitPort(2234)))
        assertEquals(ServerCode.SHARED_FOLDERS_FILES, SharedFoldersFiles(3, 4).code)
        val r = MessageReader(payloadOf(SharedFoldersFiles(3, 4)))
        assertEquals(3L, r.uint32())
        assertEquals(4L, r.uint32())
        val fs = MessageReader(payloadOf(FileSearchRequest(77, "daft punk")))
        assertEquals(77, fs.int32())
        assertEquals("daft punk", fs.string())
        val us = MessageReader(payloadOf(UserSearchRequest("bob", 5, "x")))
        assertEquals("bob", us.string())
        assertEquals(5, us.int32())
        assertEquals("x", us.string())
        assertEquals(0, payloadOf(ServerPing).size)
        assertEquals(1, payloadOf(HaveNoParent(true)).size)
        assertEquals(ServerCode.MESSAGE_ACKED, MessageAcked(9).code)
        val sp = MessageReader(payloadOf(SendUploadSpeed(123456)))
        assertEquals(123456L, sp.uint32())
        val jr = MessageReader(payloadOf(JoinRoomRequest("Nicotine", true)))
        assertEquals("Nicotine", jr.string())
        assertEquals(1L, jr.uint32())
        val ctp = MessageReader(payloadOf(ConnectToPeerRequest(-2, "carol", "F")))
        assertEquals(-2, ctp.int32())
        assertEquals("carol", ctp.string())
        assertEquals("F", ctp.string())
    }

    // ------------------------------------------------------------------------------------------------- sunucu → istemci

    @Test
    fun loginResponses() {
        assertServerRoundTrip(LoginSuccess("Welcome", "82.94.1.200", "abc123", true))
        assertServerRoundTrip(LoginRejected("INVALIDPASS", null))
        assertServerRoundTrip(LoginRejected("INVALIDUSERNAME", "too long"))
    }

    @Test
    fun userMessages() {
        assertServerRoundTrip(PeerAddressResponse("bob", "10.1.2.3", 2234, 1, 2235))
        assertServerRoundTrip(WatchUserResponse("bob", true, UserStatus.ONLINE, UserStats(5000, 12, 300, 40), "TR"))
        assertServerRoundTrip(WatchUserResponse("ghost", false))
        assertServerRoundTrip(UserStatusResponse("bob", UserStatus.AWAY, true))
        assertServerRoundTrip(UserStatsResponse("bob", UserStats(1, 2, 3, 4)))
        assertServerRoundTrip(PrivateMessageNotification(7, 1_700_000_000L, "bob", "hello ça va", true))
        assertServerRoundTrip(SearchRequestNotification("bob", -5, "query text"))
        assertServerRoundTrip(ConnectToPeerNotification("bob", "P", "1.2.3.4", 2234, 99, true, 0, 0))
        assertServerRoundTrip(CantConnectToPeerNotification(12, "bob"))
        assertEquals(ReloggedNotification, serverRoundTrip(ReloggedNotification))
    }

    @Test
    fun roomMessages() {
        val users = listOf(
            RoomUser("a", UserStatus.ONLINE, UserStats(1, 2, 3, 4), slotsFull = true, countryCode = "DE"),
            RoomUser("b", UserStatus.AWAY, UserStats(0, 0, 0, 0), slotsFull = false, countryCode = null),
        )
        assertServerRoundTrip(JoinRoomResponse("room", users))
        assertServerRoundTrip(JoinRoomResponse("secret", users, owner = "a", operators = listOf("b")))
        assertServerRoundTrip(UserJoinedRoomNotification("room", users[0]))
        assertServerRoundTrip(UserLeftRoomNotification("room", "a"))
        assertServerRoundTrip(LeaveRoomNotification("room"))
        assertServerRoundTrip(RoomMessageNotification("room", "a", "hi"))
        assertServerRoundTrip(RoomTickerAddNotification("room", "a", "ticker"))
        assertServerRoundTrip(RoomTickerRemoveNotification("room", "a"))
        assertServerRoundTrip(RoomTickerStateNotification("room", linkedMapOf("a" to "x", "b" to "y")))
        assertServerRoundTrip(
            RoomListResponse(
                listOf(
                    RoomInfo("Public", 10), RoomInfo("Mine", 2, isPrivate = true, isOwned = true),
                    RoomInfo("Member", 3, isPrivate = true), RoomInfo("Op", 0, isPrivate = true, isOperated = true),
                ),
            ),
        )
        assertServerRoundTrip(CantCreateRoomNotification("bad"))
    }

    @Test
    fun networkNotifications() {
        assertServerRoundTrip(PrivilegedUsersNotification(listOf("x", "y")))
        assertServerRoundTrip(ParentMinSpeedNotification(1))
        assertServerRoundTrip(ParentSpeedRatioNotification(50))
        assertServerRoundTrip(PrivilegesResponse(86_400))
        assertServerRoundTrip(WishlistIntervalNotification(720))
        assertServerRoundTrip(ResetDistributedNotification)
        assertServerRoundTrip(ExcludedSearchPhrasesNotification(listOf("a b", "c")))
        assertServerRoundTrip(AdminMessageNotification("maintenance"))
        assertServerRoundTrip(
            PossibleParentsNotification(listOf(PossibleParent("p1", "1.2.3.4", 2234), PossibleParent("p2", "9.8.7.6", 1))),
        )
        assertEquals(EmbeddedMessageNotification(3, byteArrayOf(1, 2, 3)), serverRoundTrip(EmbeddedMessageNotification(3, byteArrayOf(1, 2, 3))))
    }

    @Test
    fun unknownServerCodeIsIgnoredAndTruncatedBodyThrows() {
        assertNull(ServerMessages.decode(9999, byteArrayOf(1, 2, 3)))
        try {
            ServerMessages.decode(ServerCode.LEAVE_ROOM, byteArrayOf(5, 0, 0, 0, 1))
            fail("ProtocolException bekleniyordu")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun hostileListCountsAreRejectedWithoutAllocation() {
        // 0xFFFFFFFF öğe iddia eden ama gövdesi olmayan liste.
        val body = MessageWriter().uint32(0xFFFFFFFFL).toByteArray()
        try {
            ServerMessages.decode(ServerCode.PRIVILEGED_USERS, body)
            fail("ProtocolException bekleniyordu")
        } catch (_: ProtocolException) {
        }
    }

    // ------------------------------------------------------------------------------------------------- eş mesajları

    private val mp3 = SharedFile("Music\\Album\\01 Song.mp3", 5_000_000, "mp3", FileAttributes(bitrate = 320, durationSeconds = 200, vbr = false))
    private val flac = SharedFile("Music\\Album\\02 Lossless.flac", 30_000_000_000L, "flac", FileAttributes(durationSeconds = 300, sampleRate = 44100, bitDepth = 16))

    @Test
    fun searchResponseCompressedRoundTrip() {
        val message = FileSearchResponseMessage("bob", 4242, listOf(mp3, flac), listOf(mp3.copy(path = "Locked\\x.mp3")), true, 123_456, 7)
        val decoded = peerRoundTrip(message) as FileSearchResponseMessage
        assertEquals("bob", decoded.username)
        assertEquals(4242, decoded.token)
        assertEquals(2, decoded.files.size)
        assertEquals(mp3.path, decoded.files[0].path)
        assertEquals(320, decoded.files[0].attributes.bitrate)
        assertEquals(200, decoded.files[0].attributes.durationSeconds)
        assertEquals(false, decoded.files[0].attributes.vbr)
        assertEquals(30_000_000_000L, decoded.files[1].size)
        assertEquals(44100, decoded.files[1].attributes.sampleRate)
        assertEquals(16, decoded.files[1].attributes.bitDepth)
        assertEquals(1, decoded.lockedFiles.size)
        assertTrue(decoded.freeSlots)
        assertEquals(123_456L, decoded.uploadSpeed)
        assertEquals(7L, decoded.queueLength)
    }

    @Test
    fun searchResponseHeaderCanBePeekedWithoutFullDecode() {
        val payload = MessageReader(FileSearchResponseMessage("bob", -17, listOf(mp3), emptyList(), false, 1, 2).encode()).also { it.skip(8) }.rest()
        val header = PeerMessages.peekSearchResponse(payload)
        assertNotNull(header)
        assertEquals("bob", header!!.username)
        assertEquals(-17, header.token)
        assertNull(PeerMessages.peekSearchResponse(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun sharedFileListRoundTripIncludingLockedFolders() {
        val folders = listOf(SharedFolder("Music\\Album", listOf(mp3, flac)), SharedFolder("Music\\Empty", emptyList()))
        val locked = listOf(SharedFolder("Private\\Stuff", listOf(mp3.copy(path = "Private\\Stuff\\a.mp3"))))
        val decoded = peerRoundTrip(SharedFileListResponse(folders, locked)) as SharedFileListResponse
        assertEquals(listOf("Music\\Album", "Music\\Empty"), decoded.folders.map { it.path })
        assertEquals(listOf(mp3.path, flac.path), decoded.folders[0].files.map { it.path })
        assertEquals(0, decoded.folders[1].files.size)
        assertEquals(listOf("Private\\Stuff"), decoded.lockedFolders.map { it.path })
        assertEquals("Private\\Stuff\\a.mp3", decoded.lockedFolders[0].files[0].path)
    }

    @Test
    fun folderContentsRoundTrip() {
        val decoded = peerRoundTrip(FolderContentsResponse(31, "Music\\Album", listOf(SharedFolder("Music\\Album", listOf(mp3))))) as FolderContentsResponse
        assertEquals(31, decoded.token)
        assertEquals("Music\\Album", decoded.folder)
        assertEquals(mp3.path, decoded.folders[0].files[0].path)
        assertEquals(FolderContentsRequest(5, "A\\B"), peerRoundTrip(FolderContentsRequest(5, "A\\B")))
    }

    @Test
    fun fileSizeQuirkWithHighByteSetKeepsLow32Bits() {
        val w = MessageWriter()
        w.uint32(1)
        w.uint8(1).string("x.bin").uint64((0xFFL shl 56) or 5_000L).string("").uint32(0)
        val files = SharesCodec.readFolderFileList(MessageReader(w.toByteArray()), "dir")
        assertEquals(5_000L, files[0].size)
        assertEquals("dir\\x.bin", files[0].path)
    }

    @Test
    fun transferMessagesRoundTrip() {
        assertEquals(TransferRequest(1, 55, "A\\b.mp3", 123_456_789_012L), peerRoundTrip(TransferRequest(1, 55, "A\\b.mp3", 123_456_789_012L)))
        assertEquals(TransferRequest(0, 56, "A\\b.mp3", 0), peerRoundTrip(TransferRequest(0, 56, "A\\b.mp3")))
        assertEquals(TransferResponse(55, true, null, null), peerRoundTrip(TransferResponse(55, true)))
        assertEquals(TransferResponse(55, true, null, 99L), peerRoundTrip(TransferResponse(55, true, size = 99)))
        assertEquals(TransferResponse(55, false, "Queued"), peerRoundTrip(TransferResponse(55, false, "Queued")))
        assertEquals(QueueUpload("A\\b.mp3"), peerRoundTrip(QueueUpload("A\\b.mp3")))
        assertEquals(PlaceInQueueRequest("A\\b.mp3"), peerRoundTrip(PlaceInQueueRequest("A\\b.mp3")))
        assertEquals(PlaceInQueueResponse("A\\b.mp3", 12), peerRoundTrip(PlaceInQueueResponse("A\\b.mp3", 12)))
        assertEquals(UploadDenied("A\\b.mp3", "File not shared."), peerRoundTrip(UploadDenied("A\\b.mp3", "File not shared.")))
        assertEquals(UploadFailed("A\\b.mp3"), peerRoundTrip(UploadFailed("A\\b.mp3")))
    }

    @Test
    fun legacyQueueUploadIsLatin1() {
        val legacy = MessageReader(QueueUpload("Café\\x.mp3", legacy = true).encode()).also { it.skip(8) }.rest()
        // Latin-1: é tek bayt (0xE9); dizgi uzunluğu 10.
        assertEquals(10, MessageReader(legacy).uint32().toInt())
        assertTrue(legacy.contains(0xE9.toByte()))
        // Katı UTF-8 geçersiz olduğundan okuyucu Latin-1'e düşer ve aynı metni verir.
        assertEquals("Café\\x.mp3", (PeerMessages.decode(PeerCode.QUEUE_UPLOAD, legacy) as QueueUpload).path)
    }

    @Test
    fun userInfoRoundTrip() {
        val picture = ByteArray(300) { it.toByte() }
        val decoded = peerRoundTrip(UserInfoResponse("hi there", picture, 2, 10, true, 1)) as UserInfoResponse
        assertEquals("hi there", decoded.description)
        assertArrayEquals(picture, decoded.picture)
        assertEquals(2, decoded.totalSlots)
        assertEquals(10, decoded.queueSize)
        assertTrue(decoded.slotsFree)
        assertEquals(1, decoded.uploadAllowed)
        val noPicture = peerRoundTrip(UserInfoResponse("x", null, 1, 0, false, null)) as UserInfoResponse
        assertNull(noPicture.picture)
        assertNull(noPicture.uploadAllowed)
        assertEquals(UserInfoRequest, peerRoundTrip(UserInfoRequest))
        assertEquals(SharedFileListRequest, peerRoundTrip(SharedFileListRequest))
    }

    @Test
    fun decompressionBombIsRejected() {
        val bomb = Frame.compress(ByteArray(8 * 1024 * 1024))
        assertTrue(bomb.size < 100_000)
        try {
            Frame.decompress(bomb, 1024 * 1024)
            fail("ProtocolException bekleniyordu")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun peerSizeLimitsFollowTheMessageType() {
        assertEquals(Frame.MAX_PEER_DEFAULT, PeerMessages.maxIncomingSize(PeerCode.TRANSFER_REQUEST))
        assertEquals(Frame.MAX_PEER_HUGE, PeerMessages.maxIncomingSize(PeerCode.SHARED_FILE_LIST_RESPONSE))
        assertTrue(PeerMessages.isKnownCode(PeerCode.QUEUE_UPLOAD))
        assertTrue(!PeerMessages.isKnownCode(9999))
    }

    // ------------------------------------------------------------------------------------------------- başlangıç ve dağıtık

    @Test
    fun initMessagesRoundTrip() {
        for (m in listOf(PierceFireWall(0x7FFFFFFF), PeerInit("alice", "F", 0), PeerInit("bob", "P", 12))) {
            val frame = m.encode()
            val r = MessageReader(frame)
            assertEquals((frame.size - 4).toLong(), r.uint32())
            val code = r.uint8()
            assertEquals(m, InitMessages.decode(code, r.rest()))
        }
        // Başlangıç çerçevesi tek baytlık kod kullanır: uzunluk(4) + kod(1) + token(4).
        assertEquals(9, PierceFireWall(1).encode().size)
    }

    @Test
    fun distributedMessagesRoundTrip() {
        for (m in listOf<DistributedMessage>(DistribPing, DistribBranchLevel(3), DistribBranchRoot("root"), DistribChildDepth(2))) {
            val frame = m.encode()
            val r = MessageReader(frame)
            assertEquals((frame.size - 4).toLong(), r.uint32())
            assertEquals(m, DistributedMessages.decode(r.uint8(), r.rest()))
        }
        val search = DistribSearch("bob", 77, "some query")
        val frame = search.encode()
        val r = MessageReader(frame)
        r.uint32()
        assertEquals(DistribCode.SEARCH, r.uint8())
        val body = r.rest()
        assertEquals(search, DistributedMessages.decodeSearch(body))
        // Alınan gövde çocuklara değişmeden iletilir.
        assertArrayEquals(frame, DistributedMessages.searchFrame(body))
    }

    @Test
    fun distributedSearchWithWrongIdentifierIsRejected() {
        val body = MessageWriter().uint32(7).string("bob").int32(1).string("q").toByteArray()
        try {
            DistributedMessages.decodeSearch(body)
            fail("ProtocolException bekleniyordu")
        } catch (_: ProtocolException) {
        }
        assertTrue(!DistributedMessages.isValidUsername("server"))
        assertTrue(!DistributedMessages.isValidUsername(""))
        assertTrue(DistributedMessages.isValidUsername("alice"))
    }
}
