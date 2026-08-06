# Wire protocol — Mindustry Python Control Bridge

**Protocol version: 1** (unreleased — this document is the design, written before implementation
so it can be reviewed on paper.)

This specifies the complete conversation between the game (server) and an external Python process
(client). Nothing outside this document is reachable over the socket. If a behaviour is not
described here, it does not exist.

Related: `CLAUDE.md` §4 (hard invariants), `docs/SECURITY.md` (threat model — threat IDs `T*` below
refer to it).

---

## 1. Transport

**Raw length-framed TCP over loopback. Nothing else.**

| Property | Value |
|---|---|
| Address | `127.0.0.1` — hard-coded, never configurable (T1) |
| Port | OS-assigned or user-configured *port only*; published in the connection file |
| Transport | Raw TCP. **No** HTTP, **no** WebSocket, **no** TLS (T2) |
| Direction | The game **only accepts** connections. It never dials out (T1). |
| Byte order | Big-endian ("network order") — Python `struct` `!` prefix |
| Text encoding | UTF-8, no BOM |

**Why no TLS.** TLS protects data in transit against a network attacker. On loopback there is no
network path to attack — traffic never leaves the kernel. The realistic local adversary (T3, T16)
is a process running as the same user, which TLS does not stop, because it could read our keys
just as it could read our token. TLS here would add a large dependency and a large attack surface
(certificate handling, protocol negotiation) in exchange for no threat actually mitigated.

---

## 2. Framing

Every message in both directions is a single frame:

```
 0       4                          4 + length
 +-------+--------------------------+
 | length|         payload          |
 +-------+--------------------------+
   uint32          UTF-8 JSON
  big-endian
```

- **`length`** — unsigned 32-bit big-endian, the byte count of `payload` only (the 4 header bytes
  are not included).
- **`payload`** — UTF-8 encoded JSON. The top-level value **must be a JSON object** (`{...}`);
  arrays, bare strings and numbers are rejected.

### Limits — enforced before allocation (T9)

| Rule | Value | On violation |
|---|---|---|
| `MAX_FRAME_BYTES` | **65536** (64 KiB) | **Close connection immediately.** No error frame. |
| Minimum length | 1 | Close connection immediately. Empty frames are not valid. |

**The length check happens before any buffer is allocated, and applies to unauthenticated
connections too.** A client that declares a 2 GB frame must be disconnected on the strength of the
header alone — if the check happened after allocation, a single unauthenticated packet would be an
out-of-memory kill (T9).

**Why JSON rather than a binary encoding.** JSON is slower and larger than a packed binary format.
It is chosen deliberately: it is trivially readable in Python without a schema compiler, it is
human-auditable when logged or captured, and a hand-written binary parser is exactly the kind of
code where length-handling bugs hide. Throughput is not a constraint here — traffic is rate-limited
by design (T4), so the cost buys auditability at no practical loss.

### Reference framing code (Python side)

```python
import json, struct

MAX_FRAME_BYTES = 65536

def send(sock, obj):
    payload = json.dumps(obj).encode("utf-8")
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError("frame too large")
    sock.sendall(struct.pack("!I", len(payload)) + payload)

def recv_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf

def recv(sock):
    (length,) = struct.unpack("!I", recv_exactly(sock, 4))
    if length == 0 or length > MAX_FRAME_BYTES:   # check BEFORE allocating
        raise ValueError("bad frame length")
    return json.loads(recv_exactly(sock, length).decode("utf-8"))
```

TCP is a byte stream, not a message stream: a `recv()` may return a partial frame or several at
once. Both sides must loop until the full frame is read, as `recv_exactly` does above.

---

## 3. Connection file

The game publishes its port and session token in a small JSON file, so the client never hardcodes
a port and never prompts for a secret. This is the same pattern Jupyter uses for kernel connection
files.

**Location:** `<Vars.dataDirectory>/pybridge/connection.json`

```json
{
  "protocol": 1,
  "port": 47100,
  "token": "9f2c…64 hex chars…",
  "pid": 12345
}
```

| Field | Meaning |
|---|---|
| `protocol` | Protocol version the game speaks |
| `port` | TCP port on `127.0.0.1` |
| `token` | Session token, 256 bits as 64 lowercase hex characters |
| `pid` | Game process ID — lets a client detect a stale file |

### Security properties (T3, T7, T8, T14)

- Written **only** when the feature is explicitly enabled (`CLAUDE.md` §4.3).
- Token is **256 bits from `SecureRandom`**. Never `Math.random`, `java.util.Random`, or
  Mindustry's `Mathf.random` — all predictable (T7).
