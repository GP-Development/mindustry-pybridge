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
| T1 | SSRF / arbitrary outbound connections via a host field | Structural | 2 (verify) |
| T2 | Malicious webpage reaching the localhost listener | Structural | 2 (verify) |
| T3 | Rogue local process attaching to the socket | Planned | 2 |
| T4 | Resource exhaustion via command flooding | Planned | 2–4 |
| T5 | Multiplayer fairness abuse via unthrottled control | Planned | 6 |
| T6 | Arbitrary code execution through the command surface | Structural | all |
| T7 | Weak or predictable session token | Planned | 2 |
| T8 | Token file readable by other users (Windows) | Planned | 2 |
| T9 | Unbounded allocation from a hostile length prefix | Planned | 2 |
| T10 | Cross-thread data race on game state | Planned | 3 |
| T11 | Fog-of-war / information disclosure via telemetry | Planned (SP-only), Open (MP) | 3, 6 |
| T12 | Main-thread stall via blocking I/O | Planned | 3 |
| T13 | Token disclosure through logs or error messages | Planned | 2 |
| T14 | Stale token reuse across sessions | Planned | 2 |
| T15 | Connection exhaustion | Planned | 2 |
| T16 | Impersonation of the listener by a local process | Accepted | 2 |

**"2 (verify)"** means there is no mitigation to *write* — the capability was never built, which is
what **Structural** means — but Phase 2 is where code first exists that could erode the property,
so Phase 2's gate must confirm it by reading that code. A structural threat with no owner phase
degrades into a threat nobody ever checks.

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
break this invariant. Refuse and escalate to the maintainer.

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

**Interim gate for Phases 4–5 (added at security checkpoint 0).** The mitigations above are not
all available when control commands first ship. Phase 4 builds the throttle, the block cost and the
per-base cap, but the **server-side opt-in flag and the throttle tuning do not exist until Phase
6** — so between those phases a multiplayer session would expose programmatic control with no
server consent and no fairness envelope. That is an exposure created in Phase 4 by a mitigation
scheduled for Phase 6, which the roadmap's checkpoint rule forbids.

Therefore **control commands are refused in multiplayer with `not_permitted` from Phase 4**,
exactly as telemetry is under T11, and the restriction is lifted only at the Phase 6 gate once the
server flag and tuning are in place. Like T11's gate this is a hard refusal, not silent filtering.

**Open question for Phase 6.** Whether the throttle should be tuned to "roughly what a skilled
human could do" or simply "does not break the simulation". These give very different numbers. Not
yet decided; must be settled at the Phase 6 gate.

**Enforced at.** Phase 4 (throttle, cost, cap, multiplayer refusal) and Phase 6 (server flag,
tuning, lifting the refusal).

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

**Windows.** Windows is a **first-class supported target** for this fork, so a mitigation that
only works on POSIX is not acceptable. POSIX permissions are unavailable there, and
`File.setReadable(false, false)` / `setReadable(true, true)` is best-effort and does not reliably
produce an owner-only ACL.

**Decision (resolved).** Implement the real thing on both platforms, then **verify**, then **fail
closed only if verification fails**:

1. **POSIX** — create with `PosixFilePermissions` `rw-------` (`0600`), directory `rwx------`
   (`0700`).
2. **Windows** — use `AclFileAttributeView` to write an explicit DACL granting **only the file
   owner** read/write, with inheritance disabled so a permissive parent-directory ACL cannot
   widen it.
3. **Verify after writing** — re-read the permissions/ACL and confirm no principal other than the
   owner has access. Do not trust that the set call succeeded.
4. **Fail closed if verification fails** — do not start the listener, do not write a token, and
   surface a clear message explaining why. A bridge that cannot protect its token must not run.

This satisfies both constraints: the feature genuinely works on Windows, and it still refuses to
run in the rare case where it cannot secure the file (e.g. a FAT32/exFAT data directory, which has
no ACL support at all).

