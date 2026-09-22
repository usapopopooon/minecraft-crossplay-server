import copy
import hashlib
import io
import itertools
import json
import struct
import unittest
import uuid
import zipfile
import zlib

import build


class PackBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs = build.build_outputs()
        cls.java = zipfile.ZipFile(io.BytesIO(cls.outputs["cyan-portal-java-v1.zip"]))
        cls.bedrock = zipfile.ZipFile(io.BytesIO(cls.outputs["cyan-portal-bedrock-v1.mcpack"]))
        cls.vanilla = json.loads((build.ROOT / "source/vanilla-tripwire-26.2.json").read_bytes())

    def test_all_128_states_exist_and_only_two_change_vanilla_models(self):
        variants = json.loads(self.java.read("assets/minecraft/blockstates/tripwire.json"))["variants"]
        expected = set()
        changed = set()
        for values in itertools.product(("false", "true"), repeat=7):
            state = dict(zip(build.FLAGS, values))
            key = ",".join(f"{flag}={state[flag]}" for flag in build.FLAGS)
            expected.add(key)
            vanilla_key = ",".join(f"{flag}={state[flag]}" for flag in build.FLAGS if flag not in ("disarmed", "powered"))
            if variants[key] != self.vanilla["variants"][vanilla_key]:
                changed.add(key)
        self.assertEqual(expected, set(variants))
        self.assertEqual({build.carrier_state(False), build.carrier_state(True)}, changed)
        self.assertEqual("usapo:block/cyan_portal_x", variants[build.carrier_state(False)]["model"])
        self.assertEqual("usapo:block/cyan_portal_z", variants[build.carrier_state(True)]["model"])

    def test_vanilla_reference_is_not_mutated(self):
        original = copy.deepcopy(self.vanilla)
        build.blockstates(self.vanilla)
        self.assertEqual(original, self.vanilla)

    def test_mapping_targets_only_carriers_and_does_not_override_item(self):
        mapping = json.loads(self.outputs["cyan-portal-v1.json"])
        self.assertEqual(1, mapping["format_version"])
        self.assertEqual({"minecraft:tripwire"}, set(mapping["blocks"]))
        entry = mapping["blocks"]["minecraft:tripwire"]
        self.assertTrue(entry["only_override_states"])
        self.assertFalse(entry["included_in_creative_inventory"])
        self.assertEqual({build.carrier_state(False), build.carrier_state(True)}, set(entry["state_overrides"]))

    def test_every_mapping_permutation_explicitly_has_zero_collision_and_selection(self):
        entry = build.geyser_mapping()["blocks"]["minecraft:tripwire"]
        for component in [entry, *entry["state_overrides"].values()]:
            for box in ("collision_box", "selection_box"):
                self.assertEqual({"origin": [0, 0, 0], "size": [0, 0, 0]}, component[box])
            self.assertEqual(0, component["light_dampening"])
            self.assertTrue(component["place_air"])
            material = component["material_instances"]["*"]
            self.assertEqual(build.TEXTURE, material["texture"])
            self.assertEqual("blend", material["render_method"])
            self.assertFalse(material["face_dimming"])
            self.assertEqual(0, material["ambient_occlusion"])

    def test_java_and_bedrock_plane_axes_and_visible_faces_match(self):
        geometries = json.loads(self.bedrock.read("models/blocks/cyan_portal.geo.json"))["minecraft:geometry"]
        mapping = build.geyser_mapping()["blocks"]["minecraft:tripwire"]["state_overrides"]
        for axis, constant_axis, faces, powered in [("x", 2, {"north", "south"}, False), ("z", 0, {"west", "east"}, True)]:
            model = json.loads(self.java.read(f"assets/usapo/models/block/cyan_portal_{axis}.json"))
            element = model["elements"][0]
            self.assertEqual(faces, set(element["faces"]))
            self.assertEqual(0.25, element["to"][constant_axis] - element["from"][constant_axis])
            self.assertEqual(8, (element["to"][constant_axis] + element["from"][constant_axis]) / 2)
            self.assertFalse(element["shade"])
            self.assertEqual(15, element["light_emission"])
            self.assertTrue(all("cullface" not in face for face in element["faces"].values()))
            identifier = mapping[build.carrier_state(powered)]["geometry"]
            geometry = next(item for item in geometries if item["description"]["identifier"] == identifier)
            cube = geometry["bones"][0]["cubes"][0]
            self.assertEqual(faces, set(cube["uv"]))
            self.assertEqual(0.25, cube["size"][constant_axis])
            self.assertEqual(0, cube["origin"][constant_axis] + cube["size"][constant_axis] / 2)
            self.assertEqual(16, cube["size"][1])
            self.assertEqual(0, cube["origin"][1])

    def test_java_pack_declares_verified_major_minor_range(self):
        metadata = json.loads(self.java.read("pack.mcmeta"))["pack"]
        self.assertEqual([88, 0], metadata["min_format"])
        self.assertEqual([97, 1], metadata["max_format"])
        self.assertNotIn("pack_format", metadata)

    def test_bedrock_manifest_stable_distinct_valid_ids_and_resources_only(self):
        manifest = json.loads(self.bedrock.read("manifest.json"))
        self.assertEqual(2, manifest["format_version"])
        self.assertEqual([1, 26, 0], manifest["header"]["min_engine_version"])
        self.assertEqual([1, 0, 0], manifest["header"]["version"])
        self.assertEqual(["resources"], [item["type"] for item in manifest["modules"]])
        ids = [build.JAVA_UUID, manifest["header"]["uuid"], manifest["modules"][0]["uuid"]]
        self.assertEqual(3, len(set(ids)))
        for value in ids:
            self.assertEqual(value, str(uuid.UUID(value)))

    def test_animation_is_identical_in_both_editions_and_wraps_without_a_jump(self):
        java_png = self.java.read("assets/usapo/textures/block/cyan_portal.png")
        self.assertEqual(java_png, self.bedrock.read("textures/blocks/usapo_cyan_portal.png"))
        width, height, channels, pixels = build.read_png(java_png)
        self.assertEqual((64, 2048), (width, height))
        self.assertEqual(4, channels)
        self.assertTrue(all(0 < alpha < 255 for alpha in pixels[3::4]))
        frames = [pixels[index * 64 * 64 * 4:(index + 1) * 64 * 64 * 4] for index in range(32)]
        self.assertEqual(32, len(set(frames)))
        for index, frame in enumerate(frames):
            expected = frame[2 * 64 * 4:] + frame[:2 * 64 * 4]
            self.assertEqual(expected, frames[(index + 1) % 32])
        java_animation = json.loads(self.java.read("assets/usapo/textures/block/cyan_portal.png.mcmeta"))["animation"]
        bedrock_animation = json.loads(self.bedrock.read("textures/flipbook_textures.json"))[0]
        self.assertEqual(list(range(32)), java_animation["frames"])
        self.assertEqual(java_animation["frames"], bedrock_animation["frames"])
        self.assertEqual(2, java_animation["frametime"])
        self.assertEqual(2, bedrock_animation["ticks_per_frame"])
        self.assertFalse(java_animation["interpolate"])
        self.assertFalse(bedrock_animation["blend_frames"])

    def test_compilation_preserves_source_rgba_samples_exactly(self):
        width, height, channels, source = build.read_png((build.ROOT / "source/cyan-tile-translucent-v1.png").read_bytes())
        _, _, _, animation = build.read_png(self.java.read("assets/usapo/textures/block/cyan_portal.png"))
        self.assertEqual(4, channels)
        for y in range(64):
            sy = (2 * y + 1) * height // 128
            for x in range(64):
                sx = (2 * x + 1) * width // 128
                source_offset = (sy * width + sx) * 4
                target_offset = (y * 64 + x) * 4
                self.assertEqual(source[source_offset:source_offset + 4], animation[target_offset:target_offset + 4])
        alpha = animation[3:64 * 64 * 4:4]
        self.assertGreater(sum(alpha) / len(alpha), 0.65 * 255)
        self.assertLess(sum(alpha) / len(alpha), 0.75 * 255)

    def test_all_texture_references_resolve_inside_corresponding_pack(self):
        terrain = json.loads(self.bedrock.read("textures/terrain_texture.json"))
        path = terrain["texture_data"][build.TEXTURE]["textures"]
        self.assertIn(path + ".png", self.bedrock.namelist())
        flipbook = json.loads(self.bedrock.read("textures/flipbook_textures.json"))[0]
        self.assertEqual(path, flipbook["flipbook_texture"])
        self.assertEqual(build.TEXTURE, flipbook["atlas_tile"])
        for axis in ("x", "z"):
            model = json.loads(self.java.read(f"assets/usapo/models/block/cyan_portal_{axis}.json"))
            for texture in model["textures"].values():
                namespace, path = texture.split(":")
                self.assertIn(f"assets/{namespace}/textures/{path}.png", self.java.namelist())

    def test_pack_file_allowlists_do_not_change_nethernor_gameplay_assets(self):
        self.assertEqual({"pack.mcmeta", "assets/minecraft/blockstates/tripwire.json", "assets/usapo/models/block/cyan_portal_x.json", "assets/usapo/models/block/cyan_portal_z.json", "assets/usapo/textures/block/cyan_portal.png", "assets/usapo/textures/block/cyan_portal.png.mcmeta"}, set(self.java.namelist()))
        self.assertEqual({"manifest.json", "models/blocks/cyan_portal.geo.json", "textures/terrain_texture.json", "textures/flipbook_textures.json", "textures/blocks/usapo_cyan_portal.png"}, set(self.bedrock.namelist()))

    def test_archive_metadata_is_fixed_and_json_has_no_duplicate_keys(self):
        def unique(pairs):
            result = {}
            for key, value in pairs:
                self.assertNotIn(key, result)
                result[key] = value
            return result
        for archive in (self.java, self.bedrock):
            self.assertIsNone(archive.testzip())
            self.assertEqual(sorted(archive.namelist()), archive.namelist())
            for entry in archive.infolist():
                self.assertEqual((2026, 1, 1, 0, 0, 0), entry.date_time)
                self.assertEqual(zipfile.ZIP_STORED, entry.compress_type)
                self.assertEqual(0o100644, entry.external_attr >> 16)
                if entry.filename.endswith((".json", ".mcmeta")):
                    json.loads(archive.read(entry), object_pairs_hook=unique)

    def test_manifest_hashes_match_actual_artifacts_and_committed_distribution(self):
        manifest = json.loads(self.outputs["checksums.json"])
        for name, checks in manifest["files"].items():
            contents = self.outputs[name]
            self.assertEqual(hashlib.sha1(contents).hexdigest(), checks["sha1"])
            self.assertEqual(hashlib.sha256(contents).hexdigest(), checks["sha256"])
            self.assertEqual(len(contents), checks["bytes"])
        for name, contents in self.outputs.items():
            self.assertEqual(contents, (build.ROOT / "dist" / name).read_bytes())

    def test_bridge_delivery_contract_matches_manifest(self):
        path = build.ROOT.parent / "event-bridge/src/main/resources/cyan-portal-pack.properties"
        properties = {}
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                properties[key.strip()] = value.strip()
        manifest = json.loads(self.outputs["checksums.json"])
        self.assertEqual(manifest["java_uuid"], properties["uuid"])
        self.assertEqual(manifest["files"]["cyan-portal-java-v1.zip"]["sha1"], properties["sha1"])
        self.assertEqual(manifest["files"]["cyan-portal-bedrock-v1.mcpack"]["sha256"], properties["bedrock-sha256"])
        self.assertEqual(manifest["files"]["cyan-portal-v1.json"]["sha256"], properties["mapping-sha256"])
        self.assertEqual("https://raw.githubusercontent.com/usapopopooon/minecraft-crossplay-server/main/portal-packs/dist/cyan-portal-java-v1.zip", properties["url"])


