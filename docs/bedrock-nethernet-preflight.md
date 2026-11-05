# Bedrock 26.60 / NetherNet preparation

Status: **production rollout authorized on 2026-11-05 JST; verification pending**.
The 2026-11-04 preparation did not change production. The subsequent release
authorization covers this exact candidate, scoped relay/firewall additions and
an announced restart after a verified stopped-server backup.
The base Paper-only candidate and its tests remain documented in the
[Paper preflight](paper-upgrade-preflight.md).

## Candidate and activation boundary

Use `docker-compose.yml` plus the explicit `docker-compose.nethernet.yml`
overlay. The base file alone retains the old Geyser and old pair of ports.
For Coolify's single Compose-file location, the prepared equivalent is
`/docker-compose.nethernet.coolify.yml`. It contains the same merged settings
with variables still unresolved, and a regression test compares its fully
rendered result with the two-file combination. Do not enter two filenames into
Coolify's single location field or rely on custom start flags to merge files
after its configuration parser has run. Changing the selected location is a
future explicitly approved production action; the current location is unchanged.
The inspected Coolify 4.6.0 installation accepts list-style environment entries
and preserves multiline/equals-containing values; this was checked read-only
using its conversion helper, without saving any application configuration.
The overlay retains Paper **26.2 build 135**, Multiverse-Core 5.8.1, Floodgate
2.2.5 build 141 and the other current Multiverse plugins, including the isolated
inventory package. It changes only the transport dependencies and configuration:

- Geyser 2.12.1 build **1286**, SHA-256
  `e66ab70604e2feff2f5e3615d10820ce1960978eaa210d3a449a3b3f8f4d9b3e`.
- ViaVersion and ViaBackwards **5.12.2**, pinned Modrinth version IDs
  `Fkfcr89K` and `WFO37e8T`.
- New transport settings under `/usapo-nethernet-patches`. That directory also
  contains the same canonical Paper patches as the default directory, so
  selecting it preserves the existing packet-limit and sapling-despawn rules.

New Geyser speaks Java 26.3 internally, but ViaVersion supplies the
26.3-client-to-26.2-server conversion. A Paper 26.3 world/data upgrade is not
required. Keep Paper and Core at the conservative versions above.