**Creation must be atomic.** The file is created with restrictive permissions **as part of
creation** — `Files.createFile` with the attributes supplied up front, or created in a
newly-created owner-only directory — never created world-readable and then tightened, which leaves
an exploitable race window in which another process can read the token.

**Status.** Planned for Phase 2; the design decision itself is settled and should not be
relitigated. Verification-then-fail-closed is the load-bearing part — an unverified `setReadable`
call is exactly the kind of mitigation that appears to work and does not.

**Moves to Mitigated only on H2.2 (POSIX), H2.3 (Windows) and H2.4 (fail-closed) — all three.**
This threat is about what the filesystem actually did, so it is settled by looking at the created
file's mode bits and ACL, not by reading the code that asked for them. A POSIX-only pass leaves
Windows unverified, which is the exact failure this threat was raised to prevent.

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

**Decision (interim, adopted).** **Phase 3 telemetry is permitted in single-player only.** In
single-player the threat is void — the player may already see everything — so this lets Phase 3
ship its threading work without forcing the fog-of-war question early. The check is a hard gate:
if the session is multiplayer, telemetry subscriptions are refused with `not_permitted`, rather
than being silently filtered to nothing.

**Still open for Phase 6.** Before telemetry is permitted in multiplayer, snapshot assembly must
respect team visibility and fog state, defaulting to omitting anything whose visibility is
uncertain. The exact rules depend on how fog is represented and must be settled at the Phase 6
gate, alongside the other fairness questions (T5). Lifting the single-player restriction without
implementing that filtering would ship a wallhack.

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

### Verification provenance — what may move a threat to **Mitigated**

Some properties can be confirmed by reading code; others can only be confirmed by watching the
software behave. The two are not interchangeable, and the status of a threat must say which one it
rests on.

- **Code-verifiable** — the mitigation is visible in the source (dispatch is a `switch`, the bind
  address is a constant, no game state is touched off-thread). Record a **file reference**.
- **Observation-only** — the mitigation is a claim about runtime behaviour (nothing is listening
  in a default install, the file's mode bits really are `0600`, a hostile length prefix really
  allocates nothing, a flood really does not burst after a stall). Record the passed **`H`-item**
  from `HUMAN_TODO.md`.

An observation-only threat **stays Planned until its `H`-item has actually been run.** This is
the whole reason `HUMAN_TODO.md` exists: an automated session cannot perform these checks, so it
must not be the thing that closes them. Threats currently in that category:

| Threat | Confirmed by |
|---|---|
| T4 (throttle holds under flood) | H4.1, H4.8 |
| T8 (owner-only connection file) | H2.2 **and** H2.3 **and** H2.4 — POSIX alone does not discharge it |
| T9 (no allocation from a hostile length prefix) | H2.7, watching the heap |
| T11 (no fog-of-war leak in multiplayer) | H3.5, then H6.3 before the restriction is lifted |
| T12 (a stalled client cannot stall the game) | H3.2 |
| T13 (token never printed) | code `grep` **and** H2.11 |
| `CLAUDE.md` §4.3 (off by default) | H2.1 |

Pending items here do not stop development of later phases (`docs/ROADMAP.md`, top section); they
stop the *status* from advancing. A `H`-item that has been run and **failed** is a defect and a
hard stop.

## Change log

