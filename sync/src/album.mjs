// Extracts photo entries from a link-shared Google Photos album page.
// Strategy: read the structured data the page itself embeds (AF_initDataCallback
// blobs) and walk it for item-shaped arrays, rather than relying on DOM/CSS.
// Item shape (observed): [mediaKey, [imageUrl, width, height, ...], takenMs, ...]

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const IMG_HOST = /^https:\/\/lh\d*\.googleusercontent\.com\//;

export class AlbumError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

/** Pull every AF_initDataCallback payload out of the page HTML. */
export function extractDataBlobs(html) {
  const blobs = [];
  const re = /AF_initDataCallback\(\{key:\s*'(ds:\d+)'[\s\S]*?data:/g;
  let m;
  while ((m = re.exec(html))) {
    const start = m.index + m[0].length;
    const json = readBalanced(html, start);
    if (!json) continue;
    try { blobs.push({ key: m[1], data: JSON.parse(json) }); } catch { /* skip */ }
  }
  return blobs;
}

function readBalanced(s, i) {
  while (s[i] === ' ') i++;
  if (s[i] !== '[') return null;
  let depth = 0, inStr = false;
  for (let j = i; j < s.length; j++) {
    const c = s[j];
    if (inStr) { if (c === '\\') j++; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true;
    else if (c === '[') depth++;
    else if (c === ']' && --depth === 0) return s.slice(i, j + 1);
  }
  return null;
}

/** Walk any parsed structure and collect item-shaped arrays. Dedupes by media key. */
export function findPhotos(root) {
  const found = new Map();
  const walk = (node) => {
    if (!Array.isArray(node)) return;
    const [key, img, taken] = node;
    if (typeof key === 'string' && key.length > 20 && /^[\w-]+$/.test(key) &&
        Array.isArray(img) && typeof img[0] === 'string' && IMG_HOST.test(img[0]) &&
        Number.isFinite(img[1]) && Number.isFinite(img[2])) {
      if (!found.has(key)) found.set(key, {
        id: key,
        baseUrl: img[0].split('=')[0],
        width: img[1], height: img[2],
        capturedAt: Number.isFinite(taken) && taken > 0 ? new Date(taken).toISOString() : null,
      });
      return;
    }
    node.forEach(walk);
  };
  walk(root);
  return [...found.values()];
}

/** Find a pagination token + album key, if the page says there is more. */
export function findContinuation(blobs) {
  for (const { data } of blobs) {
    const albumKey = data?.[3]?.[0] ?? null;
    const token = typeof data?.[2] === 'string' && data[2].length > 10 ? data[2] : null;
    if (token) return { token, albumKey: typeof albumKey === 'string' ? albumKey : null };
  }
  return null;
}

export function parseAlbumHtml(html) {
  const blobs = extractDataBlobs(html);
  const photos = blobs.flatMap((b) => findPhotos(b.data));
  const unique = [...new Map(photos.map((p) => [p.id, p])).values()];
  return { photos: unique, continuation: findContinuation(blobs), blobCount: blobs.length };
}

/** Parse a batchexecute continuation response (XSSI-prefixed, length-framed). */
export function parseBatchResponse(text) {
  const body = text.replace(/^\)\]\}'\s*/, '');
  const photos = [];
  let token = null;
  for (const line of body.split('\n')) {
    if (!line.startsWith('[[')) continue;
    let outer; try { outer = JSON.parse(line); } catch { continue; }
    for (const row of outer) {
      if (row?.[0] !== 'wrb.fr' || typeof row[2] !== 'string') continue;
      let inner; try { inner = JSON.parse(row[2]); } catch { continue; }
      photos.push(...findPhotos(inner));
      if (typeof inner?.[2] === 'string' && inner[2].length > 10) token = inner[2];
    }
  }
  return { photos, token };
}

export async function fetchAlbum(shareUrl, { fetchImpl = fetch, maxPages = 50 } = {}) {
  const res = await fetchImpl(shareUrl, { headers: { 'user-agent': UA, 'accept-language': 'en' }, redirect: 'follow' });
  if (res.status === 404 || res.status === 403) throw new AlbumError('LINK_REVOKED', `Album link returned HTTP ${res.status}: sharing was probably turned off or the link changed.`);
  if (!res.ok) throw new AlbumError('HTTP', `Album page returned HTTP ${res.status}`);
  const finalUrl = new URL(res.url || shareUrl);
  if (/accounts\.google\.com/.test(finalUrl.host)) throw new AlbumError('LINK_REVOKED', 'Album redirected to sign-in: link sharing is off.');
  const html = await res.text();
  const first = parseAlbumHtml(html);
  if (first.blobCount === 0) throw new AlbumError('LAYOUT_CHANGED', 'No AF_initDataCallback data found; Google may have changed the album page.');
  const all = new Map(first.photos.map((p) => [p.id, p]));
  let cont = first.continuation;
  const authKey = finalUrl.searchParams.get('key');
  for (let page = 0; cont && page < maxPages; page++) {
    const inner = JSON.stringify([cont.albumKey, cont.token, null, authKey]);
    const body = new URLSearchParams({ 'f.req': JSON.stringify([[['snAcKc', inner, null, 'generic']]]) });
    const r = await fetchImpl('https://photos.google.com/_/PhotosUi/data/batchexecute?rpcids=snAcKc&source-path=%2Fshare&rt=c', {
      method: 'POST', body, headers: { 'user-agent': UA, 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    });
    if (!r.ok) throw new AlbumError('HTTP', `Pagination returned HTTP ${r.status}`);
    const parsed = parseBatchResponse(await r.text());
    const before = all.size;
    parsed.photos.forEach((p) => all.set(p.id, p));
    cont = parsed.token && all.size > before ? { ...cont, token: parsed.token } : null;
  }
  return [...all.values()];
}

/** Size-capped rendition URL for a photo (server-side resize). */
export const renditionUrl = (p, maxEdge = 2048) => `${p.baseUrl}=w${maxEdge}-h${maxEdge}`;
