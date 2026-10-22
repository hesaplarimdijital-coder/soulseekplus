# Soulseek protocol reference (derived from Nicotine+ pynicotine, authoritative)

Source files: `slskmessages.py` (codes/fields), `slskproto.py` (framing, connections, distributed),
`downloads.py`/`uploads.py`/`transfers.py` (transfer flows), `search.py`/`shares.py` (search/shares),
`portmapper.py` (NAT-PMP/UPnP), `users.py`/`config.py` (login flow/defaults).
"N+" = Nicotine+. All numbers below are verified against that code. Items marked (N+ choice) are N+
behaviour rather than protocol requirements.

---------------------------------------------------------------------------------------------------
## 1. Wire format basics

* Byte order: LITTLE-ENDIAN for every integer (`struct "<I"`, `"<i"`, `"<Q"`, `"<H"`).
* Every message on S/P/D/init connections is length-prefixed: `uint32 length` = number of bytes
  FOLLOWING the length field (i.e. code + payload). Total bytes on wire = length + 4.
* Primitive types

| Type | Encoding |
|---|---|
| bool | 1 byte, 0/1 (read: nonzero = true) |
| uint8 | 1 byte |
| uint16 | 2 bytes LE (only seen in GetPeerAddress obfuscated port) |
| int32 / uint32 | 4 bytes LE (signed / unsigned). Tokens are uint32. |
| uint64 | 8 bytes LE (file sizes, file offset) |
| string | `uint32 byteLength` + raw bytes. NO terminator. |
| bytes | same as string, raw (user picture) |
| ip | 4 bytes. Wire bytes b0 b1 b2 b3 => dotted IP `b3.b2.b1.b0` (i.e. the uint32 LE value, written as big-endian dotted quad; equivalently reverse the 4 bytes then inet_ntoa) |

* String encoding
  * SEND: UTF-8 (`encode("utf-8","replace")`).
  * RECEIVE: try strict UTF-8; on failure decode as latin-1 (ISO-8859-1).
  * "Legacy" send (is_legacy=True) used ONLY for QueueUpload.file, PlaceInQueueRequest.file,
    FolderContentsRequest.dir when retrying against old Soulseek NS clients: encode latin-1; if
    not encodable fall back to UTF-8 (replace). Triggered after `UploadDenied "File not shared."`
    (retry once) or after `UploadFailed` (retry once) -- see section 7.
