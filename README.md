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
Minecraft log. Experience is batched per player once every five seconds to avoid one
log record per orb. The companion `mc-bot` consumes those events without
continuously polling scoreboards or experience over RCON. Active voice bonus
experience is applied inside the Paper event, so it does not issue an RCON
command per gain. The plugin runs only on the server; clients do not install
anything.

All event-bridge bonuses can be disabled without removing the plugin by setting
`USAPO_BONUSES_ENABLED=false`. This stops the fishing, woodcutting, natural
experience, and voice XP listeners while leaving unrelated RCON, whitelist,
and shop functions available. The Compose default is temporarily `false` for
performance diagnosis; change it back to `true` to restore the bonuses.

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

The default runtime profile targets up to 20 players on a small dedicated
host: a 6 GiB Java heap, an 8 GiB container memory limit, a view distance of 16,
and a simulation distance of 4. Aikar JVM flags are enabled to reduce garbage
collection pauses. Actual capacity still depends on CPU performance, explored
chunks, entities, farms, and other workloads on the host.

The whitelist is enabled and enforced. `OVERRIDE_WHITELIST` remains disabled,
so existing entries in `whitelist.json` are preserved across container starts.
Add Java players with `whitelist` and Floodgate players with `fwhitelist`.

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
