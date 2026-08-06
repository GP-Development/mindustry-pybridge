/**
 * <h2>Fork-local package — the Python control bridge</h2>
 *
 * <p>Everything this fork adds on top of upstream Mindustry lives in this package. Upstream will
 * never create a package with this name, so it can never produce a merge conflict. Code placed
 * anywhere else is, by definition, an edit to an upstream file and must carry a
 * {@code // FORK: pybridge} marker (see {@code CLAUDE.md} §6).</p>
 *
 * <p><b>What this package is for.</b> Letting an external process on the same machine observe and
 * (later) control the game over a loopback socket. It is an <i>addition</i> to the in-game mlog
 * logic system, never a replacement — nothing here modifies or refactors mlog.</p>
 *
 * <p><b>Hard invariants that govern every file in this package</b> (the full statements, with
 * reasoning, are in {@code CLAUDE.md} §4 — this list is a reminder, not a substitute):</p>
 *
 * <ol>
 *   <li>The listener binds {@code 127.0.0.1} only. There is no configurable bind address, no
 *       remote-host field anywhere, and the game never dials out.</li>
 *   <li>The transport is raw length-framed TCP. No HTTP, no WebSocket, not even an HTTP-shaped
 *       handshake — a browser must remain structurally unable to reach this listener.</li>
 *   <li>The feature is off by default. Nothing is bound and no file is written until the operator
 *       explicitly opts in.</li>
 *   <li>The command set is a closed allowlist. No evaluation of client-supplied code, no
 *       reflection driven by client input, no filesystem, process or environment access.</li>
 *   <li>Every command is validated and rate-limited before it can affect game state, with an
 *       mlog-shaped per-tick budget whose accumulator is capped so budget cannot be banked.</li>
 *   <li>The network thread never touches game state — not {@code Vars.world}, not
 *       {@code Vars.state}, not a tile, building or unit, not even to read one. All game-state
 *       access is marshalled onto the main thread ({@code CLAUDE.md} §5).</li>
 * </ol>
 *
 * <p><b>Current state: roadmap phase 1 — an inert block.</b> This package contains one block and
 * no networking whatsoever: no socket, no thread, no file is written by any code here. The block
 * exists to prove the content-registration path end to end before any of the above becomes
 * reachable. See {@code docs/ROADMAP.md} for the phase plan, {@code docs/SECURITY.md} for the
 * running threat model, and {@code docs/PROTOCOL.md} for the wire protocol these phases build
 * towards.</p>
 */
package mindustry.pybridge;
