import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from validate_nethernet import validate


ROOT = Path(__file__).resolve().parents[1]


def coolify_460_service_networks(networks):
    # Characterization oracle: installed Coolify 4.6.0 applicationParser,
    # bootstrap/helpers/parsers.php:1775-1788. Its string/array-only loop drops
    # null map values. These cases were also reproduced with its PHP collect().
    # https://github.com/coollabsio/coolify/blob/0becfba9113063336b09860579627fd218cb3fc4/bootstrap/helpers/parsers.php#L1775
    result = {}
    entries = enumerate(networks) if isinstance(networks, list) else networks.items()
    for key, value in entries:
        if isinstance(value, str):
            result[value] = None
        elif isinstance(value, (dict, list)):
            result[key] = value
    result["application-network"] = None
    return result


def compose(*, overlay=True, public_ip="192.0.2.10", single_file=False):
    env = os.environ.copy()
    env.update(MINECRAFT_BIND_IP="127.0.0.1", MINECRAFT_RCON_PASSWORD="ci-placeholder")
    env.pop("MINECRAFT_BEDROCK_PUBLIC_IPV4", None)
    if public_ip is not None:
        env["MINECRAFT_BEDROCK_PUBLIC_IPV4"] = public_ip
    args = ["docker", "compose", "--env-file", "/dev/null", "-f",
            "docker-compose.nethernet.coolify.yml" if single_file else "docker-compose.yml"]
    if overlay and not single_file:
        args += ["-f", "docker-compose.nethernet.yml"]
    return subprocess.run(args + ["config", "--format", "json"], cwd=ROOT, env=env,
                          capture_output=True, text=True, check=False)


class NetherNetDeploymentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base, candidate = compose(overlay=False), compose()
        if base.returncode or candidate.returncode:
            raise RuntimeError("Could not render the deployment test fixtures")
        cls.base = json.loads(base.stdout)
        cls.candidate = json.loads(candidate.stdout)

    def test_overlay_is_opt_in(self):
        env = self.base["services"]["minecraft"]["environment"]
        self.assertEqual(env["PATCH_DEFINITIONS"], "/usapo-paper-patches")
        self.assertIn("builds/1245/", env["PLUGINS"])
        self.assertNotIn("CFG_BEDROCK_WEBRTC_ADDRESS", env)
        self.assertEqual(len(self.base["services"]["minecraft"]["ports"]), 2)

    def test_overlay_only_changes_transport_dependencies_and_ports(self):
        base = copy.deepcopy(self.base)
        candidate = copy.deepcopy(self.candidate)
        for config in (base, candidate):
            service = config["services"]["minecraft"]
            service.pop("ports")
            for key in ("PATCH_DEFINITIONS", "PLUGINS", "MODRINTH_PROJECTS",
                        "CFG_BEDROCK_WEBRTC_ADDRESS"):
                service["environment"].pop(key, None)
        self.assertEqual(base, candidate)

    def test_dependency_pins(self):
        old = self.base["services"]["minecraft"]["environment"]
        new = self.candidate["services"]["minecraft"]["environment"]
        self.assertEqual(old["PLUGINS"].splitlines()[1:], new["PLUGINS"].splitlines()[1:])
        self.assertIn("versions/2.12.1/builds/1286/", new["PLUGINS"])
        self.assertEqual(new["MODRINTH_PROJECTS"].splitlines(),
                         ["viaversion:Fkfcr89K", "viabackwards:WFO37e8T"])

    def test_rendered_topology(self):
        validate(self.candidate, allow_test_address=True)

    def test_single_file_coolify_candidate_equals_overlay(self):
        result = compose(single_file=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout), self.candidate)
        for value in (None, ""):
            with self.subTest(value=value):
                self.assertNotEqual(compose(single_file=True, public_ip=value).returncode, 0)

    def test_coolify_network_parser_characterization(self):
        cases = (
            (["default", "minecraft-control"],
             {"default": None, "minecraft-control": None, "application-network": None}),
            ({"default": None, "minecraft-control": None}, {"application-network": None}),
            ({"default": {}, "minecraft-control": {"aliases": ["minecraft"]}},
             {"default": {}, "minecraft-control": {"aliases": ["minecraft"]},
              "application-network": None}),
        )
        for declared, expected in cases:
            with self.subTest(declared=declared):
                self.assertEqual(coolify_460_service_networks(declared), expected)

    def test_single_file_preserves_shared_network_through_coolify(self):
        # Check the source form BEFORE Docker Compose normalizes list and null
        # map to the same object, concealing this Coolify-specific regression.
        source = (ROOT / "docker-compose.nethernet.coolify.yml").read_text()
        blocks = re.findall(r"^    networks:\n((?:      [^\n]*\n)+)", source, re.MULTILINE)
        self.assertEqual(len(blocks), 1)
        lines = blocks[0].splitlines()
        self.assertEqual(lines, ["      - default", "      - minecraft-control"])
        declared = [line.strip()[2:] for line in lines]
        self.assertEqual(set(coolify_460_service_networks(declared)),
                         {"default", "minecraft-control", "application-network"})

    def test_shared_control_network_is_required(self):
        for drift in ("membership", "definition", "not_external", "empty_name"):
            with self.subTest(drift=drift):
                candidate = copy.deepcopy(self.candidate)
                if drift == "membership":
                    candidate["services"]["minecraft"]["networks"].pop("minecraft-control")
                elif drift == "definition":
                    candidate["networks"].pop("minecraft-control")
                elif drift == "not_external":
                    candidate["networks"]["minecraft-control"]["external"] = False
                else:
                    candidate["networks"]["minecraft-control"]["name"] = ""
                with self.assertRaises(ValueError):
                    validate(candidate, allow_test_address=True)

    def test_shared_control_network_name_override_is_preserved(self):
        with patch.dict(os.environ, {"MINECRAFT_CONTROL_NETWORK": "diagnostic-shared-control"}):
            result = compose(single_file=True)
        self.assertEqual(result.returncode, 0)
        candidate = json.loads(result.stdout)
        self.assertIn("minecraft-control", candidate["services"]["minecraft"]["networks"])
        self.assertEqual(candidate["networks"]["minecraft-control"]["name"],
                         "diagnostic-shared-control")
        self.assertIs(candidate["networks"]["minecraft-control"]["external"], True)
        validate(candidate, allow_test_address=True)

    def test_missing_or_empty_public_ip_blocks_rendering(self):
        for value in (None, ""):
            with self.subTest(value=value):
                self.assertNotEqual(compose(public_ip=value).returncode, 0)

    def test_bad_public_endpoints_are_rejected(self):
        for value in ("", " :19133", "192.0.2.10 :19133", "localhost:19133",
                      "127.0.0.1:19133", "10.13.13.3:19133", "[::1]:19133",
                      "224.0.0.1:19133", "0.0.0.0:19133", "8.8.8.8:19132"):
            with self.subTest(endpoint=value):
                candidate = copy.deepcopy(self.candidate)
                candidate["services"]["minecraft"]["environment"]["CFG_BEDROCK_WEBRTC_ADDRESS"] = value
                with self.assertRaises(ValueError):
                    validate(candidate)

    def test_rcon_publish_is_rejected(self):
        candidate = copy.deepcopy(self.candidate)
        candidate["services"]["minecraft"]["ports"].append(
            {"target": 25575, "published": "25575", "protocol": "tcp", "host_ip": "127.0.0.1"})
        with self.assertRaises(ValueError):
            validate(candidate, allow_test_address=True)

    def test_auth_and_version_drift_rejected(self):
        for key, value in (("VERSION", "26.3"), ("PAPER_BUILD", "latest"),
                           ("ONLINE_MODE", "FALSE"), ("ENABLE_WHITELIST", "FALSE")):
            with self.subTest(key=key):
                candidate = copy.deepcopy(self.candidate)
                candidate["services"]["minecraft"]["environment"][key] = value
                with self.assertRaises(ValueError):
                    validate(candidate, allow_test_address=True)

    def test_plugin_dependency_drift_rejected(self):
        for key in ("PLUGINS", "MODRINTH_PROJECTS"):
            with self.subTest(key=key):
                candidate = copy.deepcopy(self.candidate)
                candidate["services"]["minecraft"]["environment"][key] = self.base["services"]["minecraft"]["environment"][key]
                with self.assertRaises(ValueError):
                    validate(candidate, allow_test_address=True)

    def test_patch_has_only_seven_transport_fields(self):
        patch = json.loads((ROOT / "nethernet-patches/bedrock-transport.json").read_text())
        self.assertEqual(patch["file"], "/data/plugins/Geyser-Spigot/config.yml")
        paths = []
        for op in patch["ops"]:
            operation, settings = next(iter(op.items()))
            self.assertIn(operation, ("$set", "$put"))
            paths.append(settings["path"] + ("." + settings["key"] if operation == "$put" else ""))
        self.assertCountEqual(paths, ["$.bedrock.transport", "$.bedrock.port",
            "$.bedrock.webrtc-port", "$.bedrock.clone-remote-port", "$.bedrock.signaling.mode",
            "$.bedrock.signaling.advertise-addresses", "$.bedrock.signaling.stun-discovery"])

    def test_existing_paper_patches_are_reused(self):
        dockerfile = (ROOT / "Dockerfile").read_text()
        self.assertIn("COPY paper-patches /usapo-paper-patches", dockerfile)
        self.assertIn("COPY paper-patches /usapo-nethernet-patches", dockerfile)
        self.assertIn("COPY nethernet-patches /usapo-nethernet-patches", dockerfile)

    def test_legacy_signaling_keys_are_upserted(self):
        # Existing Geyser 1245 configuration can omit both newer signaling keys.
        # The parent map and mode exist, so preserve that map and insert its keys.
        patch = json.loads((ROOT / "nethernet-patches/bedrock-transport.json").read_text())
        operations = {}
        for op in patch["ops"]:
            operation, settings = next(iter(op.items()))
            path = settings["path"] + ("." + settings["key"] if operation == "$put" else "")
            operations[path] = operation
        for key in ("advertise-addresses", "stun-discovery"):
            with self.subTest(key=key):
                self.assertEqual(operations["$.bedrock.signaling." + key], "$put")

    def test_relay_patch_changes_only_the_two_port_lists(self):
        lines = (ROOT / "deployment/minecraft-relay-ports.patch").read_text().splitlines()
        removed = [line[1:].strip() for line in lines if line.startswith("-") and not line.startswith("---")]
        added = [line[1:].strip() for line in lines if line.startswith("+") and not line.startswith("+++")]
        self.assertEqual(removed, ['UDP_PORTS: "19132"', 'TCP_PORTS: "25565"'])
        self.assertEqual(added, ['UDP_PORTS: "19132,19133"', 'TCP_PORTS: "25565,19132"'])
        self.assertEqual(sum(line.startswith("diff --git ") for line in lines), 1)