class PngCompilerTest(unittest.TestCase):
    def test_rgba_png_preserves_fractional_alpha(self):
        pixels = bytes([10, 20, 30, 139, 40, 50, 60, 222])
        self.assertEqual((2, 1, 4, pixels), build.read_png(build.write_png(2, 1, 4, pixels)))

    def test_stored_png_roundtrips_across_multiple_deflate_blocks(self):
        rgb = bytes(range(256)) * 768
        self.assertEqual((256, 256, rgb), build.read_rgb_png(build.write_rgb_png(256, 256, rgb)))

    def test_all_five_source_png_filters_decode_the_same_pixels(self):
        width, height = 2, 2
        rgb = bytes([10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120])
        for kind in range(5):
            raw = bytearray()
            previous = bytes(6)
            for index in range(height):
                row = rgb[index * 6:(index + 1) * 6]
                raw.append(kind)
                for x, value in enumerate(row):
                    left = row[x - 3] if x >= 3 else 0
                    above = previous[x]
                    corner = previous[x - 3] if x >= 3 else 0
                    prediction = (0, left, above, (left + above) // 2, build.paeth(left, above, corner))[kind]
                    raw.append((value - prediction) & 255)
                previous = row
            header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
            png = build.PNG_SIGNATURE + build.png_chunk(b"IHDR", header) + build.png_chunk(b"IDAT", zlib.compress(raw)) + build.png_chunk(b"IEND", b"")
            self.assertEqual((width, height, rgb), build.read_rgb_png(png))

    def test_bad_png_crc_rejected(self):
        png = bytearray(build.write_rgb_png(1, 1, b"abc"))
        png[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            build.read_rgb_png(png)

    def test_wrong_pixel_count_rejected(self):
        with self.assertRaisesRegex(ValueError, "pixel count"):
            build.write_rgb_png(2, 2, b"abc")

    def test_unsafe_archive_path_rejected(self):
        with self.assertRaisesRegex(ValueError, "unsafe"):
            build.archive({"../outside": b""})


if __name__ == "__main__":
    unittest.main()