* Paths in the protocol use backslash `\` as separator. N+ converts incoming `/` to `\` in
  share/search/folder listings when parsing. Local filenames containing a literal backslash are
  substituted with the sentinel `@@BACKSLASH@@` in shared virtual paths (N+ share scanner).
* Message code width / frame layout per connection type

| Connection | Frame |
|---|---|
| Server (S) | `uint32 len` `uint32 code` payload  (len = 4 + payload) |
| Peer init (first message on any peer socket) | `uint32 len` `uint8 code` payload (len = 1 + payload) |
| Peer "P" (after init) | `uint32 len` `uint32 code` payload (len = 4 + payload) |
| Distributed "D" (after init) | `uint32 len` `uint8 code` payload (len = 1 + payload) |
| File "F" (after init) | NO framing. Raw: uploader sends `uint32 token`; downloader sends `uint64 offset`; then raw file bytes. |

* Compression: zlib (RFC1950 wrapper, `zlib.compress(data, 4)`; use standard `Inflater`, not
  nowrap) on the WHOLE payload (after the code) for: SharedFileListResponse(5),
  FileSearchResponse(9), FolderContentsResponse(37). Everything else uncompressed.
* Incoming size limits enforced by N+ (close connection if exceeded):
  server 448 MiB; peer-init 16 KiB; distributed 16 KiB; peer "P": default 1 MiB,
  FileSearchResponse/FolderContentsResponse/unknown 16 MiB, SharedFileListResponse/UserInfoResponse
  448 MiB. SharedFileListResponse and UserInfoResponse are only accepted if solicited from that user
  (else N+ closes the connection). Decompressed caps: shares 2 GiB; search/folder 128 MiB.
* Unknown message code: skip `len` bytes and keep connection open (all types).
* Parse errors in one message: log, skip (message already fully framed), keep connection.

---------------------------------------------------------------------------------------------------
## 2. Server connection and login

* Default server: `server.slsknet.org` port `2242` (TCP). Default listen port `2234`.
* TCP_NODELAY on all sockets. N+ uses TCP keepalive on the server socket: idle 10 s, interval 2 s,
  10 probes (TCP_USER_TIMEOUT 30 s). N+ does NOT send ServerPing(32) at all. If implementing, send
  at most once per minute (empty body, no response is sent by server anymore).
* Reconnect (N+): first retry random 5..15 s, then doubles each failure, capped when >=300 s;
  fixed 5 s if listen port bind failed / manual reconnect. No reconnect after manual disconnect,
  Relogged, or login rejection.
* Bring-up order on socket connect (N+ sends both immediately, before waiting for response):
  1. `Login(1)`
  2. `SetWaitPort(2)` (just the listen port)
  Only Login may be sent while unauthenticated (N+ drops other messages until connected).
* After Login success response, N+ sends: `HaveNoParent(71, true)`, `BranchRoot(127, ownUsername)`,
  `BranchLevel(126, 0)`, `AcceptChildren(100, false)`; then app level: `CheckPrivileges(92)`,
  `SetStatus(28, 2 or 1)`, `WatchUser(5, ownUsername)` + `GetUserStatus(7, ownUsername)` (WatchUser response gives our avg upload speed, used
  for distributed child limit and FileSearchResponse.ulspeed), `SharedFoldersFiles(35, dirs, files)`.
  Also `PrivilegedUsers(69)`, `ParentMinSpeed(83)`, `ParentSpeedRatio(84)`, `WishlistInterval(104)`,
  `ExcludedSearchPhrases(160)`, `RoomList(64)` etc. arrive unsolicited after login.
* Listen port: N+ sends `SetWaitPort` with plain port, no obfuscation. (SoulseekQt additionally
  sends `uint32 obfuscationType(1)`, `uint32 obfuscatedPort`; optional, N+ does not implement
  obfuscation. Soulseek.NET also sends only the port.) ObfuscationType: 0 none, 1 rotated.

### Login (code 1)
SEND fields in order:

| # | Type | Value |
|---|---|---|
| 1 | string | username |
| 2 | string | password |
| 3 | uint32 | client major version = **160** (N+ reserved; NS/SoulseekQt use 157, Soulseek.NET/slskd 170; experimental clients may use 177) |
| 4 | string | lowercase hex MD5 of `username + password` (UTF-8 bytes concatenated, 32 chars) |
| 5 | uint32 | minor version = **3** (N+: 1 >=2.2.1, 2 >=3.3.0, 3 >=3.4.0) |

RECEIVE:

| # | Type | Notes |
|---|---|---|
| 1 | bool | success |
| failure: 2 | string | reason, one of `INVALIDUSERNAME`, `EMPTYPASSWORD`, `INVALIDPASS`, `INVALIDVERSION`, `SVRFULL`, `SVRPRIVATE` |
| failure: 3 | string | OPTIONAL detail (present only if bytes remain); for INVALIDUSERNAME it describes why |
| success: 2 | string | greeting / banner (MOTD) |
| success: 3 | ip | our external IP as seen by server |
| success: 4 | string | MD5 hexdigest of the password we sent (checksum, ignorable) |
| success: 5 | bool | is_supporter (privileged/donor flag) |

On failure N+ treats the server connection as closed and does not auto-retry.

### Status codes (UserStatus)
`0 offline`, `1 away`, `2 online`. SetStatus sends `int32` (1 or 2). Server does NOT echo a status
update when we go from away back to online (N+ fakes a local update).

---------------------------------------------------------------------------------------------------
## 3. Server messages  (frame: uint32 len, uint32 code)

Direction: C->S = we send; S->C = we receive; both = same code, different body per direction.
"(obs)" = obsolete/no-op. Strings are UTF-8 (see 1). `u32` = uint32, `i32` = int32.

| Code | Name | Dir | Fields (in order) |
|---|---|---|---|
| 1 | Login | C->S | string user, string pass, u32 major(160), string md5hex(user+pass), u32 minor(3) |
| 1 | Login | S->C | see section 2 |
| 2 | SetWaitPort | C->S | u32 port  (SoulseekQt: + u32 obfType + u32 obfPort) |
| 3 | GetPeerAddress | C->S | string user |
| 3 | GetPeerAddress | S->C | string user, ip address, u32 port, u32 obfuscationType, u16 obfuscatedPort. ip `0.0.0.0` => user offline. port 0 => server doesn't know port yet. |
| 5 | WatchUser | C->S | string user |
| 5 | WatchUser | S->C | string user, bool exists; if exists: u32 status, u32 avgspeed, u32 uploadnum, u32 unknown, u32 files, u32 dirs; then ONLY if bytes remain (user online): string countryCode. If offline the body ends after dirs. |
| 6 | UnwatchUser | C->S | string user |
| 7 | GetUserStatus | C->S | string user (N+ sends after WatchUser to learn privileged flag) |
| 7 | GetUserStatus | S->C | string user, u32 status, bool privileged. Pushed whenever a watched user (or room member) changes status. |
| 11 | IgnoreUser | both | string user (obs) |
| 12 | UnignoreUser | both | string user (obs) |
| 13 | SayChatroom | C->S | string room, string message |
| 13 | SayChatroom | S->C | string room, string user, string message |
| 14 | JoinRoom | C->S | string room, u32 private (0 public / 1 create-or-join private; N+ always writes this field) |
| 14 | JoinRoom | S->C | string room, then USER LIST (below); then ONLY if bytes remain (private room): string owner, u32 numOperators, numOperators x string operator |
| 15 | LeaveRoom | both | string room (S->C echo confirms leaving) |
| 16 | UserJoinedRoom | S->C | string room, string user, u32 status, u32 avgspeed, u32 uploadnum, u32 unknown, u32 files, u32 dirs, u32 slotsfull, string countryCode |
| 17 | UserLeftRoom | S->C | string room, string user |
| 18 | ConnectToPeer | C->S | u32 token, string user, string connType ("P"/"F"/"D") |
| 18 | ConnectToPeer | S->C | string user, string connType, ip address, u32 port, u32 token, bool privileged, u32 obfuscationType, u32 obfuscatedPort |
| 22 | MessageUser | C->S | string user, string message |
| 22 | MessageUser | S->C | u32 messageId, u32 timestamp(unix s), string user, string message, bool isNew (false = replay of an older queued message) |
| 23 | MessageAcked | C->S | u32 messageId (N+ sends immediately on each received MessageUser; else server re-sends) |
| 25 | FileSearchRoom | C->S | u32 token, u32 roomId, string text (obs, superseded by 120) |
| 26 | FileSearch | C->S | u32 token, string query |
| 26 | FileSearch | S->C | string searchUsername, u32 token, string query. Server pushes this (outside the distributed network) for UserSearch(42)/RoomSearch(120) requests aimed at us. Handled exactly like DistribSearch. |
| 28 | SetStatus | C->S | i32 status (1 away, 2 online) |
| 32 | ServerPing | C->S | (empty) |
| 33 | SendConnectToken | both | string user, u32 token (obs) |
| 34 | SendDownloadSpeed | C->S | string user, u32 speed (obs; superseded by 121) |
| 35 | SharedFoldersFiles | C->S | u32 numFolders, u32 numFiles |
| 36 | GetUserStats | C->S | string user |
| 36 | GetUserStats | S->C | string user, u32 avgspeed, u32 uploadnum, u32 unknown, u32 files, u32 dirs. Pushed for room members. avgspeed in bytes/s. |
| 40 | UploadSlotsFull | C->S | u32 slotsfull (obs); S->C: string user, u32 slotsfull (obs) |
| 41 | Relogged | S->C | (empty). Someone else logged in with our name; server disconnects us. |
| 42 | UserSearch | C->S | string targetUser, u32 token, string query  (target receives it as FileSearch 26) |
| 50 | SimilarRecommendations | both | C->S string rec; S->C string rec, u32 n, n x string (obs) |
| 51 | AddThingILike | C->S | string item |
| 52 | RemoveThingILike | C->S | string item |
| 54 | Recommendations | C->S empty; S->C | RECOMMENDATION LIST (below) |
| 55 | MyRecommendations | both | C->S empty; S->C u32 n, n x string (obs) |
| 56 | GlobalRecommendations | C->S empty; S->C | RECOMMENDATION LIST |
| 57 | UserInterests | C->S | string user |
| 57 | UserInterests | S->C | string user, u32 nLikes, nLikes x string, u32 nHates, nHates x string |
| 58 | AdminCommand | C->S | string cmd, u32 n, n x string arg (obs) |
| 59 | PlaceInLineRequest | C->S | string user, u32 token (obs) |
| 60 | PlaceInLineResponse | both | string user, u32 token, u32 place (obs) |
| 62 | RoomAdded | S->C | string room (obs) |
| 63 | RoomRemoved | S->C | string room (obs) |
| 64 | RoomList | C->S | (empty) |
| 64 | RoomList | S->C | see ROOM LIST below |
| 65 | ExactFileSearch | C->S | u32 token, string file, string folder, u64 size, u32 checksum, u8 unknown (obs) |
| 66 | AdminMessage | S->C | string message |
| 67 | GlobalUserList | C->S empty; S->C USER LIST (obs) |
| 68 | TunneledMessage | both | (obs) |
| 69 | PrivilegedUsers | S->C | u32 n, n x string user |
| 71 | HaveNoParent | C->S | bool haveNoParent (true = we need a parent) |
| 73 | ParentIP | C->S | u32 ip (obs/deprecated) |
| 83 | ParentMinSpeed | S->C | u32 minSpeed |
| 84 | ParentSpeedRatio | S->C | u32 ratio |
| 86,87,88,90,91 | ParentInactivityTimeout, SearchInactivityTimeout, MinParentsInCache, DistribPingInterval, AddToPrivileged | S->C | single u32 (86,87,88,90) or string user (91); obs, ignore |
| 92 | CheckPrivileges | C->S | (empty) |
| 92 | CheckPrivileges | S->C | u32 secondsRemaining of privileges |
| 93 | EmbeddedMessage | S->C | u8 distribCode, then the raw distributed-message body (see section 6). Only distribCode 3 (DistribSearch) is used. |
| 100 | AcceptChildren | C->S | bool accept |
| 102 | PossibleParents | S->C | u32 n, n x { string user, ip address, u32 port } (max 10) |
| 103 | WishlistSearch | C->S | u32 token, string query |
| 104 | WishlistInterval | S->C | u32 seconds |
| 110 | SimilarUsers | C->S empty; S->C | u32 n, n x { string user, u32 rating } |
| 111 | ItemRecommendations | C->S | string item |
| 111 | ItemRecommendations | S->C | string item, RECOMMENDATION LIST |
| 112 | ItemSimilarUsers | C->S | string item |
| 112 | ItemSimilarUsers | S->C | string item, u32 n, n x string user |
| 113 | RoomTickerState | S->C | string room, u32 n, n x { string user, string ticker }  (sent on join) |
| 114 | RoomTickerAdd | S->C | string room, string user, string ticker |
| 115 | RoomTickerRemove | S->C | string room, string user |
| 116 | RoomTickerSet | C->S | string room, string ticker (empty string removes ours) |
| 117 | AddThingIHate | C->S | string item |
| 118 | RemoveThingIHate | C->S | string item |
| 120 | RoomSearch | C->S | string room, u32 token, string query |
| 121 | SendUploadSpeed | C->S | u32 speed (bytes/s, average of the just-finished upload) |
| 122 | UserPrivileged | both | C->S string user; S->C string user, bool privileged (deprecated) |
| 123 | GivePrivileges | C->S | string user, u32 days |
| 124 | NotifyPrivileges | both | u32 token, string user (deprecated) |
| 125 | AckNotifyPrivileges | both | u32 token (deprecated) |
| 126 | BranchLevel | C->S | u32 level |
| 127 | BranchRoot | C->S | string rootUsername |
| 129 | ChildDepth | C->S | u32 depth (obs) |
| 130 | ResetDistributed | S->C | (empty) |
| 133 | RoomMembers | S->C | string room, u32 n, n x string user (private room, excludes owner) |
| 134 | AddRoomMember | both | string room, string user |
| 135 | RemoveRoomMember | both | string room, string user |
| 136 | CancelRoomMembership | C->S | string room |
| 137 | CancelRoomOwnership | C->S | string room |
| 138 | RoomSomething | both | string room (obs) |
| 139 | RoomMembershipGranted | S->C | string room |
| 140 | RoomMembershipRevoked | S->C | string room |
| 141 | EnableRoomInvitations | both | bool enabled |
| 142 | ChangePassword | both | string password |
| 143 | AddRoomOperator | both | string room, string user |
| 144 | RemoveRoomOperator | both | string room, string user |
| 145 | RoomOperatorshipGranted | S->C | string room |
| 146 | RoomOperatorshipRevoked | S->C | string room |
| 148 | RoomOperators | S->C | string room, u32 n, n x string user |
| 149 | MessageUsers | C->S | u32 n, n x string user, string message |
| 150 | JoinGlobalRoom | C->S | (empty)  (public room feed) |
| 151 | LeaveGlobalRoom | C->S | (empty) |
| 152 | GlobalRoomMessage | S->C | string room, string user, string message |
| 153 | RelatedSearch | both | C->S string query; S->C string query, u32 n, n x { string term, u32 score } (obs) |
| 160 | ExcludedSearchPhrases | S->C | u32 n, n x string phrase. Files whose lowercased path contains a phrase must not be returned in search results. |
| 1001 | CantConnectToPeer | C->S | u32 token, string user |
| 1001 | CantConnectToPeer | S->C | u32 token (N+ reads only the token; a trailing string username may follow) |
| 1003 | CantCreateRoom | S->C | string room |

### Compound structures

**USER LIST (JoinRoom S->C, GlobalUserList)** -- struct-of-arrays, NOT per-user records:
```
u32 numUsers;      numUsers x string username
u32 numStatuses;   numStatuses x u32 status
u32 numStats;      numStats x { u32 avgspeed, u32 uploadnum, u32 unknown, u32 files, u32 dirs }
u32 numSlotsFull;  numSlotsFull x u32 slotsfull
u32 numCountries;  numCountries x string countryCode
```
Index i of each array belongs to username[i]. (Counts normally all equal numUsers.)
`avgspeed` = server's average upload speed for that user (bytes/s).

**RECOMMENDATION LIST**: `u32 n; n x { string item, i32 rating }`; then, if bytes remain, a second
identical list. rating >= 0 => recommendation, rating < 0 => unrecommendation (N+ merges both lists
and splits by sign).

**ROOM LIST (code 64 S->C)**:
```
u32 nRooms;        nRooms x string room          // public rooms
u32 nCounts;       nCounts x u32 userCount       // parallel to the list above
u32 nOwned;        nOwned x string room          // private rooms we own
u32 nOwnedCounts;  nOwnedCounts x u32 userCount
u32 nMember;       nMember x string room         // private rooms we are a member of
u32 nMemberCounts; nMemberCounts x u32 userCount
u32 nOperator;     nOperator x string room       // private rooms we operate (NO counts array)
```
On login server only lists rooms with >=5 users; sending RoomList(64) returns the full list.

**JoinRoom room-name rules** (server enforces; violations produce a private message from "server"):
non-empty, ASCII only, <=24 chars, no leading/trailing space, no consecutive spaces.

**ConnectToPeer (18) response semantics**: `token` is the token the REQUESTER chose; `user` is the
requester; `ip/port` is the requester's listening address; `privileged` indicates the requester has
privileges (N+ caches it for upload-queue priority). `obfuscationType/obfuscatedPort` ignored by N+.
If connType not in {"P","F","D"} ignore.

**GetUserStatus.privileged**: true if user is a privileged/supporter user. N+ maintains a set from
`PrivilegedUsers`, `GetUserStatus.privileged`, and `ConnectToPeer.privileged`.

**WatchUser** note: server does not push GetUserStats for watched users, only the initial stats in
the WatchUser response (stats can be stale). Status changes are pushed via GetUserStatus. When a
watched user transitions offline->online N+ re-requests GetUserStats and GetPeerAddress.

**User address cache (N+)**: GetPeerAddress result cached per watched user; entries expire after
1800 s; port 0 or offline status invalidates; GetUserStatus(offline) clears.

---------------------------------------------------------------------------------------------------
## 4. Peer connections: init messages and handshake

Every peer TCP connection (either direction) starts with exactly one init message:

| Code (u8) | Name | Fields |
|---|---|---|
| 0 | PierceFireWall | u32 token |
| 1 | PeerInit | string ourUsername, string connType, u32 token (always 0; ignored on receive) |

Frame: `u32 len(=1+payload)`, `u8 code`, payload. Connection types: `"P"` (peer messages),
`"F"` (file transfer), `"D"` (distributed). Anything else => reject/close.

### Receiving PeerInit validation (N+)
Username byte size must be 1..256, must not equal `"server"`, must be printable; connType must be
P/F/D; else close. For P and D connections N+ keeps ONE connection per (username, connType): a new
inbound PeerInit for an existing key replaces (closes) the old connection and inherits its pending
outgoing messages. F connections are never deduplicated. Connection to ourselves is allowed.

### Direct connection (we initiate, N+ order)
1. Need peer's address: from cache or send `GetPeerAddress(3, user)` and wait. `0.0.0.0` => offline;
   report failure with is_offline.
2. With the address do BOTH simultaneously (N+ choice; SoulseekQt sends ConnectToPeer at the same
   time as GetPeerAddress). The indirect request is always sent; the direct attempt is skipped if
   port is not in 1..65535:
   a. Send `ConnectToPeer(18, token=++indirectToken, user, connType)` to server. Remember token ->
      pending init for 20 s.
   b. Open TCP to ip:port. Direct attempt must reach "established" within **2 s** else abandoned.
3. On direct TCP established: send `PeerInit(ourUsername, connType, 0)`, then queued messages. The
   remote side sends nothing in response to PeerInit (it just starts processing messages).
4. If the indirect request's PierceFireWall arrives first, the direct attempt is cancelled and the
   pierced socket becomes primary. If direct was already established, the pierced socket is kept
   open as secondary; a message received on it promotes it to primary.
5. If neither works within 20 s (indirect timeout), or server sends `CantConnectToPeer(token)`, the
   connection attempt fails; queued messages are reported as failed (downloads -> "Connection
   timeout"; offline -> "User logged off").

### Indirect connection, we are the target (we receive ConnectToPeer 18 from server)
1. Server delivers `ConnectToPeer(user=requester, connType, ip, port, token, privileged, ...)`.
2. We open TCP to requester ip:port (must establish within 2 s).
3. On established: FIRST send `PierceFireWall(token)` (the token from the message, not ours). Do NOT
   send PeerInit. Then act as normal peer for that user+connType (including accepting their messages).
4. If TCP fails/times out: send `CantConnectToPeer(1001, token, user)` to server.

### Indirect connection, we are the requester (we sent ConnectToPeer)
The remote connects to OUR listen port and sends `PierceFireWall(token)` (u32 token, init code 0).
Look up token in pending requests; unknown/expired token => close socket. Else this socket is the
connection for (user, connType); flush queued messages onto it. No PeerInit is exchanged in this
direction. For "F": we (uploader) then send `u32 fileToken` raw (see section 7).

### Idle/ghost timeouts (N+)
* Non-server connections closed after 60 s without traffic. "Ghost" connection (never saw any
  message after init) closed after 10 s. Connection-limit pressure closes idle P connections first.
* After receiving a search result (FileSearchResponse) N+ closes that P connection if nothing is
  pending to send (so result connections don't accumulate).
* F and D connections are treated as critical (not closed on pressure) but still subject to idle
  timeout when no data moves.
* On server disconnect all peer connections are closed.

### Who accepts what
* Inbound sockets on our listen port: first message is PeerInit (code 1) or PierceFireWall (code 0).
* Message dispatch after init is decided by the connType from PeerInit (or from the pending request
  for PierceFireWall).

---------------------------------------------------------------------------------------------------
## 5. Peer ("P") messages  (frame: u32 len, u32 code)

| Code | Name | Dir | Body |
|---|---|---|---|
| 4 | SharedFileListRequest | both | (empty) |
| 5 | SharedFileListResponse | both | zlib( SHARED LIST ) |
| 8 | FileSearchRequest | both | u32 token, string query (obs, ignore) |
| 9 | FileSearchResponse | send/recv | zlib( SEARCH RESULT ) |
| 15 | UserInfoRequest | both | (empty) |
| 16 | UserInfoResponse | both | USER INFO |
| 36 | FolderContentsRequest | both | u32 token, string folder |
| 37 | FolderContentsResponse | both | zlib( u32 token, string folder, u32 numDirs, numDirs x { string dir, FILE LIST } ) |
| 40 | TransferRequest | both | u32 direction, u32 token, string virtualPath, [u64 fileSize ONLY if direction==1] |
| 41 | TransferResponse | both | u32 token, bool allowed, then if !allowed: string reason; if allowed: [u64 fileSize OPTIONAL] |
| 42 | PlaceholdUpload | both | string file (obs) |
| 43 | QueueUpload | both | string virtualPath |
| 44 | PlaceInQueueResponse | both | string virtualPath, u32 place |
| 46 | UploadFailed | both | string virtualPath |
| 50 | UploadDenied | both | string virtualPath, string reason |
| 51 | PlaceInQueueRequest | both | string virtualPath |
| 52 | UploadQueueNotification | both | (empty; deprecated, sent by SoulseekNS, N+ never sends) |
| 12547 | (unknown) | recv | ignore |

### FILE ENTRY (used in SHARED LIST / SEARCH RESULT / FOLDER CONTENTS)
```
u8     code            // always 1
string filename        // SharedList/FolderContents: basename within dir.  SearchResult: FULL virtual path "root\dir\file.ext"
u64    size
u32    extLen          // N+ sends 0; on receive skip extLen bytes
u32    numAttrs
numAttrs x { u32 attrCode, u32 attrValue }
```
* File-size quirk (Soulseek NS bug): if byte 7 of the 8-byte size (the most significant byte) equals
  0xFF, the file is >2 GiB mangled: read only the first uint32 as the size and skip the other 4
  bytes. Otherwise read the full uint64.
* Attribute codes (FileAttribute): 0 bitrate (kbps), 1 length (seconds), 2 VBR (0/1), 3 encoder
  (obsolete, ignore), 4 sample rate (Hz), 5 bit depth.
* N+ SEND order: lossless files (bitDepth known): [1 length][4 sampleRate][5 bitDepth] (only those
  present). Lossy files: [0 bitrate][1 length][2 vbr] where vbr is emitted only if bitrate exists.
  (SoulseekQt/others may order differently; parse by code, any order.)
* Interpretation on receive (`parse_audio_quality_length`):
  * bitrate missing but sampleRate and bitDepth present => bitrate = sampleRate*bitDepth*2/1000 kbps
    (stereo assumption).
  * length missing and bitrate > 0 => length = fileSize / (bitrate*125) seconds (estimate).
  * bitrate <= 0 or > uint32 max => treated as unknown (0).
  * Quality string: sampleRate && bitDepth => "44.1 kHz / 16 bit"; else "<bitrate> kbps" plus
    " (vbr)" if vbr==1.
* FILE LIST = `u32 numFiles; numFiles x FILE ENTRY`.

### SHARED LIST (code 5 body, after zlib inflate)
```
u32 numDirs
numDirs x { string dirPath ("Root\Sub\Folder"), FILE LIST }
u32 unknown            // official clients and N+ send 0
[ u32 numPrivateDirs   // OPTIONAL: present only if bytes remain
  numPrivateDirs x { string dirPath, FILE LIST } ]
