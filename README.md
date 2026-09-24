# Chill Cafe cross-play server

Paper, Geyser, and Floodgate configuration for the Chill Cafe cross-play server.

Paper 26.2 build 92 and itzg/minecraft-server 2026.8.0 remain pinned while
Enderman and experience behavior is investigated. Geyser 2.11.3 build 1245
supports Bedrock 26.51; Floodgate 2.2.5 build 141, ViaVersion 5.11.0,
ViaBackwards 5.11.0, Multiverse-Core 5.8.1, Multiverse-Portals 5.3.0, and
Multiverse-NetherPortals 5.1.0 are also pinned. Startup cleanup is limited to
those seven plugin JAR families and the replaced official inventory JAR so
persisted copies are replaced by the pinned artifacts; unrelated plugins are
not removed.

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
anything. Placing any log or Nether stem that qualifies for the woodcutting
combo publishes a reset before that placed block can extend the same player's
combo when broken again.

All event-bridge bonuses can be disabled without removing the plugin by setting
`USAPO_BONUSES_ENABLED=false`. This stops the fishing, woodcutting, mining,
natural experience, and voice XP listeners while leaving unrelated RCON,
whitelist, and shop functions available. The Compose default is `true`; use
`false` only for temporary performance diagnosis.

The resource shop can atomically exchange emeralds from an online linked
player's inventory for diamonds at 32:1 (32/64 emeralds for 1/2 diamonds),
and diamonds back to emeralds at 1:16 (1/4 diamonds for 16/64 emeralds).
The plugin verifies both the input and output inventory before changing either,
rejects a full output inventory without consuming the source item,
and stores recent request UUIDs in player data so an RCON response retry cannot
repeat an exchange. Successful exchanges are announced in Minecraft and
written as a UUID-based structured audit event for mc-bot's Discord log.

Linked players can open the shared exchange menu with `/exchange`. Java clients
receive a chest menu, while Floodgate/Bedrock clients receive a touch-friendly
form. Both include a confirmation screen for server XP to Minecraft XP, server
XP to resources, diamond/emerald conversion, resource buyback, and a private XP
balance check. Resource buyback accepts full stacks of emeralds (500 server XP
per stack), dirt, sand, sandstone, deepslate, cobbled deepslate, and tuff at
their configured fixed rates. It ignores named or
metadata-bearing items and awards at most 3,000 server XP per player per JST
day, resetting at 00:00 JST. The completion message includes the updated server
XP balance and the remaining daily buyback allowance. Clients where a menu
cannot be shown can use `/exchange xp <50|250|500|5000>`,
`/exchange resource <resource-id> <count>` (with current values offered by tab completion),
`/exchange emerald-diamond <32|64>`,
`/exchange diamond-emerald <1|4>`,
`/exchange buyback <1|2|4|8|16|max|all>` while holding the item in the main
hand, and `/exchange balance`. The request carries
the exact displayed cost, but mc-bot checks it again against level-bot's current
shop before spending XP. Price changes are rejected and the player is asked to
open the menu again. Results are sent privately to the requesting player; the
existing completed-exchange announcements remain unchanged. No client add-on
is required.

The server-XP resource catalog is synchronized as one versioned snapshot from
mc-bot over the internal RCON command. The plugin validates every item against
Paper's material registry, persists the snapshot atomically in
`plugins/UsapoEventBridge/resource-catalog.yml`, and then swaps the in-memory
catalog shared by Java menus, Floodgate forms, and direct commands. A stale,
conflicting, malformed, or unpersistable snapshot leaves the last valid catalog
active. Once this plugin version has been installed with one planned restart,
later catalog and price changes do not require a Minecraft restart.

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
full pagination, common-price buttons with an arbitrary-price fallback, listing
and cancellation confirmations, an own-listings view, and a private server XP
balance check. Java players receive a chest menu that shows the actual listed
items and provides product details, purchase confirmation, pagination,
own-listing cancellation, and a button-only number pad for the listing price.
If neither UI can be shown, players can still use
`/market list [page]`, `/market sell <total-price>`, `/market buy <listing>`,
`/market mine`, `/market cancel <listing>`, `/market claim`, and `/market balance`. Listing moves
the entire main-hand stack, including its item metadata, into persistent escrow.
Prices and balances are displayed explicitly as server XP, distinct from
Minecraft experience. A completed purchase charges the displayed server XP
from the buyer and credits it to the seller. Delivery and return require the
recipient to be online with enough inventory space; retry IDs stored in player
data prevent a lost RCON response from duplicating the item.
When mc-bot revokes a managed account, it can atomically cancel each active
listing into a persistent UUID-scoped market return mailbox without requiring
the seller to be online. Java and Bedrock menus show that mailbox, `/market claim`
delivers every item that fits, and join notices identify returns still waiting.
Listing cancellation and mailbox creation are one YAML save; claim delivery uses
persisted transfer history so retries do not duplicate an item.
Java command output, Bedrock forms, and the listing event sent to mc-bot all
render the item's effective name through the bundled Minecraft Java 26.2
Japanese translations. This covers data-dependent vanilla names while
preserving custom item names. When that effective name differs from the item's
underlying type, recognized enchantment-description names append the translated
type in parentheses, for example
`効率Ⅴ耐久力Ⅲ修繕付きの斧（ダイヤモンドの斧）`. Player-assigned base names
stay intact. Ordinary enchanted equipment, including tridents, appends every
enchantment name and level to both default and player-assigned names. Enchanted
books append their stored enchantment names and levels;
books with five or more enchantments show the first four and the number of
remaining types. Every market surface uses the same name.