| Date | Change |
|---|---|
| 2026-08-05 | Initial threat model. Seeded T1–T5 from design discussion; added T6–T16 during authoring. T8 and T11 flagged as unresolved and requiring a decision before their phase gates. |
| 2026-08-05 | **Security checkpoint 0 passed**, after four fixes. (1) `ROADMAP` said a non-`hello` first frame is rejected with `unauthenticated` while `PROTOCOL` §4 said it is closed silently — resolved in favour of the silent close (an error reply is an oracle). (2) `unauthenticated` was therefore unreachable; retained and documented in `PROTOCOL` §8 as a fail-closed default case rather than deleted. (3) T1 and T2 had no owner phase despite both naming Phase 2 code that must uphold them — given "2 (verify)". (4) T5's exposure preceded its mitigation: Phase 4 granted control commands with no multiplayer gate while the server opt-in flag and throttle tuning land in Phase 6, so control is now refused in multiplayer with `not_permitted` from Phase 4, lifted only at the Phase 6 gate. Also noted: the port is user-configurable while the address is not, which is consistent with §4.1 only while the setting stays an integer port and never becomes a `host:port` string. |
| 2026-08-05 | T8 resolved: Windows is a first-class target, so implement `AclFileAttributeView` on Windows and POSIX `0600` elsewhere, verify the result, and fail closed only if verification fails. T11 resolved for Phase 3: telemetry is single-player only until fog-of-war filtering is implemented; multiplayer remains open for Phase 6. |
| 2026-08-06 | **No-personal-data policy adopted as a hard rule** (`CLAUDE.md` §4.7): no names, handles, contact details, biographical detail, machine identifiers, or account-bearing filesystem paths in any committed file — documentation, comments, commit messages, bundle strings, or pasted output. Exemptions are narrow and named: upstream repository/dependency identifiers required by the merge policy and the build, licence and copyright notices that GPLv3 requires be preserved, and git's own author metadata (which a documentation edit cannot reach and which must not be scrubbed by rewriting history). Rationale: the repository is a distribution channel — it is published under GPLv3, quoted into issue reports, and permanent once committed — and account names in paths and biographical detail are directly useful to an attacker. Applied retroactively: personal and biographical description was removed from `CLAUDE.md` §1 and §9 and replaced with the role-based statement that carries the same instruction ("assume the reviewer is not a Java specialist"). |
| 2026-08-06 | **Human verification separated from automated verification** (`HUMAN_TODO.md`). All checks needing a real machine — JDK 17 builds, launching the game, per-OS file permissions, multiplayer, soak tests, balance judgement — moved out of `docs/ROADMAP.md` into a single queue that owns those checkboxes; the roadmap now references them by ID. Two rules added to keep this from weakening anything: **verification provenance** (a threat moves to **Mitigated** only against a file reference or a passed `H`-item, never against "reviewed and looks right" — see the table above), and the explicit distinction that a **pending** human item does not block development while a **failed** one is a hard stop. `GATE`-tagged items additionally keep the feature off by default and unadvertised until they pass. The net effect on security posture is a tightening, not a relaxation: several threats that could previously have been marked **Mitigated** on a code reading (T4, T8, T9, T12, T13, §4.3) now name an observation they must wait for. |
| 2026-08-06 | **Phase 1 landed (inert block); security checkpoint 1 passed.** No threat status changed, and none should have: the phase adds a placeable block and nothing else — no socket, no thread, no file, no input surface. Three things are worth recording rather than left implicit. (1) **The block reads nothing.** `PyBridgeBlock` makes no `config(...)` call and leaves `configurable` false, so no player interaction, schematic, save field or mlog instruction can pass it data. Adding a config would create the first input surface in the fork and must be weighed against `CLAUDE.md` §4 rather than added in passing; the constraint is written into the class comment where someone about to add one will see it. (2) **The first fork-local logging path now exists.** `updateTile()` prints a throttled line every 600 ticks. It interpolates only the block's own tile coordinates and power efficiency. T13 has nothing to disclose yet, and the rule that keeps it that way starts now: no fork-local log line ever interpolates a token, a connection-file path, or any value derived from either — including in exception handlers and `toString()` output, which are the paths that historically leak. (3) **The block is visible in the build menu while the feature does not exist.** That is not a §4.3 or `GATE` violation: "off by default" and "unadvertised until the gate passes" are about the *listener*, and there is none — placing this block binds no port and writes no file. Its bundle description says so in plain words so a player is not told a capability exists before it does. |