@unittest.skipUnless(os.environ.get("NETHERNET_TEST_IMAGE"), "Set NETHERNET_TEST_IMAGE for Docker patch integration")
class PatchRuntimeTests(unittest.TestCase):
    def test_real_helper_migrates_missing_legacy_signaling_keys(self):
        # Key presence is the pseudo-oracle observed in the stopped 1245 config:
        # signaling/mode and webrtc-port exist, but both new signaling keys do not.
        # Values and preservation sentinels below are synthetic, not private data.
        for missing in (("advertise-addresses", "stun-discovery"),
                        ("advertise-addresses",), ("stun-discovery",)):
            with self.subTest(missing=missing):
                signaling = {"mode": "builtin", "advertise-addresses": ["198.51.100.5:19133"],
                             "stun-discovery": True, "preserve-marker": "unchanged"}
                for key in missing:
                    signaling.pop(key)
                fixture = {"bedrock": {"address": "0.0.0.0", "port": 19132,
                    "transport": "raknet", "webrtc-port": 19132, "clone-remote-port": False,
                    "signaling": signaling}, "java": {"auth-type": "online"},
                    "advanced": {"bedrock": {"validate-bedrock-login": True,
                        "use-haproxy-protocol": False}, "java": {"use-haproxy-protocol": False}},
                    "preserve-top-level": "unchanged"}
                with tempfile.TemporaryDirectory(prefix="nethernet-legacy-patch-") as directory:
                    path = Path(directory) / "plugins/Geyser-Spigot/config.yml"
                    path.parent.mkdir(parents=True)
                    path.write_text(json.dumps(fixture))
                    args = ["docker", "run", "--rm", "--network=none", "--entrypoint", "mc-image-helper",
                        "-v", f"{directory}:/data", "-v", f"{ROOT / 'nethernet-patches'}:/patches:ro",
                        "-e", "CFG_BEDROCK_WEBRTC_ADDRESS=192.0.2.10:19133", os.environ["NETHERNET_TEST_IMAGE"]]
                    for attempt in range(2):
                        result = subprocess.run(args + ["patch", "/patches"], capture_output=True, text=True)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        current = hashlib.sha256(path.read_bytes()).hexdigest()
                        if attempt == 0:
                            initial = current
                        else:
                            self.assertEqual(initial, current)
                    expected = {"$.bedrock.transport": "both", "$.bedrock.port": "19132",
                        "$.bedrock.webrtc-port": "19133", "$.bedrock.clone-remote-port": "false",
                        "$.bedrock.signaling.mode": "builtin",
                        "$.bedrock.signaling.advertise-addresses[0]": "192.0.2.10:19133",
                        "$.bedrock.signaling.advertise-addresses.length()": "1",
                        "$.bedrock.signaling.stun-discovery": "false", "$.bedrock.address": "0.0.0.0",
                        "$.java.auth-type": "online", "$.advanced.bedrock.validate-bedrock-login": "true",
                        "$.advanced.bedrock.use-haproxy-protocol": "false",
                        "$.advanced.java.use-haproxy-protocol": "false",
                        "$.bedrock.signaling.preserve-marker": "unchanged", "$.preserve-top-level": "unchanged"}
                    for query, value in expected.items():
                        result = subprocess.run(args + ["yaml-path", "--file", "/data/plugins/Geyser-Spigot/config.yml", query],
                                                check=True, capture_output=True, text=True)
                        self.assertEqual(result.stdout.strip(), value, query)

    def test_real_helper_preserves_settings_and_interpolates_idempotently(self):
        fixture = {"bedrock": {"address": "0.0.0.0", "port": 19132,
            "transport": "raknet", "clone-remote-port": False,
            "signaling": {"mode": "builtin", "advertise-addresses": [],
                "stun-discovery": True, "preserve-marker": "unchanged"}},
            "java": {"auth-type": "online"},
            "advanced": {"bedrock": {"validate-bedrock-login": True,
                                      "use-haproxy-protocol": False},
                         "java": {"use-haproxy-protocol": False}},
            "preserve-top-level": "unchanged"}
        with tempfile.TemporaryDirectory(prefix="nethernet-patch-") as directory:
            path = Path(directory) / "plugins/Geyser-Spigot/config.yml"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(fixture))
            args = ["docker", "run", "--rm", "--network=none", "--entrypoint", "mc-image-helper",
                "-v", f"{directory}:/data", "-v", f"{ROOT / 'nethernet-patches'}:/patches:ro",
                "-e", "CFG_BEDROCK_WEBRTC_ADDRESS=192.0.2.10:19133", os.environ["NETHERNET_TEST_IMAGE"]]
            for attempt in range(2):
                subprocess.run(args + ["patch", "/patches"], check=True, capture_output=True)
                current = hashlib.sha256(path.read_bytes()).hexdigest()
                if attempt == 0:
                    initial = current
                else:
                    self.assertEqual(initial, current)
            expected = {"$.bedrock.transport": "both", "$.bedrock.port": "19132",
                "$.bedrock.webrtc-port": "19133", "$.bedrock.signaling.mode": "builtin",
                "$.bedrock.signaling.advertise-addresses[0]": "192.0.2.10:19133",
                "$.bedrock.signaling.advertise-addresses.length()": "1",
                "$.bedrock.signaling.stun-discovery": "false", "$.bedrock.address": "0.0.0.0",
                "$.java.auth-type": "online", "$.advanced.bedrock.validate-bedrock-login": "true",
                "$.advanced.bedrock.use-haproxy-protocol": "false",
                "$.advanced.java.use-haproxy-protocol": "false",
                "$.bedrock.signaling.preserve-marker": "unchanged", "$.preserve-top-level": "unchanged"}
            for query, value in expected.items():
                result = subprocess.run(args + ["yaml-path", "--file", "/data/plugins/Geyser-Spigot/config.yml", query],
                                        check=True, capture_output=True, text=True)
                self.assertEqual(result.stdout.strip(), value, query)


if __name__ == "__main__":
    unittest.main()
