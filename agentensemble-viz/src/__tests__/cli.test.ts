// @vitest-environment node
/**
 * CLI integration tests.
 *
 * These tests spawn the Node.js process directly so they exercise the real
 * runtime behaviour without jsdom. They do NOT import cli.js (which would
 * start the HTTP server on import) - instead they use child_process.spawnSync
 * to run the script as a subprocess.
 *
 * NODE_OPTIONS is stripped from the child environment to prevent vitest's
 * internal loader flags (e.g. --import @vitest/...) from interfering with
 * the spawned process.
 */
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';

const __dirname = dirname(fileURLToPath(import.meta.url));
const CLI_PATH = resolve(__dirname, '../../cli.js');

/**
 * Build a clean environment for spawned subprocesses by stripping vitest-
 * injected flags that would cause the child process to fail.
 */
function cleanEnv(): NodeJS.ProcessEnv {
  const env = { ...process.env };
  delete env['NODE_OPTIONS'];
  delete env['NODE_V8_COVERAGE'];
  return env;
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

interface HttpResponse {
  statusCode: number | undefined;
  contentType: string | undefined;
  body: string;
}

function httpGet(url: string): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () =>
          resolve({
            statusCode: res.statusCode,
            contentType: res.headers['content-type'],
            body: Buffer.concat(chunks).toString('utf8'),
          }),
        );
      })
      .on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('agentensemble-viz CLI', () => {
  describe('--version flag', () => {
    it('prints the version string and exits with code 0', () => {
      const result = spawnSync(process.execPath, [CLI_PATH, '--version'], {
        encoding: 'utf8',
        timeout: 5000,
        env: cleanEnv(),
      });

      expect(result.status).toBe(0);
      expect(result.stdout).toMatch(/^agentensemble-viz\/\d+\.\d+\.\d+/);
    });

    it('includes the package version number in the output', () => {
      const result = spawnSync(process.execPath, [CLI_PATH, '--version'], {
        encoding: 'utf8',
        timeout: 5000,
        env: cleanEnv(),
      });

      // The version embedded in the binary must match the semver format
      const match = result.stdout.match(/agentensemble-viz\/(\d+\.\d+\.\d+)/);
      expect(match).not.toBeNull();
    });

    it('does not start the HTTP server when --version is passed', () => {
      const result = spawnSync(process.execPath, [CLI_PATH, '--version'], {
        encoding: 'utf8',
        timeout: 5000,
        env: cleanEnv(),
      });

      // Server startup banner must NOT appear
      expect(result.stdout).not.toContain('AgentEnsemble Trace Viewer');
    });

    it('exits cleanly with no stderr output', () => {
      const result = spawnSync(process.execPath, [CLI_PATH, '--version'], {
        encoding: 'utf8',
        timeout: 5000,
        env: cleanEnv(),
      });

      expect(result.stderr.trim()).toBe('');
    });
  });

  describe('HTTP server', () => {
    // PORT=0 lets the OS assign an ephemeral port so the test never clashes
    // with a running development instance or another parallel CI job.
    // The actual bound port is parsed from the startup banner and stored here.
    let actualPort: number;
    let serverProcess: ReturnType<typeof spawn>;

    beforeAll(async () => {
      await new Promise<void>((resolvePromise, rejectPromise) => {
        serverProcess = spawn(process.execPath, [CLI_PATH], {
          stdio: ['ignore', 'pipe', 'pipe'],
          env: { ...cleanEnv(), PORT: '0', NO_OPEN: '1' },
        });

        // Parse the actual bound port from the startup banner, e.g.:
        //   Local:   http://localhost:54321
        serverProcess.stdout?.on('data', (chunk: Buffer) => {
          const text = chunk.toString();
          const match = text.match(/Local:\s+http:\/\/localhost:(\d+)/);
          if (match) {
            actualPort = parseInt(match[1], 10);
            resolvePromise();
          }
        });

        serverProcess.on('error', rejectPromise);

        // Safety timeout in case the server never starts
        setTimeout(() => rejectPromise(new Error('Server did not start within 10 s')), 10000);
      });
    }, 15000);

    afterAll(() => {
      serverProcess?.kill('SIGTERM');
    });

    it('serves the root path with 200 and text/html content type', async () => {
      const res = await httpGet(`http://localhost:${actualPort}/`);
      expect(res.statusCode).toBe(200);
      expect(res.contentType).toContain('text/html');
    });

    it('serves the root page with an HTML doctype', async () => {
      const res = await httpGet(`http://localhost:${actualPort}/`);
      expect(res.body.toLowerCase()).toContain('<!doctype html');
    });

    it('returns 200 for an unrecognised route (SPA fallback)', async () => {
      const res = await httpGet(`http://localhost:${actualPort}/some-react-route`);
      expect(res.statusCode).toBe(200);
      expect(res.contentType).toContain('text/html');
    });

    it('returns the index.html for the /live React Router path (SPA fallback)', async () => {
      const res = await httpGet(`http://localhost:${actualPort}/live`);
      expect(res.statusCode).toBe(200);
      expect(res.body.toLowerCase()).toContain('<!doctype html');
    });

    it('returns 200 and JSON from /api/files', async () => {
      const res = await httpGet(`http://localhost:${actualPort}/api/files`);
      expect(res.statusCode).toBe(200);
      expect(res.contentType).toContain('application/json');
      const data = JSON.parse(res.body) as { files: unknown[]; directory: string };
      expect(Array.isArray(data.files)).toBe(true);
      expect(typeof data.directory).toBe('string');
    });

    it('rejects path-traversal attempts on /api/file with 400', async () => {
      const res = await httpGet(
        `http://localhost:${actualPort}/api/file?name=..%2F..%2Fetc%2Fpasswd`,
      );
      expect(res.statusCode).toBe(400);
    });

    it('responds to CORS preflight OPTIONS with 204', async () => {
      const response = await new Promise<{ statusCode: number | undefined }>((resolve, reject) => {
        const req = http.request(
          { hostname: 'localhost', port: actualPort, path: '/', method: 'OPTIONS' },
          (res) => {
            res.resume();
            res.on('end', () => resolve({ statusCode: res.statusCode }));
          },
        );
        req.on('error', reject);
        req.end();
      });
      expect(response.statusCode).toBe(204);
    });
  });
});
