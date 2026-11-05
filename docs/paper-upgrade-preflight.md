# Conservative Paper upgrade preflight

Status: **conservative candidate prepared locally; NOT deployed; production
release requires approval and the checks below**.
Inspection date: 2026-11-04 (Asia/Tokyo).

## Scope and pinned candidate

Prepare the upgrade without changing the live server, its admission state,
worlds, player data, persistent configuration, proxy, firewall or DNS. Do not
push to an automatically deployed branch. No production restart is authorized
by the preparation request.

| Component | Previous | Prepared candidate |
| --- | --- | --- |
| Paper | 26.2 build 92 | 26.2 build 135, official STABLE |
| Multiverse-Core | 5.8.1 | Unchanged |
| ViaVersion | 5.11.0 | Unchanged |
| ViaBackwards | 5.11.0 | Unchanged |
| Geyser | 2.11.3 build 1245 | Unchanged |
| Floodgate | 2.2.5 build 141 | Unchanged |
| Multiverse-Portals | 5.3.0 | Unchanged |
| Multiverse-NetherPortals | 5.1.0 | Unchanged |
| Multiverse-Inventories | 5.3.6, isolated NBT | Unchanged, retain the isolated package |
| Java / container base | Java 25 / itzg 2026.8.0 digest pin | Unchanged |

Paper's official 26.2 build-135 SHA-256 is
`ef3dbc68b013deafdbbee3bebf1ba351671ebcda94a8f136809b7532d5ba2fe1`.
The API dependency in `event-bridge/pom.xml` matches the selected server build.
The plugin descriptor retains its minimum API version 26.2; that is not a
claim that worlds or item data saved by 26.3 can be downgraded to 26.2.

Sources:

