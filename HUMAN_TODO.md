# HUMAN_TODO — verification that only a human on a real machine can do

This file is the **single place** where work that an automated session cannot perform is tracked.
Everything here needs a real desktop machine: a JDK 17 install, a display, a running game, a second
operating system, or a human judgement call about fairness or balance.

Two rules make this file work:

1. **Nothing is ticked here that was not actually run.** A ticked box means the check was
   performed and its result observed — not that the code "should" pass it. An unticked box is
   useful information; a wrongly ticked box destroys the reason to look. (`CLAUDE.md` §9.)
2. **This file is the only owner of these checkboxes.** `docs/ROADMAP.md` references items by ID
   (`H2.4`) instead of duplicating them, so there is never a pair of boxes disagreeing about
   whether something was verified.

**No personal data anywhere in this file.** Record results as OS family and version, JDK build, and
date — never a hostname, account name, home-directory path, or screenshot containing one. Redact
paths as `<data-directory>`. This is the hard policy in `CLAUDE.md` §4.7 and it applies to the
result notes at the bottom of this file as much as to anything else.

**Legend:** `[ ]` not done · `[~]` partly done · `[x]` done and observed · `[!]` done and **failed**

---

## 1. Does a pending item here block development?

**No — with one exception.** Development of later phases continues while items here are pending.
What a pending item blocks is *claiming a phase is verified*, which is a different thing.

| State of an item | Effect |
|---|---|
| `[ ]` pending | Code work continues, including on later phases. The phase it belongs to **cannot be marked done** in `docs/ROADMAP.md`, and no threat may move to **Mitigated** in `docs/SECURITY.md` on the strength of it. |
| `[!]` failed | **Hard stop.** A failed check is a real defect. Fix it before any further phase work — this is the existing rule that a failed gate blocks the next phase (`ROADMAP.md` standing rule 5), and it is unchanged. |
| `[x]` passed | Phase may be marked done; dependent threats may move to **Mitigated** with a file reference. |

**The exception — security-gating items.** Items tagged **`GATE`** below verify a §4 invariant or a
threat mitigation by *observation*, which is the only way those particular properties can be
confirmed. Code that depends on one may be written and reviewed, but the feature it belongs to must
stay **off by default and unadvertised** until the `GATE` item passes. In practice this costs
nothing, because the bridge is off by default anyway (`CLAUDE.md` §4.3).

Rationale for allowing development to run ahead: the alternative is that the whole project stalls
on a build check, and stalling does not make anything safer. What it would make less safe is
*ticking the box anyway to keep moving* — so the box stays untickable and the work continues
beside it.

---

## 2. Why these cannot be automated

| Capability needed | Why an automated session lacks it |
|---|---|
| **JDK 17 build** | The project requires JDK 17 (`build.gradle:197-198`). A container ships whatever JDK it ships; even with JDK 17 installed, dependency resolution needs network hosts that may be blocked — see §5. |
| **A display** | The desktop client needs a GPU/display context. Headless containers have none, so nothing that requires *seeing* the game can run there. |
| **Playing the game** | Placing a block, loading a save, watching frame time, judging whether telemetry matches the screen — all require a human at the controls. |
| **A second OS** | Windows is a first-class target (`CLAUDE.md` §3). Windows ACL behaviour cannot be tested from Linux, and no emulation substitutes for it. |
| **A multiplayer session** | Needs a real server and a second client. |
| **Balance judgement** | "Is this fair?" and "is this strictly better than mlog?" are decisions, not assertions. |

---

## 3. The queue

Each item states **Run** (what to do), **Pass** (what a pass looks like), and **Covers** (which
roadmap phase, security checkpoint, or threat it discharges).

### H0 — Baseline (before Phase 1 can be marked done)

- [ ] **H0.1 `GATE` Clean vanilla build on JDK 17**
      **Run:** `./gradlew desktop:dist` on a JDK 17 toolchain, on an unmodified checkout.
      **Pass:** build succeeds; a jar appears in `desktop/build/libs/`.
      **Covers:** Phase 0. Everything after this is meaningless if the baseline was already broken.
- [ ] **H0.2 Vanilla game launches and a save loads**
      **Run:** `./gradlew desktop:run`; start or load a save; play for a minute.
      **Pass:** the game runs at a normal frame rate with no errors in the console.
      **Covers:** Phase 0.

### H1 — Inert block (Phase 1)

