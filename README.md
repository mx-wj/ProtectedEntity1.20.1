
# Protected Entity

Minecraft Forge mod for Minecraft 1.20.1.

- Mod ID: `protectedentity`
- Java package: `com.mx_wj.protectedentity`
- Author: `mx_wj`

Build with `gradlew.bat build` on Windows.

Summon the entity in game with `/summon protectedentity:protected_entity`.
Its red spawn egg appears in the creative Spawn Eggs tab and can be obtained with `/give @s protectedentity:protected_entity_spawn_egg`.
It has 2000 health, hunts nearby living entities, and uses Steve's appearance.
If its distance to a target does not shrink for 12 consecutive seconds, it tries another target when one is available, unless rage has activated. Combat movement uses the greedy terrain planner directly, without a vanilla pathfinding pass or fallback.

On clear, supported level terrain, it moves directly toward the target's current horizontal position. Otherwise, greedy steps prioritize the smallest straight-line distance to the goal, including diagonal steps with corner clearance and support checks. Breakable obstructions on the preferred approach are excavated instead of choosing a longer route to save block edits. If no legal step makes progress, a bounded greedy best-first search supplies a detour and retains its waypoints to prevent immediately returning to the previous position; it does not guarantee a globally shortest path. Planned block placements and removals support consecutive bridge blocks, stairs, digging, and placing blocks underneath itself toward a higher target. Excavation can open a 3-by-3 face, preserving supporting blocks, unbreakable blocks, and block entities. Placement follows the required support route rather than filling a fixed platform. Construction performs up to 18 block edits per tick. Movement slows near a waypoint, and invalidated movement commands are stopped. A completed step can start the next step in the same tick; unsuccessful searches retry after two ticks. Destroyed footholds or displacement trigger replanning, while a clear direct approach can replace a stale waypoint. New pursuit steps start only while grounded. With mob griefing disabled, the planner follows routes that require no block edits. An airborne target's goal uses the supporting ground within four blocks below its feet when available, so ordinary jumps do not cause unnecessary upward construction.

On level approaches, a persistent Bresenham line between the starting and target cells determines both walking waypoints and bridge placement. X and Z advances are distributed along that line, including shallow angles, instead of completing one axis before the other or recomputing the direction at every block. It previews three to six steps of the same route based on its movement speed and fills missing supports, including diagonal corner supports, before reaching them. Moving the target or invalidating the route regenerates the line. Prebuilding stops when the preview would require digging, draining, or changing height. Sprinting starts only beyond twenty blocks and stops at twenty blocks or closer; nearby pursuit uses normal walking speed. Attacking, losing the target, fall rescue, and void rebound disable sprinting.

For higher targets within two horizontal blocks, the planner prioritizes a vertical pillar beneath itself. Farther targets use steps that move both forward and up, including diagonal ascending stairs with corner supports. If a pillar is obstructed and cannot be prepared, the planner can use an advancing step instead. Diagonal descent clears the intervening corners as well. When an ascending step needs a new foothold, the entity centers itself and waits until the top of its jump before placing the support under or beside itself, then descends naturally. Entering melee range during the jump preserves that ascending step so the foothold is still built. It uses a roughly three-block-high jump when the target is at least ten blocks above the route's starting height and there is enough headroom; smaller height differences or low ceilings use a normal jump. It keeps placement clear of its hitbox. Once above a stair's top, controlled horizontal movement carries it onto the new foothold. It looks at blocks while placing them and at its target while pursuing or attacking. An attack from another living entity immediately switches its target to that attacker, including during its damage cooldown.

For lower targets, the planner checks the current and eight adjacent columns for an existing landing surface, scanning up to 64 blocks down without going below the target's ground height. It chooses a landing that closes the distance, centers itself before opening the descent, excavates the full descent corridor when necessary, and falls onto that surface rather than demanding support just one block below. Solid floors can be dug down one layer at a time; separated platforms allow a longer planned drop. Descending steps never place a floor beneath themselves or break the chosen landing support. Unbreakable blocks, block entities, build limits, and mob griefing still constrain the route. Planned drops retain their step when entering melee range, steer toward the landing, and are exempt from fall rescue while following the supported route.

Fall rescue runs only after a valid combat target has been selected and does not require landing first. With no target, pending construction and rescue routes are cleared and no blocks are placed or destroyed, including at a void-rebound apex; passive void rebound and water walking remain active. It predicts a catch position using horizontal momentum, steering time, and downward movement. A bounded three-dimensional search checks within six horizontal blocks and four vertical blocks for an attachment on a column, platform edge, underside, or existing protruding support. It builds only the connected chain needed to reach the catch block, including vertical turns where necessary, without filling a plane or blocking its approach to the landing. A failed forward catch can retry beneath its current position. It steers onto the support, replans if it is destroyed, and resumes pursuit after landing. Ordinary planned jumps are exempt, and rescue placement respects mob griefing, living-entity collisions, and the shared per-tick edit limit.

Melee attacks occur every 10 ticks with a two-block reach (normal player entity reach minus one), measured between the nearest surfaces of the attacker's and target's hitboxes and requiring a clear line of sight. They knock targets back, swing visibly, and play an attack sound. Hits pass 35% of maximum health to `actuallyHurt` for targets with up to 40 maximum health, or 20% for targets above 40. It can skim across water, rebound below the dimension's minimum build height, sometimes build a platform at the rebound apex, and throw homing eggs. Eggs pass 15% of the victim's maximum health to `actuallyHurt`. Terrain changes respect the mob-griefing game rule.
Incoming damage is reduced by 95%, then health loss is capped at 10 per hit.

Rage activates when the hitbox gap to the same target stays above four blocks for 200 ticks (10 seconds at 20 TPS), without landing a melee hit. Coming within four blocks restarts the timer. During rage, melee hitbox reach increases from 2 to 2.5 blocks with the same line-of-sight requirement. The shared terrain-edit budget rises from 18 to 54 per tick, block placement is cheaper in detour planning, construction movement is faster, runway previews extend to 12–16 steps, and failed terrain searches retry every tick. Every planned ascent uses the higher rage jump; low-clearance ascents are retried instead of using a normal jump. Placement still waits for the jump apex and hitbox clearance. Rage launches ten homing eggs every 40 ticks (two seconds), starting immediately, and has a 1-in-12 chance each tick to launch a homing Ender Eye when its 60-tick cooldown has expired. An eye that reaches its target teleports the entity to a safe nearby position and immediately attempts a melee attack. A successful melee hit or losing or changing the target clears rage; egg hits do not. While enraged, the entity keeps pursuing its current target instead of switching targets after the usual stalled-chase timeout.

It remains present in Peaceful difficulty and does not despawn when players move away.
Players tracking the entity see a custom textured boss health bar rendered through Forge's boss overlay event.

## Access Transformer generator

Run `tools\generate_at.bat` for interactive prompts, or pass a class and member on the command line:

```text
tools\generate_at.bat net.minecraft.world.entity.LivingEntity DATA_HEALTH_ID
tools\generate_at.bat net.minecraft.world.entity.LivingEntity setHealth
```

The tool reads `build/moddev/artifacts/forge-*-merged.jar` and `namedToIntermediate.tsrg`, so run `gradlew.bat build` first. Methods do not need a descriptor in the input. If a name has multiple overloads, it prints an entry for each one. Add `--write` to append new entries to `src/main/resources/META-INF/accesstransformer.cfg` without duplicates.

## License

Protected Entity is licensed under the [MIT License](LICENSE). The NeoForged MDK template files retain their separate [template license](TEMPLATE_LICENSE.txt).
