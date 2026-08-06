# Roadmap — Mindustry Python Control Bridge

Phased plan. **Every phase ends with the game still building and running**, so work can stop at any
phase boundary and leave something that works.

Each phase has a **security review checkpoint** that must pass before the next phase begins. The
checkpoints are not ceremony: they are the points at which `docs/SECURITY.md` is reconciled against
code that actually exists.

Threat IDs (`T1`–`T16`) refer to `docs/SECURITY.md`. Invariant references (`§4.1` etc.) refer to
`CLAUDE.md`. `H`-prefixed IDs (`H2.3`) refer to `HUMAN_TODO.md`.

**Legend:** `[ ]` not started · `[~]` in progress · `[x]` done

---

## How this file relates to `HUMAN_TODO.md`

Checkboxes in this file are things an automated session can do and tick: writing code, writing
docs, greps, code reads. Anything that needs a real machine — a JDK 17 build, a running game, a
second operating system, a multiplayer match, a balance judgement — lives in **`HUMAN_TODO.md`**
and is referenced from here as **“Human: H2.3”** rather than duplicated. One checkbox, one owner;
two files can never disagree about whether something was verified.

**A pending human item does not stop development.** Work continues into the next phase while
`HUMAN_TODO.md` items sit unticked. What a pending item withholds is narrower and non-negotiable:

- the phase **cannot be marked done** here, and
- no threat may move to **Mitigated** in `docs/SECURITY.md` on the strength of it, and
- a `GATE`-tagged item additionally means the feature stays **off by default and unadvertised**
  until it passes (which costs nothing — it is off by default anyway, §4.3).

A human item that has been run and **failed** is different in kind: that is a real defect and a
hard stop, exactly as standing rule 5 has always said. *Pending* is not *failed*.

---

## Phase 0 — Fork hygiene

*No behaviour change. Establishes the ground rules before any code exists.*

- [x] Establish the fork-local code layout: all bridge code in `core/src/mindustry/pybridge/`
- [x] Establish the fork-local change-marking convention (`// FORK: pybridge — <reason>`)
- [x] Author `CLAUDE.md` — orientation, hard invariants, threading contract, merge policy
- [x] Author `docs/SECURITY.md` — running threat model, seeded T1–T16
- [x] Author `docs/PROTOCOL.md` — protocol v1 designed on paper before implementation
- [x] Author `docs/ROADMAP.md` — this file
- [x] Add `upstream` remote (`https://github.com/Anuken/Mindustry.git`) and record the merge base
- [x] Author `HUMAN_TODO.md` — the human verification queue, and the rule that a pending item
      there does not block development
- Human: **H0.1** clean vanilla build on JDK 17 · **H0.2** game launches and a save loads

> **Merge base with upstream:** `fc0113c887804b6c78881e8b1c2da394ff588213`
> ("Automatic bundle update", 2026-08-05). Every commit in this fork after that point is ours; at
> the time of recording, upstream `master` was 1 commit ahead
> (`8718814bff94bd4e6e2141b09c72985f51ac9eff`). Re-derive at any time with
> `git merge-base HEAD upstream/master`.

> **Build not yet verified anywhere.** JDK 17 *can* be installed in the development container
> (verified 2026-08-06), but dependency resolution fails there: the engine dependency (`Arc`) is
> published on `jitpack.io`, and that host plus `dl.google.com` are refused by the environment's
> network policy. No compile has been run. `HUMAN_TODO.md` §5 records the exact state and how to
> lift it.
>
> H0.1 therefore stands. It does **not** hold up Phase 1 development — but Phase 1 cannot be
> marked done until the baseline build has actually been observed to work, because Phase 1 is
> meaningless if the baseline was already broken.

**Security checkpoint 0**

- [x] `CLAUDE.md` §4 invariants are stated unambiguously and are not contradicted by any doc
- [x] Every threat in `docs/SECURITY.md` has an owner phase
- [x] No open threat is scheduled later than the phase that first creates its exposure

> **Checkpoint 0 review, 2026-08-05.** Passed after four fixes; see the `docs/SECURITY.md` change
> log for the full record.
>
> 1. **Handshake contradiction.** This file said a non-`hello` first frame is rejected with
>    `unauthenticated`, while `docs/PROTOCOL.md` §4 (and the next line of this file) said it is
>    closed silently. Resolved in favour of the silent close — an error reply is an oracle.
> 2. **`unauthenticated` was unreachable.** With `hello` mandatory as frame 1, no path could emit
>    the code. Documented in `PROTOCOL.md` §8 as a defensive default-case rather than deleted.
> 3. **T1/T2 had no owner phase** (`—` in the summary table) even though both bodies name Phase 2
>    code that must uphold them. Given a verification phase, so "structural" cannot come to mean
>    "nobody checks".
> 4. **T5 exposure preceded its mitigation.** Phase 4 granted control commands with no multiplayer
>    gate, while T5's fairness mitigations land in Phase 6 — check 3 above failed. Phase 4 now
>    carries the same hard multiplayer gate Phase 3 applies to telemetry.
>
> Note that the port *is* user-configurable (`PROTOCOL.md` §1) while the address is not. That is
> consistent with §4.1, but Phase 2 must keep it so: the setting must be an **integer port only**,
> never a `host:port` string, which is the obvious way a host field gets reintroduced by accident.