Linked players can create item-delivery quests with `/quest`. Quests accept
ordinary stackable items without custom names or other metadata, plus enchanted
books. An enchanted book used as the requested item must match the stored
enchantment types, levels, and visible custom name at submission; hidden anvil
repair history is ignored. Its requested count is fixed at one, so Java and
Bedrock creation screens skip the count input for books. Quest displays include
every stored enchantment instead of abbreviating books with five or more types.
Enchanted books can also be escrowed as rewards without losing their metadata.
Both the requested amount and reward must fit in one stack.
Creation is a two-step escrow flow: hold a sample of the requested item and run
`/quest create <count> <hours>`, then hold the entire reward stack and run
`/quest confirm`. `/quest discard` removes a stale draft without consuming an item.
Bedrock shows the exact request, deadline, and held reward in a final confirmation
before escrow. The reward is persisted before the quest is published.
Server managers can also create system-issued quests from mc-bot's Minecraft
admin menu without a linked Minecraft account or escrowed inventory. The Discord
flow searches the Java 26.2 Japanese and English item catalogs or item IDs,
requires explicit selection when a name is ambiguous, and sends the selected
request item, reward item, counts, and 1–72 hour fulfillment deadline through
the internal RCON command.
Paper validates that both IDs are real item materials and that each count fits
one stack before atomically creating the quest. The system issuer uses the zero
UUID and Minecraft name `-`; Discord renders the bot account as the issuer.
On completion, the submitted item is consumed and only the worker's generated
reward enters a mailbox. Cancellation or listing expiry never creates a refund
or a notice for the non-existent system player.
Java players can browse with `/quest list [page]`; Floodgate/Bedrock players get
controller- and touch-friendly paginated browse, count and deadline sliders,
confirmation, own-quest, submit, abandon, cancel, and claim forms from `/quest`.
Java players get the corresponding chest
menus from `/quest`, including item previews and button-only number pads for the
requested count and fulfillment hours. The argument-based commands remain as a
fallback if a UI cannot be shown.

A quest has one assignee and requires all requested items in one submission.
Use `/quest accept <quest>`, `/quest submit <quest>`, `/quest abandon <quest>`,
and `/quest cancel <quest>`. An accepted quest cannot be cancelled by its owner.
If its 1–72 hour fulfillment deadline passes, it is reopened; an unaccepted
quest expires after seven days and returns the reward. Completed submissions,
rewards, and cancelled/expired returns enter a persistent mailbox and are
delivered exactly once with `/quest claim`, including after a restart or a full
inventory. A full-inventory claim skips other items that still fit and names the
items left behind. Assignment and listing expiry notices are persisted and shown
on the affected players' next command or login. Paper stores quest state,
transition IDs, escrow, and mailbox claims
atomically in `plugins/UsapoEventBridge/quest.yml`. The same file also keeps the latest
state publication and completion broadcast pending until each succeeds. A crash between
quest completion and notification therefore resumes the missing work at plugin startup
or during the periodic recovery pass. The internal mc-bot reconciliation command can
invalidate an unlinked owner's quest even after it was accepted; no submitted item is
removed in that state, and the escrowed reward returns to the owner's mailbox.
Account revocation invokes that invalidation for every nonterminal owned quest and
releases every assignment held by the departing account, allowing it to be
relisted immediately. The operations are idempotent so mc-bot can keep the
account removal pending and safely retry after an ambiguous RCON response.

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

