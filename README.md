# Chill Cafe cross-play server

Paper, Geyser, and Floodgate configuration for the Chill Cafe cross-play server.

The Minecraft runtime is pinned to the versions used for the 2026-08-05
deployment while Enderman and experience behavior is investigated: Paper 26.2
build 92, itzg/minecraft-server 2026.8.0, Geyser 2.11.1 build 1208, Floodgate
2.2.5 build 138, ViaVersion 5.11.0, and ViaBackwards 5.11.0. Startup cleanup is
limited to those four plugin JARs so persisted copies are replaced by the
pinned artifacts; unrelated plugins are not removed.

The local `UsapoEventBridge` Paper plugin is built into the server image. It
listens for successful fishing catches, supported log/stem breaks, and natural
experience gains, then writes UUID-based structured events to the normal
Minecraft log. Survival players also receive an immediate mining bonus for ore
broken with a preferred non-Silk-Touch tool: coal, Nether quartz, and Nether
gold award 5 XP; iron and copper award 10; gold, redstone, and lapis award 20;
diamond and emerald award 50; and ancient debris awards 100. Fortune does not
multiply this fixed per-block bonus, and crafted storage blocks are excluded.
Experience is batched per player once every five seconds to avoid one log record
per orb. The companion `mc-bot` consumes those events without
continuously polling scoreboards or experience over RCON. Active voice bonus
experience is applied inside the Paper event, so it does not issue an RCON
command per gain. The plugin runs only on the server; clients do not install
anything.

All event-bridge bonuses can be disabled without removing the plugin by setting
`USAPO_BONUSES_ENABLED=false`. This stops the fishing, woodcutting, mining,
natural experience, and voice XP listeners while leaving unrelated RCON,
whitelist, and shop functions available. The Compose default is `true`; use
`false` only for temporary performance diagnosis.

The resource shop can atomically exchange emeralds from an online linked
player's inventory for diamonds at a fixed rate of 32:1 (32/64 emeralds for
1/2 diamonds). The plugin verifies both the input and output inventory before
changing either, rejects a full output inventory without consuming emeralds,
and stores recent request UUIDs in player data so an RCON response retry cannot
repeat an exchange. Successful exchanges are announced in Minecraft and
written as a UUID-based structured audit event for mc-bot's Discord log.

Linked players can open the shared exchange menu with `/exchange`. On
Floodgate/Bedrock clients this opens a touch-friendly menu and confirmation
screen for server XP to Minecraft XP, server XP to resources, held emeralds to
diamonds, and a private XP balance check. Java clients, or clients where a form
cannot be shown, can use `/exchange xp <50|250|500|5000>`,
`/exchange resource <diamond|emerald> <count>`,
`/exchange emerald-diamond <32|64>`, and `/exchange balance`. The request carries
the exact displayed cost, but mc-bot checks it again against level-bot's current
shop before spending XP. Price changes are rejected and the player is asked to
open the menu again. Results are sent privately to the requesting player; the
existing completed-exchange announcements remain unchanged. No client add-on
is required.

Linked players can start the shared XP item gacha from inside the game with
`/gacha`. Floodgate/Bedrock players receive a touch-friendly selection form and
a second form for the draw type, followed by confirmation. The first form offers
random, resources/mining, adventure, and equipment/upgrades. Java players, or
Bedrock players if forms are unavailable, use `/gacha normal` for the 100 XP
random draw or `/gacha rare` for the 1,000 XP R-or-higher random draw. A category
can be selected with commands such as `/gacha resource normal`,
`/gacha adventure rare`, or `/gacha equipment normal`. All entry points use
mc-bot's existing JST
daily limit of three total draws, reward table, XP reservation, public result
notifications, and duplicate-delivery protection. Status and errors are sent
only to the requesting player in Minecraft. The confirmed price is included in
the versioned structured request together with the category; mc-bot rejects a
legacy request without a confirmed price or a price mismatch
without spending XP, so separately deployed plugin versions cannot silently
change the confirmed charge. No client add-on is required.

Linked players can also trade ordinary held item stacks with each other through
`/market`. Floodgate/Bedrock players receive a touch-friendly product list,
listing form, purchase confirmation, own-listings view, and private server XP
balance check. Java players, or clients where a form cannot be shown, can use
`/market list [page]`, `/market sell <total-price>`, `/market buy <listing>`,
`/market mine`, `/market cancel <listing>`, and `/market balance`. Listing moves
the entire main-hand stack, including its item metadata, into persistent escrow.
Prices and balances are displayed explicitly as server XP, distinct from
Minecraft experience. A completed purchase charges the displayed server XP
from the buyer and credits it to the seller. Delivery and return require the
recipient to be online with enough inventory space; retry IDs stored in player
data prevent a lost RCON response from duplicating the item.
Java command output, Bedrock forms, and the listing event sent to mc-bot all
render the item's effective name through the bundled Minecraft Java 26.2
Japanese translations. This covers data-dependent vanilla names while
preserving custom item names, so every market surface uses the same name.

```text
Java Edition:    <your-hostname>:25565
Bedrock Edition: <your-hostname>:19132
```

## Coolify deployment

Create a Public Repository resource on the second Coolify instance with these
settings:

```text
Branch: main
Build pack: Docker Compose
Docker Compose location: /docker-compose.yml
Auto deploy: disabled
```

The Compose build compiles and tests `event-bridge` before producing the server
image. Applying a newly built plugin requires a planned Minecraft restart;
building or committing the image alone does not load it into a running server.

Set `MINECRAFT_BIND_IP` in Coolify to the host address that should accept game
traffic. Optionally set `MINECRAFT_SERVER_NAME` and `MINECRAFT_MOTD`. Do not
commit deployment-specific addresses or hostnames.

The named volume `minecraft-crossplay-data` stores the server, plugins,
configuration, and world independently of application recreation.

Dropped tree-regrowth items remain for 15 minutes instead of the default five:
the eight overworld saplings, mangrove propagules, azaleas, and flowering
azaleas. The image applies this narrowly scoped Paper world-default patch at
startup, so other dropped items and the vanilla leaf loot tables are unchanged.
Applying the setting to a running server requires a planned Minecraft restart.

The default runtime profile targets up to 20 players on a small dedicated
host: a 6 GiB Java heap, an 8 GiB container memory limit, a view distance of 16,
and a simulation distance of 4. Aikar JVM flags are enabled to reduce garbage
collection pauses. Actual capacity still depends on CPU performance, explored
chunks, entities, farms, and other workloads on the host.

The whitelist is enabled and enforced. `OVERRIDE_WHITELIST` remains disabled,
so existing entries in `whitelist.json` are preserved across container starts.
Add Java players with `whitelist` and Floodgate players with `fwhitelist`.
The vanilla spawn protection radius is set to `0`, so non-operator players can
build at the initial world spawn like anywhere else.

The companion `mc-bot` manages registrations over RCON. RCON is only reachable
through the external `minecraft-control` Docker network and is not published as
a host port. Create that network once on the Docker host before deploying:

```sh
docker network create minecraft-control
```

Set the same strong `MINECRAFT_RCON_PASSWORD` secret on this application and
the mc-bot application. `MINECRAFT_CONTROL_NETWORK` can be changed when a
different pre-created network name is required. Never publish TCP/25575.

## World replacement

Stop the server cleanly before copying or replacing world data. Back up the
entire `minecraft-crossplay-data` volume first. Validate converted Bedrock data
against a separate copy running the same Paper version before replacing the
live world.

Do not commit world data, Floodgate keys, player data, RCON credentials, or
server-generated configuration.
