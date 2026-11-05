import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from validate_nethernet import validate


ROOT = Path(__file__).resolve().parents[1]


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

    def test_relay_patch_changes_only_the_two_port_lists(self):
        lines = (ROOT / "deployment/minecraft-relay-ports.patch").read_text().splitlines()
        removed = [line[1:].strip() for line in lines if line.startswith("-") and not line.startswith("---")]
        added = [line[1:].strip() for line in lines if line.startswith("+") and not line.startswith("+++")]
        self.assertEqual(removed, ['UDP_PORTS: "19132"', 'TCP_PORTS: "25565"'])
        self.assertEqual(added, ['UDP_PORTS: "19132,19133"', 'TCP_PORTS: "25565,19132"'])
        self.assertEqual(sum(line.startswith("diff --git ") for line in lines), 1)


@unittest.skipUnless(os.environ.get("NETHERNET_TEST_IMAGE"), "Set NETHERNET_TEST_IMAGE for Docker patch integration")
class PatchRuntimeTests(unittest.TestCase):
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
