import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createProxy } from './settlement-proxy.mjs';

// Verify blocking avoids the upstream and dropping loses only an already-successful response.
test('settlement faults', async () => {
  let bookings = 0;
  const upstream = http.createServer((req, res) => {
    if (req.url.endsWith('/settlement')) bookings++;
    res.setHeader('Content-Type', 'application/json');
    res.end('{"ok":true}');
  }).listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const proxy = createProxy(`http://127.0.0.1:${upstream.address().port}`).listen(0, '127.0.0.1');
  await once(proxy, 'listening');
  const url = `http://127.0.0.1:${proxy.address().port}`;
  try {
    await fetch(`${url}/__fault`, { method: 'POST', body: 'block' });
    assert.equal((await fetch(`${url}/settlement`, { method: 'POST' })).status, 503);
    assert.equal(bookings, 0);
    assert.equal((await fetch(`${url}/validation-data`)).status, 200);
    await fetch(`${url}/__fault`, { method: 'POST', body: 'drop' });
    await assert.rejects(fetch(`${url}/settlement`, { method: 'POST' }));
    assert.equal(bookings, 1);
    assert.equal((await (await fetch(`${url}/__fault`)).json()).dropped, 1);
    await fetch(`${url}/__fault`, { method: 'POST', body: 'pass' });
    assert.equal((await fetch(`${url}/settlement`, { method: 'POST' })).status, 200);
    assert.equal(bookings, 2);
  } finally {
    proxy.closeAllConnections();
    upstream.closeAllConnections();
    await Promise.all([new Promise(resolve => proxy.close(resolve)), new Promise(resolve => upstream.close(resolve))]);
  }
});