---

## Phase 1 — Inert block

*A placeable block that costs something and does nothing but log. Proves the registration path end
to end, with zero networking.*

- [x] Create `core/src/mindustry/pybridge/` with a package-level README comment
      (`package-info.java`)
- [x] Add the block class (start from `world/blocks/production/GenericCrafter.java` as a shape
      reference; see `CLAUDE.md` §8) — `pybridge/PyBridgeBlock.java`
- [x] Give it real cost: build requirements, power draw, `update = true`
- [x] Override `updateTile()` to log at a **throttled** rate — never once per tick, which would
      flood the console at 60 Hz (one line per 600 ticks = 10 s per placed block)
- [x] Register it in `content/Blocks.java`, marked `// FORK: pybridge`
- [x] Add a name and description to the bundle so the UI does not show a raw key
- [x] Add a block sprite (`core/assets-raw/sprites/blocks/pybridge/pybridge.png`, 32×32) so the
      block draws as itself rather than as the atlas error texture

Human: **H1.1** block appears, places, draws, deconstructs · **H1.2** survives a save/load round
trip · **H1.3** logging is throttled, not per-tick · **H1.4** `./gradlew desktop:dist` still
succeeds.

> **Phase 1 cannot be marked done yet.** Every code item above is written, but nothing has been
> compiled or run: the container still cannot resolve `jitpack.io` (`HUMAN_TODO.md` §5), and H0.1
> — the baseline vanilla build — has not been observed either. What *was* checked automatically is
> recorded under checkpoint 1 below; it is a set of greps and a type-check against hand-written
> stubs, which is not a build and must not be reported as one.

> **Two deliberate deferrals, so a later session does not read them as oversights.**
>
> 1. **No tech-tree node.** The block is registered in `Blocks.java` but not in
>    `content/TechTree.java`, so it appears in custom/sandbox games and is not researchable in the
>    campaign. Placing it in the tech tree is a balance decision (which node it hangs off, what it
>    costs to unlock) and belongs with **H6.4**, not with an automated session. It also keeps a
>    second heavily-edited upstream file out of the merge surface for now.
> 2. **Block cost, power draw and size are provisional.** Marked as such in both
>    `PyBridgeBlock.java` and `Blocks.java`. The real numbers follow from **H6.4** — the judgement
>    that the bridge must not end up strictly superior to an mlog processor.

**Security checkpoint 1**

- [x] No socket, no thread, no file written — Phase 1 must add none of these
- [x] Every upstream edit site carries a `FORK: pybridge` marker
- [x] Nothing in the block reads user-controlled input of any kind

> **Checkpoint 1 review, 2026-08-06.** Passed. What was actually checked, and how:
>
> 1. **No socket, thread, or file.** `grep -rnE` over `core/src/mindustry/pybridge/` for
>    `Socket|ServerSocket|Thread|Executor|Runnable|Files\.|Fi\.|Core\.app\.post|new File|InetAddress|Channel`
>    returns exactly one hit, and it is prose: the word "WebSocket" in the package README comment
>    listing what must never be built. `PyBridgeBlock.java` itself returns nothing. The package's
>    entire import list is `arc.util`, `mindustry.gen`, `mindustry.world` and
>    `mindustry.world.meta`; it reaches no I/O API at all.
> 2. **Markers.** Two upstream files are touched — `content/Blocks.java` and
>    `assets/bundles/bundle.properties` — across **four** edit sites: the import, the field
>    declaration, the `load()` registration, and the bundle keys. Every site carries a marker.
>    The checkbox previously read "marker count matches the number of upstream files touched",
>    which is not the property that matters and is not achievable: one file can need edits in
>    several places (a field declaration and a registration cannot be adjacent), and a
>    `.properties` file cannot use the `//` marker form because `#` is its comment character.
>    Reworded to state the intent — *every edit site is marked* — which is what makes the
>    pre-merge grep complete. Note that `grep -rn "FORK: pybridge" --include=*.java .`
>    (`CLAUDE.md` §6) will not show the bundle marker; use an unfiltered grep before a merge.
> 3. **No input surface.** `PyBridgeBlock` makes no `config(...)` call and leaves `configurable`
>    at its default of `false`, so no player interaction, schematic, save field or mlog
>    instruction can hand it data. Its `updateTile()` interpolates only its own tile coordinates
>    and power efficiency into the log line. This is the first fork-local code that writes to the
>    console; T13 (token disclosure through logs) has nothing to disclose yet, and the habit that
>    keeps it that way is recorded in `docs/SECURITY.md`.