The per-player Paper packet limit is raised from the default 500 to 100,000
packets per seven-second interval. The existing interval and `KICK` action stay
unchanged, preserving the rate-limit protection while allowing larger legitimate
bursts from building and cross-play clients. The image reapplies this setting at
startup from `paper-patches/packet-rate-limit.json`; applying it to a running
server requires a planned Minecraft restart.

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

## Additional world and inventory groups

Multiverse-Core 5.8.1 exposes the existing world as `world_1` and the additional
normal world, generated with seed `259`, as `world_2`. All six dimensions have
survival as their configured default. Only the normal `world_2` dimension
allows players to select creative mode with `/mode`; `world_1` and all four
Nether/End dimensions remain survival. Enable `world.enforce-gamemode` so
joining or changing worlds applies the destination's game mode (operators are not exempt
unless an explicit `mv.bypass.gamemode.<worldname>` permission is granted).
These are
Multiverse display/command aliases; the stored dimension keys and legacy names
remain unchanged to preserve player locations and world identity. Both run in
the same Paper process, so the configured maximum
of 20 players applies across all worlds together. Multiverse-Inventories 5.3.6
separates inventory/hotbar, armor, offhand, and Ender Chest into two groups:
`world_1` plus the existing Nether/End, and `world_2` plus its dedicated
`world_2_nether` and `world_2_the_end`. Experience, health/food/respawn behavior,
and ordinary activity/advancement rewards remain unchanged. Game-mode-specific
inventory splitting remains disabled; there is no new inventory migration.

Ordinary Java and Bedrock players can use `/mode` without arguments to toggle
their own survival/creative mode only in the normal `world_2` dimension (exact
key `minecraft:resource`). The dedicated `usapo.mode.use` permission defaults
to true; it grants no OP, vanilla gamemode, target-player, adventure, or
spectator privileges. All other dimensions, including both Nether/End pairs,
reject this command even for operators. Existing administrative commands are
unchanged. A world_2-only persistent player-data key remembers each player's
last survival/creative choice. Rejoining the server or returning from another
world restores that choice; a player without a remembered choice starts in
survival. An existing player logging back into world_2 for the first time after
this update imports their vanilla saved logout mode before Multiverse applies
its default. Commands, departures, respawns, quits and clean server shutdowns
preserve the selection; other worlds never overwrite it or restore creative.
The plugin brackets Multiverse's NORMAL join/world-change handlers, with
`gamemode-and-flight-enforce-delay: 0`; keep those priorities and that delay.
Existing creative flight is preserved across login restoration. Toggling
does not replace inventory, armor, offhand, Ender Chest, or experience and does
not lift world-based economy restrictions. This command remains registered
when activity bonuses are disabled. Installing its new plugin image requires
one announced restart; using the command afterward requires no restart.

Gacha, the player market, exchanges (including buyback/balance), and quests are
unavailable in all three dimensions of `world_2_inventory`. The policy uses
exact dimension keys, not aliases, permissions, or the current game mode, so
carrying creative items into the survival Nether/End cannot bypass it. Commands
and Java/Bedrock menu callbacks show a Japanese unavailable message. Callbacks
recheck the player's world after scheduling, including menus opened before
travel. The companion mc-bot performs the same check for Discord controls and
queued requests before spending, and guards raw item/XP delivery in the same
server command. Internal item transactions reject new work with
`world_restricted` but still reconcile already-applied request IDs. Automatic
account-revocation cleanup and stored escrow recovery remain available without
new item delivery in the restricted group. `world_1` and its Nether/End retain
the existing four features.

This is not complete economic isolation: Minecraft experience and ordinary
activity/advancement rewards are still shared, as explicitly left outside this
change. Do not silently change those policies or existing inventory profiles.

For an initial economy-restriction rollout, deploy both the Paper plugin and
companion mc-bot before allowing creative mode. The mode-memory update only
requires the Paper plugin. With players held offline and configurations backed
up, use `mv modify resource set gamemode survival`, `mv config enforce-gamemode true`,
and `mv config gamemode-and-flight-enforce-delay 0`. Applying the game mode
in the world-change event prevents a creative-mode tick in a survival world.
Confirm the other five mode settings remain survival, the inventory groups and
gamerules are unchanged, and the normal travel confirmation still works.

The image packages the checksum-pinned official Multiverse-Inventories 5.3.6
release with only its embedded `com.viaversion.nbt` package relocated to a
private namespace. The upstream JAR otherwise conflicts with the existing
ViaVersion/ViaBackwards class loaders. `multiverse-inventories-isolated` builds
and verifies this isolated artifact; plugin identity, item serialization, and
saved inventory data formats are unchanged. Do not additionally install the
unmodified JAR alongside `/plugins/usapo-multiverse-inventories.jar`.