- **Regenerated every listener start**; the file is rewritten, not appended. Deleted on clean
  shutdown. A token from a previous session is worthless (T14).
- Created **owner-only, atomically at creation**, then **verified**; the listener **fails closed**
  if owner-only access cannot be confirmed. POSIX `0600` in a `0700` directory;
  `AclFileAttributeView` with inheritance disabled on Windows (T8).

---

## 4. Handshake

The client must authenticate in its **first frame**. Until it succeeds, no other message type is
accepted.

```
client                                        game
  |------------ TCP connect ------------------>|
  |                                            |   (server sends no banner)
  |------------ hello (frame 1) -------------->|
  |                                            |   validate version, then token
  |<----------- welcome -----------------------|   or error + close
```

**The server sends nothing until the client has spoken.** No banner, no version greeting. A
listener that announces itself is a free fingerprint for anything scanning localhost ports.

### `hello` (client → game, must be frame 1)

```json
{
  "type": "hello",
  "protocol": 1,
  "token": "9f2c…",
  "client": "mindustry-py/0.1.0"
}
```

`client` is a free-form identifier used only for logging. It is untrusted input: it must be
length-capped, stripped of control characters, and never interpolated into anything but a log line.

### `welcome` (game → client, on success)

```json
{
  "type": "welcome",
  "protocol": 1,
  "permissions": ["read"],
  "limits": {
    "max_frame_bytes": 65536,
    "max_frames_per_second": 60,
    "max_commands_per_tick": 4,
    "max_telemetry_hz": 10
  }
}
```

`limits` is advertised so a well-behaved client can pace itself. **It is documentation, not
enforcement** — the server enforces every limit independently and never trusts the client to
respect them.

`permissions` is the negotiated access level: `["read"]` for telemetry, `["read","write"]` when a
control-capable block authorises it. Read and write are separate levels, delivered in that order
across phases.

### Handshake failures

| Condition | Response | Then |
|---|---|---|
| Frame 1 is not `hello` | *(nothing)* | Close |
| `protocol` mismatch | `error` `protocol_version` | Close |
| Malformed JSON / not an object | `error` `malformed` | Close |
| Token missing, wrong length, or wrong | `error` `auth_failed` | Close |
| No `hello` within **5 seconds** | *(nothing)* | Close (T15) |

**The `auth_failed` reply is deliberately generic** (T13). It never says "wrong length", "wrong
prefix", or "expected 64 characters" — a specific error turns the server into an oracle that helps
an attacker converge on a valid token. Token comparison uses **`MessageDigest.isEqual`**
(constant-time), so response *timing* cannot leak the token byte by byte either.

`protocol_version` and `malformed` **are** answered before authentication, because they leak
nothing secret and save a legitimate developer a great deal of confusion. Everything else fails
silently.

### Post-handshake abuse controls (T15)

- **Max concurrent connections: 4.** The expected legitimate count is one. The cap is small on
  purpose.
- Repeated authentication failures trigger **backoff** before new connections are accepted.
- Connection handling must not allocate an unbounded thread per connection.

---

## 5. Message envelope

After the handshake, every frame carries a `type`, and every client **request** carries an `id`.

```json
{ "type": "ping", "id": 7 }
```

- **`id`** — client-chosen integer, unique among that client's in-flight requests. The server
  echoes it in the matching response so replies can be correlated. Responses may arrive out of
  order; a client must match on `id`, not on arrival order.
- **Unsolicited server messages** (telemetry pushes, events) carry **no `id`**, which is how a
  client distinguishes a push from a reply.

### Strictness rules

| Situation | Behaviour | Why |
|---|---|---|
| Unknown **message type** | `error` `unknown_type` | The command set is a closed allowlist (T6). A type that was not deliberately implemented does not exist. |
| Unknown **top-level field** | Ignored | Forward compatibility: lets v1 clients talk to later servers. |
| Unknown or wrong-typed **command argument** | `error` `invalid_argument` | Command arguments are validated strictly — type, range, and ownership — before anything touches game state (`CLAUDE.md` §4.5). |

The asymmetry is intentional: tolerance for *unknown fields* costs nothing, whereas tolerance for
*unknown types or arguments* is precisely how an allowlist decays into a denylist.

---

## 6. Message types

### Phase 2 — connectivity only

#### `ping` / `pong`

```json
→ { "type": "ping",  "id": 7 }
← { "type": "pong",  "id": 7, "server_time_ms": 1717430400123 }
```

**`pong` deliberately contains no game state** — no tick counter, no wave number, nothing read
from the simulation. Phase 2's defining property is that it touches zero game state (`ROADMAP`
Phase 2), and a tick number in `pong` would quietly violate it *and* require a main-thread hop
before the threading handoff has been reviewed. `server_time_ms` is wall-clock time, which the
network thread may read freely.

