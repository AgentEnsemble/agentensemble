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
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { describe, it, expect } from 'vitest';

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
});