Players travel through the registered gates. `/mvtp world_2`, `/mvtp world_1`,
and other Multiverse teleport command forms are operator-only; the server's
`permissions.yml` no longer grants ordinary players self-teleport permission.
Existing Nether/End portal access remains available. The new world does not
have an automatic reset schedule.
`command.resolve-alias-name` is enabled. Fine-grained permission targets still
use the internal legacy names `world` and `resource`, not the aliases.

Multiverse-NetherPortals 5.1.0 links normal Nether and End portals to the correct
set of dimensions. Keep explicit bidirectional links from `world` to the
existing `world_nether`/`world_the_end`, and from `resource` to the new
`world_2_nether`/`world_2_the_end`. New dimensions are generated with seed 259;
existing worlds, seeds, portal builds, and player files are not replaced.
Set `handle-end-exit-respawn: true` in its current-version config so the dedicated
End exit returns to `world_2`, preserving a valid bed/anchor in that same set.
The new dimensions' fallback `respawn-world` is explicitly `resource`, because
their names cannot auto-resolve the base world's internal name. Existing
dimension respawn settings and players' saved respawn points are unchanged.

The event bridge also rejects Multiverse destination teleports into Nether and
End for all players, including operators. It checks the resolved destination,
so `/mvtp`, its aliases, coordinate/player/anchor/bed/cannon destinations, and
`--unsafe` cannot bypass this policy. Normal Nether/End portals, respawns,
unrelated Bukkit teleports, and return travel to either normal world are
unchanged. Custom gates using Multiverse destinations also cannot shortcut
into Nether/End.

Multiverse-Portals supplies `/mvp` and its built-in `/mvp wand` selection tool;
WorldEdit is not required. Operators can select the walk-through area inside a
gate and register it with `/mvp create <portal-name> w:world_2` or `w:world_1`.
Installation does not create gates or choose their locations. Keep portal
creation and management limited to operators, while allowing regular players
to use the intended gates (`portal-usage.enforce-portal-access: false`).
`portal-usage.use-on-move` remains enabled for hollow frames, including
glowstone frames without portal blocks. Portal definitions persist in the same
data volume.

The two existing gates named `to_world_1` and `to_world_2` display animated,
semitransparent cyan surfaces inside their glowstone frames (about 70% average
opacity). This animates the membrane, not the player's whole-screen Nether
distortion. Their actual interiors are AIR, so the
display does not create a Nether portal, obstruct movement, or change redstone
or block physics. The event bridge sends client-only block states to nearby
players every 10 ticks with bounded work, only in loaded and already-sent chunks.
It never changes world blocks or loads chunks. Other portal names, frames,
liquids, and decorations are untouched. Removing a display restores the current
real block, not an old snapshot. Selections support up to 32 blocks high/wide.

Java players receive an **optional** resource-pack prompt once per session.
Only success for this pack's UUID enables the surface; decline/download failure
keeps the gate usable with cyan particles. Other plugins' resource packs are not
replaced. Bedrock uses Geyser's existing custom-content/integrated-pack support
and existing required-pack policy; the bridge verifies the installed pack and
mapping hashes before displaying it. See [portal-packs](portal-packs/README.md)
for reproducible assets and their two reserved tripwire states. Those states are
client carriers only, not physical blocks. Before rollout, scan actual world
block indices for collisions with both reserved states. Future constructions
using those exact vanilla states would also display the custom model.

For an existing purple gate, back up before removing only its verified interior
Nether-portal blocks, with players held offline. Do not clear ordinary Nether
portals or change gate bounds/destinations. The old purple initializer remains
in source for rollback but is no longer scheduled. No restart-time block
migration is performed by the plugin.

Cross-group travel first opens a Java inventory menu or Bedrock confirmation
form. Items are saved for the departing group and restored for the arriving
group automatically; players do not need to remove their equipment. Canceling,
closing, dying, disconnecting, leaving the gate, or waiting over 30 seconds does
not move the player or restore an old item snapshot. Same-group travel,
including normal Nether/End portals within either world's own set, needs no
confirmation. The listener
uses Paper's final resolved teleport destination, not preliminary portal-search
coordinates. Cross-group native-portal confirmation preserves that destination
and cooldown but does not replay vanilla's post-transition sound/ticket callback.
Outside a gate, operators can retry a canceled, unavailable, or expired
confirmation with the teleport command without first moving away. Automatic
gate retries remain suppressed until the player leaves the gate. Travel
diagnostics record request/confirmation outcomes and validity checks by UUID,
without recording coordinates or item contents.

