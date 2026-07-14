# Minecraft cross-play server

Paper, Geyser, and Floodgate configuration for a cross-play test server.

Paper tracks the latest stable Minecraft release. Geyser, Floodgate,
ViaVersion, and ViaBackwards are also resolved to their latest compatible
release whenever the container starts. Redeploy or restart the application to
pick up releases published after the previous start.

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

Set `MINECRAFT_BIND_IP` in Coolify to the host address that should accept game
traffic. Optionally set `MINECRAFT_SERVER_NAME` and `MINECRAFT_MOTD`. Do not
commit deployment-specific addresses or hostnames.

The named volume `minecraft-crossplay-data` stores the server, plugins,
configuration, and world independently of application recreation.

The whitelist is disabled, so any authenticated Java or Floodgate player can
join while the game ports are publicly reachable. Enable and enforce the
whitelist before using this configuration for a private server.

RCON remains disabled. From an exec shell inside the Minecraft container, send
administrative commands through the local console pipe, for example:

```sh
mc-send-to-console whitelist add PlayerName
```

## World replacement

Stop the server cleanly before copying or replacing world data. Back up the
entire `minecraft-crossplay-data` volume first. Validate converted Bedrock data
against a separate copy running the same Paper version before replacing the
test world.

Do not commit world data, Floodgate keys, player data, RCON credentials, or
server-generated configuration.
