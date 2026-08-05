# CLAUDE.md — Mindustry Python Control Bridge fork

This file is written for a future Claude Code session that has **no memory of the conversation
that started this work**. Read it fully before touching anything. If something here conflicts
with what you are asked to do, raise the conflict rather than silently resolving it.

---

## 1. Project identity

This repository is a **fork of [Anuken/Mindustry](https://github.com/Anuken/Mindustry)** — an
automation tower-defense RTS written in Java.

**What the fork adds:** the ability to control the game from an **external Python process** over
a local network socket. This is an *addition* to the in-game mlog logic system, **not a
replacement**. Upstream mlog stays exactly as it is; do not modify, "improve", or refactor it as
part of bridge work.

**Licensing — this matters legally, not just stylistically.** Upstream Mindustry is **GPLv3**.
Therefore:

- This fork **must remain GPLv3**. It cannot be relicensed, dual-licensed, or shipped under more
  permissive terms.
- Any **distribution of a binary must include (or offer) the corresponding source**, including
  our modifications.
- New files we author are GPLv3 too. Do not add code copied from incompatible-licensed sources.

**The user is new to Java** and self-taught in Python (~5 years). This shapes how you must work
— see §9.

---

## 2. Build and run

**JDK 17 is required. Other JDK versions will not work.** This is not a soft preference; the
build sets `sourceCompatibility`/`targetCompatibility` to `VERSION_17` (`build.gradle:197-198`)
and upstream's README states other versions fail outright.

| Task | Linux / macOS | Windows |
|---|---|---|
| Run desktop client | `./gradlew desktop:run` | `gradlew desktop:run` |
| Build desktop jar | `./gradlew desktop:dist` | `gradlew desktop:dist` |
| Build server jar | `./gradlew server:dist` | `gradlew server:dist` |
| Pack sprites | `./gradlew tools:pack` | `gradlew tools:pack` |

Build output jars land in `desktop/build/libs/` (and `server/build/libs/` for the server).

If `./gradlew` gives "Permission denied" on Linux/macOS, run `chmod +x ./gradlew` once.

### `mindustry.gen` is generated — never hand-edit it

The `mindustry.gen` package **does not exist as source**. It is generated *at build time* by the
annotation processor in `annotations/`, from the component classes in
`core/src/mindustry/entities/comp/` (e.g. `BuildingComp.java` generates `mindustry.gen.Building`).

- Never edit anything under a `build/` directory or in `mindustry.gen` — your changes are
  destroyed on the next build.
- If you need to change generated behavior, change the `*Comp.java` **component source** instead.
- If your IDE reports `mindustry.gen.*` as missing, you have not run a build yet. Run one.

---

## 3. Fork scope: desktop only

**"Desktop" means Windows, Linux and macOS — all three are first-class supported targets.**
Windows in particular must work; a feature or mitigation that only functions on POSIX is not
acceptable. Where a platform API differs (notably file permissions — see `docs/SECURITY.md` T8),
implement the real per-platform path rather than degrading Windows to a weaker guarantee.

**Mobile (Android / iOS) support is intentionally dropped in this fork.**

- Do **not** fix Android or iOS build breakage. If `android/` or `ios/` fails to compile, that is
  expected and acceptable.
- Do not add mobile-conditional code paths for bridge features.
- Do not spend effort testing mobile targets.

The `android/` and `ios/` directories are retained only to keep upstream merges tractable
(deleting them creates conflicts on every merge for no benefit).

---

## 4. HARD SECURITY INVARIANTS

**Security outranks everything else in this project, including shipping speed.** These are rules
a future session must **never** violate. They are not defaults to be weighed against convenience.
If a request appears to require breaking one, **stop and raise it with the user** instead of
implementing around it.

### 4.1 Bind to loopback only

The listener binds to **`127.0.0.1` only**, always.

- There must be **no configurable bind address**, anywhere, ever.
- There must be **no remote host field**, in config, in the protocol, or in the UI.
- The game must never make an **outbound** connection on behalf of the bridge.

*Why:* a user-supplied host or outbound-connect capability turns the feature into an SSRF
primitive and exposes the control channel to the network. Loopback-only removes the entire
remote-network attack surface by construction rather than by validation. A validated host field
is still a host field; the only safe amount is zero.

### 4.2 Raw framed TCP only

The transport is **raw length-framed TCP**. Never add an HTTP or WebSocket transport, and never
add an HTTP-like handshake to the existing one.

*Why:* browsers cannot originate raw TCP connections. Restricting the transport this way
structurally eliminates the "malicious webpage silently talks to a localhost listener" attack
class (DNS rebinding, forged cross-origin requests). Adding HTTP or WebSocket support would
reopen it no matter what origin checks were bolted on afterward.

### 4.3 Off by default, explicit opt-in

The feature is **disabled by default**. No listener socket is opened, no port is bound, and no
connection file is written unless the user has explicitly opted in.

*Why:* a player who does not know this feature exists must not be running a control listener.
Defaults are the only setting most users ever have.

### 4.4 Closed command set — no arbitrary execution

Everything reachable over the socket must be a member of a **fixed, closed, explicitly
implemented command vocabulary**. This is an **allowlist**: a command that has not been
deliberately written and validated does not exist.

Specifically, the socket must **never** expose:

- evaluation of arbitrary code or expressions in any language,
- reflection, class loading, or dynamic dispatch driven by client input,
- filesystem, process, or environment access,
- any passthrough that forwards client-supplied strings to an interpreter.

*Why:* this is the invariant that actually **contains** damage. Authentication decides *who*
connects; the closed command set decides *what any connected party can ever do*. Even a fully
authenticated hostile client must be unable to reach outside the game simulation. Every other
mitigation is secondary to this one.

### 4.5 Validate and rate-limit before touching game state

Every command from the socket must be **validated and rate-limited before it is allowed to affect
game state**. Validation includes bounds-checking coordinates, verifying entity/building
ownership and team, checking the requesting block still exists and is powered, and rejecting
malformed or oversized frames.

Throttling follows the **mlog model** (see §5): a bounded per-tick command budget with a **capped
accumulator**, so a client cannot bank unused budget and flush a burst after a stall. There is no
wall-clock timeout anywhere in mlog and there should be none here — the safety property is
structural (bounded work per tick), not time-based.

Frames must have a **hard maximum size**, enforced before allocation, so a client cannot cause an
unbounded allocation by declaring a huge length prefix.

### 4.6 Never touch game state from the network thread

The network thread **may never read or write** `Vars.world`, `Vars.state`, tiles, `Building`s,
units, or entity groups. Not even "just a read" — the game is single-threaded and an unsynchronized
read can observe a torn or half-updated object.

All game-state access is marshalled onto the main thread (see §5).

---

## 5. Threading contract

**Read this before writing any threaded code. Do not improvise here.**

### The rule

Mindustry's game simulation is **single-threaded**. There is no separate "logic thread": the Arc
framework's frame loop calls `update()` on each `ApplicationListener` module (`Logic`, `Control`,
`Renderer`, `UI`, …) on one thread, then renders, every frame. `ClientLauncher.update()` is the
per-frame entry point; `core/src/mindustry/core/Logic.java` advances world state.