The cyan gates show confirmation in place, without stepping the player back.
Java clients automatically close chest menus while touching a Nether portal.
For real purple portals, including unregistered vanilla Nether portals when crossing
inventory groups, the bridge checks the player's whole body
(not only the feet), moves them to a checked nearby spot in the **same world**,
then opens confirmation after the position update. This short step back does
not switch inventories. The helper never loads chunks or changes blocks; it
requires empty body space and safe, solid support, and refuses the transfer if
neither side is safe. Moving away or re-entering before confirmation invalidates
the request. Arrival cooldown also covers Multiverse's `PLUGIN` teleport cause,
preventing the destination gate from immediately returning the player.
Ordinary `world_2` Nether/End travel now stays in its dedicated group and bypasses
confirmation and step-back logic, just as ordinary `world_1` Nether/End travel
does. Unusual cross-group native transfers still require confirmation and use
the safe-outside check as a fallback. Nether and End shortcuts through Multiverse
commands remain blocked; the linked native portal events do not use those commands.

For first activation, stop the server and back up the entire data volume.
Confirm every existing player file is in `world`, `world_nether`, or
`world_the_end` before installing fresh inventory profiles; if any player is in
`resource`, resolve the migration explicitly instead of silently adopting that
player's existing items into `world_2`. Existing shared items remain in the
`world_1` group and each player's first `world_2` inventory is empty. Paper 26.2
player files live in `world/players/data/`; do not convert their item format or
import staging profiles. Multiverse-Inventories saves the existing loaded items
on the first group departure or logout, without a separate importer.

Persist these tested Multiverse-Inventories settings in the data volume:
`enable-bypass-permissions: false`, `enable-gamemode-share-handling: false`,
`default-ungrouped-worlds: false`, `active-optional-shares: []`,
`validate-bed-anchor-respawn-location: false`, `reset-last-location-on-death: false`,
`use-byte-serialization-for-inventory-data: true`, and
`apply-playerdata-on-join: false`. `world_1_inventory` shares `[inventory]` across
`world`, `world_nether`, and `world_the_end`; `world_2_inventory` shares
`[inventory]` across `resource`, `world_2_nether`, and `world_2_the_end`.
A `global_stats` group covers all six worlds with
`shares: []` and `disabled-shares: [all, -inventory]`, preventing changes to
experience, health, food, respawn, and other non-item state. Do not replace this
with an actively shared `all` group. No conflicting inventory shares are allowed.
When adding dedicated dimensions, retain the existing group names and profiles;
add only the two world memberships. Do not rerun first-activation item adoption
or rewrite existing player files. Back up while stopped, keep the admission hold
until links/groups are verified, and copy only appropriate rule/border/Paper
settings from the matching existing dimensions while the new worlds are unloaded.

Keep `plugins/UsapoEventBridge/inventory-migration.pending` present while
verifying the rollout: the event bridge blocks new logins until the marker is
removed and both inventory handling and confirmation registration are ready.
Remove the marker only after checking startup logs, profiles/configuration,
unchanged player files and world settings, and Java/Bedrock readiness. Operators
are subject to the same inventory separation as other players.

Back up the complete data volume with the server stopped before first enabling
Multiverse. Its persisted configuration must preserve Paper's existing game
mode, flight, entity spawning, chat, join, and respawn behavior. Disable spawn
adjustment for the existing dimensions before the first import. Keep the
existing overworld's full gamerule set, difficulty, PvP state, world border,
and Paper world overrides when initializing the additional world; setting only
`keep_inventory` is insufficient. In particular, this server uses custom
sleeping-percentage and random-tick rules.

Paper 26.2 stores dimensions inside `world/dimensions/`. Only copy the source
dimension's `data/minecraft/game_rules.dat`, `data/minecraft/world_border.dat`,
and applicable `paper-world.yml` settings while the relevant worlds are
unloaded. Never copy the source's UUID metadata, world generation settings,
chunks, or player files into the seed-259 dimension. Validate the resulting
seed, rule equality, startup logs, and both travel directions before opening
the world to players. Preserve the generated world and Multiverse settings in
the existing persistent data volume across deployments.

## World replacement

Stop the server cleanly before copying or replacing world data. Back up the
entire `minecraft-crossplay-data` volume first. Validate converted Bedrock data
against a separate copy running the same Paper version before replacing the
live world.

Do not commit world data, Floodgate keys, player data, RCON credentials, or
server-generated configuration.