```
* The optional trailing section lists private/buddy-only (and trusted-only) folders that exist but
  that THIS requester is not allowed to download (N+ includes them only when "reveal buddy/trusted
  shares" is enabled). Display as locked; downloading them gets "File not shared." / "Banned".
* N+ sorts dirs by name and files by name after parsing. Dir paths use `\`.
* N+ response content depends on requester permission: public always; buddy shares added for
  buddies/trusted; trusted shares added for trusted. A request from the same user within 0.4 s of
  the previous one is ignored. Banned users: no response.
* Request: we must remember we asked (N+ whitelists the responding username); unsolicited
  responses make N+ drop the connection.

### SEARCH RESULT (code 9 body, after zlib inflate)
```
string username        // the RESPONDER's username (our-side: the peer who has the files)
u32    token           // search token of the original query
u32    numResults
numResults x FILE ENTRY        // filename = full virtual path
bool   freeUploadSlots // peer has a free slot right now
u32    uploadSpeed     // peer's avg upload speed, bytes/s (from server stats)
u32    queueLength     // peer's current upload queue length for us
u32    unknown         // N+ sends 0; reader: present if bytes remain
[ u32 numPrivate       // OPTIONAL, only if bytes remain after unknown
  numPrivate x FILE ENTRY ]   // "private" (locked) results we can see but not download
