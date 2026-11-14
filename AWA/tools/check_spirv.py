#!/usr/bin/env python3
"""Checks the committed SPIR-V against the descriptor layout the native keyer binds.

`chroma_vulkan.cpp` binds five descriptors in set 0: a 48 byte uniform block on binding 0 and four
storage buffers on 1..4. A shader edit that changes a binding, drops the entry point or targets a
newer SPIR-V version would only fail on the device, at pipeline creation time; this catches it on
the build machine instead.

Run: python AWA/tools/check_spirv.py
"""

import struct
import sys
from pathlib import Path

MAGIC = 0x07230203
OP_NAME, OP_ENTRY_POINT, OP_EXEC_MODE, OP_TYPE_POINTER, OP_TYPE_STRUCT, OP_VARIABLE, OP_DECORATE = (
    5, 15, 16, 32, 30, 59, 71,
)
DECOR_BLOCK, DECOR_BUFFER_BLOCK, DECOR_BINDING = 2, 3, 33
STORAGE_UNIFORM = 2
EXEC_MODE_LOCAL_SIZE = 17

EXPECTED = {
    0: ("uniform buffer", DECOR_BLOCK),
    1: ("storage buffer", DECOR_BUFFER_BLOCK),
    2: ("storage buffer", DECOR_BUFFER_BLOCK),
    3: ("storage buffer", DECOR_BUFFER_BLOCK),
    4: ("storage buffer", DECOR_BUFFER_BLOCK),
}


def instructions(words):
    i = 5
    while i < len(words):
        count, opcode = words[i] >> 16, words[i] & 0xFFFF
        yield opcode, words[i + 1:i + count]
        i += count


def check(path):
    words = struct.unpack(f"<{len(path.read_bytes()) // 4}I", path.read_bytes())
    problems = []

    if words[0] != MAGIC:
        return [f"{path.name}: not SPIR-V (magic 0x{words[0]:08x})"]
    major, minor = words[1] >> 16, (words[1] >> 8) & 0xFF
    if (major, minor) != (1, 0):
        problems.append(f"{path.name}: SPIR-V {major}.{minor}, expected 1.0 for Vulkan 1.0 devices")

    decorations = {}       # id -> set of decoration values
    struct_of = {}         # pointer type id -> (storage class, pointee type id)
    struct_types = set()
    variables = {}         # variable id -> (pointer type id, storage class)
    entry_points = []
    local_size = None
    names = {}

    for opcode, operands in instructions(words):
        if opcode == OP_NAME:
            names[operands[0]] = "".join(
                struct.pack("<I", w).decode("utf-8", "ignore") for w in operands[1:]
            ).rstrip("\0")
        elif opcode == OP_ENTRY_POINT:
            model = operands[0]
            name = "".join(
                struct.pack("<I", w).decode("utf-8", "ignore") for w in operands[2:]
            ).split("\0")[0]
            entry_points.append((model, name))
        elif opcode == OP_EXEC_MODE and operands[1] == EXEC_MODE_LOCAL_SIZE:
            local_size = tuple(operands[2:5])
        elif opcode == OP_TYPE_STRUCT:
            struct_types.add(operands[0])
        elif opcode == OP_TYPE_POINTER:
            struct_of[operands[0]] = (operands[1], operands[2])
        elif opcode == OP_VARIABLE:
            variables[operands[1]] = (operands[0], operands[2])
        elif opcode == OP_DECORATE:
            decorations.setdefault(operands[0], set()).add(operands[1])

    if not entry_points:
        problems.append(f"{path.name}: no entry point")
    elif entry_points[0][0] != 5 or entry_points[0][1] != "main":
        problems.append(
            f"{path.name}: entry point is {entry_points[0][1]!r} "
            f"(execution model {entry_points[0][0]}), expected GLCompute 'main'"
        )
    if local_size is None:
        problems.append(f"{path.name}: missing LocalSize execution mode")

    seen = {}
    for var_id, (pointer_type, storage) in variables.items():
        if storage != STORAGE_UNIFORM:
            continue
        binding = _binding_of(words, var_id)
        pointee = struct_of.get(pointer_type, (None, None))[1]
        decor = decorations.get(pointee, set())
        if binding is None:
            problems.append(f"{path.name}: uniform variable {var_id} has no Binding decoration")
            continue
        kind = DECOR_BLOCK if DECOR_BLOCK in decor else (
            DECOR_BUFFER_BLOCK if DECOR_BUFFER_BLOCK in decor else None)
        if kind is None:
            problems.append(f"{path.name}: binding {binding} is neither a Block nor a BufferBlock")
            continue
        seen[binding] = kind

    for binding, (label, expected_kind) in EXPECTED.items():
        if binding not in seen:
            problems.append(f"{path.name}: binding {binding} missing ({label})")
        elif seen[binding] != expected_kind:
            problems.append(
                f"{path.name}: binding {binding} is "
                f"{'Block' if seen[binding] == DECOR_BLOCK else 'BufferBlock'}, "
                f"the native side binds a {label}"
            )

    if len(seen) != len(EXPECTED):
        problems.append(f"{path.name}: {len(seen)} uniform descriptors, expected {len(EXPECTED)}")

    uniforms = sum(1 for _, storage in variables.values() if storage == STORAGE_UNIFORM)
    print(f"{path.name}: SPIR-V {major}.{minor}, LocalSize {local_size}, "
          f"{uniforms} resources, bindings {sorted(seen)}")
    return problems


def _binding_of(words, target):
    for opcode, operands in instructions(words):
        if opcode == OP_DECORATE and operands[0] == target and operands[1] == DECOR_BINDING:
            return operands[2]
    return None


def main():
    root = Path(__file__).resolve().parent.parent
    shader_dir = root / "app/src/main/assets/shaders"
    failures = []
    for name in ("chroma_key_y.spv", "chroma_key_uv.spv"):
        path = shader_dir / name
        if not path.exists():
            failures.append(f"{name}: missing (run tools/build_shaders.mjs)")
            continue
        failures.extend(check(path))

    if failures:
        print("\nFAILED")
        for problem in failures:
            print("  - " + problem)
        return 1
    print("\nOK - the shaders match the descriptor layout in chroma_vulkan.cpp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