### Phase 3 — read-only telemetry

Telemetry is **single-player only** until fog-of-war filtering exists (T11). In a multiplayer
session, `subscribe` is refused with `not_permitted`.

```json
→ { "type": "subscribe",   "id": 8, "topic": "base_summary", "interval_ms": 250 }
← { "type": "ack",         "id": 8, "topic": "base_summary", "interval_ms": 250 }

← { "type": "telemetry", "topic": "base_summary", "tick": 91234, "data": { … } }

→ { "type": "unsubscribe", "id": 9, "topic": "base_summary" }
← { "type": "ack",         "id": 9, "topic": "base_summary" }
```

- `interval_ms` is **clamped** server-side to `max_telemetry_hz`. A client asking for 1 ms gets the
  floor, not an error, and not 1 ms.
- `topic` values come from a **fixed enumeration**; an unrecognised topic is `invalid_argument`.
- Snapshots are assembled **on the main thread** into immutable value objects and handed to the
  network thread for serialisation. A live `Building`, `Unit`, or `Tile` reference must never cross
  the thread boundary (T10, `CLAUDE.md` §5).
- If the client is not draining its socket, the outbound queue fills and telemetry is **dropped**
  for that client — the simulation is never made to wait on a slow reader (T12).

### Phase 4 — control commands

Requires `"write"` in `permissions`, which requires a control-capable bridge block.

Control is **single-player only** until the Phase 6 server opt-in flag and throttle tuning exist
(T5) — the same hard gate telemetry has under T11. In a multiplayer session a `command` is refused
with `not_permitted`.

```json
→ { "type": "command", "id": 10, "block": 4821, "action": "unit_move",
    "args": { "unit": 99312, "x": 412.0, "y": 883.5 } }
← { "type": "ack",     "id": 10, "accepted": true }
```

Every command is validated **on the main thread, before it is applied**:

1. The authorising `block` still exists, is ours, and is powered and enabled.
2. The target entity exists, is on our team, and is controllable.
3. Coordinates and numeric arguments are finite and within world bounds.
4. The per-tick command budget (§7) has room.

Any failure yields `error` with the relevant code and **no partial application**. `action` values
are a fixed enumeration dispatched through an explicit `switch` — never a name-to-method
reflective lookup (T6).

---

## 7. Rate limiting and throttling (T4)

Four independent layers. Each is enforced regardless of the others.

| Layer | Where | Mechanism |
|---|---|---|
| 1. Frame rate | Network thread, pre-parse | Frames per second per connection; excess → `rate_limited`, sustained abuse → close |
| 2. Queue depth | Boundary | **Bounded** queue; when full, reject with `rate_limited`. Never grows without limit — an unbounded queue converts a flood into an OOM crash |
| 3. Per-tick budget | Main thread | mlog-style capped accumulator (below) |
| 4. Per-block limits | Main thread | Caps aggregate throughput so more blocks ≠ unbounded rate |

Layer 3 copies `LogicBlock.java:556-571` exactly in shape:

```java
// Cap BEFORE use: unused budget cannot be banked and flushed as a burst after a stall.
if(accumulator > maxCommandScale * cpt) accumulator = maxCommandScale * cpt;
while(accumulator >= 1f && !queue.isEmpty()){
    applyOneCommand(queue.poll());
    accumulator--;
}
accumulator += edelta() * cpt;
```

**There is no wall-clock timeout anywhere in this design, by intent.** mlog has none either. The
guarantee is *structural* — a bounded amount of work per tick — not time-based. A timeout fails
badly here because by the time a timer could fire, the work is already executing inside the tick.

---

## 8. Errors

```json
{ "type": "error", "id": 7, "code": "rate_limited", "message": "command budget exceeded" }
```

`id` echoes the offending request, or is `null` if the error is not attributable to one.

| Code | Meaning | Closes? |
|---|---|---|
| `protocol_version` | Unsupported `protocol` | Yes |
| `malformed` | Not valid JSON, or not an object | Yes |
| `unauthenticated` | Message sent before a successful `hello` — see note below | Yes |
| `auth_failed` | Token rejected — deliberately unspecific (T13) | Yes |
| `unknown_type` | `type` not in the allowlist (T6) | No |
| `invalid_argument` | Failed validation: type, range, bounds, ownership | No |
| `not_permitted` | Lacks the permission level, or blocked by policy (e.g. multiplayer telemetry) | No |
| `rate_limited` | Exceeded a limit in §7 | No |
| `unavailable` | Valid but not possible now (no game loaded, block destroyed) | No |
| `internal` | Unexpected server-side failure | No |

