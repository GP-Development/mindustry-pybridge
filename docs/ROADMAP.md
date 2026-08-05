# Roadmap — Mindustry Python Control Bridge

Phased plan. **Every phase ends with the game still building and running**, so work can stop at any
phase boundary and leave something that works.

Each phase has a **security review checkpoint** that must pass before the next phase begins. The
checkpoints are not ceremony: they are the points at which `docs/SECURITY.md` is reconciled against
code that actually exists.

Threat IDs (`T1`–`T16`) refer to `docs/SECURITY.md`. Invariant references (`§4.1` etc.) refer to
`CLAUDE.md`.

**Legend:** `[ ]` not started · `[~]` in progress · `[x]` done

---

## Phase 0 — Fork hygiene

*No behaviour change. Establishes the ground rules before any code exists.*

- [x] Establish the fork-local code layout: all bridge code in `core/src/mindustry/pybridge/`
- [x] Establish the fork-local change-marking convention (`// FORK: pybridge — <reason>`)
- [x] Author `CLAUDE.md` — orientation, hard invariants, threading contract, merge policy
- [x] Author `docs/SECURITY.md` — running threat model, seeded T1–T16
- [x] Author `docs/PROTOCOL.md` — protocol v1 designed on paper before implementation
- [x] Author `docs/ROADMAP.md` — this file
- [ ] **Confirm a clean vanilla build on JDK 17** — `./gradlew desktop:dist`
- [ ] Confirm the game launches and a save loads
- [ ] Add `upstream` remote (`https://github.com/Anuken/Mindustry.git`) and record the merge base

> **Build not yet verified in this workspace.** The development container has **JDK 21**, and this
> project requires **JDK 17** (`build.gradle:197-198`); other versions do not work. The vanilla
> build has therefore *not* been run here. It must be confirmed on a JDK 17 machine before Phase 1
> starts — Phase 1 is meaningless if the baseline was already broken.

**Security checkpoint 0**

- [ ] `CLAUDE.md` §4 invariants are stated unambiguously and are not contradicted by any doc
- [ ] Every threat in `docs/SECURITY.md` has an owner phase
- [ ] No open threat is scheduled later than the phase that first creates its exposure

---

## Phase 1 — Inert block

*A placeable block that costs something and does nothing but log. Proves the registration path end
to end, with zero networking.*

- [ ] Create `core/src/mindustry/pybridge/` with a package-level README comment
- [ ] Add the block class (start from `world/blocks/production/GenericCrafter.java` as a shape
      reference; see `CLAUDE.md` §8)
- [ ] Give it real cost: build requirements, power draw, `update = true`
- [ ] Override `updateTile()` to log at a **throttled** rate — never once per tick, which would
      flood the console at 60 Hz
- [ ] Register it in `content/Blocks.java`, marked `// FORK: pybridge`
- [ ] Add a name and description to the bundle so the UI does not show a raw key
- [ ] Confirm the block appears in the build menu, places, draws, and can be deconstructed
- [ ] Confirm it survives a **save/load round trip**
- [ ] Confirm `./gradlew desktop:dist` still succeeds

**Security checkpoint 1**

- [ ] No socket, no thread, no file written — Phase 1 must add none of these
- [ ] The `// FORK: pybridge` marker count matches the number of upstream files touched
- [ ] Nothing in the block reads user-controlled input of any kind

---

## Phase 2 — Listener, no game access

*A loopback TCP listener that completes a handshake and answers `ping`. Touches zero game state.
Off by default. This is the phase that establishes every security primitive.*

### Opt-in and lifecycle

- [ ] Add a setting, **default off** (§4.3). No listener, no port bound, no file written unless on
- [ ] Start the listener only on explicit enable; stop it cleanly on disable and on game exit
- [ ] Verify with `netstat`/`ss` that **no port is bound** in a default installation

### Connection file (T3, T7, T8, T14)

- [ ] Generate a 256-bit token from `SecureRandom` — inline comment on why `Mathf.random` is
      forbidden, since it is the idiomatic choice in this codebase and will otherwise be reached for
- [ ] Write `<dataDirectory>/pybridge/connection.json` per `docs/PROTOCOL.md` §3
- [ ] Create it **owner-only atomically**: POSIX `0600` in a `0700` directory
- [ ] Windows: set an owner-only DACL via `AclFileAttributeView`, inheritance disabled
- [ ] **Verify** permissions by re-reading them after creation — do not trust the set call
- [ ] **Fail closed**: if verification fails, do not start the listener and explain why
- [ ] Regenerate the token every start; delete the file on clean shutdown
- [ ] **Test on Windows and on Linux.** A POSIX-only mitigation is not acceptable (`CLAUDE.md` §3)

