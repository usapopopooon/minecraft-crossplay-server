# Cyan portal display packs

These packs are **client display assets**, not world data or teleport logic.
The event bridge sends two reserved tripwire states over real air. Do not place
physical tripwire or replace ordinary Nether portal textures with this pack.

Build and verify using Python 3.11+ (standard library only):

```sh
python3 portal-packs/build.py --check
python3 -m unittest discover -s portal-packs -p 'test_*.py' -v
```

`build.py` without `--check` creates the three versioned distribution files and
`dist/checksums.json`. Never overwrite an already published version: bump the
version, Bedrock manifest version and filenames before changing these assets.
Archive timestamps, ordering, permissions, PNG compression and ZIP storage are
fixed so that rebuilding is byte-for-byte reproducible on macOS/Linux.

## Display contract

All seven Java properties are explicit and lexically sorted:

| Plane | Reserved state |
| --- | --- |
| Along X, constant Z | `minecraft:tripwire[attached=true,disarmed=true,east=true,north=true,powered=false,south=true,west=true]` |
| Along Z, constant X | `minecraft:tripwire[attached=true,disarmed=true,east=true,north=true,powered=true,south=true,west=true]` |

The Java blockstates file expands the vanilla 32 visual variants into all 128
complete states. Only the two states above change model; the other 126 retain
the exact vanilla model and rotation. An existing real tripwire using either
reserved state would also display the custom surface on a pack-accepting client;
the server never places these states. Both faces of the thin central plane are
rendered, without changing geometry outside its own block.

Java clients may decline the optional pack; the bridge must then avoid sending
these carrier states and use its no-pack fallback. Java pack UUID is
`c73b7eef-646d-4554-92b7-deedd3335abf`. Bedrock resource-pack header/module UUIDs
are `071961f2-0540-488f-92b3-8371a5b3000a` and
`f75d9df3-0bc1-4514-999b-e2106fc51995`. The Bedrock pack has no behavior pack.
Geyser supplies custom-block definitions from `cyan-portal-v1.json`; only the
two explicit states are overridden, with zero collision and selection boxes.
The ordinary string item is not replaced.

The generated 64×64 texture is mechanically sampled from the original image,
then wrapped vertically into 32 frames at two game ticks each (3.2 seconds per
loop). The approved translucent source's RGBA pixels are preserved exactly,
including its roughly 71% average opacity. Colors are not recolored and no
generated frame changes gameplay. Bedrock materials use `render_method: blend`;
Java 26.2/26.3 chooses a translucent layer from the texture's fractional alpha.
Both editions receive exactly the same animation PNG. Its faces are bright;
client-only light does not change the server light level or mob spawning.

## Verified format sources

- Java 26.2 `version.json`: resource format **88.0**. Java 26.3: **97.1**.
  Both installed official client JARs have the same `tripwire.json`, SHA-256
  `37eb32db8bb407ce1fb24f43bf3bb5451e5e8ce4698591c37e6b3cc7bb59ce42`.
  A reference copy is retained as `source/vanilla-tripwire-26.2.json` for the
  preservation check. The pack declares `min_format: [88,0]` and
  `max_format: [97,1]`, rather than guessing a legacy `pack_format` number.
- Installed Geyser 2.11.3 build 1245, commit
  `2808f7d21358a13019727fdf8737a5f978b23af4`:
  [BlockMappingsReader_v1](https://github.com/GeyserMC/Geyser/blob/2808f7d21358a13019727fdf8737a5f978b23af4/core/src/main/java/org/geysermc/geyser/registry/mappings/versions/block/BlockMappingsReader_v1.java).
  `only_override_states` must be true. State components do **not** inherit the
  root components. Empty boxes must use `origin`/`size` objects: Boolean false
  is not parsed by this version. Numeric ambient occlusion is supported.
- Bedrock format 2 resource manifest, `min_engine_version: [1,26,0]`, based
  on the [Mojang 26.0 sample manifest](https://github.com/Mojang/bedrock-samples/blob/v1.26.0.2/resource_pack/manifest.json).
  This preserves 26.45 compatibility instead of unnecessarily requiring 26.50.
  Geometry uses format 1.12.0 and per-face UVs. Texture animation uses
  [26.0 flipbook textures](https://github.com/Mojang/bedrock-samples/blob/v1.26.0.2/resource_pack/textures/flipbook_textures.json).

Local checks prove structure, exact carrier isolation, matching orientations,
collision settings, animation frames and deterministic packaging. They are not
a substitute for an actual Java and Bedrock client render check.

`VerifyJavaPack.java` is an optional native-client parsing smoke test. Run it
with Java 25, an installed official 26.2 or 26.3 client JAR, and that version's
library classpath. It bootstraps registries without opening a window, parses
the pack metadata, instantiates all 128 tripwire variants, parses both models
and the animation, and reads PNG pixels through Minecraft's `NativeImage`.
It asserts that the actual `SpriteContents` alpha classification selects
`ChunkSectionLayer.TRANSLUCENT`, not `CUTOUT`. It does not connect to a server
or replace a rendered-client acceptance check.

## Image provenance

`source/cyan-tile-v1.png` is the unchanged image generated with the built-in
image-generation tool for this request. Prompt summary: seamless cyan/aqua
pixel-art portal energy tile matching the user's approved glowstone-framed
cyan plane, without frame, text or perspective. The original is 1254×1254 RGB,
SHA-256 `52312af8e26645e9b05fee2be13c9d7ed5c02a1760fa8820348c44c2c072aabf`.
Only nearest-neighbor sampling and reversible wrapped scrolling are used when
compiling this source into the game animation format.

After the user requested translucency, the image-generation tool edited that
original into `source/cyan-tile-translucent-v1.png`, also retained unchanged.
It is 1254×1254 RGBA, SHA-256
`ba51e2a6d818022ef8d45d5f8baa6235b0b0dd637919491985f2cc37909c985a`.
All 1,572,516 pixels have fractional alpha (139–222/255, average 180.11/255).
This edited source, not the opaque original, is compiled into both packs.
No alpha values are invented or adjusted by the pack compiler.
