# Threat model — Mindustry Python Control Bridge

**This is a living document.** Every security-relevant decision made in this fork gets recorded
here, with its rationale and the place in the code where it is enforced. If you make such a
decision and do not write it down here, the decision is not finished.

Read `CLAUDE.md` §4 first — that file states the hard invariants as *rules*. This file explains
the *threats those rules exist to stop*, so that a future session can tell the difference between
a restriction that is load-bearing and one that merely looks redundant.

**Status vocabulary**

| Status | Meaning |
|---|---|
| **Mitigated** | Implemented and enforced in code today. |
| **Planned** | Mitigation is designed and assigned to a phase, but not yet written. |
| **Structural** | Prevented by the architecture; there is no code to point at because the capability was never built. |
| **Accepted** | Known residual risk we are deliberately living with, with reasoning. |
| **Open** | Identified, not yet resolved. Must not remain in this state at the phase gate. |

Phase numbers refer to `docs/ROADMAP.md`.

---

## Summary table

| ID | Threat | Status | Phase |
|---|---|---|---|
| T1 | SSRF / arbitrary outbound connections via a host field | Structural | — |
| T2 | Malicious webpage reaching the localhost listener | Structural | — |
| T3 | Rogue local process attaching to the socket | Planned | 2 |
| T4 | Resource exhaustion via command flooding | Planned | 2–4 |
| T5 | Multiplayer fairness abuse via unthrottled control | Planned | 6 |
| T6 | Arbitrary code execution through the command surface | Structural | all |
| T7 | Weak or predictable session token | Planned | 2 |
| T8 | Token file readable by other users (Windows) | Open | 2 |
| T9 | Unbounded allocation from a hostile length prefix | Planned | 2 |
| T10 | Cross-thread data race on game state | Planned | 3 |
| T11 | Fog-of-war / information disclosure via telemetry | Open | 3, 6 |
| T12 | Main-thread stall via blocking I/O | Planned | 3 |
| T13 | Token disclosure through logs or error messages | Planned | 2 |
| T14 | Stale token reuse across sessions | Planned | 2 |
| T15 | Connection exhaustion | Planned | 2 |
| T16 | Impersonation of the listener by a local process | Accepted | 2 |

---

## T1 — SSRF / arbitrary outbound connections via a host field

**Threat.** If the bridge ever accepted a user- or config-supplied host or URL, the game process
could be induced to open connections to arbitrary destinations. On a normal desktop that is bad
enough; on a hosted or cloud machine it becomes a classic SSRF pivot, reaching internal services
and cloud metadata endpoints that are reachable from the host but not from the attacker.

**Mitigation.** The listener binds `127.0.0.1` and nothing else. There is no bind-address setting,
no host field in config, no host field in the protocol, and no code path that makes an *outbound*
connection on the bridge's behalf. The game only ever **accepts** connections; it never **makes**
them.

**Why structural rather than validated.** Host validation is a recurring source of bypasses —
parser differentials, DNS rebinding, redirect following, IPv6 and decimal-encoded literals. The
only version of this that cannot be bypassed is the one where the field does not exist. A
validated host field is still a host field.

**Enforced at.** `CLAUDE.md` §4.1. Code: the listener bind call in `mindustry/pybridge/`
(Phase 2) — the loopback address must be a hard-coded constant, never a variable read from
config.

**Future sessions:** a request to "support connecting to a remote Python process" is a request to
break this invariant. Refuse and escalate to the user.

---

## T2 — Malicious webpage reaching the localhost listener

**Threat.** A webpage the user visits in a browser attempts to talk to `127.0.0.1:<port>` and
issue control commands. This is the attack class that has repeatedly broken localhost-listening
desktop software — the page is untrusted content executing on the same machine, so loopback
binding alone does not exclude it. DNS rebinding defeats naive origin checks by making the
attacker's domain resolve to `127.0.0.1`.

