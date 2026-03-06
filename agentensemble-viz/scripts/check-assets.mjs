#!/usr/bin/env node
/**
 * check-assets.mjs
 *
 * Validates that dist-assets.js contains only the empty placeholder Map and
 * not the auto-generated content produced by `npm run embed`.
 *
 * Use this as a CI check or pre-commit guard to prevent accidentally
 * committing the large generated asset map. Running `npm run embed` locally
 * overwrites dist-assets.js in-place; this script catches that before it is
 * staged or pushed.
 *
 * Usage:
 *   node scripts/check-assets.mjs
 *
 * Exit code 0: placeholder is clean (safe to commit)
 * Exit code 1: file contains generated content (must be reverted)
 */

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const assetsFile = process.env.ASSETS_FILE ?? join(__dirname, '..', 'dist-assets.js');

let content;
try {
  content = readFileSync(assetsFile, 'utf8');
} catch (err) {
  console.error(`Error: could not read ${assetsFile}: ${err.message}`);
  process.exit(1);
}

// The placeholder file contains `new Map()` (empty). The generated file
// contains `new Map([` (populated entries). Detecting the open bracket is
// sufficient to distinguish the two states.
if (content.includes('new Map([')) {
  console.error('Error: dist-assets.js contains generated content and must not be committed.');
  console.error('Revert it before committing:  git checkout dist-assets.js');
  process.exit(1);
}

console.log('dist-assets.js: placeholder is clean (OK).');