---

## Phase 2 — Listener, no game access

*A loopback TCP listener that completes a handshake and answers `ping`. Touches zero game state.
Off by default. This is the phase that establishes every security primitive.*

### Opt-in and lifecycle

- [ ] Add a setting, **default off** (§4.3). No listener, no port bound, no file written unless on
- [ ] Start the listener only on explicit enable; stop it cleanly on disable and on game exit

Human: **H2.1 `GATE`** confirm by observation that a default installation binds nothing.

### Connection file (T3, T7, T8, T14)

- [ ] Generate a 256-bit token from `SecureRandom` — inline comment on why `Mathf.random` is
      forbidden, since it is the idiomatic choice in this codebase and will otherwise be reached for
- [ ] Write `<dataDirectory>/pybridge/connection.json` per `docs/PROTOCOL.md` §3
- [ ] Create it **owner-only atomically**: POSIX `0600` in a `0700` directory
- [ ] Windows: set an owner-only DACL via `AclFileAttributeView`, inheritance disabled
- [ ] **Verify** permissions by re-reading them after creation — do not trust the set call
- [ ] **Fail closed**: if verification fails, do not start the listener and explain why
- [ ] Regenerate the token every start; delete the file on clean shutdown

Human: **H2.2 `GATE`** POSIX permissions · **H2.3 `GATE`** Windows DACL · **H2.4 `GATE`**
fail-closed on a filesystem with no ACL support. A POSIX-only result does not discharge T8
(`CLAUDE.md` §3) — H2.2 and H2.3 are both required.

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

- [ ] Require `hello` as frame 1; any other first frame is closed **silently** — no error reply at
      all (`docs/PROTOCOL.md` §4). Replying would make the listener a probing oracle
- [ ] Compare tokens with `MessageDigest.isEqual` (constant-time)
- [ ] `auth_failed` carries **no diagnostic detail**; send no banner before the client speaks
- [ ] Answer only `protocol_version` and `malformed` before authentication
- [ ] Implement `ping`/`pong` — `pong` contains **no game state**, only wall-clock time

### Verification

All of Phase 2's verification needs a running game, so it lives in `HUMAN_TODO.md`:

Human: **H2.5** handshake and `ping` round trip · **H2.6** wrong token refused without detail ·
**H2.7 `GATE`** oversized length prefix causes no allocation (watch the heap) · **H2.8** garbage
bytes survivable · **H2.9** connection flood hits the cap · **H2.10** clean shutdown with a client
attached · **H2.11** token absent from the console in practice · **H1.4** `desktop:dist` still
succeeds.

**Security checkpoint 2 — the heaviest gate in the plan**

- [ ] `grep` the bridge package: **no** `Vars.world`, `Vars.state`, tile, building, or unit access
      anywhere on the network thread (§4.6)
- [ ] `grep` for the token in every logging call and error path — must not appear (T13)
- [ ] Bind address cannot be influenced by config, protocol, or UI (T1)
- [ ] No HTTP or WebSocket handling exists anywhere in the bridge (T2)
- [ ] Walk `docs/PROTOCOL.md` §11 checklist against real code; update T3/T7/T9/T13/T14/T15 to
      **Mitigated** with file references

Human, and required before this checkpoint passes: **H2.1** (default install binds nothing —
observed, not read), **H2.2**/**H2.3**/**H2.4** (permissions on both platforms, T8), **H2.7**
(no allocation on a hostile length prefix, T9), **H2.11** (token never printed, T13). T8 stays
**Planned** until H2.2, H2.3 and H2.4 have all passed — code review cannot substitute for looking
at the resulting file's mode bits.

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

Human: **H3.1** telemetry matches on-screen reality · **H3.2** a client that stops reading causes
drops, not stalls · **H3.3** client killed mid-stream tears down cleanly · **H3.4** `interval_ms`
clamped rather than rejected · **H3.5 `GATE`** telemetry refused in multiplayer · **H3.6** 30-minute
soak with no leak or frame-time drift.

**Security checkpoint 3**

- [ ] Re-read every line that crosses the thread boundary and confirm no mutable game object
      escapes (T10)
- [ ] Confirm the main thread never blocks on the network thread (T12)
- [ ] Confirm no telemetry field exposes data the player could not see in single-player (T11)
- [ ] Confirm by code reading that the multiplayer gate cannot be bypassed by subscribing before a
      match starts