**Mitigation.** The transport is **raw length-framed TCP**. Browsers cannot originate raw TCP
connections — JavaScript is limited to HTTP(S), WebSocket, WebRTC and similar higher-level
protocols, all of which impose handshakes our listener does not implement and will not accept. A
webpage therefore cannot form a conversation with our socket at all.

Additionally, the handshake requires a session token the page cannot read (see T3), so even a
transport bypass fails closed.

**Why not WebSocket.** WebSocket would be more convenient and is explicitly rejected. It is
originatable from a browser, and its origin header is the only barrier — a barrier that has been
defeated repeatedly in practice. Choosing a transport browsers cannot speak removes the entire
attack class by construction instead of defending against it.

**Enforced at.** `CLAUDE.md` §4.2. Code: the framing implementation in `mindustry/pybridge/`
(Phase 2). A non-conforming first frame must cause an immediate disconnect, not an error reply —
we do not want to be a useful oracle for probing.

---

## T3 — Rogue local process attaching to the socket

**Threat.** Loopback binding removes the network as an attacker but does **not** remove other
processes on the same machine. Any local program that can open a TCP socket can reach the
listener: another user account on a shared machine, a sandboxed or lower-privileged process, or
some unrelated program that scans localhost ports.

**Mitigation.** A per-session **token handshake**:

- On listener start the game generates a **256-bit token from `SecureRandom`**.
- The token and the chosen port are written to a **connection file** in `Vars.dataDirectory`, with
  owner-only permissions.
- The client must present the token in its **first frame**. Until it does, no other message type
  is accepted.
- The comparison uses **`MessageDigest.isEqual`** (constant-time), so response timing cannot leak
  the token byte by byte.
- A connection that does not authenticate within a short timeout is dropped; repeated failures
  trigger backoff (see T4, T15).

This is the same pattern Jupyter uses for kernel connection files, and it keeps the client side
to two lines of Python — read the file, send the token.

**Residual risk — stated plainly.** **No local authentication scheme can defeat a hostile process
running as the same OS user.** Such a process can simply read the connection file, exactly as the
legitimate client does. The token's real accomplishment is narrower and still worthwhile: it
raises the bar from "anything that can open a socket" to "something already executing with the
user's privileges" — at which point the user has far larger problems than Mindustry. This residual
risk is **accepted**, and it is the main reason T6 (closed command set) matters more than
authentication does.

**Enforced at.** `docs/PROTOCOL.md` (handshake), and `mindustry/pybridge/` (Phase 2).

---

## T4 — Resource exhaustion via command flooding

**Threat.** A client — buggy or hostile — sends commands as fast as the socket allows. Without a
budget this consumes main-thread time inside the tick, degrades or freezes the simulation, and
becomes a denial of service against the player's own game. Unbounded queues turn the same flood
into an out-of-memory crash.

**Mitigation.** Throttling copies the **mlog model** (`LogicBlock.java:45-48, 556-571`): a bounded
number of commands processed per tick, with the accumulator **capped before use** so unused budget
cannot be banked and flushed as a burst after a stall.

Layers:

1. **Per-connection read rate limit** on the network thread, before parsing.
2. **Bounded queue** between network and main thread. When full, apply backpressure or drop with
   an error reply — never grow without limit.
3. **Per-tick command budget** on the main thread, capped accumulator, mlog-style.
4. **Per-block limits** so more blocks does not mean unbounded aggregate throughput.

**Why not a wall-clock timeout.** mlog has no timeout anywhere; its guarantee is *structural* —
bounded work per tick. Timeouts fail badly here because the work is already inside the tick by the
time a timer would fire. Match the existing model.

**Enforced at.** `CLAUDE.md` §4.5. Code: Phase 2 (connection-level), Phase 3–4 (per-tick budget).
Precedent to copy: `Ratekeeper` in `net/NetConnection.java` and `net/Administration.java`.

---

## T5 — Multiplayer fairness abuse via unthrottled control

