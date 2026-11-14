#!/usr/bin/env node
// Compiles the compute shaders in app/src/main/cpp/shaders to SPIR-V in app/src/main/assets.
//
// The SPIR-V blobs are committed, so a normal build does not need this. Run it only after editing
// a .comp file:
//
//     cd AWA && npm install --no-save @webgpu/glslang && node tools/build_shaders.mjs
//
// (@webgpu/glslang is a WASM build of glslang, which keeps this script free of any Vulkan SDK.)
// On a machine that has the Vulkan SDK, `glslc --target-env=vulkan1.0 -O file.comp -o file.spv`
// produces equivalent output.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const srcDir = join(root, 'app/src/main/cpp/shaders');
const outDir = join(root, 'app/src/main/assets/shaders');

const shaders = ['chroma_key_y', 'chroma_key_uv'];

let glslang;
try {
    ({ default: glslang } = await import('@webgpu/glslang'));
} catch {
    console.error('Missing @webgpu/glslang. Run: npm install --no-save @webgpu/glslang');
    process.exit(1);
}

const module = await glslang();
for (const name of shaders) {
    const source = readFileSync(join(srcDir, `${name}.comp`), 'utf8');
    // '1.0' keeps the module usable on Vulkan 1.0 devices (Android 8+).
    const spv = module.compileGLSL(source, 'compute', false, '1.0');
    const bytes = Buffer.from(spv.buffer, spv.byteOffset, spv.byteLength);
    if (bytes.readUInt32LE(0) !== 0x07230203) {
        throw new Error(`${name}: not a SPIR-V module`);
    }
    const target = join(outDir, `${name}.spv`);
    if (!existsSync(outDir)) {
        throw new Error(`Missing ${outDir}`);
    }
    writeFileSync(target, bytes);
    console.log(`${name}.spv  ${bytes.length} bytes`);
}