- [ ] **H1.1 Block appears and behaves in the build menu**
      **Run:** launch, open the build menu, place the block, deconstruct it. Use a **custom or
      sandbox game**, not the campaign: the block has no tech-tree node yet, which is a deliberate
      deferral to H6.4 (`docs/ROADMAP.md` phase 1), so it is not researchable. Look under the
      **Logic** category, named "Control Bridge".
      **Pass:** it appears with a real name and description (no raw bundle key such as
      `block.pybridge.name`), places, draws, and deconstructs.
      **Covers:** Phase 1.
      **If it draws as the pink/white error texture,** the sprite atlas is stale rather than the
      sprite missing. `desktop:run` and `desktop:dist` depend on `:tools:pack` **only when sprites
      have never been packed** (`desktop/build.gradle:68`), so a checkout that was built before the
      block sprite was added will not repack on its own. Run `./gradlew tools:pack` once, then
      relaunch. This is worth knowing before concluding the sprite is broken.
- [ ] **H1.2 Block survives a save/load round trip**
      **Run:** place it, save, quit to menu, reload the save.
      **Pass:** the block is still there, intact, with its state.
      **Covers:** Phase 1.
- [ ] **H1.3 Logging is throttled, not per-tick**
      **Run:** watch the console for 60 seconds with the block placed.
      **Pass:** a slow trickle of lines, not a 60 Hz flood. Expect roughly **one line every ten
      seconds per placed block**, prefixed `pybridge:`, reporting the block's tile coordinates and
      power efficiency. An **unpowered** block still logs, with efficiency `0.00` — that is
      intentional (the timer uses `Time.delta`, not `edelta()`), so silence means the block is not
      updating rather than that it is unpowered.
      **Covers:** Phase 1.
- [ ] **H1.4 `./gradlew desktop:dist` still succeeds** — see §5; automatable once the build runs
      headlessly, human-only until then.
      **Covers:** Phase 1, standing rule 1.

### H2 — Listener (Phase 2) — the heaviest set

- [ ] **H2.1 `GATE` A default installation binds nothing**
      **Run:** launch with the feature never enabled. Check with `ss -ltnp` (Linux),
      `netstat -ano` (Windows), or `lsof -iTCP -sTCP:LISTEN` (macOS).
      **Pass:** no bridge port is listening, and no `<data-directory>/pybridge/` file exists.
      **Covers:** `CLAUDE.md` §4.3, security checkpoint 2. Must be verified by **observation** —
      reading the code is not sufficient, because this is precisely the property a code path can
      silently break.
- [ ] **H2.2 `GATE` Connection-file permissions on POSIX**
      **Run:** enable the feature; `ls -l <data-directory>/pybridge/`.
      **Pass:** file is `0600`, directory is `0700`, both owned by the running account.
      **Covers:** T8, security checkpoint 2.
- [ ] **H2.3 `GATE` Connection-file permissions on Windows**
      **Run:** enable the feature on Windows; inspect the DACL
      (`icacls <data-directory>\pybridge\connection.json`).
      **Pass:** only the file owner has access; inheritance disabled; no `Users` or `Everyone`
      entry.
      **Covers:** T8, security checkpoint 2, `CLAUDE.md` §3. **A POSIX-only result does not
      discharge T8** — this item and H2.2 are both required.
- [ ] **H2.4 `GATE` Fail-closed when permissions cannot be secured**
      **Run:** point the data directory at a filesystem without ACL support (FAT32/exFAT volume or
      USB stick) and enable the feature.
      **Pass:** the listener does **not** start, no token is written, and a clear message explains
      why.
      **Covers:** T8 step 4 — the load-bearing half of that mitigation.
- [ ] **H2.5 Handshake and `ping` round trip**
      **Run:** throwaway Python script: read the connection file, send `hello`, then `ping`.
      **Pass:** `welcome` then `pong`; `pong` carries wall-clock time and no game state.
      **Covers:** Phase 2 verification, `PROTOCOL.md` §10.
- [ ] **H2.6 Wrong token is refused without detail**
      **Run:** connect with a corrupted token.
      **Pass:** generic failure, connection closed, no hint as to *why* it failed.
      **Covers:** T3, T13.
- [ ] **H2.7 `GATE` Oversized length prefix causes no allocation**
      **Run:** send a frame header declaring a huge length (e.g. 2 GB) while watching heap usage in
      a JVM monitor.
      **Pass:** immediate disconnect; **no allocation spike**. Repeat *before* authenticating — the
      limit must hold pre-handshake.
      **Covers:** T9. The heap observation is the point; a clean disconnect alone does not prove
      the allocation was avoided.
