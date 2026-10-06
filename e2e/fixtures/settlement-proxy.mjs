// Test-only proxy: block settlement or discard its response after the upstream commits.
import http from 'node:http';
import { Buffer } from 'node:buffer';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

export function createProxy(upstream) {
  let mode = 'pass';
  let blocked = 0;
  let dropped = 0;
  const server = http.createServer(async (req, res) => {
    if (req.url === '/__fault') {
      if (req.method === 'POST') {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const value = Buffer.concat(chunks).toString();
        if (!['pass', 'block', 'drop'].includes(value)) {
          res.writeHead(400).end();
          return;
        }
        mode = value;
        blocked = 0;
        dropped = 0;
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ mode, blocked, dropped }));
      return;
    }
    const settlement = req.method === 'POST' && req.url.endsWith('/settlement');
    const selectedMode = mode;
    if (settlement && selectedMode === 'block') {
      blocked++;
      res.writeHead(503).end('Injected settlement outage');
      return;
    }
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const headers = { ...req.headers };
      delete headers.host;
      delete headers.connection;
      delete headers['content-length'];
      const body = Buffer.concat(chunks);
      const response = await fetch(new URL(req.url, upstream), {
        method: req.method, headers, body: body.length ? body : undefined,
        signal: AbortSignal.timeout(10_000)
      });
      const bytes = Buffer.from(await response.arrayBuffer());
      if (settlement && selectedMode === 'drop' && response.ok) {
        dropped++;
        res.destroy(); // The account transaction committed, but order-app gets no response.
        return;
      }
      res.writeHead(response.status, { 'Content-Type': response.headers.get('content-type') ?? 'application/json' });
      res.end(bytes);
    } catch {
      res.writeHead(502).end('Upstream unavailable');
    }
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createProxy('http://account-app:8082').listen(8080, '0.0.0.0');
}