### Listener and framing (T1, T2, T9, T15)

- [ ] Bind `127.0.0.1` as a **hard-coded constant** — no config path can reach it (§4.1)
- [ ] Network thread is a **daemon** with an explicit name and an uncaught-exception handler
      (pattern: `net/ArcNetProvider.java:384-392`)
- [ ] Implement length-prefixed framing per `docs/PROTOCOL.md` §2
- [ ] Enforce `MAX_FRAME_BYTES` **before allocation**, including pre-handshake (T9)
- [ ] Reject zero-length frames; close on any framing violation without an error reply
- [ ] Cap concurrent connections at 4; enforce a 5-second handshake timeout; back off on repeated
      failures (T15)

### Handshake (T3, T13)

- [ ] Require `hello` as frame 1; reject every other type with `unauthenticated`
- [ ] Compare tokens with `MessageDigest.isEqual` (constant-time)
- [ ] `auth_failed` carries **no diagnostic detail**; send no banner before the client speaks
- [ ] Answer only `protocol_version` and `malformed` before authentication
- [ ] Implement `ping`/`pong` — `pong` contains **no game state**, only wall-clock time

### Verification

- [ ] Throwaway Python script completes the handshake and round-trips a `ping`
- [ ] Wrong token → generic failure and close
- [ ] Oversized length prefix → immediate close, **no allocation spike** (watch heap)
- [ ] Garbage bytes → close, no crash, no stack trace to the client
- [ ] Connection flood → cap holds, game stays responsive
- [ ] Game exits cleanly with a client still connected; port released, file removed
- [ ] `./gradlew desktop:dist` still succeeds

**Security checkpoint 2 — the heaviest gate in the plan**

- [ ] `grep` the bridge package: **no** `Vars.world`, `Vars.state`, tile, building, or unit access
      anywhere on the network thread (§4.6)
- [ ] `grep` for the token in every logging call and error path — must not appear (T13)
- [ ] Bind address cannot be influenced by config, protocol, or UI (T1)
- [ ] No HTTP or WebSocket handling exists anywhere in the bridge (T2)
- [ ] Default install binds nothing — verified by observation, not by reading code (§4.3)
- [ ] Connection-file permissions verified **on Windows and POSIX** (T8)
- [ ] Walk `docs/PROTOCOL.md` §11 checklist against real code; update T3/T7/T8/T9/T13/T14/T15 to
      **Mitigated** with file references

---

## Phase 3 — Read-only telemetry

*Data flows out. Still no control. **This phase builds the thread boundary** and needs the most
careful review of any phase.*

- [ ] Write the threading design into `docs/SECURITY.md` **before** writing code, and walk through
      it in plain language first (`CLAUDE.md` §5)
- [ ] Define a fixed enumeration of telemetry topics — no dynamic or client-named queries (T6)
- [ ] Assemble snapshots **on the main thread** into immutable value objects
- [ ] Hand snapshots to the network thread; **never** a live `Building`, `Unit`, or `Tile` (T10)
- [ ] Serialise on the network thread, never on the main thread
- [ ] Bounded outbound queue; a slow client causes **drops**, never main-thread blocking (T12)
- [ ] Clamp `interval_ms` server-side to `max_telemetry_hz` — clamp, do not error
- [ ] Implement `subscribe`/`unsubscribe`/`ack`; drop subscriptions on disconnect
- [ ] **Refuse telemetry in multiplayer** with `not_permitted` — hard gate, not silent filtering
      (T11)
- [ ] Reject unknown topics with `invalid_argument`

### Verification

- [ ] Telemetry matches on-screen reality while the game runs
- [ ] Client that stops reading → drops, stable memory, game unaffected
- [ ] Client killed mid-stream → clean teardown, no leaked thread or queue
- [ ] Subscribe at 1 ms → clamped, not honoured, not an error
- [ ] Multiplayer session → subscription refused
- [ ] Long soak (30 min+) with telemetry running → no leak, no frame-time drift

**Security checkpoint 3**

- [ ] Re-read every line that crosses the thread boundary and confirm no mutable game object
      escapes (T10)
- [ ] Confirm the main thread never blocks on the network thread (T12)
- [ ] Confirm no telemetry field exposes data the player could not see in single-player (T11)
- [ ] Confirm the multiplayer gate cannot be bypassed by subscribing before a match starts
- [ ] Update T10/T11/T12 in `docs/SECURITY.md` with file references

---

