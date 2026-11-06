"""Validate rendered Compose JSON without printing credentials or changing state."""

import argparse
import ipaddress
import json
import sys


def validate(config, *, allow_test_address=False):
    service = config["services"]["minecraft"]
    control_network = config.get("networks", {}).get("minecraft-control", {})
    if ("minecraft-control" not in service.get("networks", {})
            or not isinstance(control_network, dict)
            or control_network.get("external") is not True
            or not isinstance(control_network.get("name"), str)
            or not control_network["name"].strip()):
        raise ValueError("The shared external Minecraft control network must remain attached")
    env = service["environment"]
    endpoint = env.get("CFG_BEDROCK_WEBRTC_ADDRESS", "")
    host, separator, port = endpoint.rpartition(":")
    if separator != ":" or port != "19133" or host != host.strip():
        raise ValueError("A numeric public relay IPv4 and UDP port 19133 are required")
    try:
        address = ipaddress.IPv4Address(host)
    except ipaddress.AddressValueError:
        raise ValueError("The advertised relay address must be a numeric IPv4") from None
    if address.is_multicast or address.is_unspecified or address.is_reserved:
        raise ValueError("The advertised relay IPv4 is not unicast")
    if not allow_test_address and not address.is_global:
        raise ValueError("The advertised relay IPv4 must be globally reachable")
    expected = {
        "VERSION": "26.2",
        "PAPER_BUILD": "135",
        "PATCH_DEFINITIONS": "/usapo-nethernet-patches",
        "MAX_PLAYERS": "20",
        "ONLINE_MODE": "TRUE",
        "ENABLE_WHITELIST": "TRUE",
        "ENFORCE_WHITELIST": "TRUE",
    }
    if any(str(env.get(key)) != value for key, value in expected.items()):
        raise ValueError("Candidate version, patch selection or admission settings differ")
    expected_plugins = [
        "https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/1286/downloads/spigot",
        "https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/spigot",
        "https://github.com/Multiverse/Multiverse-Core/releases/download/5.8.1/multiverse-core-5.8.1.jar",
        "https://github.com/Multiverse/Multiverse-Portals/releases/download/5.3.0/multiverse-portals-5.3.0.jar",
        "https://github.com/Multiverse/Multiverse-NetherPortals/releases/download/5.1.0/multiverse-netherportals-5.1.0.jar",
    ]
    if env.get("PLUGINS", "").splitlines() != expected_plugins:
        raise ValueError("The resolved plugin URLs differ from the tested candidate")
    if env.get("MODRINTH_PROJECTS", "").splitlines() != ["viaversion:Fkfcr89K", "viabackwards:WFO37e8T"]:
        raise ValueError("The resolved Via versions differ from the tested candidate")
    published = service.get("ports", [])
    expected_ports = {(25565, "tcp"), (19132, "udp"), (19132, "tcp"), (19133, "udp")}
    actual = {(int(p["target"]), p["protocol"]) for p in published}
    if len(published) != 4 or actual != expected_ports:
        raise ValueError("Exactly the four approved game ports must be published; never RCON")
    if any(int(p["published"]) != int(p["target"]) for p in published):
        raise ValueError("Public and container port numbers must match this relay plan")
    bindings = {p.get("host_ip", "") for p in published}
    if len(bindings) != 1 or next(iter(bindings)) in ("", "0.0.0.0", "::"):
        raise ValueError("All game ports must bind to the same explicit host address")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-test-address", action="store_true",
                        help="Only for isolated fixtures, never production activation")
    args = parser.parse_args()
    try:
        validate(json.load(sys.stdin), allow_test_address=args.allow_test_address)
    except (ValueError, KeyError, TypeError):
        # Do not echo the rendered configuration: it includes the RCON password.
        print("NetherNet preflight failed: check address, pins, admission settings, ports and networks.",
              file=sys.stderr)
        return 1
    print("NetherNet Compose preflight passed (no changes applied).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
