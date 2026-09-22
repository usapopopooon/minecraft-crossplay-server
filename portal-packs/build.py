#!/usr/bin/env python3
"""Compile checked-in pixel artwork into deterministic, isolated display packs."""

import argparse
import binascii
import copy
import hashlib
import io
import itertools
import json
from pathlib import Path
import struct
import zipfile
import zlib


ROOT = Path(__file__).resolve().parent
VERSION = 1
JAVA_UUID = "c73b7eef-646d-4554-92b7-deedd3335abf"
BEDROCK_HEADER_UUID = "071961f2-0540-488f-92b3-8371a5b3000a"
BEDROCK_MODULE_UUID = "f75d9df3-0bc1-4514-999b-e2106fc51995"
SOURCE_SHA256 = "ba51e2a6d818022ef8d45d5f8baa6235b0b0dd637919491985f2cc37909c985a"
VANILLA_SHA256 = "37eb32db8bb407ce1fb24f43bf3bb5451e5e8ce4698591c37e6b3cc7bb59ce42"
FLAGS = ("attached", "disarmed", "east", "north", "powered", "south", "west")
SIZE = 64
FRAMES = 32
FRAME_TICKS = 2
TEXTURE = "usapo_cyan_portal"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def carrier_state(powered):
    values = {flag: "true" for flag in FLAGS}
    values["powered"] = str(powered).lower()
    return ",".join(f"{flag}={values[flag]}" for flag in FLAGS)


def paeth(a, b, c):
    p = a + b - c
    distances = (abs(p - a), abs(p - b), abs(p - c))
    return (a, b, c)[distances.index(min(distances))]


