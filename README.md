# Protected Entity

A boss mod for Minecraft 1.20.1 and Forge. The Protected Entity has 2,000 health, a custom boss bar, and a red spawn egg.

## Install

Use Minecraft 1.20.1 with Forge 47.1.3 or newer and Java 17. Download the JAR from [Releases](https://github.com/mx-wj/ProtectedEntity1.20.1/releases) and place it in your `mods` folder.

## Play

Summon the boss:

```mcfunction
/summon protectedentity:protected_entity
```

Or get its spawn egg:

```mcfunction
/give @s protectedentity:protected_entity_spawn_egg
```

The boss hunts nearby living entities. It can dig, build bridges and stairs, and recover from falls while chasing a target. It prefers walking to a natural drop over digging through the floor, and it can strike a visible target one block higher without first building up to that level. Terrain changes respect the `mobGriefing` game rule.

Terrain routes recognize the actual standing surface of dirt paths, farmland, and flat slabs, so these walkable surfaces are not mistaken for blocks obstructing its feet.

Fall rescue checks the next position using the full current velocity, including while still grounded at an edge. Landing prediction accounts for that displacement before applying rescue braking.

If it cannot get within four blocks of a target for ten seconds, it enters rage. A target moving horizontally at least three blocks per second while maintaining or increasing its distance resets that timer. In rage, it builds more aggressively, jumps higher, fires ten homing eggs every two seconds at non-player targets, and may throw an Ender Eye that teleports it near its target. Players receive only the occasional single homing egg, even during rage. Egg hits use 15% of the target's maximum health as base damage.

Its ordinary walking speed matches a player's normal walking speed on level ground. Its existing sprint speed is unchanged.

It turns gradually and waits until it faces a block or target before acting. Building takes priority over attacking; it finishes the required terrain work and reaches stable footing before turning to attack. Teleporting follows the same attack rules. On crossing the world's minimum build height, its void rescue measures the roof thickness, clears a 2-by-2 upward shaft at once, and accelerates through it. When it creates a rescue platform, it places the whole platform in one tick. Terrain clearing can break bedrock when `mobGriefing` allows it.

While following terrain routes, it turns toward each waypoint before moving, brakes near the waypoint, and finishes that step before switching routes. Only the terrain controller requests climbing jumps. Existing stairs take priority over building a pillar, and optional bridge lookahead cannot stop the current step. Route timeouts measure lack of progress rather than total time spent building and moving.

For higher targets, it prefers jumping and building underfoot over constructing new forward stairs. A jump can extend the pillar as soon as its body clears each block. Forward supports do not require a separate jump for every placement. Digging and placing have no operation cooldown or per-tick block limit; facing and collision checks still apply.

The boss does not despawn when players move away and remains present in Peaceful difficulty.

## Build

Run `gradlew.bat build` on Windows. The Access Transformer helper is at `tools\generate_at.bat`.

## License

Protected Entity is licensed under the [MIT License](LICENSE). The NeoForged MDK template files have a separate [template license](TEMPLATE_LICENSE.txt).