**Threat.** In multiplayer, a player with programmatic control has a mechanical advantage over
players using mouse and keyboard — faster and more precise actions than a human can produce.
Unthrottled, this is indistinguishable from cheating, and it is a fairness problem even when every
individual command is legitimate.

**Mitigation.**

- Server-side **opt-in flag**: servers decide whether the bridge is permitted at all, and it is
  off unless enabled.
- Control is gated behind a block with **real cost** — power draw, expensive build requirements,
  and a per-base cap — so it is not strictly superior to mlog for free.
- Command rates are throttled to a defensible envelope (T4), not merely to what the socket can
  carry.
- Read and write permissions are **separate levels**; a server may permit telemetry while
  forbidding control.

**Open question for Phase 6.** Whether the throttle should be tuned to "roughly what a skilled
human could do" or simply "does not break the simulation". These give very different numbers. Not
yet decided; must be settled at the Phase 6 gate.

**Enforced at.** Phase 4 (throttle, cost, cap) and Phase 6 (server flag, tuning).

---

## T6 — Arbitrary code execution through the command surface

**Threat.** The catastrophic case. If any socket message can cause evaluation of client-supplied
code, or reach reflection, class loading, the filesystem, the process table, or environment
variables, then socket access escalates from "controls a game" to "controls the machine". A
convenience feature such as "run this expression" or "call this method by name" is sufficient to
create it.

**Mitigation — structural, and the most important one in this document.** Everything reachable
over the socket is a member of a **fixed, closed, explicitly implemented command vocabulary**: an
**allowlist**. A command that has not been deliberately written and validated does not exist.
There is no interpreter, no `eval`, no reflection driven by client input, no filesystem or process
access, and no passthrough that forwards client strings to anything that could execute them.

**Why this outranks authentication.** Authentication decides *who* may connect; the closed command
set decides *what any connected party can ever do*. Because T3's residual risk is accepted — a
same-user process can obtain the token — the containment property must not depend on
authentication holding. It must hold even for a fully authenticated hostile client. Under this
design the worst such a client can do is play the game badly and quickly; it cannot reach outside
the simulation.

**Enforced at.** `CLAUDE.md` §4.4. Code: the command dispatch table in `mindustry/pybridge/`
(Phases 3–4). Dispatch must be an explicit `switch`/map over known command IDs with a default case
that rejects — never a name-to-method lookup.

**Future sessions:** any request phrased as "let the Python side run arbitrary logic in the game"
breaks this. The correct answer is to add a specific, validated command for the concrete thing the
user wants, not a general execution mechanism.

---

## T7 — Weak or predictable session token

**Threat.** A token generated from a non-cryptographic RNG can be predicted by an attacker who
observes or guesses the seed, defeating T3's handshake without any need to read the connection
file. `java.util.Random` is seeded predictably and its internal state is recoverable from a small
number of outputs.

**Mitigation.** Tokens come from **`java.security.SecureRandom`**, 256 bits, hex- or
base64-encoded. **Never** `Math.random`, `java.util.Random`, or Mindustry's `Mathf.random` — the
latter is a game-simulation RNG chosen for speed and reproducibility, which are the opposite of
what is wanted here.

**Enforced at.** Token generation in `mindustry/pybridge/` (Phase 2). The call site gets an inline
comment saying why `Mathf.random` is forbidden, because it is otherwise the obvious idiomatic
choice in this codebase and a future session will reach for it.

---

## T8 — Token file readable by other users (Windows)

**Threat.** The connection file's protection depends on filesystem permissions. If it is
world-readable, T3's mitigation collapses for multi-user machines: any local account can read the
token and authenticate.

**Mitigation.** On POSIX (Linux, macOS) the file is created with owner-only permissions (`0600`)
via `Files.setPosixFilePermissions`, and the containing directory is owner-only too.