```
Receive optimisation N+ uses: inflate just enough to read username + token; if token is not an
active search token, discard (do not inflate/parse the rest). Limit 128 MiB inflated.
Sender (N+) sorts results by path, includes at most `maxresults` (default 300) files, and only
sends if >=1 result.

### USER INFO (code 16)
```
string description
bool   hasPicture
[ bytes picture ]            // u32 len + data, only if hasPicture
u32    totalUploadSlots      // N+: get_total_uploads_allowed()
u32    queueSize             // current upload queue size (privilege-aware)
bool   slotsFree
u32    uploadAllowed         // OPTIONAL: read only if >=4 bytes remain (Museek+ sends garbage after slotsFree)
```
uploadAllowed values (who may send files to this user): 0 no one, 1 everyone, 2 friends, 3 trusted
friends. N+ sends 0 when remote downloads disabled.

### FolderContents
Request: `u32 token, string folderPath` (backslash path exactly as in the share listing). N+ answers
with ONE dir: `u32 token, string folder, u32 1, string folder, FILE LIST` (basenames, only that
folder, no subfolders); unknown folder or banned => `u32 token, string folder, u32 0` (empty).
N+ requester: 5 s timeout, retries once, and if the first response has zero dirs retries once more
with a legacy latin-1 folder string.

### TransferRequest / TransferResponse
* `direction`: 0 = Download (requester wants to download from receiver; legacy), 1 = Upload
  (sender is ready to upload to receiver; modern flow).
* direction 1 body: `u32 1, u32 token, string path, u64 fileSize`.
  direction 0 body: `u32 0, u32 token, string path` (NO size).
* TransferResponse ALLOWED: `u32 token, bool true [, u64 fileSize]`. fileSize present only when
  responding to a direction-0 request with an immediately-granted slot (N+ never does this; when
  N+ accepts a direction-1 request it sends `u32 token, bool true` with no size).
  Parse: size read only if bytes remain.
* TransferResponse REJECTED: `u32 token, bool false, string reason`.
* Reasons (TransferRejectReason): `"Queued"`, `"Complete"`, `"Cancelled"`, `"File read error."`,
  `"File not shared."`, `"Banned"`, `"Pending shutdown."`, `"Too many files"`,
  `"Too many megabytes"`, `"Disallowed extension"`. Also free-form like `"User limit of N megabytes
  exceeded"` (downloader treats prefix "User limit of" like Too many megabytes), and
  `"Banned (custom message)"`.