**`message` is a human-readable hint and must never contain** the token, filesystem paths, stack
traces, or internal identifiers (T13). Clients must branch on `code`, never on `message` text.

**Framing violations produce no error frame at all** — the connection is closed. A malformed frame
means the stream is no longer trustworthy, and replying would make the server a probing oracle.

**`unauthenticated` should be unreachable, and that is intentional.** §4 requires `hello` as frame
1 and closes any other first frame *silently*, so a correct implementation has no path that reaches
a pre-authentication message of another type. The code is retained as the **default case** of the
pre-handshake state machine: if a future change ever does let a frame through unauthenticated, that
is a bug, and it should fail closed with a generic code rather than fall through to a handler. If
it is ever observed on the wire, treat it as a defect report rather than expected behaviour.
(Recorded at security checkpoint 0.)

---

## 9. Versioning

`protocol` is a single integer. Version 1 is defined by this document.

| Change | Bumps version? |
|---|---|
| New message type | No |
| New **optional** field | No |
| New topic or action in an existing enumeration | No |
| Removing or renaming a field or type | **Yes** |
| Changing the meaning, units, or type of an existing field | **Yes** |
| Any change to framing | **Yes** |

The server rejects a `hello` whose `protocol` it does not support, with `protocol_version`, before
authentication. There is no negotiation and no downgrade path: a mismatched client is refused
rather than served a guessed-compatible subset.

**A version bump is a security review trigger.** Framing and handshake changes are exactly where
this class of protocol acquires vulnerabilities, so any change bumping the version requires a pass
over `docs/SECURITY.md` before it lands.

---

## 10. Full session example

```
                    (feature enabled in settings; game writes connection.json,
                     owner-only, verified; listener binds 127.0.0.1:47100)

client reads connection.json  →  port 47100, token 9f2c…
client connects
client  → {"type":"hello","protocol":1,"token":"9f2c…","client":"mindustry-py/0.1.0"}
game    ← {"type":"welcome","protocol":1,"permissions":["read"],"limits":{…}}

client  → {"type":"ping","id":1}
game    ← {"type":"pong","id":1,"server_time_ms":1717430400123}

client  → {"type":"subscribe","id":2,"topic":"base_summary","interval_ms":250}
game    ← {"type":"ack","id":2,"topic":"base_summary","interval_ms":250}
game    ← {"type":"telemetry","topic":"base_summary","tick":91234,"data":{…}}
game    ← {"type":"telemetry","topic":"base_summary","tick":91249,"data":{…}}

client  → {"type":"subscribe","id":3,"topic":"nonsense"}
game    ← {"type":"error","id":3,"code":"invalid_argument","message":"unknown topic"}

client closes
                    (game drops subscriptions and frees the connection slot)
```

---

## 11. Implementation checklist

Verify every item against the code before the Phase 2 and Phase 3 gates.

Reading the code settles most of these. Four of them are claims about *runtime behaviour* and are
settled by observation instead — the connection file's real permissions, the absence of an
allocation on a hostile length prefix, the token's absence from a real console session, and the
multiplayer refusals. Those are queued in `HUMAN_TODO.md` (H2.2–H2.4, H2.7, H2.11, H3.5, H4.6) and
are noted below where they apply.

- [ ] Bind address is a hard-coded loopback constant, not read from config (T1)
- [ ] No HTTP/WebSocket handling exists anywhere in the bridge (T2)
- [ ] Frame length validated **before** allocation, pre-handshake included (T9) — observed: H2.7
- [ ] `MAX_FRAME_BYTES` enforced on both read and write paths
- [ ] Token from `SecureRandom`, 256 bits (T7)
- [ ] Token compared with `MessageDigest.isEqual` (T3)
- [ ] Connection file created owner-only atomically, **verified**, fail-closed (T8) — observed: H2.2, H2.3, H2.4
- [ ] Token absent from every log statement and error `message` (T13) — observed: H2.11
- [ ] `auth_failed` carries no diagnostic detail (T13)
- [ ] 5-second handshake timeout and connection cap enforced (T15)
- [ ] Dispatch is an explicit `switch` over known types — no reflection (T6)
- [ ] No game state read or written on the network thread, including `pong` (T10)
- [ ] Telemetry snapshots are immutable copies; no live entity references cross threads (T10)
- [ ] Telemetry refused in multiplayer sessions (T11) — observed: H3.5
- [ ] Control commands refused in multiplayer sessions until Phase 6 (T5) — observed: H4.6
- [ ] Queues bounded; slow client causes drops, never main-thread blocking (T4, T12)
- [ ] Per-tick accumulator capped before use (T4) — observed: H4.1, H4.8