**Open — known gap.** Java's cross-platform permission story on **Windows** is weaker: POSIX
permissions are unavailable, and `File.setReadable(false, false)` / `setReadable(true, true)` is
best-effort and does not reliably produce an owner-only ACL. Proper handling requires
`AclFileAttributeView`, which is verbose and easy to get subtly wrong.

**Decision pending at the Phase 2 gate.** Options: (a) implement an `AclFileAttributeView` path
for Windows; (b) document the weaker guarantee and accept it for single-user desktops; (c) refuse
to start the listener if owner-only permissions cannot be established. Option (c) fails closed and
is the security-maximal choice; option (b) is the least work and is defensible for a
non-distributed single-user project. **Not yet decided — must not ship unresolved.**

Whichever is chosen: the file must be created with restrictive permissions **atomically at
creation**, not created world-readable and then chmod'ed, which leaves an exploitable window.

---

## T9 — Unbounded allocation from a hostile length prefix

**Threat.** In a length-prefixed framing scheme, a client sends a frame header declaring a length
of, say, 2 GB. A naive reader allocates that buffer immediately and the JVM dies of
`OutOfMemoryError` — a one-packet denial of service requiring no authentication if the check
happens after allocation.

**Mitigation.** A **hard maximum frame size**, checked **before any allocation**. A frame
declaring more than the maximum causes immediate disconnection, not an error reply and not a
resize. The limit applies to unauthenticated connections too — it must be enforced before the
handshake, since an unauthenticated client can send bytes.

**Enforced at.** `CLAUDE.md` §4.5, `docs/PROTOCOL.md` (framing section), and the frame reader in
`mindustry/pybridge/` (Phase 2).

---

## T10 — Cross-thread data race on game state

**Threat.** Mindustry's simulation is single-threaded and its data structures carry no internal
synchronization. A network thread reading or writing world state concurrently with the tick can
observe torn or half-updated objects, corrupt entity lists, or crash the game. This is a security
concern and not merely a correctness one: memory-visibility bugs produce unpredictable state, and
unpredictable state is not auditable.

**Mitigation.** The network thread **never** touches game state — not even for reads. All access
is marshalled onto the main thread via `Core.app.post(Runnable)` (upstream precedent:
`net/ArcNetProvider.java:83, 94, 101-107`). Telemetry crosses the boundary as an **immutable
snapshot** taken on the main thread; live `Building`, `Unit`, and `Tile` references are never
handed to the network thread, because such an object mutates while it is being serialized.

**Enforced at.** `CLAUDE.md` §4.6 and §5. Code: Phase 3, which is the phase where this boundary is
first built and therefore requires the most careful review.

---

## T11 — Fog-of-war / information disclosure via telemetry

**Threat.** *Raised during design; not part of the original threat list.* Read-only access is
intuitively the safe permission level, but in multiplayer it is not automatically benign. If
telemetry exposes world state the player's client knows but the player is not *supposed* to see —
positions under fog of war, enemy unit composition, resource state of other teams — then
"read-only" becomes a wallhack. Mindustry has a fog-of-war system, so this is concrete rather than
hypothetical.

The subtlety: the client process legitimately holds some of this data for rendering and
prediction purposes even when the UI does not display it. A telemetry API that reports "what the
client knows" therefore leaks more than "what the player can see".

**Mitigation (to be designed in Phase 3).** Telemetry must be filtered to **what the player is
permitted to observe**, not to what the client process happens to hold in memory. Concretely:
respect team visibility and fog-of-war state when assembling snapshots, and default to omitting
anything whose visibility is uncertain.

**Open.** The exact filtering rules depend on how fog is represented and must be settled at the
Phase 3 gate, then re-reviewed at Phase 6 alongside the other multiplayer fairness questions
(T5). In single-player this threat is void — the player may already see everything — so an interim
option is to permit telemetry only in single-player until the filtering is implemented.

---

## T12 — Main-thread stall via blocking I/O

