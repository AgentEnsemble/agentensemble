// @vitest-environment node
/**
 * Tests for scripts/embed-dist.mjs.
 *
 * The script is spawned as a subprocess so it exercises the real Node.js
 * ESM runtime. DIST_DIR and OUTPUT_PATH environment variables redirect the
 * script to a temporary directory so the repo is never modified during tests.
 */
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  rmSync,
  existsSync,
} from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';

const __dirname = dirname(fileURLToPath(import.meta.url));
const EMBED_SCRIPT = join(__dirname, '../../scripts/embed-dist.mjs');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function cleanEnv(): NodeJS.ProcessEnv {
  const env = { ...process.env };
  delete env['NODE_OPTIONS'];
  delete env['NODE_V8_COVERAGE'];
  return env;
}

function runEmbed(
  distDir: string,
  outputPath: string,
): ReturnType<typeof spawnSync> {
  return spawnSync(process.execPath, [EMBED_SCRIPT], {
    encoding: 'utf8',
    timeout: 10000,
    env: { ...cleanEnv(), DIST_DIR: distDir, OUTPUT_PATH: outputPath },
  });
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

let tmpDir: string;
let distDir: string;
let outputPath: string;

beforeAll(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'agentensemble-viz-embed-test-'));
  distDir = join(tmpDir, 'dist');
  outputPath = join(tmpDir, 'dist-assets.js');

  mkdirSync(distDir);
  mkdirSync(join(distDir, 'assets'));

  // Regular files that should be embedded
  writeFileSync(join(distDir, 'index.html'), '<html><body>hello</body></html>', 'utf8');
  writeFileSync(join(distDir, 'assets', 'main.js'), 'console.log("hello")', 'utf8');
  writeFileSync(join(distDir, 'assets', 'style.css'), 'body { color: red; }', 'utf8');
  writeFileSync(join(distDir, 'assets', 'logo.svg'), '<svg></svg>', 'utf8');

  // Source maps -- must be excluded from the output
  writeFileSync(join(distDir, 'assets', 'main.js.map'), '{"mappings":""}', 'utf8');
  writeFileSync(join(distDir, 'assets', 'style.css.map'), '{"mappings":""}', 'utf8');
});