def read_png(data):
    """Read 8-bit RGB/RGBA noninterlaced sources, checking every PNG CRC.

    Deliberately not a general image editor: unsupported input formats fail.
    """
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("not a PNG")
    offset = len(PNG_SIGNATURE)
    compressed = bytearray()
    dimensions = None
    ended = False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("truncated PNG chunk")
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError("truncated PNG data")
        crc = struct.unpack_from(">I", data, end - 4)[0]
        if binascii.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise ValueError("PNG checksum mismatch")
        if kind == b"IHDR":
            if dimensions is not None or len(payload) != 13:
                raise ValueError("invalid PNG header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if (depth, compression, filtering, interlace) != (8, 0, 0, 0) or color not in (2, 6):
                raise ValueError("source must be noninterlaced 8-bit RGB or RGBA")
            channels = 3 if color == 2 else 4
            if not (1 <= width <= 4096 and 1 <= height <= 4096):
                raise ValueError("PNG dimensions out of bounds")
            dimensions = (width, height)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            if payload or end != len(data):
                raise ValueError("invalid PNG end")
            ended = True
        elif not kind[0] & 0x20:
            raise ValueError("unsupported PNG critical chunk")
        offset = end
    if dimensions is None or not ended:
        raise ValueError("incomplete PNG")
    width, height = dimensions
    stride = width * channels
    raw = zlib.decompress(compressed)
    if len(raw) != (stride + 1) * height:
        raise ValueError("PNG pixel length mismatch")
    pixels = bytearray()
    previous = bytearray(stride)
    for row_index in range(height):
        start = row_index * (stride + 1)
        filter_type = raw[start]
        row = bytearray(raw[start + 1:start + 1 + stride])
        if filter_type > 4:
            raise ValueError("unsupported PNG filter")
        for x in range(stride):
            left = row[x - channels] if x >= channels else 0
            above = previous[x]
            corner = previous[x - channels] if x >= channels else 0
            predictor = (0, left, above, (left + above) // 2, paeth(left, above, corner))[filter_type]
            row[x] = (row[x] + predictor) & 255
        pixels.extend(row)
        previous = row
    return width, height, channels, bytes(pixels)


def read_rgb_png(data):
    width, height, channels, pixels = read_png(data)
    if channels != 3:
        raise ValueError("expected RGB PNG")
    return width, height, pixels


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)


def write_png(width, height, channels, pixels):
    """Use explicit stored DEFLATE blocks, independent of zlib encoder version."""
    if channels not in (3, 4) or len(pixels) != width * height * channels:
        raise ValueError("wrong pixel count")
    stride = width * channels
    raw = b"".join(b"\0" + pixels[y * stride:(y + 1) * stride] for y in range(height))
    compressed = bytearray(b"\x78\x01")
    for start in range(0, len(raw), 65535):
        block = raw[start:start + 65535]
        compressed.append(int(start + len(block) == len(raw)))
        compressed.extend(struct.pack("<HH", len(block), len(block) ^ 0xFFFF))
        compressed.extend(block)
    compressed.extend(struct.pack(">I", zlib.adler32(raw) & 0xFFFFFFFF))
    header = struct.pack(">IIBBBBB", width, height, 8, 2 if channels == 3 else 6, 0, 0, 0)
    return PNG_SIGNATURE + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", compressed) + png_chunk(b"IEND", b"")


def write_rgb_png(width, height, pixels):
    return write_png(width, height, 3, pixels)


def compile_animation(source):
    width, height, channels, pixels = read_png(source)
    if width != height:
        raise ValueError("source tile must be square")
    if channels != 4 or not all(0 < alpha < 255 for alpha in pixels[3::4]):
        raise ValueError("source must have fractional alpha throughout the membrane")
    # Pixel-center nearest-neighbor sampling only; no recoloring or filtering.
    tile = bytearray()
    for y in range(SIZE):
        sy = min(height - 1, (2 * y + 1) * height // (2 * SIZE))
        for x in range(SIZE):
            sx = min(width - 1, (2 * x + 1) * width // (2 * SIZE))
            offset = (sy * width + sx) * channels
            tile.extend(pixels[offset:offset + channels])
    frames = bytearray()
    for frame in range(FRAMES):
        # Exactly one complete wrapped scroll; the last-to-first step is equal.
        offset = frame * SIZE // FRAMES
        for y in range(SIZE):
            row = (y + offset) % SIZE
            frames.extend(tile[row * SIZE * channels:(row + 1) * SIZE * channels])
    return write_png(SIZE, SIZE * FRAMES, channels, frames)


def blockstates(vanilla):
    variants = {}
    for values in itertools.product(("false", "true"), repeat=len(FLAGS)):
        state = dict(zip(FLAGS, values))
        key = ",".join(f"{flag}={state[flag]}" for flag in FLAGS)
        vanilla_key = ",".join(f"{flag}={state[flag]}" for flag in FLAGS if flag not in ("disarmed", "powered"))
        variants[key] = copy.deepcopy(vanilla["variants"][vanilla_key])
    variants[carrier_state(False)] = {"model": "usapo:block/cyan_portal_x"}
    variants[carrier_state(True)] = {"model": "usapo:block/cyan_portal_z"}
    return {"variants": variants}


def java_model(axis):
    along_x = axis == "x"
    faces = ("north", "south") if along_x else ("west", "east")
    return {
        "ambientocclusion": False,
        "textures": {"particle": "usapo:block/cyan_portal", "portal": "usapo:block/cyan_portal"},
        "elements": [{
            "from": [0, 0, 7.875] if along_x else [7.875, 0, 0],
            "to": [16, 16, 8.125] if along_x else [8.125, 16, 16],
            "shade": False,
            "light_emission": 15,
            "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#portal"} for face in faces},
        }],
    }


def bedrock_geometry(axis):
    along_x = axis == "x"
    faces = ("north", "south") if along_x else ("west", "east")
    return {
        "description": {"identifier": f"geometry.usapo.cyan_portal_{axis}", "texture_width": SIZE, "texture_height": SIZE, "visible_bounds_width": 2, "visible_bounds_height": 2, "visible_bounds_offset": [0, 0.5, 0]},
        "bones": [{"name": "surface", "pivot": [0, 0, 0], "cubes": [{
            "origin": [-8, 0, -0.125] if along_x else [-0.125, 0, -8],
            "size": [16, 16, 0.25] if along_x else [0.25, 16, 16],
            "uv": {face: {"uv": [0, 0], "uv_size": [SIZE, SIZE]} for face in faces},
        }]}],
    }


def geyser_components(axis):
    return {
        "display_name": "Cyan Portal",
        "geometry": f"geometry.usapo.cyan_portal_{axis}",
        "selection_box": {"origin": [0, 0, 0], "size": [0, 0, 0]},
        "collision_box": {"origin": [0, 0, 0], "size": [0, 0, 0]},
        "light_emission": 15,
        "light_dampening": 0,
        "place_air": True,
        "material_instances": {"*": {"texture": TEXTURE, "render_method": "blend", "face_dimming": False, "ambient_occlusion": 0}},
    }


def geyser_mapping():
    # Overrides do not inherit base components in Geyser's v1 reader.
    return {"format_version": 1, "blocks": {"minecraft:tripwire": {
        "name": "usapo_cyan_portal", "included_in_creative_inventory": False,
        "only_override_states": True, **geyser_components("x"),
        "state_overrides": {carrier_state(False): geyser_components("x"), carrier_state(True): geyser_components("z")},
    }}}


def archive(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as output:
        for path, contents in sorted(files.items()):
            if path.startswith("/") or ".." in Path(path).parts:
                raise ValueError("unsafe archive path")
            entry = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_STORED
            output.writestr(entry, contents)
    return stream.getvalue()


def build_outputs():
    source = (ROOT / "source/cyan-tile-translucent-v1.png").read_bytes()
    vanilla_raw = (ROOT / "source/vanilla-tripwire-26.2.json").read_bytes()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("original artwork changed: publish a new asset version")
    # apply_patch adds a terminal newline to the otherwise exact client JSON.
    if hashlib.sha256(vanilla_raw.rstrip(b"\n")).hexdigest() != VANILLA_SHA256:
        raise ValueError("vanilla tripwire reference changed")
    animation = compile_animation(source)
    vanilla = json.loads(vanilla_raw)
    java = {
        "pack.mcmeta": json_bytes({"pack": {"description": "Usapo Cyan Portals v1", "min_format": [88, 0], "max_format": [97, 1]}}),
        "assets/minecraft/blockstates/tripwire.json": json_bytes(blockstates(vanilla)),
        "assets/usapo/textures/block/cyan_portal.png": animation,
        "assets/usapo/textures/block/cyan_portal.png.mcmeta": json_bytes({"animation": {"frametime": FRAME_TICKS, "frames": list(range(FRAMES)), "interpolate": False}}),
    }
    for axis in ("x", "z"):
        java[f"assets/usapo/models/block/cyan_portal_{axis}.json"] = json_bytes(java_model(axis))
    bedrock = {
        "manifest.json": json_bytes({"format_version": 2, "header": {"name": "Usapo Cyan Portals", "description": "Animated client-only cyan portal surfaces", "uuid": BEDROCK_HEADER_UUID, "version": [1, 0, 0], "min_engine_version": [1, 26, 0]}, "modules": [{"type": "resources", "uuid": BEDROCK_MODULE_UUID, "version": [1, 0, 0]}]}),
        "models/blocks/cyan_portal.geo.json": json_bytes({"format_version": "1.12.0", "minecraft:geometry": [bedrock_geometry(axis) for axis in ("x", "z")]}),
        "textures/terrain_texture.json": json_bytes({"resource_pack_name": "Usapo Cyan Portals", "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4, "texture_data": {TEXTURE: {"textures": "textures/blocks/usapo_cyan_portal"}}}),
        "textures/flipbook_textures.json": json_bytes([{"flipbook_texture": "textures/blocks/usapo_cyan_portal", "atlas_tile": TEXTURE, "ticks_per_frame": FRAME_TICKS, "frames": list(range(FRAMES)), "blend_frames": False}]),
        "textures/blocks/usapo_cyan_portal.png": animation,
    }
    outputs = {
        "cyan-portal-java-v1.zip": archive(java),
        "cyan-portal-bedrock-v1.mcpack": archive(bedrock),
        "cyan-portal-v1.json": json_bytes(geyser_mapping()),
    }
    checksums = {
        "version": VERSION, "java_uuid": JAVA_UUID,
        "bedrock_header_uuid": BEDROCK_HEADER_UUID, "bedrock_module_uuid": BEDROCK_MODULE_UUID,
        "files": {name: {"sha1": hashlib.sha1(data).hexdigest(), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)} for name, data in outputs.items()},
    }
    outputs["checksums.json"] = json_bytes(checksums)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare every rebuilt artifact to committed dist without writing")
    args = parser.parse_args()
    outputs = build_outputs()
    dist = ROOT / "dist"
    if args.check:
        expected = set(outputs)
        actual = {entry.name for entry in dist.iterdir() if entry.is_file()} if dist.is_dir() else set()
        if actual != expected:
            raise SystemExit(f"distribution file set differs: expected {sorted(expected)}, found {sorted(actual)}")
        for name, data in outputs.items():
            if (dist / name).read_bytes() != data:
                raise SystemExit(f"distribution differs: {name}; do not overwrite a published version")
        print("PASS: all four distribution artifacts reproduce byte-for-byte")
    else:
        dist.mkdir(exist_ok=True)
        for name, data in outputs.items():
            (dist / name).write_bytes(data)
        print(json.dumps(json.loads(outputs["checksums.json"]), indent=2))


if __name__ == "__main__":
    main()