- [Paper 26.2 build 135](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/135)
- [Paper 26.3 build 240 (held)](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/240)
- [Multiverse-Core 5.8.2](https://github.com/Multiverse/Multiverse-Core/releases/tag/5.8.2)
- [ViaVersion 5.12.2](https://github.com/ViaVersion/ViaVersion/releases/tag/5.12.2)
- [ViaBackwards 5.12.2](https://github.com/ViaVersion/ViaBackwards/releases/tag/5.12.2)

## Why 26.3 is held

The larger candidate (Paper 26.3 build 240, Multiverse-Core 5.8.2 and
ViaVersion/ViaBackwards 5.12.2) passed compilation and all 350 existing unit
tests. A synthetic 26.2-save to 26.3-load integration probe also passed its
inventory and mode checks. However, Geyser 1245 **failed to enable** in that
real 26.3 runtime: its bundled cloud command parser could not find the
`CraftItemStack.asBukkitCopy` / `asCraftMirror` method it expected.

ViaBackwards registers the required 26.2-to-26.3 protocol path, but that does
not fix this earlier plugin-initialization failure. Therefore the 26.3 image
is diagnostic evidence, not an approved release artifact. The working-tree
candidate was narrowed back to a same-Minecraft-version Paper build update,
without plugin changes. Never downgrade the 26.3 test data: the conservative
candidate is tested using a fresh copy of the saved 26.2 baseline.

## Separately prepared Bedrock transport option

Bedrock 26.60 removes RakNet server connections in favor of NetherNet.
[Geyser's official announcement](https://geysermc.org/blog/preparing-for-nethernet)
describes the additional transport requirements. The existing deployment only
exposes the Bedrock UDP port. A current Geyser update is therefore not just a
JAR replacement: its built-in signaling also needs a TCP path through the
container, private host and public relay.

Geyser 2.12.1 build 1284 supports Java 26.3 and Bedrock 26.60, but build 1280
removed RakNet-only mode and migrates that setting to NetherNet. Choosing
build 1279 does not solve the new client's requirement: that build retains
RakNet-only mode, but Bedrock 26.60 does not use it. Supporting both old and
new transports requires an explicit topology and port plan. Do not invent
proxy-trust ranges, turn on external signaling services, or open additional
ports without the user's direction.

The base 26.2 candidate retains Geyser 1245 and current connectivity.
This must not be presented as Bedrock 26.60 support. The latest Bedrock client
requires a separate Geyser/network preparation; it is not silently enabled by
this same-version Paper build update. The user subsequently authorized that
**preparation only**, still without production changes. The opt-in
`docker-compose.nethernet.yml` now pins Geyser 1286 and ViaVersion/ViaBackwards
5.12.2 while retaining Paper 26.2 build 135 and Core 5.8.1. See the
[NetherNet preflight](bedrock-nethernet-preflight.md) for the additional tests,
relay/firewall changes, activation gate and limitations. Paper 26.3 is not a
prerequisite for that option.

## Verification record

- Before changes: all 350 event-bridge unit tests passed under Java 25.
- After selecting Paper 26.3 build 240 / Core 5.8.2: `clean verify` passed all
  350 tests, with no failures, errors or skipped tests. No existing assertion
  was weakened or rewritten; gameplay source is unchanged.
- Portal packs: 20 Python tests passed; all four distribution artifacts
  reproduced byte-for-byte. Existing packs already declare Java 26.3 format
  support. This does not replace a client render check.
- Isolated inventories packaging: official upstream checksum, all 47 NBT
  classes, 1,131 class references, 16 resources and unchanged plugin metadata
  passed verification. Do not replace it with the unisolated upstream JAR.
- Compose validation and whitespace checks passed.
- The diagnostic 26.3 Docker image built successfully and reran all 350 unit
  tests, but is **not releasable** because of the Geyser failure above.
- Final 26.2 build-135 `clean verify` passed all 350 tests; its Docker image
  also built successfully and reran all 350 tests. The downloaded Paper JAR's
  SHA-256 matches the official value above. Compose validation and
  `git diff --check` were repeated successfully for this narrowed candidate.
- All 180 entries inside the image's event-bridge JAR match the standalone JAR
  used by the native probe byte-for-byte (ZIP timestamps make the whole-archive
  SHA different). No test-only probe is included in the distribution image.
- Native synthetic baseline on Paper 26.2 build 92: 58 checks passed before
  saving a real server-side test player and Multiverse inventory profiles.
- A fresh copy of that baseline on build 135 passed 231 migration checks:
  saved player data, world-group inventories, armor, offhand, Ender Chests,
  item names/PDC/enchantments/durability, nested shulker contents and shared XP;
  remembered creative login and creative/survival re-entry; immediate survival
  in the other five dimensions; and economy restrictions in the world_2 group.
- A further 202 checks passed for the eight Nether/End routing directions and
  both AIR-gate directions. Gate tests dispatched PlayerMoveEvent, accepted the
  real confirmation inventory event and verified the resulting world change,
  no step-back, preserved frame and AIR interior. Native portal physics and
  an authenticated client's input/rendering were not exercised.
- Existing Geyser 1245 enabled on build 135, remained internally running and
  selected `GeyserSpigotNativeWorldManager`. UDP/19132 bound successfully and
  a container-local RakNet ping returned Bedrock 26.51 with maximum 20 players.
  This is a protocol status check, not a completed authenticated game login.
- All four newly created verification containers stopped with exit code 0;
  none published host ports. Existing containers were not changed.
- No production terrain/player data was copied into staging. The tests use a
  small synthetic six-world fixture with the relevant production settings,
  not a complete production-world conversion rehearsal.

Geyser needs outbound discovery during initialization. The first strictly
offline test could not complete that discovery; startup was repeated with
outbound access and no exposed ports. Later intentional network disconnection
produced update-check/skin-service DNS errors (including the old skin uploader's
JSON error path), but Geyser remained running and still answered RakNet pings.
These environmental errors are recorded, not claimed to be an error-free log.
Real Java/Bedrock authenticated login, final rendering and the complete
production-world conversion remain unverified.

Local candidate image: `minecraft-paper262-prepared:20261104`, image ID
`sha256:da3f9e141f6559d74c38fd5cbe88f115321beb576ac19624d7664762ef32f7d2`.
It has not been pushed or deployed. No GitHub CI run was triggered because no
commit or push was made; local CI-equivalent checks are the evidence above.

CI-equivalent commands (Java 25):

```sh
python3 -m unittest discover -s portal-packs -p 'test_*.py' -v
python3 portal-packs/build.py --check
mvn --batch-mode --no-transfer-progress --file event-bridge/pom.xml clean verify
mvn --batch-mode --no-transfer-progress --file multiverse-inventories-isolated/pom.xml verify
MINECRAFT_BIND_IP=127.0.0.1 MINECRAFT_RCON_PASSWORD=ci-placeholder docker compose config --quiet
git diff --check
docker build --tag minecraft-paper262-prepared:20261104 .
```

## Required release gates

1. Explicit approval to reflect the prepared candidate in production. Resolve
   the intended application from the private operational record and confirm
   repository, branch, current SHA, auto-deploy state and absence of another
   active deployment. Do not infer that a push is harmless.
2. Verify the existing Java/Bedrock client path for the conservative candidate.
   A future 26.3/Geyser update additionally requires the approved transport
   choice and complete new path. Old-client compatibility and latest-client
   support are separate.
3. Check real Java/Bedrock gate display, confirmation forms, travel, inventory
   separation and mode persistence. Include old 26.2-saved item data, armor,
   offhand, Ender Chests, nested/enchanted items, market/quest escrow, and
   world_1/world_2 plus their dedicated Nether/End dimensions.
4. Plan enough free space for the image, verified full stopped-server backup,
   upgrade writes and rollback. The inspected production volume is large
   relative to available space; do not assume an uncompressed extra copy fits
   safely. Retain older recovery points unless deletion is separately approved.
5. Announce the planned restart in-game before stopping. Save and stop cleanly,
   then make a full backup of the data volume and verify it. No live file copy
   or synthetic fixture is a substitute for this backup. Record all existing
   plugin/config hashes, world identities/seeds/rules/borders and data profiles.
6. Build and test the exact source, commit/push only the intended changes, verify
   GitHub CI for that SHA, and queue/track exactly that deployment. Keep players
   held out until startup, profiles, aliases, groups, links, rules, plugin
   registrations and both client paths pass verification.
7. Release admission only after checks succeed. Rollback must restore the full
   stopped pre-upgrade data and matching binaries; never run 26.2 over worlds
   or player data already saved by 26.3. Preserve failed-upgrade data separately
   before a restore. Never silently select a different SHA or dependency build.
   If players have already been admitted and made new progress, do not overwrite
   it with a pre-release backup without explicit recovery direction; preserve
   both states first.

Unchanged policies include world_2 survival default and remembered mode,
operator-only Multiverse teleport commands, inventory groups, shared XP,
economy restrictions, day/weather/monster rules, gate bounds and appearance,
maximum players and permissions. Bundled item translations/catalogs remain the
existing 26.2 data; new 26.3 item-name coverage has not been added by this update.