- [ ] Update T10/T11/T12 in `docs/SECURITY.md` with file references

Human, and required before this checkpoint passes: **H3.5** (the multiplayer refusal, including
the subscribe-before-the-match path, T11) and **H3.2** (a stalled client cannot stall the game,
T12 — the observed counterpart to the code reading above).

---

## Phase 4 — Control commands

*Write access, gated behind the higher-cost block. Everything until now was preparation for
containing this.*

- [ ] **Refuse control commands in multiplayer** with `not_permitted` — the same hard gate Phase 3
      applies to telemetry, for the same reason: T5's fairness mitigations (server opt-in flag,
      throttle tuning) do not exist until Phase 6, so control must not be reachable in a
      multiplayer session before then. Lifted only at the Phase 6 gate
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

Human: **H4.1 `GATE`** command flood stays inside the budget, with no burst after a stall ·
**H4.2** out-of-bounds/NaN/infinite coordinates rejected · **H4.3** cross-team commands rejected ·
**H4.4** authorising block destroyed mid-stream · **H4.5** unpowered block cannot command ·
**H4.6 `GATE`** control refused in multiplayer · **H4.7** save/load with commands in flight ·
**H4.8** throttle cannot be bypassed by multiple connections or blocks.

**Security checkpoint 4**

- [ ] Enumerate every reachable action and confirm each is deliberately implemented and validated
      (T6) — this is the allowlist audit, and it is the most important review in the project
- [ ] Confirm no command path reaches reflection, class loading, the filesystem, processes, or the
      environment (§4.4)
- [ ] Confirm no command can affect another team, or state outside the simulation
- [ ] Confirm by code reading that throttling cannot be bypassed by multiple connections or blocks
- [ ] Update T6 in `docs/SECURITY.md` with file references

Human, and required before this checkpoint passes: **H4.1** and **H4.8** — T4's capped accumulator
is a claim about behaviour under load, and only a flood test settles it. T4 stays **Planned** until
both pass.

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

*Phase 5 is deliberately shaped so almost all of it is testable with no game running.* Only
**H5.1** (the README example works against a real game) needs a human.

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
- [ ] Document the balance rationale in `docs/SECURITY.md`

Human: **H6.1 `GATE`** server opt-in flag is genuinely off by default · **H6.2 `GATE`** client
cannot override it · **H6.3 `GATE`** adversarial fog-of-war review in a live match · **H6.4** the
balance judgement itself — the throttle target ("roughly what a skilled human could do" versus
"does not break the simulation", T5) and the resulting cost, power and cap tuning. **H6.4 is a
decision, not a check**: an automated session must not settle it alone.

**Security checkpoint 6**

- [ ] Confirm by code reading that the server flag cannot be overridden by a client
- [ ] Confirm a compromised client cannot exceed the fairness envelope (T5)
- [ ] Full re-read of `docs/SECURITY.md` against the finished system; no threat left **Open**

Human, and required before this checkpoint passes: **H6.1**, **H6.2** and **H6.3**. The
single-player-only telemetry restriction is lifted **only** after H6.3 passes — lifting it on a
code reading alone would ship a wallhack (T11).

---

## Standing rules

Applies to every phase:

1. **Each phase ends with code that is meant to build and run.** `./gradlew desktop:dist` on
   JDK 17, and the game runs. Where that cannot be confirmed automatically, it is queued in
   `HUMAN_TODO.md` and the phase stays unmarked until it is — but development continues in the
   meantime. Stalling the project on a build check does not make anything safer; ticking the box
   to avoid stalling would.
2. **Each phase ends with `docs/SECURITY.md` current.** Mitigations move to **Mitigated** with real
   file references; new capabilities get examined for new threats.
3. **Small, reviewable increments** (`CLAUDE.md` §9). Land one coherent piece, get it reviewed,
   then continue. This matters more while builds are human-gated: a small change that breaks the
   build is cheap to find, a large one is not.
4. **Report honestly.** If a build or test fails or a step was skipped, say so with the actual
   output. A checkbox ticked without verification is worse than an unticked one, because it removes
   the reason to look.
5. **A phase gate that *fails* blocks the next phase.** It does not become a follow-up task. A
   gate that is merely **pending** on a human check does not block development — see the section
   at the top of this file. *Pending* and *failed* are different states and must not be collapsed
   in either direction.
6. **Verification provenance is recorded, not assumed.** A threat moves to **Mitigated** only when
   the thing that confirmed it is named: a file reference for code, or a passed `H`-item for an
   observation. "Reviewed and looks right" is not a mitigation record.
7. **No personal data in any document** (`CLAUDE.md` §4.7) — including result notes, pasted
   command output, and screenshots.