**All game state is owned exclusively by that main thread.**

### Who owns what

| Thread | May touch | May **not** touch |
|---|---|---|
| **Main / frame thread** | Everything: world, state, tiles, buildings, units, entity groups. Bridge command queue (drain side). | — |
| **Bridge network thread(s)** | Sockets, byte buffers, framing, parsing, auth, rate-limit counters, the command queue (submit side). | **Any** game state. |

### The handoff mechanism

Work moves from the network thread to the main thread via **`Core.app.post(Runnable)`**, which
enqueues the runnable to execute on the main thread at a safe point. This is the established
idiom throughout upstream — see `core/src/mindustry/net/ArcNetProvider.java` lines 83, 94,
101-107, 146, and 156-183, where every network callback wraps its work in `Core.app.post(...)`
before touching anything.

The flow for an inbound command is therefore:

```
[network thread]  read bytes → deframe → parse → authenticate → validate → rate-limit
                                                                              ↓
                                                          Core.app.post(...)  or  bounded queue
                                                                              ↓
[main thread]     drain up to N commands per tick (capped accumulator) → apply to game state
```

Telemetry flows the opposite way: **snapshot** the data on the main thread into a plain
immutable/value object, hand *that* to the network thread, and serialize it there. Never hand a
live `Building`, `Unit`, or `Tile` reference across the boundary — the object will mutate under
the network thread while it is being read.