Sources: [Geyser build 1286](https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/1286),
[NetherNet announcement](https://geysermc.org/blog/preparing-for-nethernet),
[ViaVersion protocol registration](https://github.com/ViaVersion/ViaVersion/blob/5.12.2/common/src/main/java/com/viaversion/viaversion/protocol/ProtocolManagerImpl.java).

## Exact network plan

| Purpose | Public relay → private host → container | Change |
| --- | --- | --- |
| Java game connection | TCP 25565 → 25565 → 25565 | Preserve |
| Legacy Bedrock RakNet | UDP 19132 → 19132 → 19132 | Preserve |
| NetherNet built-in signaling | TCP 19132 → 19132 → 19132 | Add |
| NetherNet WebRTC game traffic | UDP 19133 → 19133 → 19133 | Add |

All container ports bind to the existing explicit `MINECRAFT_BIND_IP`, not all
host interfaces. RCON remains unpublished. Keep DNS A-only operation, WireGuard,
the unrelated IPv6 forwarder and HAProxy unchanged.

Set `MINECRAFT_BEDROCK_PUBLIC_IPV4` to the **verified numeric IPv4 of the public
relay**, not the private host, container, wildcard or loopback address. The
overlay passes it as `CFG_BEDROCK_WEBRTC_ADDRESS=<public IPv4>:19133`.
Validate the resolved configuration without printing its embedded credentials:

```sh
docker compose -f docker-compose.yml -f docker-compose.nethernet.yml config --format json |
  python3 deployment/validate_nethernet.py
```

Do not use the validator's `--allow-test-address` option in production. Missing
or empty public address blocks Compose rendering; the validator additionally
rejects whitespace, hostnames, non-global addresses, unexpected ports and
version/authentication drift. Rendering/validation applies no changes.

The scoped patch preserves the rest of the existing Geyser file. It sets
`bedrock.transport: both`, `port: 19132`, `webrtc-port: 19133`,
`clone-remote-port: false`, `signaling.mode: builtin`, the one public advertised
endpoint, and `signaling.stun-discovery: false`. `$put` adds or updates the
WebRTC port and the two signaling keys without replacing their parent maps;
`value-type: list of auto` expands the advertised endpoint
as an actual YAML sequence. Raw nested JSON strings do not receive environment
interpolation in the pinned helper, so do not replace this with a literal array
containing `${CFG_BEDROCK_WEBRTC_ADDRESS}`.

Before applying, assert the existing `bedrock.address` is the intended wildcard
bind, the signaling map exists, Floodgate is enabled and
`advanced.bedrock.validate-bedrock-login` remains true, and no environment/system-property
overrides supersede this file. Preserve current HAProxy/X-Forwarded-For/PROXY
trust settings; the plain L4 relay does not require enabling those mechanisms.
Do not replace the full generated Geyser configuration or trust arbitrary proxy
addresses. Java and signaling must remain different TCP ports: a collision can
cause Geyser to switch to an external NXS registration flow, outside this plan.

The inspected production YAML stores `java.auth-type: online`. Do not change
that field: both old and new Geyser's Spigot bootstrap automatically choose
Floodgate when its plugin is present. Verify effective runtime auth is
`FLOODGATE` and the Floodgate cipher/key loaded, rather than incorrectly requiring
the persisted field to say `floodgate`.
[Automatic auth selection](https://github.com/GeyserMC/Geyser/blob/109bf9dce446d620078ea4b4d4bcdb13879fb494/core/src/main/java/org/geysermc/geyser/GeyserImpl.java#L418).

An empty advertised-address list does not fail closed: Geyser can fall back to
STUN or local interface addresses. Keep the explicit public endpoint and STUN
disabled. Built-in HTTP may show the client a first-connection trust prompt;
HTTPS certificates are not introduced here. Preserve and back up
`plugins/Geyser-Spigot/nethernet/identity.pem` after initial generation. Replacing
that identity requires clients to trust the server again.

Implementation evidence: [configuration schema](https://github.com/GeyserMC/Geyser/blob/109bf9dce446d620078ea4b4d4bcdb13879fb494/core/src/main/java/org/geysermc/geyser/configuration/GeyserConfig.java),
[NetherNet binding and NAT handling](https://github.com/GeyserMC/Geyser/blob/109bf9dce446d620078ea4b4d4bcdb13879fb494/core/src/main/java/org/geysermc/geyser/network/bedrock/nethernet/NetherNetServer.java).

## Relay preparation

Read-only live inspection confirmed the existing relay uses IPv4 DNAT/SNAT for
UDP 19132 and TCP 25565 to the Minecraft host. Neither new protocol/port was
occupied on the relay or Minecraft host. The host firewall does not yet allow
the two new public ports. The private infrastructure repository contains the
exact application/relay target and a separately prepared, unapplied two-line
change: add `19133` to the existing forwarder's UDP list and `19132` to its TCP
list. Do not copy private addresses or credentials into this public repository.

At approved activation, add only TCP 19132 and UDP 19133 to the relay's verified
firewall zone in both runtime and permanent state. Avoid a whole-firewall reload
that could discard the existing forwarding rules. Recreate only the existing
Minecraft forwarding service, not WireGuard or the entire stack. Recreating it
briefly interrupts the existing ports too, so announce maintenance first.
Provider-side port restrictions and the unopened external path remain untested.

## Verification and remaining release gates

- Deployment tests cover both Compose entry points, version/authentication
  drift and port/address validation, including the actual pinned image helper
  applying the patch twice to a disposable file. The new key, expanded single
  address, idempotence, Floodgate/authentication sentinels and unrelated values
  were checked. Existing assertions were not changed.
- Existing Java 25 event-bridge `mvn verify` passed all 350 tests again; isolated
  inventory packaging `mvn verify` passed its official checksum, 47 classes,
  1,131 references, 16 resources and unchanged plugin descriptor checks.
- The 20 portal-pack tests passed and all four artifacts reproduced exactly.
- The prepared image built successfully; existing Maven build layers were
  reused. Image: `minecraft-paper262-nethernet-prepared:20261104`, ID
  `sha256:af0c02b291d4eed89ae7730681574343c877dae9846fb5c6ee5df16a82f7c3e8`.
- An isolated six-world fixture, copied from the old Paper 26.2 build-92 test
  baseline (not production worlds/player data), starts Paper 135 with Geyser
  1286, Via 5.12.2 and unchanged Core 5.8.1. The Java translation path is present.
- That fixture passed 231 inventory/mode/economy checks, 202 Nether/End and gate
  movement checks, and 15 Geyser/Via/mapping checks: **448 in total**. Both cyan
  tripwire states map to the intended custom portal and remain distinct. No
  external NXS registration was selected. The local test container stopped
  cleanly with no published ports or remaining network attachment.
- A further restart with the same persisted `java.auth-type: online` as
  production passed 20 auth/transport/mapping checks (including the repeated
  15 above): effective auth became `FLOODGATE`, the plugin and cipher were
  loaded, Bedrock-login validation stayed enabled, and the stored auth value
  remained unchanged. RakNet and HTTP status checks passed again. This is
  still not an authenticated player login.
- A local RakNet status ping and built-in `GET /v1/join` JSON response report
  Bedrock 26.60. These are status/signaling checks, not authenticated game logins.
- New Geyser can skip malformed custom mappings without disabling the plugin;
  therefore the cyan pack/mapping checks must be inspected separately from
  plugin-enabled status.

The actual public WebRTC path, authenticated Bedrock 26.60 login, older RakNet
login, Java login, forms and final gate rendering still require client checks.
An initialization log mentioning UDP 19133 is not proof of a completed WebRTC
handshake or live ICE/DTLS data channel. The test socket table did not show that
UDP port before a peer was created; the upstream implementation creates the
WebRTC peer lazily when SDP arrives. Do not report the data path as tested.

### Production startup correction, 2026-11-05

The first authorized deployment built successfully but did not reach Minecraft
startup. Helper 1.64.1 rejected `$set` for the absent
`bedrock.signaling.advertise-addresses` key. Read-only comparison with the
stopped backup established that both `advertise-addresses` and `stun-discovery`
were absent, while their parent map, `mode`, and `webrtc-port` already existed.
The initial synthetic fixture incorrectly assumed both signaling keys existed.

The correction changes only those two operations to `$put`; all seven logical
target fields, values, component pins, authentication settings and relay rules
are unchanged. New tests reproduce the observed missing-key shape without
copying private configuration values. They cover both keys missing, either key
missing alone, repeated application, interpolation and preservation of unrelated
values. The unchanged helper failed all three migration cases before the fix.
The original runtime test remains unchanged. There are now 16 tests: 14 normal
contract checks and two Docker helper tests; runtime tests need the environment
variable shown below. A finished deployment must still pass service readiness,
stopped-backup comparison and public status checks before reopening admission.

Before any release, complete the Paper preflight's space planning, announced
maintenance, verified full stopped-server backup, exact pushed-SHA/GitHub CI and
targeted deployment checks. Preserve all worlds, inventory groups, XP/economy
rules, mode memory, gate bounds, permissions and maximum 20 players. Keep the
admission hold until both client paths and existing game behavior are verified.
The 2026-11-05 release authorization permits committing/pushing this candidate
and applying its exact application/relay changes after the safeguards above.
DNS changes, broader firewall changes and unrelated services remain out of scope.

The release's tracked relay artifact is
[`deployment/minecraft-relay-ports.patch`](../deployment/minecraft-relay-ports.patch).
It changes exactly the same two port lists as the private preparation, but omits
private endpoint context so it can travel with the application's pushed source
commit. Verify its source commit and SHA-256, dry-run against the verified live
file, and apply only that patch. Do not rely on untracked files inside the
private infrastructure repository's incomplete gitlink.

Rollback restores matching stopped-server data/configuration and old binaries,
then reverts only the new forwarding ports and rules that were absent before
activation. Preserve the failed attempt and generated identity for investigation;
do not delete or overwrite existing backups to make space.
This full restore assumes players are still held out during maintenance. If
admission has resumed and new progress exists, do not overwrite it with an old
backup: preserve both states and obtain explicit direction on a recovery plan.

Reproducible additional checks:

```sh
NETHERNET_TEST_IMAGE=minecraft-paper262-nethernet-prepared:20261104 \
  python3 -m unittest discover -s deployment -p 'test_*.py' -v
docker build --tag minecraft-paper262-nethernet-prepared:20261104 .
git diff --check
```

Without `NETHERNET_TEST_IMAGE`, CI runs the 14 Compose/contract tests and skips
the two Docker patch integration tests. The integration tests must run locally;
the full existing CI-equivalent checks are still required before a future push.
