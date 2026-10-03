import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAlbumHtml, parseBatchResponse } from '../src/album.mjs';
import { diffAlbum } from '../src/diff.mjs';

const K = (n) => `AF1QipN${String(n).padStart(30, 'x')}`;
const item = (key, w, h, ms) => [key, [`https://lh3.googleusercontent.com/pw/${key}-img=w400-h300`, w, h], ms];
const page = (items, token = null) =>
  `<html><script>AF_initDataCallback({key: 'ds:1', hash: '2', data:[null,${JSON.stringify(items)},${JSON.stringify(token)},[${JSON.stringify(K(0))}]], sideChannel: {}});</script>` +
  `<script>AF_initDataCallback({key: 'ds:0', data:[1,"a ] \\" tricky"], sideChannel: {}});</script></html>`;

test('parses items independent of position and dedupes', () => {
  const html = page([item(K(1), 4000, 3000, 1700000000000), item(K(2), 3000, 4000, 1700000100000), item(K(1), 4000, 3000, 1700000000000)]);
  const { photos } = parseAlbumHtml(html);
  assert.equal(photos.length, 2);
  assert.equal(photos[0].id, K(1));
  assert.equal(photos[0].baseUrl, `https://lh3.googleusercontent.com/pw/${K(1)}-img`);
  assert.deepEqual([photos[1].width, photos[1].height], [3000, 4000]);
  assert.equal(photos[0].capturedAt, '2023-11-14T22:13:20.000Z');
});

test('ids are stable across re-parses and independent of ordering', () => {
  const a = parseAlbumHtml(page([item(K(1), 1, 1, 1), item(K(2), 1, 1, 1)])).photos.map((p) => p.id).sort();
  const b = parseAlbumHtml(page([item(K(2), 1, 1, 1), item(K(1), 1, 1, 1)])).photos.map((p) => p.id).sort();
  assert.deepEqual(a, b);
});

test('empty album yields no photos but is still a valid page', () => {
  const r = parseAlbumHtml(page([]));
  assert.equal(r.photos.length, 0);
  assert.ok(r.blobCount > 0);
});

test('detects continuation token', () => {
  const r = parseAlbumHtml(page([item(K(1), 1, 1, 1)], 'TOKEN_ABCDEFGHIJKLMNOP'));
  assert.equal(r.continuation.token, 'TOKEN_ABCDEFGHIJKLMNOP');
});

test('parses batchexecute continuation response', () => {
  const inner = JSON.stringify([[item(K(5), 10, 20, 5)], null, 'NEXTTOKEN_1234567890']);
  const row = JSON.stringify([['wrb.fr', 'snAcKc', inner, null, null, null, 'generic']]);
  const r = parseBatchResponse(")]}'\n\n" + row.length + '\n' + row + '\n');
  assert.equal(r.photos[0].id, K(5));
  assert.equal(r.token, 'NEXTTOKEN_1234567890');
});

test('diff detects additions and removals', () => {
  const p = (id) => ({ id });
  const d = diffAlbum([p('a'), p('b')], [p('b'), p('c')]);
  assert.deepEqual(d.added.map((x) => x.id), ['c']);
  assert.deepEqual(d.removed.map((x) => x.id), ['a']);
  assert.deepEqual(d.unchanged.map((x) => x.id), ['b']);
});

test('diff of identical states is empty', () => {
  const d = diffAlbum([{ id: 'a' }], [{ id: 'a' }]);
  assert.equal(d.added.length + d.removed.length, 0);
});