afterAll(() => {
  rmSync(tmpDir, { recursive: true, force: true });
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('embed-dist.mjs', () => {
  describe('successful generation', () => {
    it('exits with code 0', () => {
      const result = runEmbed(distDir, outputPath);
      expect(result.status).toBe(0);
    });

    it('creates the output file', () => {
      runEmbed(distDir, outputPath);
      expect(existsSync(outputPath)).toBe(true);
    });

    it('exports a Map named distAssets', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).toContain('export const distAssets = new Map([');
    });

    it('embeds index.html with the html MIME type', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).toContain('"/index.html"');
      expect(content).toContain('text/html; charset=utf-8');
    });

    it('embeds JS files with the javascript MIME type', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).toContain('"/assets/main.js"');
      expect(content).toContain('application/javascript; charset=utf-8');
    });

    it('embeds CSS files with the css MIME type', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).toContain('"/assets/style.css"');
      expect(content).toContain('text/css; charset=utf-8');
    });

    it('embeds SVG files with the svg MIME type', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).toContain('"/assets/logo.svg"');
      expect(content).toContain('image/svg+xml');
    });

    it('excludes .map (source map) files', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      expect(content).not.toContain('.map');
    });

    it('encodes file content as base64', () => {
      runEmbed(distDir, outputPath);
      const content = readFileSync(outputPath, 'utf8');
      // The HTML content "<html><body>hello</body></html>" encoded as base64
      const expected = Buffer.from('<html><body>hello</body></html>', 'utf8').toString('base64');
      expect(content).toContain(expected);
    });

    it('prints a summary line to stdout listing embedded files', () => {
      const result = runEmbed(distDir, outputPath);
      expect(result.stdout).toContain('Embedded 4 dist assets');
    });

    it('includes the actual output path in the summary log', () => {
      const result = runEmbed(distDir, outputPath);
      expect(result.stdout).toContain(`into ${outputPath}`);
    });

    it('lists each embedded file path in stdout', () => {
      const result = runEmbed(distDir, outputPath);
      expect(result.stdout).toContain('/index.html');
      expect(result.stdout).toContain('/assets/main.js');
      expect(result.stdout).toContain('/assets/style.css');
      expect(result.stdout).toContain('/assets/logo.svg');
    });

    it('generates valid importable JavaScript', async () => {
      runEmbed(distDir, outputPath);
      // Dynamically import the generated file and verify the Map is populated
      const mod = await import(outputPath + `?t=${Date.now()}`);
      expect(mod.distAssets).toBeInstanceOf(Map);
      expect(mod.distAssets.size).toBe(4);
      expect(mod.distAssets.has('/index.html')).toBe(true);
      expect(mod.distAssets.has('/assets/main.js')).toBe(true);
    });

    it('base64 content round-trips correctly to original file bytes', async () => {
      runEmbed(distDir, outputPath);
      const mod = await import(outputPath + `?t=${Date.now() + 1}`);
      const { content: b64 } = mod.distAssets.get('/index.html') as {
        content: string;
        mimeType: string;
      };
      const decoded = Buffer.from(b64, 'base64').toString('utf8');
      expect(decoded).toBe('<html><body>hello</body></html>');
    });

    it('generates file entries in stable sorted urlPath order', async () => {
      runEmbed(distDir, outputPath);
      const mod = await import(outputPath + `?t=${Date.now() + 2}`);
      const keys = [...(mod.distAssets as Map<string, unknown>).keys()] as string[];
      const sorted = [...keys].sort((a, b) => a.localeCompare(b));
      expect(keys).toEqual(sorted);
    });
  });

  describe('error handling', () => {
    it('exits with code 1 when dist directory does not exist', () => {
      const result = runEmbed('/nonexistent/path/dist', outputPath);
      expect(result.status).toBe(1);
    });

    it('prints an error message to stderr when dist directory is missing', () => {
      const result = runEmbed('/nonexistent/path/dist', outputPath);
      expect(result.stderr).toContain('Error:');
    });
  });

  describe('check-assets.mjs', () => {
    const CHECK_SCRIPT = join(__dirname, '../../scripts/check-assets.mjs');
    let checkTmpDir: string;

    function runCheckAssets(assetsFile: string): ReturnType<typeof spawnSync> {
      return spawnSync(process.execPath, [CHECK_SCRIPT], {
        encoding: 'utf8',
        timeout: 5000,
        env: { ...cleanEnv(), ASSETS_FILE: assetsFile },
      });
    }

    beforeAll(() => {
      checkTmpDir = mkdtempSync(join(tmpdir(), 'agentensemble-check-assets-'));
    });

    afterAll(() => {
      rmSync(checkTmpDir, { recursive: true, force: true });
    });

    it('exits with code 0 when the file contains the empty placeholder Map', () => {
      const file = join(checkTmpDir, 'placeholder.js');
      writeFileSync(file, 'export const distAssets = new Map();\n', 'utf8');
      const result = runCheckAssets(file);
      expect(result.status).toBe(0);
    });

    it('prints an OK message to stdout for a clean placeholder', () => {
      const file = join(checkTmpDir, 'placeholder-ok.js');
      writeFileSync(file, 'export const distAssets = new Map();\n', 'utf8');
      const result = runCheckAssets(file);
      expect(result.stdout).toContain('OK');
    });

    it('exits with code 1 when the file contains generated content', () => {
      const file = join(checkTmpDir, 'generated.js');
      writeFileSync(
        file,
        'export const distAssets = new Map([\n' +
          '  ["/index.html", { content: "abc", mimeType: "text/html" }],\n' +
          ']);\n',
        'utf8',
      );
      const result = runCheckAssets(file);
      expect(result.status).toBe(1);
    });

    it('prints an instructive error to stderr when generated content is present', () => {
      const file = join(checkTmpDir, 'generated-err.js');
      writeFileSync(
        file,
        'export const distAssets = new Map([\n' +
          '  ["/index.html", { content: "abc", mimeType: "text/html" }],\n' +
          ']);\n',
        'utf8',
      );
      const result = runCheckAssets(file);
      expect(result.stderr).toContain('git checkout dist-assets.js');
    });

    it('exits with code 1 when the assets file does not exist', () => {
      const result = runCheckAssets(join(checkTmpDir, 'nonexistent.js'));
      expect(result.status).toBe(1);
    });
  });
});