### Rules of thumb

- Network thread runs as a **daemon** thread with an explicit name and an uncaught-exception
  handler (upstream pattern: `ArcNetProvider.java:384-392`), so a crash surfaces instead of
  vanishing silently.
- **Never block the main thread** on network I/O — no socket reads, no `Future.get()` on I/O, no
  locks held across I/O.
- Queues between threads must be **bounded**. An unbounded queue converts command flooding into
  an out-of-memory crash.
- Prefer `java.util.concurrent` primitives over hand-rolled `synchronized` blocks.

### Java idioms you will meet here (explain these in comments)

- `Core.app.post(() -> { ... })` — a **lambda**; roughly Python's `lambda`, but it can span
  multiple statements. It captures surrounding variables, which must be effectively final.
- `volatile` — marks a field so reads/writes are visible across threads. Not a lock; it does not
  make compound operations (like `x++`) atomic.
- `AtomicInteger` / `AtomicBoolean` — thread-safe counters and flags.
- `ConcurrentLinkedQueue` / `ArrayBlockingQueue` — thread-safe queues; the latter is bounded.
- `MessageDigest.isEqual(a, b)` — **constant-time** byte comparison. Used for token checks so
  response timing cannot leak the token byte by byte.
- `SecureRandom` — cryptographic RNG. **Never** use `Math.random`, `java.util.Random`, or
  Mindustry's `Mathf.random` for anything security-relevant; they are predictable.

---

## 6. Where our code lives

**All fork-local bridge code lives in one new package:**

```
core/src/mindustry/pybridge/
```

Upstream will never create this package, so it can never conflict during a merge.

### Marking fork-local edits to upstream files

Some integration points unavoidably require editing files that upstream also edits (registering
the block in `core/src/mindustry/content/Blocks.java`, wiring the listener into the app
lifecycle). **Every such edit must be marked** so it stays greppable at merge time:

```java
// FORK: pybridge — <one-line reason>
```

For a multi-line region:

```java
// FORK: pybridge — begin
... our code ...
// FORK: pybridge — end
```

Before any upstream merge, `grep -rn "FORK: pybridge" --include=*.java .` gives the complete list
of touched upstream sites. **Keep these edits as small as possible** — ideally a single call into
our package, with the real logic living in `mindustry/pybridge/`. The smaller the footprint in
upstream files, the cheaper every future merge is.

### Documentation

- `CLAUDE.md` (this file) — orientation and invariants.
- `docs/SECURITY.md` — **the running threat model.** Update it *every time* a security-relevant
  decision is made. This is not optional bookkeeping; it is the audit trail.
- `docs/PROTOCOL.md` — the wire protocol spec. Written before implementation so it can be
  reviewed on paper.
- `docs/ROADMAP.md` — the phased plan, with checkboxes. Keep it current as work proceeds.

---

## 7. Upstream merge policy

Upstream is `https://github.com/Anuken/Mindustry` (branch `master`).

```bash
git remote add upstream https://github.com/Anuken/Mindustry.git   # once
git fetch upstream
git checkout -b merge-upstream-<date>
git merge upstream/master
```

Merge into a **scratch branch first**, never straight into the working branch.

### After merging, check all of these

1. **Conflicts in marked regions** — `grep -rn "FORK: pybridge" --include=*.java .` and confirm
   every marker survived and still makes sense in its new surroundings.
2. **Block registration still intact** — our block is still registered in `Blocks.load()` and its
   content ID has not collided with a newly added upstream block.
3. **Clean build** — `./gradlew desktop:dist` succeeds on JDK 17.
4. **Generated code still generates** — annotation processing succeeded; `mindustry.gen` resolves.
5. **The security invariants in §4 still hold.** An upstream refactor can silently move code out
   from under an assumption. Re-verify: still loopback-only, still off by default, still no game
   state touched off-thread.
6. **Threading assumptions** — if upstream changed the tick/update structure or introduced new
   threading, re-read §5 against reality before trusting it.