- [ ] **H2.8 Garbage bytes are survivable**
      **Run:** send random bytes, truncated frames, and a valid header with a truncated body.
      **Pass:** connection closes; the game does not crash; no stack trace reaches the client.
      **Covers:** Phase 2 verification, T2.
- [ ] **H2.9 Connection flood hits the cap**
      **Run:** open many concurrent connections; open connections that never complete the
      handshake.
      **Pass:** the cap holds, half-open connections time out, the game stays responsive.
      **Covers:** T15.
- [ ] **H2.10 Clean shutdown with a client attached**
      **Run:** connect a client, then exit the game normally.
      **Pass:** port released, connection file removed, no lingering thread.
      **Covers:** T14, Phase 2 verification.
- [ ] **H2.11 Token absent from the console in practice**
      **Run:** run through H2.5–H2.9 with the console visible, then search the log for the token.
      **Pass:** the token never appears — not on success, not on failure, not in any stack trace.
      **Covers:** T13. The agent-side `grep` for logging calls (§5) is necessary but not
      sufficient; this is the observed half.

### H3 — Telemetry (Phase 3)

- [ ] **H3.1 Telemetry matches on-screen reality**
      **Run:** subscribe to each topic while playing; compare against what the UI shows.
      **Pass:** values agree; nothing is reported that the screen does not corroborate.
      **Covers:** Phase 3, T11.
- [ ] **H3.2 A client that stops reading causes drops, not stalls**
      **Run:** connect, subscribe, then stop reading the socket. Leave it for several minutes.
      **Pass:** frames are dropped; memory is stable; the game's frame time is unaffected.
      **Covers:** T12, security checkpoint 3.
- [ ] **H3.3 Client killed mid-stream tears down cleanly**
      **Pass:** subscriptions dropped, no leaked thread or queue, no error spam.
      **Covers:** Phase 3.
- [ ] **H3.4 Interval clamping**
      **Run:** subscribe with `interval_ms: 1`.
      **Pass:** clamped to `max_telemetry_hz` and acknowledged — **not** an error.
      **Covers:** Phase 3.
- [ ] **H3.5 `GATE` Telemetry refused in multiplayer**
      **Run:** join or host a multiplayer session; attempt to subscribe. Also attempt to subscribe
      *before* the match starts and keep the subscription open as it begins.
      **Pass:** `not_permitted` in both cases; the pre-match path does not survive into the match.
      **Covers:** T11, security checkpoint 3.
- [ ] **H3.6 Long soak**
      **Run:** 30 minutes or more with telemetry streaming.
      **Pass:** no memory growth trend, no frame-time drift.
      **Covers:** Phase 3.

### H4 — Control commands (Phase 4)

- [ ] **H4.1 `GATE` Command flood stays inside the budget**
      **Run:** send commands as fast as the socket allows; then stall for several seconds and
      resume, to test that budget was not banked.
      **Pass:** frame time stays flat; the post-stall resumption produces **no burst**.
      **Covers:** T4 — the capped accumulator is the specific thing being tested.
- [ ] **H4.2 Hostile coordinates are rejected**
      **Run:** out-of-bounds, `NaN`, and infinite coordinates.
      **Pass:** rejected with no crash and no partial application.
      **Covers:** §4.5.
- [ ] **H4.3 Cross-team commands are rejected**
      **Covers:** §4.5, security checkpoint 4.
- [ ] **H4.4 Authorising block destroyed mid-stream**
      **Run:** stream commands, then destroy the block issuing them.
      **Pass:** subsequent commands are rejected.
      **Covers:** §4.5.
- [ ] **H4.5 Unpowered block cannot command**
      **Covers:** §4.5.
- [ ] **H4.6 `GATE` Control refused in multiplayer**
      **Pass:** `not_permitted`, until the Phase 6 gate lifts it.
      **Covers:** T5 interim gate.
- [ ] **H4.7 Save/load with commands in flight**
      **Pass:** no corruption; queued commands do not survive into a reloaded world in a way that
      applies them out of context.
      **Covers:** Phase 4.
- [ ] **H4.8 Throttle cannot be bypassed by fan-out**
      **Run:** multiple connections at once; multiple bridge blocks at once.
      **Pass:** aggregate rate stays inside the per-base cap.
      **Covers:** T4, security checkpoint 4.

### H5 — Python client (Phase 5)

Most of Phase 5 is testable without a game and is therefore **not** in this file. Only this needs a
human:

- [ ] **H5.1 The README example works against a real game**
      **Pass:** copy-paste of the documented example connects and works, on a machine that has not
      seen the project before.
      **Covers:** Phase 5.

