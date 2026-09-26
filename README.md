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

The boss hunts nearby living entities. It can dig, build bridges and stairs, and recover from falls while chasing a target. Terrain changes respect the `mobGriefing` game rule.

If it cannot get within four blocks of a target for ten seconds, it enters rage. In rage, it builds more aggressively, jumps higher, fires ten homing eggs every two seconds, and may throw an Ender Eye that teleports it near its target. Egg hits use 15% of the target's maximum health as base damage.

The boss does not despawn when players move away and remains present in Peaceful difficulty.

## Build

Run `gradlew.bat build` on Windows. The Access Transformer helper is at `tools\generate_at.bat`.

## License

Protected Entity is licensed under the [MIT License](LICENSE). The NeoForged MDK template files have a separate [template license](TEMPLATE_LICENSE.txt).