* N+ rejects a direction-1 TransferRequest (i.e. someone pushing a file to us) with `Cancelled`
  (unknown file), `Complete` (already downloaded). It accepts unknown pushed files only if remote
  uploads are enabled and uploadAllowed is friends/trusted and sender qualifies.

### QueueUpload / PlaceInQueue / UploadDenied / UploadFailed
* QueueUpload(43): `string path` -- the exact virtual path from the browse/search listing.
* Receiver replies asynchronously, possibly never:
  * UploadDenied(50) `string path, string reason` -- terminal; reasons as above. Standard replies from
    N+: `"File not shared."`, `"Banned"` (+" (msg)"), `"Pending shutdown."`, `"Too many files"`,
    `"Too many megabytes"`, `"File read error."`, `"Cancelled"`(file removed from queue),
    `"Complete"`. If already queued: NO reply (silently ignored).
  * later `TransferRequest(direction=1)` when the upload starts (section 7).
* PlaceInQueueRequest(51) `string path` -> PlaceInQueueResponse(44) `string path, u32 place` (1-based;
  N+ sends nothing if the file isn't queued for that user or place would be 0). N+ polls every 300 s
  for all queued downloads.
* UploadFailed(46) `string path`: sent by an uploader when the F connection of an active upload
  closes before all bytes were sent (unless user offline). Recipient (downloader) should re-queue
  (N+: once, with legacy latin-1 path; second failure => status "Connection closed").
* Downloader reaction table for UploadDenied: "File not shared." and not yet legacy => requeue once
  with latin-1 encoded path; "Too many files"/"Too many megabytes"/prefix "User limit of" =>
  show as Queued, retry later (N+ re-enqueues up to max(5, queued-1) files at a time); anything else
  => failed with that reason. Never accept internal status strings ("Queued","Getting status",
  "Transferring","Paused","Cancelled","Filtered","Finished","User logged off","Connection closed",
  "Connection timeout","Download folder error","Local file error") as arbitrary reasons -> treat
  as "Cancelled".

---------------------------------------------------------------------------------------------------
## 6. Distributed network ("D" connections)

Frame: `u32 len(=1+payload)`, `u8 code`, payload. Body strings as usual.

| Code | Name | Body |
|---|---|---|
| 0 | DistribPing | (empty; deprecated, N+ ignores/never sends) |
| 3 | DistribSearch | `u32 identifier (=49, ASCII '1'; reject any other), string searchUsername, u32 token, string query` |
| 4 | DistribBranchLevel | `i32 level` (reject negative -> close) |
| 5 | DistribBranchRoot | `string rootUsername` (reject if empty, >256 bytes, "server", or non-printable) |
| 7 | DistribChildDepth | `u32 depth` (obsolete; ignore) |
| 93 | DistribEmbeddedMessage | `u8 distribCode (older SoulseekQt: u32 -- if first 3 bytes are 0x000000 skip them), raw body of that distributed message`. Deprecated; handle as DistribSearch wrapper. |

Server `EmbeddedMessage(93)` body = `u8 distribCode` + body (same as D body of that code). Only
distribCode 3 is meaningful; its body parses like DistribSearch above. Nothing else is honoured.
Forwarding to children: send frame `len, u8 3, <same body bytes>` unmodified (raw copy).

### Parent selection (N+)
* `PossibleParents(102)` is only processed while we have no parent: close any previous candidate
  sockets, then open a "D" connection (normal direct+indirect procedure, section 4) to ALL listed
  candidates at once (connection errors are common).
* A candidate that connects sends us `DistribBranchLevel` and `DistribBranchRoot` first (when it
  accepts us as child it immediately sends level then root). Record per candidate: `branch_level`,
  `branch_root` (if candidate sends level 0, its root = the candidate itself, a separate
  BranchRoot message is not guaranteed).
* The FIRST candidate that then sends us a DistribSearch (or Embedded wrapping a search) AND has
  both level and root recorded becomes our parent (a candidate with missing level/root is not
  adopted). On adoption:
  * `branchLevel = parent.level + 1`, `branchRoot = parent.root`, `is_server_parent=false`
  * send server: `HaveNoParent(false)`, `BranchRoot(root)`, `BranchLevel(level)`, and
    `AcceptChildren(true)` if child slots available
  * send children `DistribBranchLevel(level)` and `DistribBranchRoot(root)`
  * close all other candidate sockets.
* After a parent exists: DistribSearch/BranchLevel/BranchRoot arriving on any D socket that is not
  the parent socket => close that socket. From the parent: BranchLevel update => level = value+1,
  forward `BranchLevel(level)` to server and children; BranchRoot update => forward to server and
  children.
* Parent socket closes (or `ResetDistributed(130)`): close parent + all children, then
  `HaveNoParent(true)`, `BranchRoot(ownUsername)`, `BranchLevel(0)`, `AcceptChildren(false)` to the
  server and level=0, root=own username, parent=none.
* If the server starts sending `EmbeddedMessage(93)` (and we have no peer parent) we are a branch
  root: set `is_server_parent`, `AcceptChildren(true)` if slots available, level stays 0, root =
  own username. Embedded messages are ignored if we do have a peer parent.

### Children
* Child limit: `maxChildren = min(uploadSpeed / speedRatio / 100, 10)` using integer division, where
  `uploadSpeed` = our avgspeed from server stats, `speedRatio` from ParentSpeedRatio(84); if
  `uploadSpeed < ParentMinSpeed(83)` or ratio==0 => 0. (10 is N+'s socket-count cap.)
* Accept an inbound or pierced "D" connection as a child only if: not from ourselves, not a
  candidate parent, we have a parent (or are server-parent), not duplicate child user, and
  `children < maxChildren`. Otherwise close it.
* On adopting child: send `DistribBranchLevel(ourLevel)`, then `DistribBranchRoot(ourRoot)`. When
  count reaches max send `AcceptChildren(false)`; when a child leaves and count == max-1 send
  `AcceptChildren(true)`. Recompute whenever min speed / ratio / our speed changes.
* Branch root also relays embedded searches: for each EmbeddedMessage(DistribSearch) forward the
  raw body to every child with code 3.
* The search itself is also processed locally (section 9).
* DistribPing/ChildDepth: N+ neither sends nor needs them.

---------------------------------------------------------------------------------------------------
## 7. File transfer ("F" connection) and transfer flows

### F connection byte sequence (after the usual PeerInit "F" / PierceFireWall handshake)
```
UPLOADER ----> DOWNLOADER : u32 token                 (4 bytes raw, the token from the TransferRequest)
DOWNLOADER ---> UPLOADER  : u64 offset                (8 bytes raw, bytes already on disk; 0 = start)
UPLOADER ----> DOWNLOADER : raw file bytes from `offset` to end (size - offset bytes)
```
* No length prefix and no message codes on F connections. After the token+offset, only raw data
  flows uploader->downloader. The downloader sends nothing else.
* Who opens the TCP socket: in N+ the UPLOADER always initiates the F connection (direct: sends
  `PeerInit(uploaderName,"F",0)`; or indirect: `ConnectToPeer(...,"F")` via server and downloader
  connects back and sends `PierceFireWall`). Either way the uploader writes the token FIRST, the
  downloader replies with the offset. (Receiving side dispatches purely on byte count: first 4
  bytes = token, next 8 bytes = offset.)
* Downloader validations: token must match an active (accepted) transfer for that username and the
  transfer must not already have a socket; else close.
* Resume: downloader opens (or creates) the partial "incomplete" file, offset = its current length.
  If `offset >= size`: close the connection and treat as finished (N+). Uploader seeks to `offset`
  (error/invalid offset => close and fail). Soulseek NS bug: offsets >2 GiB may arrive as
  0xFFFFFFFFFFFFFFFF (-1); treat as invalid / restart from 0.
* Completion: downloader knows remaining = size - offset; when received it, closes socket.
  Uploader treats upload as finished if the connection closes after all bytes (offset+sent==size);
  if it closes early (and the downloader isn't offline) uploader sends `UploadFailed(path)` on the
  P connection.
* Idle: N+ closes F connections after 60 s without any data in either direction.
* Transfer timer: from sending/accepting TransferRequest until F traffic starts, N+ waits **45 s**
  (slowest known client needs ~30 s before falling back to indirect, plus 15 s margin) then marks
  "Connection timeout" and retries every 180 s.

### Modern download flow (what N+ does as downloader)
1. `QueueUpload(path)` over P connection to the uploader (virtual path exactly as listed;
   latin-1 variant on retry). Watch the user (WatchUser) so we learn if they go offline.
2. Uploader may answer `UploadDenied` (terminal / retry rules in section 5), or
   `PlaceInQueueResponse`. We poll `PlaceInQueueRequest` every 300 s.
3. When the uploader has a slot it sends `TransferRequest(direction=1, token, path, size)`.
   Our handling:
   * If we have that file queued/failed: dequeue, store `token`, update size if `size > 0`
     (if differs from cached size mark size_changed; if size==0 (SoulseekQt >2 GiB bug) keep the
     cached size), reply `TransferResponse(token, allowed=true)` (no size). Start the 45 s timer.
   * Else reply `TransferResponse(token, false, "Cancelled")` (or "Complete" if already finished).
4. The uploader opens the F connection and sends `u32 token` (see above). Match token ->
   transfer, open partial file, reply `u64 offset`, then read `size - offset` bytes, write to the
   partial file, close socket when complete, move to final destination.
5. If the socket closes before `size` bytes: status "Cancelled" (or "User logged off"); N+ then
   waits for UploadFailed / retries; connection-type failures are retried every 3 minutes;
   local I/O failures every 15 minutes.
6. Tokens are chosen by the UPLOADER; the downloader just echoes them.

### Legacy download flow (downloader-initiated TransferRequest direction 0; used by slskd/Seeker/NS)
1. Downloader sends `TransferRequest(direction=0, token=T, path)` (no size).
2. Uploader (N+) enqueues the file and replies `TransferResponse(T, false, "Queued")`. (Other
   uploaders may reply `TransferResponse(T, true, u64 size)` when a slot is free; N+ never does,
   and as downloader N+ never expects it. If you implement that branch, the same F byte sequence
   applies: uploader sends token, downloader sends offset.)
3. Later the uploader sends a NEW `TransferRequest(direction=1, token=T2, path, size)`; from here
   it is identical to the modern flow steps 3-6. Downloader must accept direction-1 requests for
   files it requested via direction 0 (match on username+path).
N+ as uploader supports receiving direction-0 requests exactly as described (it checks the same
permission/queue/limit rules as QueueUpload, rejects with the rejection reason, or enqueues and
replies "Queued").

### Upload flow (N+ as uploader)
1. On `QueueUpload(path)` (or direction-0 TransferRequest): check ban => `UploadDenied "Banned"`;
   shares rescanning => defer; already queued/active => ignore; `pending_shutdown` =>
   `"Pending shutdown."`; queue limits (files >= filelimit(100) => `"Too many files"`; queued bytes
   >= queuelimit(10000 MiB) => `"Too many megabytes"`; skipped for buddies when friendsnolimits);
   file not shared/readable => `"File not shared."` (N+ also tries lowercase-path hack for NS and
   the `@@BACKSLASH@@` sentinel). Else enqueue silently (no reply).
2. When a slot is free (queue check every 10 s and after every transfer event) pick candidate
   (section 8), verify user is not offline, file still shared and readable (`File not shared.`
   / `File read error.` via UploadDenied if not), re-stat size, allocate `token = ++token`
   (initial random 0..UINT32/1000), send `TransferRequest(direction=1, token, path, size)`.
3. Wait for `TransferResponse(token, ...)`:
   * allowed => open a NEW F connection (direct+indirect procedure, connType "F") and send
     `u32 token` raw; then wait for `u64 offset`; seek; stream the file. Any filesize in the
     response is ignored.
   * rejected: reasons "Queued" => (keep as failed/queued state, status = reason);
     "Complete" => mark finished (peer already has it); "Cancelled" => cancel; any other reason
     is shown; internal statuses mapped to "Cancelled". Then re-check queue.
   * no response / F connection never arrives within 45 s => "Connection timeout" (auto-requeued
     every 3 min).
4. After upload completes (F closed with all bytes sent and not timed out): send
   `SendUploadSpeed(avg)` to the server where avg = transferredBytesTotal / max(1, elapsedSeconds)
   (bytes/s, only if > 0). That is the only upload-speed reporting.
   If the connection closed early: send `UploadFailed(path)` to the peer.
5. Bandwidth limiting (optional, N+): per-transfer or global split, minimum 1 KiB/s per transfer.

---------------------------------------------------------------------------------------------------
## 8. Upload queue behaviour (N+ defaults in parentheses)

* Slot test `is_new_upload_accepted()`: false if shares are rescanning. If `useupslots` (true): false
  when `len(active_users) >= uploadslots` (3; <=0 treated as 1) -- NOTE this counts DISTINCT USERS
  with active uploads, not files (a user has at most one active upload at a time, see below).
  If `useupslots` false: false when current total upload bandwidth >= `uploadbandwidth` KiB/s (50).
* `freeulslots` in FileSearchResponse = this boolean. `UserInfoResponse.slotsFree` likewise.
  `UserInfoResponse.totalUploadSlots` = uploadslots when useupslots else `len(active_users)` (+1 if
  currently accepting).
* Queue length reported to a user (`inqueue` in search results and UserInfo.queueSize):
  if the user is privileged: total queued files across all privileged users; else total queued files
  of everybody.
* Privileged = in server PrivilegedUsers/flags OR a buddy marked prioritized (or all buddies if
  `preferfriends`). If ANY waiting user is privileged, only privileged users are considered for the
  next slot (non-privileged starve until privileged queue empties).
* Per-user ordering: files for a given user are served in the order they were queued.
* Candidate selection (`_get_upload_candidate`), runs when a slot is free:
  * ROUND ROBIN (default, `fifoqueue=false`): maintain `userCounters: username -> int` using a global
    monotonically increasing counter. A user gets a (new) counter value when: they have queued files,
    no active upload, and an upload for them is queued/updated/aborted/finished -- EXCEPT when the
    update is for a file being queued while the user already has a counter (queuing more files
    never moves the user back). Counter removed when the user's upload is activated or they have no
    queued files left. Pick the user with the SMALLEST counter among eligible (privileged filter),
    then that user's first queued file. Result: a user who just finished goes to the back of the
    line; users alternate; only one upload per user at a time.
  * FIFO (`fifoqueue=true`): iterate all queued files in global enqueue order, first one whose user
    has a counter entry (i.e. is not currently uploading) and passes the privileged filter.
* Queue position reported to PlaceInQueueRequest (only for files currently queued; else no reply;
  0 not sent):
  * FIFO: privileged requester => 1-based index among queued files of privileged users in global
    order; non-privileged => 1-based index among ALL queued files in global order.
  * ROUND ROBIN: `pos = indexInUsersOwnQueue(1-based) + numQueuedUsers`, where for a privileged
    requester `numQueuedUsers` = count of privileged users with queued files; for a non-privileged
    requester `numQueuedUsers` = count of ALL users with queued files AND additionally
    `+ sum(queuedFileCount of each privileged user)`.
* Queue limits per user: see section 7 step 1.
* Slots are re-evaluated every 10 s (`_check_upload_queue` timer) and immediately after any upload
  finishing/failing/aborting, TransferResponse, new QueueUpload, user going offline, etc.
* If the user to upload to is offline (status 0 from watch) the queued item is failed with
  "User logged off"; when the user returns failed items are re-queued/cancelled by N+.
* `pending_shutdown`: stop answering searches, reject new queueing with "Pending shutdown.".
* Share scan in progress: QueueUpload messages are held and replayed after scan completes.

---------------------------------------------------------------------------------------------------
## 9. Search

### Outgoing (we search)
* Token: `increment_token` (start random 0..UINT32_MAX/1000; +1 each; wraps to 1 after u32 max).
  Register the token as expected BEFORE sending (responses with unknown token are discarded).
* Modes:
  * Global: `FileSearch(26) u32 token, string query` to server (server fans out via distributed net).
  * Room: `RoomSearch(120) string room, u32 token, string query`.
  * Buddies: one `UserSearch(42) string user, u32 token, string query` per buddy, same token.
  * User: `UserSearch(42)` for each target user.
  * Wishlist: `WishlistSearch(103) u32 token, string query` (same body as FileSearch).
* Query preparation (N+): shlex-style split with `"` quoting; `-word` = exclude, `*word` = partial,
  `"phrase"` kept as one included term locally but transmitted as its inner words with these
  characters replaced by space: `! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~ –
  — ‐ ’ “ ” …`. Words beginning with `*` or `-` are transmitted unchanged (server/peers interpret
  them). Transmitted term = words joined by single spaces (if sanitizing yields empty, keep the
  original trimmed string).
* Results arrive as peer-initiated connections to us (P connection, remote sends PeerInit "P" or
  PierceFireWall with the ConnectToPeer token, then `FileSearchResponse(9)`), usually unsolicited
  and many. Match by token, parse section 5 SEARCH RESULT, apply local filters (ignored users,
  banned IPs, wishlist ignore). Keep our listen port reachable or rely on indirect.
* Removing a search: forget token so later responses are discarded.
* Wishlist: server sends `WishlistInterval(104) u32 seconds` (after login; official server
  currently sends a value like several minutes -- do not hardcode). N+ schedules a repeating timer
  at that interval (re-armed whenever a new interval arrives; ignored if 0). At each tick it
  sends ONE `WishlistSearch(token, query)` for the next wish in round-robin order (rotates the
  wish dict: pop first, push to end; skips wishes with auto_search disabled). Each wish has a
  permanent token (assigned at creation) re-registered as an allowed token before each send.
  Results arrive as normal FileSearchResponse messages with that token.

### Incoming (others search us)
Sources: `FileSearch(26)` pushed by server (fields: string searchUsername, u32 token, string query)
and `DistribSearch` from our parent (section 6) and `EmbeddedMessage` if we are branch root. All go
through one function `process_search_request(query, username, token)`:
1. Ignore if: query empty; search responses disabled; `pending_shutdown`; `maxresults <= 0`;
   `len(query) < min_search_chars (3)`; requester is banned; share index not loaded;
   `username == ourUsername` UNLESS the token belongs to a search we made against ourselves.
2. `query = lower(query)`.
3. If the query contains `-` or `*`: split on whitespace; for each word starting with `-` add the
   words of `word` (punctuation-to-space translated, split) to `excluded`; if starting with `*`
   add to `partial`.
4. Replace all punctuation (ASCII + Unicode punctuation set incl. `- * . _ / \ ' " ( ) [ ] ...`) with
   spaces, split on whitespace => `included = set(words) - excluded - partial`.
5. Word index: during scan each file contributes lowercase words from (virtual folder path words +
   basename words), split by the same punctuation translation, de-duplicated per file.
   (Folder names are therefore searchable; "Artist Album track" can match across path.)
6. Match: every `included` word must exist EXACTLY as a word in the index (else no results); need
   at least one included word (partial/exclusion-only queries return nothing). Result set =
   intersection of file-sets of included words. Then for each partial word `p`: collect files
   reachable via any index word `w` with `len(w) >= len(p)` and `w.endswith(p)` (suffix match, so
   `*ello` matches "hello" and "ello"), restricted to current result set; none => no results;
   intersect. Then subtract files of each excluded word that exists in the index.
   Single-word queries are truncated to the first `maxresults` hits early.
7. Truncate to `maxresults` (300). Drop files whose lowercase path contains any server-provided
   excluded phrase (ExcludedSearchPhrases). Permission handling: public files returned; buddy/trusted
   files returned for qualified requesters; otherwise optionally placed in the `private` result
   list (only if reveal_buddy/trusted_shares). Sort by virtual path.
8. If total results == 0 => no reply. Else send `FileSearchResponse(9)` to the requesting USERNAME over
   a P connection (direct PeerInit / indirect via ConnectToPeer, section 4):
   `username=ourName, token, results, freeUploadSlots=is_new_upload_accepted(),
   uploadSpeed=ourAvgSpeedFromServer, queueLength=get_upload_queue_size(requester), unknown=0,
   [private list]`, zlib level 4.
9. The response connection is closed by N+ after use if nothing else pending.

---------------------------------------------------------------------------------------------------
## 10. Keepalive, timeouts, status, misc constants

| Item | Value |
|---|---|
| Server keepalive | TCP keepalive idle 10 s / interval 2 s / 10 probes (N+). ServerPing(32) not used by N+. |
| Direct peer connect timeout | 2 s (to reach established) |
| Indirect (ConnectToPeer) timeout | 20 s |
| Peer connection idle timeout | 60 s; "ghost" (no post-init traffic) 10 s |
| Transfer start timeout | 45 s after TransferRequest / TransferResponse |
| Upload queue re-check | every 10 s |
| Retry failed (timeout/closed) uploads | every 180 s |
| Retry failed connection downloads | every 180 s; local I/O failures 900 s |
| Request queue positions (downloads) | every 300 s |
| Folder contents request timeout | 5 s, retry once |
| Browse (SharedFileListRequest) rate limit | ignore repeats within 0.4 s from same user |
| User address cache TTL | 1800 s |
| Server reconnect | 5..15 s random, doubling, cap ~300 s; fixed 5 s on port-bind failure |
| Login version | major 160, minor 3 |
| Listen port | 2234 (user configurable); announced via SetWaitPort |
| Max sockets (N+) | min(0.66 * fdLimit, 2048) |

* **Relogged (41)**: server then closes the socket. N+ marks manual disconnect (NO auto reconnect),
  logs "Someone logged in to your Soulseek account elsewhere".
* **Status transitions**: set 1 (away) / 2 (online) via SetStatus; server does not echo going back
  online. For watched users, GetUserStatus offline => clear cached address and fail their queued
  transfers ("User logged off"); online again => retry.
* **Tokens**: `increment_token`: if token<0 or >= 2^32, token=0; then +1. Separate token counters
  for: indirect connection requests, uploads (TransferRequest), searches/wishes, folder requests.
  Initial value random in [0, 2^32/1000].
* **Obfuscation**: not implemented by N+. GetPeerAddress/ConnectToPeer carry obfuscation fields
  that N+ ignores; always connect to the plain `port`.
* **Peer connection hygiene**: One P and one D connection per user (new inbound replaces old).
  Messages for a user are queued until the connection is established; failures are reported back
  per queued message so downloads/uploads can mark themselves failed.

### Port mapping (portmapper.py) -- optional
Strategy: try NAT-PMP first; if it throws/times out, try UPnP IGD. Lease 43200 s (12 h); renew
every 7200 s (2 h). Performed after successful login (blocking), removed on server disconnect.
External port = internal port = listen port, TCP only. Local IP: the interface IP used to reach the
LAN (`connect` a UDP socket to 10.255.255.255:1 and read `getsockname`), bind UDP sockets to it.

**NAT-PMP (RFC 6886)**
* Gateway: default route gateway (Linux `/proc/net/route` dest 0.0.0.0; elsewhere `netstat -rn`).
  On Android use `ConnectivityManager`/`LinkProperties` default route.
* UDP to `gateway:5351`. Request, network byte order (big-endian), 12 bytes:
  `u8 version=0, u8 opcode=2 (map TCP), u16 reserved=0, u16 privatePort, u16 suggestedPublicPort,
  u32 lifetime` (Python `'!BBHHHI'`). Add: public=private=listenPort, lifetime=43200.
  Remove: publicPort=0, lifetime=0 (privatePort = listenPort).
* Response 16 bytes (`'!BBHIHHI'`): `u8 version, u8 opcode(130), u16 resultCode, u32 secondsSinceEpoch,
  u16 privatePort, u16 publicPort, u32 lifetime`. resultCode 0 = success; else error.
  N+ ignores the returned mapped public port (assumes equal).
* Timing: 2 attempts, socket timeout 250 ms then 500 ms.

**UPnP IGD**
1. SSDP discovery: UDP socket bound to the local IP, `IP_MULTICAST_IF`=local IP, multicast TTL 2,
   socket timeout 1.1 s. Send M-SEARCH to `239.255.255.250:1900` once for each ST:
   `urn:schemas-upnp-org:device:InternetGatewayDevice:2`, `urn:schemas-upnp-org:service:WANIPConnection:2`,
   `urn:schemas-upnp-org:device:InternetGatewayDevice:1`, `urn:schemas-upnp-org:service:WANIPConnection:1`,
   `urn:schemas-upnp-org:service:WANPPPConnection:1`.
   Request text (CRLF lines, blank line at end):
   ```
   M-SEARCH * HTTP/1.1
   HOST: 239.255.255.250:1900
   ST: <search target>
   MAN: "ssdp:discover"
   MX: 1
   USER-AGENT: <anything>

   ```
   Read responses until a 1.1 s receive timeout. For every distinct `LOCATION:` header, HTTP GET the
   device-description XML (5 s timeout) and find a `<service>` whose `serviceType` is one of
   `urn:schemas-upnp-org:service:WANIPConnection:2`, `...:WANIPConnection:1`,
   `...:WANPPPConnection:1`; read its `<controlURL>` (namespace `urn:schemas-upnp-org:device-1-0`);
   if it starts with `/` => `scheme://host:port` + controlURL; if absolute it must start with the
   location's scheme://netloc/ else ignore. Preference order: WANIPConnection:2 > WANIPConnection:1
   > WANPPPConnection:1.
2. AddPortMapping: HTTP POST to control URL, headers
   `Host: <control url netloc>`, `Content-Type: text/xml; charset=utf-8`, `SOAPACTION:
   "<serviceType>#AddPortMapping"`; body (CRLF after XML decl and after `</s:Envelope>`):
   ```xml
   <?xml version="1.0"?>
   <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:AddPortMapping xmlns:u="SERVICETYPE"><NewRemoteHost></NewRemoteHost><NewExternalPort>PORT</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>PORT</NewInternalPort><NewInternalClient>LOCALIP</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>NicotinePlus</NewPortMappingDescription><NewLeaseDuration>43200</NewLeaseDuration></u:AddPortMapping></s:Body></s:Envelope>
   ```
   Timeout 5 s. HTTP error responses still contain a SOAP body: parse it; success = SOAP Body with
   no `errorCode`/`errorDescription` (namespace `urn:schemas-upnp-org:control-1-0`). Error 725
   (OnlyPermanentLeasesSupported) => retry once with `NewLeaseDuration` 0.
3. Remove on shutdown: same POST with `SOAPACTION: "<serviceType>#DeletePortMapping"` and body
   `<u:DeletePortMapping xmlns:u="SERVICETYPE"><NewRemoteHost></NewRemoteHost><NewExternalPort>PORT</NewExternalPort><NewProtocol>TCP</NewProtocol></u:DeletePortMapping>`.
4. Renew by repeating the add (every 2 h) while connected.

---------------------------------------------------------------------------------------------------
## 11. Implementation checklist / gotchas for a Kotlin client

1. All integers little-endian (`ByteBuffer.order(LITTLE_ENDIAN)`); strings are length-prefixed
   UTF-8 with latin-1 fallback on decode. Treat uint32 as `Long`/`UInt`, uint64 as `Long`/`ULong`.
2. IP field decoding: reverse the 4 wire bytes: wire `[a,b,c,d]` => `"d.c.b.a"`.
3. Server frame code is u32; peer-init and distributed codes are u8; "P" peer code is u32. The
   first message on a new peer socket is peer-init (u8 code). Don't mix them.
4. Compress/decompress with zlib wrapper (not raw deflate) for codes 5, 9, 37.
5. USER LIST in JoinRoom is struct-of-arrays (names, statuses, stats[5], slotsfull, countries).
6. ConnectToPeer response carries (user, type, ip, port, token, privileged, obfType u32,
   obfPort u32). Reply path is PierceFireWall(token) on a NEW socket to ip:port; if it fails send
   CantConnectToPeer(token, user).
7. Direct+indirect are raced; when a peer socket is "established" send PeerInit (direct) or
   PierceFireWall (pierce). Listener side never replies to either.
8. Download flow: QueueUpload -> (wait) -> TransferRequest(dir=1) from uploader -> respond
   TransferResponse(token, true) -> uploader opens F, sends u32 token -> we send u64 offset ->
   raw bytes. Handle both the case where we open F as the connecting side (rare) and where the
   uploader connects to us; the token/offset byte order is the same.
9. Always set up the P connection handler for unsolicited: TransferRequest, UploadDenied,
   PlaceInQueueResponse, UploadFailed, FileSearchResponse, FolderContentsResponse.
10. Remember size==0 in TransferRequest (SoulseekQt >2 GiB) => use cached listing size.
11. File sizes: handle the NS 0xFF-high-byte quirk when parsing listings/search results.
12. Search results are best-effort over many short-lived P connections; handle each independently
    and discard tokens you didn't register.
13. Send MessageAcked(22->23) for every incoming private message id.
14. If implementing sharing: keep a lowercase word index, a virtual-path to real-path map, and
    answer in the formats of section 5; if not sharing, still answer SharedFileListRequest with an
    empty list (zlib of `u32 0, u32 0`), and answer QueueUpload with UploadDenied "File not shared."
15. If you accept inbound connections (listen port), a failed port-forward is common; the
    ConnectToPeer indirect path is the fallback. Without a listen port you cannot receive
    PierceFireWall/PeerInit, so uploads and search results to you only work via the peers' direct
    connections to your forwarded port or via your own outbound direct/indirect attempts.