### H6 — Multiplayer and balance (Phase 6)

- [ ] **H6.1 `GATE` Server opt-in flag is genuinely off by default**
      **Run:** a stock dedicated server; attempt to use the bridge.
      **Pass:** refused. Then enable the flag and confirm it is honoured.
      **Covers:** T5, security checkpoint 6.
- [ ] **H6.2 `GATE` Client cannot override the server flag**
      **Covers:** T5, security checkpoint 6.
- [ ] **H6.3 `GATE` Adversarial fog-of-war review in a live match**
      **Run:** with fog of war on, compare telemetry against what the player can legitimately see.
      **Pass:** telemetry reveals **nothing** the player could not obtain by playing. Anything of
      uncertain visibility is omitted.
      **Covers:** T11 — the item that decides whether the multiplayer restriction may be lifted.
      Until this passes, it stays in place.
- [ ] **H6.4 Balance judgement**
      **Decide:** is the throttle tuned to "roughly what a skilled human could do" or to "does not
      break the simulation"? These give very different numbers (T5).
      **Then:** tune block cost, power draw and per-base cap so the bridge is not strictly superior
      to mlog, and record the rationale in `docs/SECURITY.md`.
      **Covers:** T5, Phase 6.

---

## 4. Platform matrix

Windows, Linux and macOS are all first-class (`CLAUDE.md` §3). Most items need only one platform;
these need more:

| Item | Linux | Windows | macOS |
|---|---|---|---|
| H0.1 build | required | required | best effort |
| H2.1 nothing bound by default | required | required | best effort |
| H2.2 / H2.3 file permissions | required (H2.2) | required (H2.3) | covered by H2.2 |
| H2.4 fail-closed | best effort | required | best effort |
| everything else | required | best effort | best effort |

"Best effort" means: run it if that platform is available, and note if it was not. Do not tick it
on the assumption that another platform's result carries over — for file permissions in particular,
it does not.

---

## 5. What is checked automatically instead — do not redo these by hand

These are performed by an automated session at each gate. They are listed so the human queue stays
as short as it honestly can be.

- `grep` of the bridge package for `Vars.world`, `Vars.state`, tile, building and unit access on
  the network thread (§4.6).
- `grep` for the token variable in every logging call and error path (T13) — paired with H2.11,
  which observes the other half.
- `grep` for any bind address that is not the hard-coded loopback constant (T1).
- `grep` for HTTP/WebSocket handling anywhere in the bridge (T2).
- `grep -rn "FORK: pybridge"` to confirm the marker count matches the upstream files touched.
- Enumerating the command dispatch `switch` and confirming every reachable action is deliberately
  implemented (T6, security checkpoint 4).
- Reading `docs/PROTOCOL.md` §11 against the code.
- Python client tests that need no running game: framing, partial reads, `id` correlation, error
  mapping (Phase 5).

### Compiling without a human — current status

**Verified in the development container on 2026-08-06:** JDK 17 installs cleanly
(`apt-get install openjdk-17-jdk-headless`, giving `/usr/lib/jvm/java-17-openjdk-amd64`), and the
Gradle wrapper runs under it.

**What still fails:** dependency resolution. The engine dependency (`Arc`) is published on
`jitpack.io`, and `jitpack.io` and `dl.google.com` are both refused by the environment's network
policy — `403` at the proxy, while `repo1.maven.org` resolves normally. So
`./gradlew core:compileJava` fails before compiling a single file. **No compile check has been run
in the container; only the JDK install and the wrapper launch have.**

**To remove H1.4 and every later `desktop:dist` item from the human queue,** allow `jitpack.io` and
`dl.google.com` in the environment's network policy (see the Claude Code on the web docs on
environment network configuration). Once dependencies resolve, `./gradlew core:compileJava` and
`./gradlew desktop:dist` become automated checks and the human queue reduces to what genuinely
needs a screen: launching, playing, and the per-OS items.

Until then, compile breakage is caught by the next human build — which is a slow feedback loop, so
keep changes small (`CLAUDE.md` §9) and expect the first build after a gap to fail on something
trivial.

---

## 6. Recording results

Append below when an item is run. Keep it to what a future session needs, and keep it free of
personal data (§4.7): no hostname, no account name, no home-directory path.

**Template**

```
- <ITEM ID> — <pass | FAIL> — <date> — <OS family and version> — <JDK build>
  <one or two lines: what was observed; for a failure, the actual output>
```

**Results**

_(none yet — no item in this file has been run)_
