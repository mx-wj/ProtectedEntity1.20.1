
# Protected Entity

Minecraft Forge mod for Minecraft 1.20.1.

- Mod ID: `protectedentity`
- Java package: `com.mx_wj.protectedentity`
- Author: `mx_wj`

Build with `gradlew.bat build` on Windows.

Summon the entity in game with `/summon protectedentity:protected_entity`.
It has 2000 health, hunts nearby living entities, and uses Steve's appearance.
If its distance to a target does not shrink for 8 consecutive seconds, it tries another target when one is available. It first follows vanilla navigation; only when that path cannot reach the target and progress stalls does a bounded A* route choose a local step to walk, dig, drain lava, bridge a gap, or dig down. Melee hits pass 10% of the target's maximum health to `actuallyHurt` through a cached method handle. It can skim across water, rebound below the dimension's minimum build height, sometimes build a platform at the rebound apex, throw ender pearls, and fire homing fireballs. Fireballs track their locked target for 3 seconds and pass 5% of the victim's maximum health to `actuallyHurt` without exploding. Terrain changes respect the mob-griefing game rule.
Incoming damage is reduced by 95%, then health loss is capped at 10 per hit.
It remains present in Peaceful difficulty and does not despawn when players move away.
Players tracking the entity see a custom textured boss health bar rendered through Forge's boss overlay event.

## Access Transformer generator

Run `tools\generate_at.bat` for interactive prompts, or pass a class and member on the command line:

```text
tools\generate_at.bat net.minecraft.world.entity.LivingEntity DATA_HEALTH_ID
tools\generate_at.bat net.minecraft.world.entity.LivingEntity setHealth
```

The tool reads `build/moddev/artifacts/forge-*-merged.jar` and `namedToIntermediate.tsrg`, so run `gradlew.bat build` first. Methods do not need a descriptor in the input. If a name has multiple overloads, it prints an entry for each one. Add `--write` to append new entries to `src/main/resources/META-INF/accesstransformer.cfg` without duplicates.
