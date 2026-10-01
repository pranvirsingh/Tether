# TETHER

A one-touch neon sling climber for Android. It is written in Kotlin with no engine. All art is procedural vector and all music and sound are synthesised on the device.

## How to play

- **Hold** to fire a tether at the nearest anchor of your colour.
- **Keep holding** to spin up around the anchor.
- **Let go** when the arrow glows gold. That release is a perfect one, and it builds your combo.
- Prism gates flip your colour. Only white anchors and anchors in your colour will catch you.
- The static rises from below, so keep climbing.

## Zones

| Zone | Starts at | Hazards and twists |
|---|---|---|
| Dawnline | 0 m | Shards and mines |
| Nebula | 300 m | Low gravity, prism gates |
| Aurora | 700 m | Solar wind, fragile anchors |
| Eclipse | 1200 m | Darkness, laser beams |
| Singularity | 1800 m | Gravity wells; the climb is endless from here |

## Build

```
./build.sh
```

The script needs aapt2, android.jar (API 34), the Kotlin compiler, R8 and apksigner. The paths are set at the top of `build.sh` and `kc.sh`.

## Tests (plain JVM)

```
./t.sh Shots Monkey AudioTest Sim
```

These run against a Java2D shim of `android.graphics`:

- **Shots:** screenshots of every screen.
- **Monkey:** fuzzing with process-death restores.
- **AudioTest:** synth and mixer checks.
- **Sim:** autopilot climbs that check every seed can be climbed.

## Files

- `World.kt`: physics, generation and hazards. It is pure Kotlin.
- `Fx.kt`: rendering.
- `Game.kt`: screens and saves.
- `Synth.kt` and `Audio.kt`: the music and sound.