## Phase 4 — Control commands

*Write access, gated behind the higher-cost block. Everything until now was preparation for
containing this.*

- [ ] Define the **closed action enumeration**; dispatch via explicit `switch` — no reflection,
      no name-to-method lookup (T6, §4.4)
- [ ] Require `"write"` in `permissions`, granted only by a control-capable block
- [ ] Validate on the main thread, before applying: authorising block exists, is ours, powered and
      enabled; target exists, is our team, is controllable; coordinates finite and in bounds (§4.5)
- [ ] Reject with **no partial application** on any validation failure
- [ ] Per-tick command budget with a **capped accumulator**, mlog-shaped
      (`LogicBlock.java:556-571`) — cap *before* use so budget cannot be banked (T4)
- [ ] Per-block and per-base limits so more blocks does not mean unbounded aggregate rate
- [ ] Enforce the per-base block cap

### Verification

- [ ] Command flood → budget holds, frame time stays flat, commands are not banked and burst
- [ ] Out-of-bounds, NaN, and infinite coordinates → rejected, no crash
- [ ] Commands targeting another team's units/buildings → rejected
- [ ] Destroying the authorising block mid-stream → subsequent commands rejected
- [ ] Unpowered block → commands rejected
- [ ] Save/load with commands in flight → no corruption

**Security checkpoint 4**

- [ ] Enumerate every reachable action and confirm each is deliberately implemented and validated
      (T6) — this is the allowlist audit, and it is the most important review in the project
- [ ] Confirm no command path reaches reflection, class loading, the filesystem, processes, or the
      environment (§4.4)
- [ ] Confirm no command can affect another team, or state outside the simulation
- [ ] Confirm throttling cannot be bypassed by multiple connections or multiple blocks
- [ ] Update T4/T6 in `docs/SECURITY.md` with file references

---

## Phase 5 — Python client library

*A small reference client. Lives outside the Java tree.*

- [ ] Framing helpers with a correct partial-read loop (`docs/PROTOCOL.md` §2)
- [ ] Connection-file discovery, including a stale-file check via `pid`
- [ ] Handshake, request/response correlation by `id`, push handling for unsolicited frames
- [ ] Typed wrappers for telemetry topics and command actions
- [ ] Explicit errors carrying the protocol `code` — never branch on `message` text
- [ ] Client-side self-pacing from the advertised `limits`, while assuming the server enforces
      independently
- [ ] README with a minimal working example
- [ ] Tests that do not require a running game (framing, correlation, error mapping)

**Security checkpoint 5**

- [ ] Client never writes, weakens, or relocates the connection file — it is read-only to the client
- [ ] Client never logs the token (T13)
- [ ] No client convenience feature implies a server capability that violates §4.4 — if the library
      wants it, the answer is a new validated command, never a general execution path

---

## Phase 6 — Multiplayer and balance

*The fairness phase, and where T11 is finally resolved.*

- [ ] Server-side opt-in flag; bridge **refused by default** on servers
- [ ] Implement fog-of-war and team-visibility filtering for telemetry snapshots (T11)
- [ ] Default to **omitting** anything whose visibility is uncertain
- [ ] Lift the single-player-only telemetry restriction **only after** filtering is implemented and
      reviewed
- [ ] Decide and document the throttle target: "roughly what a skilled human could do" versus
      "does not break the simulation" — these give very different numbers (T5)
- [ ] Tune block cost, power draw, and per-base cap so the bridge is not strictly superior to mlog
- [ ] Document the balance rationale in `docs/SECURITY.md`

**Security checkpoint 6**

- [ ] Adversarial review: does telemetry reveal *anything* the player could not obtain by playing?
      (T11)
- [ ] Confirm the server flag cannot be overridden by a client
- [ ] Confirm a compromised client cannot exceed the fairness envelope (T5)
- [ ] Full re-read of `docs/SECURITY.md` against the finished system; no threat left **Open**

---

## Standing rules

Applies to every phase:

1. **Each phase ends with a working build.** `./gradlew desktop:dist` on JDK 17, and the game runs.
2. **Each phase ends with `docs/SECURITY.md` current.** Mitigations move to **Mitigated** with real
   file references; new capabilities get examined for new threats.
3. **Small, reviewable increments** (`CLAUDE.md` §9). Land one coherent piece, get it reviewed,
   then continue.
4. **Report honestly.** If a build or test fails or a step was skipped, say so with the actual
   output. A checkbox ticked without verification is worse than an unticked one, because it removes
   the reason to look.
5. **A phase gate that fails blocks the next phase.** It does not become a follow-up task.