7. Run the game and place the block. Compilation success is not proof the feature works.

Do **not** accept upstream changes that weaken a §4 invariant. If upstream adds something that
conflicts with one, keep our restriction and document the divergence in `docs/SECURITY.md`.

---

## 8. Useful upstream landmarks

Reference points established while orienting in this codebase:

| What | Where |
|---|---|
| Block definition base class | `core/src/mindustry/world/Block.java` |
| Building runtime component (source of generated `Building`) | `core/src/mindustry/entities/comp/BuildingComp.java` |
| Per-tick building hook | `updateTile()` — override in a block's inner `Build` class |
| Block registration list | `core/src/mindustry/content/Blocks.java` (called from `ContentLoader.createBaseContent()`) |
| Simple block to copy as a template | `core/src/mindustry/world/blocks/production/GenericCrafter.java` |
| mlog VM | `core/src/mindustry/logic/LExecutor.java` |
| mlog throttling (the model for ours) | `core/src/mindustry/world/blocks/logic/LogicBlock.java:45-48, 556-571` |
| Main tick / simulation | `core/src/mindustry/core/Logic.java`; frame entry `ClientLauncher.update()` |
| Existing net stack (pattern reference only) | `core/src/mindustry/net/` — `Net.java`, `NetConnection.java`, `ArcNetProvider.java` |
| Rate-limiting precedent | `Ratekeeper` usage in `NetConnection.java`, `Administration.java` |
| Off-thread → main-thread handoff examples | `ArcNetProvider.java:83, 94, 101-107` |
| Data directory (connection file location) | `Vars.dataDirectory` (`core/src/mindustry/Vars.java:226`) |

### How mlog throttles itself (the model for our throttling)

`LogicBlock` runs a bounded number of VM instructions per tick using an accumulator that is
**capped before use**, so budget cannot be banked:

```java
public int maxInstructionScale = 5;
public int instructionsPerTick = 1;      // normal processor: 1 instruction/tick
public int maxInstructionsPerTick = 40;  // privileged blocks only

// in updateTile():
if(accumulator > maxInstructionScale * ipt) accumulator = maxInstructionScale * ipt;
while(accumulator >= 1f){
    executor.runOnce();
    if(executor.yield){ executor.yield = false; break; }
    accumulator--;
}
accumulator += edelta() * ipt;
```

**The guarantee is structural, not time-based.** There is no timeout. A processor cannot stall the
simulation because it is never permitted to run more than a small bounded amount of work inside a
single tick. Our command throttle must have this same shape.

---

## 9. Code conventions and how to work

The user is **new to Java and to large codebases**, and reviewing your work is their primary
defense against your mistakes. Optimize for their ability to audit, not for elegance.

- **Explain Java idioms in comments.** Anything that has no direct Python equivalent — lambdas,
  `volatile`, generics, anonymous/inner classes, `final`, interfaces, the `{{ }}` double-brace
  initializer Mindustry uses heavily in `Blocks.java` — gets a short plain-language comment. If
  the user cannot read the code, they cannot review it.
- **Prefer boring, obvious code over clever code.** Explicit loops over stream chains. Named
  intermediate variables over dense one-liners. No cleverness that saves lines at the cost of
  legibility.
- **Small, reviewable increments.** Do not write large amounts of code in one go. Land one
  coherent piece, let it be reviewed, then continue.
- **Comment the *why*, especially for restrictions.** Every security-motivated limit gets an
  inline comment explaining what attack it prevents — otherwise a future session will "clean up"
  a restriction it mistakes for redundant.
- **Ask before making architectural decisions** that are not already settled in this file.
- **Vocalize security-relevant decisions** as they are made, and record them in
  `docs/SECURITY.md`. If you notice a security implication the user has not considered, **raise it
  immediately** rather than implementing around it.
- **Every phase must end with the game still building and running.** The user must be able to stop
  at any point and have something that works.
- Match surrounding upstream style in upstream files (Mindustry uses `}else{`, minimal spacing,
  4-space indent — see `CONTRIBUTING.md`).

### Honest-reporting requirement

If a build fails, a test fails, or a step was skipped, **say so plainly** with the actual output.
Do not report a feature as working unless it has been run. Security work is worthless if its
status is misreported.
