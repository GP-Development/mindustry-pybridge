package mindustry.pybridge;

import arc.util.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.meta.*;

/**
 * The control-bridge block — the in-world thing an external process will eventually talk through.
 *
 * <p><b>Phase 1 scope: this block does nothing.</b> It can be built, it draws, it draws power, it
 * can be destroyed, and every so often it prints a line to the console proving it is ticking.
 * There is no socket, no thread, and no file written by this class or anything it calls. That is
 * deliberate: the point of this phase is to prove the block-registration path works end to end
 * <i>before</i> anything security-relevant is attached to it ({@code docs/ROADMAP.md} phase 1).</p>
 *
 * <p><b>It reads no input of any kind.</b> There is no {@code config(...)} call below and
 * {@code configurable} is left at its default of {@code false}, so there is no path by which a
 * player, a schematic, a save file or an mlog processor can hand this block data. Adding one would
 * create the first input surface in the fork and needs to be weighed against {@code CLAUDE.md} §4
 * first, not added in passing.</p>
 *
 * <p>Shape reference: {@code world/blocks/logic/LogicBlock.java} and
 * {@code world/blocks/production/GenericCrafter.java}.</p>
 */
public class PyBridgeBlock extends Block{
    /**
     * Ticks between console lines.
     *
     * <p>Mindustry simulates 60 ticks per second, so this is one line every ten seconds per placed
     * block. Logging from {@link Building#updateTile()} without a throttle would emit sixty lines a
     * second per block and make the console useless — the roadmap calls this out explicitly, and
     * {@code HUMAN_TODO.md} H1.3 is the check that it is actually a trickle and not a flood.</p>
     */
    public float logIntervalTicks = 60f * 10f;

    public PyBridgeBlock(String name){
        super(name);

        //updateTile() below is only called when this is true
        update = true;
        //units and bullets cannot pass through, matching every other logic-category block
        solid = true;
        //available on every planet: the same choice LogicBlock makes, since nothing about a local
        //control socket is environment-specific
        envEnabled = Env.any;

        //Power draw. consumePower() also flags the block as needing a power connection, so no
        //separate hasPower assignment is required.
        //
        //These numbers, and the build requirements set in Blocks.java, are PROVISIONAL. Tuning the
        //bridge so it is not strictly superior to an mlog processor is a balance judgement, not a
        //code decision, and it belongs to a human: HUMAN_TODO.md H6.4. Do not treat the current
        //values as settled.
        consumePower(1f);
    }

    /**
     * Per-building state and behaviour.
     *
     * <p>Java note for the reviewer: this is an <i>inner</i> class ({@code public class}, not
     * {@code static class}). That is required, not stylistic — {@link Block} locates the first
     * inner class that extends {@code Building} and constructs it with the enclosing block
     * instance, which only works for a non-static inner class. Because it is non-static, code in
     * here can read the block's fields ({@code logIntervalTicks}) directly.</p>
     *
     * <p>{@code Building} is generated at build time from
     * {@code entities/comp/BuildingComp.java} into the {@code mindustry.gen} package; it has no
     * hand-written source file ({@code CLAUDE.md} §2).</p>
     */
    public class PyBridgeBuild extends Building{
        /**
         * Ticks elapsed since the last console line.
         *
         * <p>Not written to the save file. That is intentional: it is cosmetic throttling state, so
         * a reloaded block simply starts its interval afresh. Adding no {@code read}/{@code write}
         * overrides at all is also what keeps the save/load round trip (H1.2) trivially correct —
         * there is no fork-local data in the save format yet.</p>
         */
        private float sinceLastLog = 0f;

        @Override
        public void updateTile(){
            //Time.delta is 1.0 at a steady 60fps and scales with frame time, so this accumulates
            //in ticks regardless of the actual frame rate.
            //
            //Deliberately Time.delta and not edelta(): edelta() is multiplied by power efficiency
            //and is zero for an unpowered block, which would mean an unpowered bridge printed
            //nothing at all. H1.3 asks for a trickle from a *placed* block, and seeing the
            //efficiency reported as 0 is more useful than silence.
            sinceLastLog += Time.delta;
            if(sinceLastLog < logIntervalTicks) return;
            sinceLastLog = 0f;

            //Arc's Log uses "@" as its placeholder, roughly like "{}" in Python's str.format.
            //Everything interpolated here is our own game-side state; no part of this string comes
            //from outside the process, because in phase 1 there is no outside.
            Log.info("pybridge: control bridge at @,@ is inert - no listener exists yet (roadmap phase 1). power efficiency @",
                tileX(), tileY(), Strings.fixed(efficiency, 2));
        }
    }
}
