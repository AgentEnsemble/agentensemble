#!/usr/bin/env node
/**
 * AgentEnsemble Trace Viewer CLI
 *
 * Usage:
 *   agentensemble-viz [directory]
 *   npx @agentensemble/viz [directory]
 *
 * Starts a local HTTP server serving the trace viewer UI and the JSON files
 * from the specified directory (default: ./traces). Opens the browser automatically.
 */

import { createServer } from 'node:http';
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, extname, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exec } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Resolve the traces directory from the CLI argument or default
const tracesDir = process.argv[2] ? resolve(process.argv[2]) : resolve('./traces');
const distDir = join(__dirname, 'dist');
const PORT = parseInt(process.env.PORT ?? '7329', 10);

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function serveStaticFile(res, filePath) {
  try {
    if (!existsSync(filePath) || !statSync(filePath).isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }
    const ext = extname(filePath).toLowerCase();
    const mimeType = MIME_TYPES[ext] ?? 'application/octet-stream';
    const content = readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': mimeType });
    res.end(content);
  } catch {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('Internal Server Error');
  }
}

function jsonResponse(res, data, statusCode = 200) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

const server = createServer((req, res) => {
  // CORS for dev
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  const pathname = url.pathname;

  // --- API: list available trace/dag files ---
  if (pathname === '/api/files') {
    try {
      const files = existsSync(tracesDir)
        ? readdirSync(tracesDir)
            .filter((name) => {
              const fullPath = join(tracesDir, name);
              return (
                statSync(fullPath).isFile() &&
                (name.endsWith('.dag.json') || name.endsWith('.trace.json'))
              );
            })
            .map((name) => ({
              name,
              type: name.endsWith('.dag.json') ? 'dag' : 'trace',
              sizeBytes: statSync(join(tracesDir, name)).size,
            }))
            .sort((a, b) => a.name.localeCompare(b.name))
        : [];
      jsonResponse(res, { files, directory: tracesDir });
    } catch (err) {
      jsonResponse(res, { error: String(err) }, 500);
    }
    return;
  }

  // --- API: serve a specific file from the traces directory ---
  if (pathname === '/api/file') {
    const name = url.searchParams.get('name');
    // Security: reject any path traversal attempts
    if (!name || name.includes('..') || name.includes('/') || name.includes('\\')) {
      jsonResponse(res, { error: 'Invalid filename' }, 400);
      return;
    }
    const filePath = join(tracesDir, name);
    if (!filePath.startsWith(tracesDir)) {
      jsonResponse(res, { error: 'Access denied' }, 403);
      return;
    }
    serveStaticFile(res, filePath);
    return;
  }

  // --- Static file serving (the built Vite app) ---
  const relPath = pathname === '/' ? '/index.html' : pathname;
  const filePath = join(distDir, relPath);

  if (existsSync(filePath) && statSync(filePath).isFile()) {
    serveStaticFile(res, filePath);
  } else {
    // SPA fallback: serve index.html for any unrecognized route
    serveStaticFile(res, join(distDir, 'index.html'));
  }
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`Port ${PORT} is already in use. Set PORT env var to use a different port.`);
  } else {
    console.error('Server error:', err.message);
  }
  process.exit(1);
});

server.listen(PORT, '127.0.0.1', () => {
  const url = `http://localhost:${PORT}`;

  console.log('');
  console.log('  AgentEnsemble Trace Viewer');
  console.log('  --------------------------');
  console.log(`  Local:   ${url}`);
  console.log(`  Traces:  ${tracesDir}`);
  console.log('');
  console.log('  Opening browser...');
  console.log('  Press Ctrl+C to stop.');
  console.log('');

  const opener =
    process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'start' : 'xdg-open';
  exec(`${opener} ${url}`, (err) => {
    if (err) {
      console.log(`  Could not open browser automatically. Visit ${url} manually.`);
    }
  });
});