**Threat.** If any socket read, write, or network wait happens on the main thread, a slow or
malicious client stalls the entire game simply by not reading its socket — the write blocks, the
tick does not complete, and the game freezes. This makes a hostile client able to halt the
simulation without sending a single command.

**Mitigation.** All socket I/O happens on bridge threads. The main thread never performs network
I/O, never calls `Future.get()` on an I/O task, and never holds a lock across I/O. Outbound
telemetry is handed to the network thread through a **bounded** queue; if the client is not
draining it, the queue fills and the bridge drops data or disconnects that client — the
simulation is never made to wait.

**Enforced at.** `CLAUDE.md` §4.6, §5. Code: Phase 3.

---

## T13 — Token disclosure through logs or error messages

**Threat.** A token that is correct in memory but printed to the console, written into a log file,
included in a crash report, or echoed in an error reply is disclosed to anyone who reads those —
including crash reports pasted publicly into bug trackers and Discord.

**Mitigation.** The token is never logged, never included in an error reply, and never included in
exception messages. Authentication failures return a generic failure with no detail about *why*
(no "wrong length", no "wrong prefix") — a specific error is an oracle. Log lines may state that a
connection authenticated or failed, never with what.

**Enforced at.** Phase 2. Also a review item: `grep` for the token variable in any logging call
before each phase gate.

---

## T14 — Stale token reuse across sessions

**Threat.** A token that persists across game sessions widens the window for disclosure — an old
connection file, a backup, or a screenshot remains valid indefinitely.

**Mitigation.** The token is **regenerated every time the listener starts**, and the connection
file is deleted on clean shutdown. A token from a previous session is worthless. The file is
rewritten, not appended.

**Note.** Deletion on shutdown is best-effort — a crash or kill leaves the file behind. Because
the token is regenerated on next start, the stale file is not *valid*, merely present. Acceptable.

**Enforced at.** Phase 2.

---

## T15 — Connection exhaustion

**Threat.** A local process opens many concurrent connections, or opens connections and never
completes the handshake, consuming file descriptors, threads, and memory until the bridge or the
JVM fails.

**Mitigation.**

- A **low hard cap** on concurrent connections. The expected legitimate count is one; the cap
  should be a small number, not a generous one.
- An **authentication timeout**: a connection that has not completed the handshake within a few
  seconds is closed.
- **Backoff on repeated failures** from the same source.
- Connection handling must not allocate an unbounded thread per connection.

Upstream precedent for connection limiting and rate-limited blacklisting:
`net/ArcNetProvider.java:130-135, 166-171`.

**Enforced at.** Phase 2.

---

## T16 — Impersonation of the listener by a local process

**Threat.** If the bridge is disabled or the game is not running, another local process could bind
the port the Python client expects, or write a forged connection file, and harvest whatever the
client sends — or feed the client fabricated telemetry that causes it to act against the player's
interest.

**Mitigation / status: accepted.** Both prerequisites (binding a port before the game does,
writing into the user's data directory) are available only to a process already running with the
user's privileges, which is the same residual risk accepted in T3. The bridge does not attempt to
defend against it, and doing so would require the client to authenticate the *server* — mutual
authentication whose key material lives in the same file the attacker could already read.

Noted here so a future session does not mistake the absence of a mitigation for an oversight.

---

## Review checkpoints

Each phase gate in `docs/ROADMAP.md` requires a pass over this file:

1. Every threat marked **Open** for that phase is resolved or explicitly re-accepted with reasons.
2. Every **Planned** mitigation for that phase is now **Mitigated**, with a real file reference.
3. Any new capability added in the phase has been examined for new threats, and any found are
   added here.
4. The invariants in `CLAUDE.md` §4 still hold in the code as written, verified by reading it
   rather than by assuming.

## Change log

| Date | Change |
|---|---|
| 2026-08-05 | Initial threat model. Seeded T1–T5 from design discussion; added T6–T16 during authoring. T8 and T11 flagged as unresolved and requiring a decision before their phase gates. |
