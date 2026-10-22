#!/usr/bin/env python3
"""Stream films straight to the TV from the same indexers that feed the library.

POST /start   {"title": "...", "year": 1999}  -> {"path": "/stream/<hash>", "label": "...", "bytes": n}
GET  /stream/<hash>                           -> the film's bytes, with Range support while it downloads
GET  /health                                  -> {"ok": true}

Candidates come from Prowlarr (the indexers Radarr searches). Only releases with enough seeders, a
plausible film size and a matching title are kept. The chosen torrent is added to qBittorrent with
sequential download, and only its film file is selected. The server reads that file as it grows.
No API keys or session cookies leave this process, and logs never include them.
"""
import datetime as dt
import http.cookiejar
import json
import logging
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger('stream-service')
STACK = Path(os.environ.get('MEDIA_STACK', '/home/deck/.local/share/media-stack'))
HOST_MEDIA = Path(os.environ.get('MEDIA_ROOT', '/home/deck/Media'))   # the Deck's media root
QBIT_MEDIA = '/media'                                               # where qBittorrent's container sees the same folder
PROWLARR = 'http://127.0.0.1:9696'
QBIT = 'http://127.0.0.1:8090'
SEERR = 'http://127.0.0.1:5055'
PORT = 5057
CATEGORY = 'mcm-stream'
MOVIE_CATEGORY = 2000
TV_CATEGORY = 5000
MIN_SEEDERS = 5
MIN_BYTES = 300 * 1024 * 1024
MAX_BYTES = 12 * 1024 ** 3
MIN_EPISODE_BYTES = 80 * 1024 * 1024    # an episode is far smaller than a film
MAX_EPISODE_BYTES = 8 * 1024 ** 3
STALL_SECONDS = 60          # no new bytes for this long means the stream has stalled
QUICK_POLL_SECONDS = 0.25   # how often a waiting stream checks for newly written bytes
COPY_CHUNK_BYTES = 256 * 1024   # small enough to start sending sooner, large enough not to thrash the socket
READY_BYTES = 16 * 1024 * 1024  # how much must be ready before the loading screen hands over to playback
FFMPEG = shutil.which('ffmpeg') or '/usr/bin/ffmpeg'
IDLE_SECONDS = 4 * 3600     # an unwatched stream is removed after this long
START_TIMEOUT = 90          # waiting for qBittorrent to fetch torrent metadata
VIDEO_EXTENSIONS = ('.mkv', '.mp4', '.m4v', '.webm', '.avi', '.mov')
BAD_RELEASE = re.compile(r'\b(cam|camrip|hdcam|ts|telesync|tc|telecine|screener|scr|r5|dvdscr|workprint)\b', re.I)
RESOLUTIONS = (('2160', 2.5), ('1080', 3.0), ('720', 2.0), ('480', 0.5))
SOURCES = (('remux', 2.0), ('bluray', 2.0), ('blu-ray', 2.0), ('web-dl', 2.0), ('webdl', 2.0), ('webrip', 1.0), ('web-rip', 1.0), ('hdtv', 0.5))


class ServiceError(Exception):
    pass


def normalize(text):
    return re.sub(r'[^a-z0-9]+', ' ', (text or '').lower()).strip()


def parse_release(name):
    """Resolution, source and rejection flags from a release name. Unknown stays unknown."""
    lowered = (name or '').lower()
    resolution = next((r for r, _ in RESOLUTIONS if r in lowered), None)
    if resolution is None and re.search(r'\b4k\b|uhd', lowered):
        resolution = '2160'
    source = next((s for s, _ in SOURCES if s in lowered), None)
    return {
        'resolution': resolution,
        'source': source,
        'bad': bool(BAD_RELEASE.search(lowered)),
        'multi_season': bool(re.search(r's\d{1,2}\s*-\s*s?\d{1,2}|complete series|season \d+\s*-\s*\d+', lowered)),
    }


def episode_marker(season, episode):
    """The SxxExx tag a release names its episode with, as a regex that does not match a longer number."""
    return re.compile(r"(?<![a-z0-9])s0*%de0*%d(?![0-9])" % (int(season), int(episode)), re.I)


def title_matches(title, year, release):
    """Every word of the film's title must appear in the release name, and a year, if given, must too."""
    words = normalize(title).split()
    haystack = ' ' + normalize(release) + ' '
    if not words or any(' ' + w + ' ' not in haystack for w in words):
        return False
    if year and not re.search(r'(?<!\d)%d(?!\d)' % int(year), release):
        return False
    return True


def acceptable(result, title, year, episode=None):
    """Whether a Prowlarr result is worth trying at all. Returns (keep, reason).

    With episode = (season, episode) the result must be a single-episode release for that episode. A season pack
    would need its file chosen per episode, which the server does not do, so packs are refused.
    """
    name = result.get('title') or ''
    seeders = result.get('seeders')
    size = result.get('size') or 0
    info = parse_release(name)
    if not isinstance(seeders, int) or seeders < MIN_SEEDERS:
        return False, 'seeders'
    low, high = (MIN_BYTES, MAX_BYTES) if episode is None else (MIN_EPISODE_BYTES, MAX_EPISODE_BYTES)
    if size < low or size > high:
        return False, 'size'
    if info['bad']:
        return False, 'poor copy'
    if info['multi_season']:
        return False, 'not a film' if episode is None else 'season pack'
    if episode is not None:
        if not episode_marker(*episode).search(name):
            return False, 'other episode'
        if not title_matches(title, None, name):
            return False, 'title'
    elif not title_matches(title, year, name):
        return False, 'title'
    if magnet_for(result, title) is None:
        return False, 'no link'
    return True, ''


# Word starts only: release names run codecs into numbers ("DDP5 1"), so a trailing \b would miss them.
HARD_AUDIO = re.compile(r'(?<![a-z])(ddp|dd\+|e-?ac-?3|dts|truehd)', re.I)
PLAIN_AUDIO = re.compile(r'(?<![a-z])(ac-?3|aac|dd ?5[ .]1)', re.I)
AUDIO_PENALTY = 3.0   # the launcher's player (Chromecast HD class) cannot decode these, so the film would play silent


def audio_unplayable(name):
    """True when the release's audio is E-AC-3/DTS/TrueHD and carries no AC-3/AAC track to fall back on."""
    return bool(HARD_AUDIO.search(name or '')) and not PLAIN_AUDIO.search(name or '')


def score(result):
    """Higher is better: resolution first, then source quality, then how well it is seeded, and small files last."""
    info = parse_release(result.get('title'))
    resolution = dict(RESOLUTIONS).get(info['resolution'], 1.0)
    source = dict(SOURCES).get(info['source'], 0.0)
    seeds = math.log10(max(result.get('seeders') or 0, 1))
    size_gb = (result.get('size') or 0) / 1024 ** 3
    oversize = max(0.0, size_gb - 8.0) * 0.5
    silent = AUDIO_PENALTY if audio_unplayable(result.get('title')) else 0.0
    return resolution * 2 + source + seeds - oversize - silent


def pick(results, title, year, episode=None):
    """Best acceptable result, or None."""
    kept = [r for r in results if acceptable(r, title, year, episode)[0]]
    return max(kept, key=score) if kept else None


def hash_from_magnet(url):
    match = re.search(r'btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})', url or '')
    return match.group(1).lower() if match else None


def magnet_for(result, title):
    """A magnet link qBittorrent can use on its own, or None.

    Prowlarr's magnetUrl is a proxy on this machine's localhost, which the qBittorrent container cannot reach,
    so the info hash is used instead. A real magnet: link is used only when there is no hash.
    """
    info = (result.get('infoHash') or '').lower()
    if re.fullmatch(r'[0-9a-f]{40}', info):
        return 'magnet:?xt=urn:btih:' + info + '&dn=' + urllib.parse.quote(title or '')
    link = result.get('magnetUrl') or ''
    return link if link.startswith('magnet:') and hash_from_magnet(link) else None


def video_file(files, episode=None):
    """The largest video file in a torrent, as (index, name, size), or None.

    With episode = (season, episode), only a video file carrying that episode's SxxExx tag counts.
    """
    marker = episode_marker(*episode) if episode is not None else None
    videos = [(f.get('index'), f.get('name', ''), f.get('size', 0)) for f in files
              if f.get('name', '').lower().endswith(VIDEO_EXTENSIONS) and (marker is None or marker.search(f.get('name', '')))]
    return max(videos, key=lambda v: v[2]) if videos else None


def parse_range(header, total):
    """(start, end) inclusive for a single 'bytes=' range, or None when unsatisfiable or absent."""
    if not header:
        return 0, total - 1
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', header.strip())
    if not match or (not match.group(1) and not match.group(2)):
        return None
    if not match.group(1):
        length = int(match.group(2))
        start, end = max(total - length, 0), total - 1
    else:
        start = int(match.group(1))
        end = int(match.group(2)) if match.group(2) else total - 1
        end = min(end, total - 1)
    if start > end or start >= total:
        return None
    return start, end


class Streams:
    """Turns a title into a torrent, keeps its film file known, and removes idle ones."""

    def __init__(self, stack=STACK, prowlarr=PROWLARR, qbit=QBIT, seerr=SEERR, host_media=HOST_MEDIA, qbit_media=QBIT_MEDIA):
        self.stack = stack
        self.prowlarr = prowlarr
        self.qbit_base = qbit
        self.seerr = seerr
        self.host_media = Path(host_media)
        self.qbit_media = qbit_media
        # qBittorrent runs in a container that sees the host's media root as /media, so it gets the container path.
        self.cache = self.host_media / 'Downloads' / 'stream'
        self.cache.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.known = {}     # hash -> {"path": str, "size": int, "label": str, "seen": float}
        self.jar = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.qbit_ready = False
        self.pending = {}   # hash -> {"label", "error"} for films whose metadata has not arrived yet

    # --- Prowlarr ---------------------------------------------------------------------------------

    def search(self, title, year, episode=None):
        key = ET.parse(self.stack / 'prowlarr' / 'config.xml').findtext('ApiKey')
        if episode is None:
            text, category = (f'{title} {year}' if year else title), MOVIE_CATEGORY
        else:
            text, category = f'{title} S{int(episode[0]):02d}E{int(episode[1]):02d}', TV_CATEGORY
        query = urllib.parse.urlencode({'query': text, 'type': 'search', 'categories': category, 'limit': 100})
        req = urllib.request.Request(f'{self.prowlarr}/api/v1/search?{query}', headers={'X-Api-Key': key, 'Accept': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return json.loads(response.read() or b'[]')
        except (urllib.error.URLError, ValueError, OSError):
            raise ServiceError('indexers unavailable') from None

    # --- qBittorrent ------------------------------------------------------------------------------

    def qbit(self, path, form=None):
        """Same login as the request pipeline: the download-client secret stays on this machine."""
        def send(endpoint, data=None):
            request = urllib.request.Request(self.qbit_base + endpoint,
                data=urllib.parse.urlencode(data).encode() if data is not None else None,
                headers={'Referer': self.qbit_base + '/'})
            return self.client.open(request, timeout=20)
        with self.lock:
            try:
                with send(path, form) as response:
                    return json.loads(response.read() or b'null')
            except urllib.error.HTTPError as error:
                if error.code not in (401, 403):
                    raise ServiceError('qBittorrent HTTP %d' % error.code) from None
            except (urllib.error.URLError, OSError):
                raise ServiceError('qBittorrent unavailable') from None
            try:
                with sqlite3.connect('file:' + str(self.stack / 'sonarr/sonarr.db') + '?mode=ro', uri=True) as db:
                    row = db.execute("SELECT Settings FROM DownloadClients WHERE Implementation='QBittorrent' AND Enable=1 LIMIT 1").fetchone()
                if not row:
                    raise ServiceError('qBittorrent not configured')
                config = json.loads(row[0])
                with send('/api/v2/auth/login', {'username': config['username'], 'password': config['password']}) as response:
                    response.read()
                with send(path, form) as response:
                    return json.loads(response.read() or b'null')
            except ServiceError:
                raise
            except Exception:
                raise ServiceError('qBittorrent authentication unavailable') from None

    def to_qbit(self, host_path):
        """The container path qBittorrent knows for a host path under the media root."""
        return self.qbit_media.rstrip('/') + '/' + Path(host_path).relative_to(self.host_media).as_posix()

    def to_host(self, qbit_path):
        """The host path for a path qBittorrent reports under its own media root."""
        if not qbit_path.startswith(self.qbit_media.rstrip('/') + '/'):
            raise ServiceError('unexpected download path')
        return self.host_media / qbit_path[len(self.qbit_media.rstrip('/')) + 1:]

    def restore(self):
        """After a restart, adopts stream torrents qBittorrent still holds, so their data is still served and swept."""
        for row in self.qbit('/api/v2/torrents/info?category=' + CATEGORY) or []:
            files = self.qbit('/api/v2/torrents/files?hash=' + row['hash']) or []
            chosen = video_file(files)
            if chosen is None:
                continue
            self.known[row['hash']] = self.entry_for(row['hash'], row['save_path'], files, chosen)

    def entry_for(self, hash_, save_path, files, chosen):
        """What the server needs to serve one film: where it is, how big, and where it sits among the torrent's pieces."""
        index, name, size = chosen
        props = self.qbit('/api/v2/torrents/properties?hash=' + hash_) or {}
        label = re.sub(r'[\._]+', ' ', Path(name).stem)[:120]
        return {
            'hash': hash_, 'path': str(self.to_host(save_path) / name), 'size': size, 'label': label,
            'offset': sum(f.get('size', 0) for f in files if f.get('index', 0) < index),
            'piece_size': props.get('piece_size') or 262144, 'pieces': [], 'pieces_at': 0.0, 'seen': time.time(),
            # Audio this TV cannot decode is converted to AAC while it is served; video is copied untouched.
            'transcode': audio_unplayable(name),
        }

    def downloaded_until(self, entry, position):
        """Film-relative byte just past the run of finished pieces that starts at [position].

        A sparse file reports its full size before it is written, so file size says nothing about what has arrived.
        qBittorrent's piece states do: 2 means downloaded. The list is refreshed at most once a second.
        """
        if time.time() - entry['pieces_at'] > 1.0:
            entry['pieces'] = self.qbit('/api/v2/torrents/pieceStates?hash=' + entry['hash']) or []
            entry['pieces_at'] = time.time()
        pieces, size = entry['pieces'], entry['piece_size']
        absolute = entry['offset'] + position
        piece = absolute // size
        while piece < len(pieces) and pieces[piece] == 2:
            piece += 1
        return min(piece * size - entry['offset'], entry['size'])

    def torrent(self, hash_):
        rows = self.qbit('/api/v2/torrents/info?hashes=' + hash_) or []
        return rows[0] if rows else None

    # --- Starting a film --------------------------------------------------------------------------

    def start(self, title, year, episode=None):
        """Finds the best copy and adds it to qBittorrent. Returns at once; the film's details arrive in the background.

        episode is (season, episode) for a TV episode, or None for a film.
        Returns (hash, label, phase) where phase is 'ready' or 'connecting'.
        """
        results = self.search(title, year, episode)
        choice = pick(results, title, year, episode)
        if choice is None:
            raise LookupError('no source')
        link = magnet_for(choice, title)
        if link is None:
            raise LookupError('no link')
        hash_ = hash_from_magnet(link)
        label = re.sub(r'[\._]+', ' ', choice.get('title') or title)[:120]
        with self.lock:
            if hash_ in self.known:
                self.known[hash_]['seen'] = time.time()
                return hash_, self.known[hash_]['label'], 'ready'
            if hash_ in self.pending:
                return hash_, self.pending[hash_]['label'], 'connecting'
            self.pending[hash_] = {'label': label, 'error': None, 'episode': episode}
        try:
            self.qbit('/api/v2/torrents/add', {
                'urls': link, 'savepath': self.to_qbit(self.cache), 'category': CATEGORY,
                'sequentialDownload': 'true', 'firstLastPiecePrio': 'true', 'paused': 'false'})
        except ServiceError:
            with self.lock:
                self.pending.pop(hash_, None)
            raise
        threading.Thread(target=self.resolve, args=(hash_,), daemon=True).start()
        return hash_, label, 'connecting'

    def resolve(self, hash_):
        """Background: waits for the torrent's metadata, so the film can be served. A failure is kept for /status."""
        try:
            self.await_film(hash_)
            with self.lock:
                self.pending.pop(hash_, None)
        except (LookupError, TimeoutError, ServiceError) as error:
            LOG.warning('a film did not start: %s', error or 'timed out')
            with self.lock:
                if hash_ in self.pending:
                    self.pending[hash_]['error'] = str(error) or 'timed out'
            try:
                self.qbit('/api/v2/torrents/delete', {'hashes': hash_, 'deleteFiles': 'true'})
            except ServiceError:
                pass

    def wait_for_entry(self, hash_):
        """The film's entry once its metadata has arrived; None if it never does or the start failed."""
        deadline = time.time() + START_TIMEOUT + 10
        while True:
            with self.lock:
                entry = self.known.get(hash_)
                pending = self.pending.get(hash_)
            if entry is not None:
                entry['seen'] = time.time()
                return entry
            if pending is None or pending['error'] or time.time() > deadline:
                return None
            time.sleep(0.25)

    def status(self, hash_):
        """What the player's loading screen shows: where the film is in starting up, and how much is ready."""
        with self.lock:
            entry = self.known.get(hash_)
            pending = self.pending.get(hash_)
        if entry is None:
            if pending is None:
                return None
            if pending['error']:
                return {'phase': 'failed', 'label': pending['label']}
            row = self.torrent(hash_) or {}
            return {'phase': 'connecting', 'label': pending['label'],
                    'seeds': row.get('num_seeds', 0), 'peers': row.get('num_leechs', 0),
                    'speed': row.get('dlspeed', 0)}
        row = self.torrent(hash_) or {}
        ready = self.downloaded_until(entry, 0) >= min(READY_BYTES, entry['size'])
        # speed (bytes/s) and size (bytes of the film file) feed the player's loading screen and Stream card.
        return {'phase': 'ready' if ready else 'buffering', 'label': entry['label'],
                'seeds': row.get('num_seeds', 0), 'peers': row.get('num_leechs', 0),
                'progress': round(row.get('progress', 0.0), 4),
                'speed': row.get('dlspeed', 0), 'size': entry['size']}

    def await_film(self, hash_):
        """Waits for metadata, then selects only the film file, so the download follows its pieces in order."""
        deadline = time.time() + START_TIMEOUT
        with self.lock:
            episode = (self.pending.get(hash_) or {}).get('episode')
        while time.time() < deadline:
            rows = self.qbit('/api/v2/torrents/info?category=' + CATEGORY) or []
            row = next((r for r in rows if hash_ and r.get('hash') == hash_), None)
            if row is None and not hash_ and rows:
                row = max(rows, key=lambda r: r.get('added_on', 0))
            if row and row.get('state') not in ('metaDL', 'checkingResumeData'):
                hash_ = row['hash']
                # Start it now: qBittorrent's active-download limit would otherwise queue the film behind the library's downloads.
                self.qbit('/api/v2/torrents/setForceStart', {'hashes': hash_, 'value': 'true'})
                files = self.qbit('/api/v2/torrents/files?hash=' + hash_) or []
                if not files:
                    # Right after the add, qBittorrent can report a state before it has the file list. Wait for it.
                    time.sleep(2)
                    continue
                chosen = video_file(files, episode)
                if chosen is None:
                    raise LookupError('no video file')
                index, name, size = chosen
                others = '|'.join(str(f['index']) for f in files if f['index'] != index)
                if others:
                    self.qbit('/api/v2/torrents/filePrio', {'hash': hash_, 'id': others, 'priority': 0})
                self.qbit('/api/v2/torrents/filePrio', {'hash': hash_, 'id': str(index), 'priority': 1})
                entry = self.entry_for(hash_, row['save_path'], files, chosen)
                self.known[hash_] = entry
                return hash_, entry
            time.sleep(2)
        raise TimeoutError('metadata')

    def sweep(self):
        """Removes torrents nobody has watched for a while, with their data."""
        now = time.time()
        with self.lock:
            stale = [h for h, e in self.known.items() if now - e['seen'] > IDLE_SECONDS]
            for hash_ in stale:
                try:
                    self.qbit('/api/v2/torrents/delete', {'hashes': hash_, 'deleteFiles': 'true'})
                except ServiceError:
                    continue
                self.known.pop(hash_, None)
                LOG.info('removed an idle stream')

    def file_for(self, hash_):
        with self.lock:
            entry = self.known.get(hash_)
            if entry is None:
                return None
            entry['seen'] = time.time()
            return entry


class Handler(BaseHTTPRequestHandler):
    server_version = 'MCM-Streams'
    sys_version = ''

    def setup(self):
        super().setup()
        self.connection.settimeout(STALL_SECONDS)

    def log_message(self, *_):
        pass  # No paths, cookies or indexer responses in logs.

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authenticate(self):
        """Same Seerr sign-in the request pipeline uses: only household members with a Seerr account can start a stream."""
        cookie = self.headers.get('Cookie', '')
        if len(cookie) > 8192 or not re.search(r'(?:^|;\s*)connect\.sid=', cookie):
            LOG.info('start rejected: no Seerr session cookie sent')
            self.reply(401, {'error': 'Sign in to Seerr'})
            return False
        try:
            request = urllib.request.Request(self.server.streams.seerr + '/api/v1/auth/me', headers={'Cookie': cookie, 'Accept': 'application/json'})
            with urllib.request.urlopen(request, timeout=10) as response:
                user = json.loads(response.read() or b'{}')
        except (urllib.error.URLError, ValueError, OSError):
            user = {}
        if not user.get('id'):
            self.reply(401, {'error': 'Sign in to Seerr'})
            return False
        return True

    def do_GET(self):
        if self.path == '/health':
            self.reply(200, {'ok': True})
        elif self.path.startswith('/stream/'):
            self.serve(self.path[len('/stream/'):].split('?')[0].lower())
        elif self.path.startswith('/status/'):
            self.report(self.path[len('/status/'):].split('?')[0].lower())
        else:
            self.reply(404, {'error': 'Not found'})

    def do_POST(self):
        if self.path != '/start':
            self.reply(404, {'error': 'Not found'})
            return
        if not self.authenticate():
            return
        title = year = ''
        try:
            length = min(int(self.headers.get('Content-Length', '0')), 4096)
            body = json.loads(self.rfile.read(length) or b'{}')
            title = str(body.get('title', '')).strip()[:200]
            year = str(body.get('year', '')).strip()[:4]
            if not title:
                self.reply(400, {'error': 'Title missing'})
                return
            season, episode = body.get('season'), body.get('episode')
            episode_key = None
            if season is not None or episode is not None:
                if not (isinstance(season, int) and isinstance(episode, int) and 0 < season < 100 and 0 < episode < 1000):
                    self.reply(400, {'error': 'Season and episode must be numbers'})
                    return
                episode_key = (season, episode)
            started = time.time()
            hash_, label, phase = self.server.streams.start(title, year if year.isdigit() else None, episode_key)
            LOG.info('start %s: %r %s %s in %.1fs as %s', phase, title, year, episode_key or '', time.time() - started, label)
            self.reply(200, {'path': '/stream/' + hash_, 'label': label, 'phase': phase})
        except LookupError as error:
            LOG.info('start: no acceptable source for %r %s %s (%s)', title, year, episode_key or '', error)
            code = 404 if str(error) in ('no source', 'no video file') else 422
            self.reply(code, {'error': 'No playable stream found'})
        except (ServiceError, ValueError) as error:
            LOG.warning('start failed for %r: %s', title, error)
            self.reply(503, {'error': str(error) if isinstance(error, ServiceError) else 'Bad request'})

    def report(self, hash_):
        """Progress for the player's loading screen. Holds no secrets: the same hash the stream itself needs."""
        if not re.fullmatch(r'[0-9a-f]{40}', hash_):
            self.reply(404, {'error': 'Not found'})
            return
        try:
            info = self.server.streams.status(hash_)
        except ServiceError:
            self.reply(503, {'phase': 'unavailable'})
            return
        if info is None:
            self.reply(404, {'error': 'Not found'})
        else:
            self.reply(200, info)

    def serve(self, hash_):
        if not re.fullmatch(r'[0-9a-f]{40}', hash_):
            self.reply(404, {'error': 'Not found'})
            return
        entry = self.server.streams.wait_for_entry(hash_)
        if entry is None:
            self.reply(404, {'error': 'Not found'})
            return
        if entry['transcode']:
            self.serve_transcoded(entry)
            return
        total = entry['size']
        window = parse_range(self.headers.get('Range'), total)
        if window is None:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{total}')
            self.end_headers()
            return
        start, end = window
        partial = bool(self.headers.get('Range'))
        self.send_response(206 if partial else 200)
        self.send_header('Content-Type', 'video/x-matroska' if entry['path'].lower().endswith('.mkv') else 'video/mp4')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if partial:
            self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            self.copy(entry, start, end)
        except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
            pass  # The viewer left, or the stream stalled; nothing to tell them.

    def serve_transcoded(self, entry):
        """Serves the film as Matroska with AAC audio, for a TV that cannot decode the original soundtrack.

        ffmpeg reads from a pipe that this method fills with finished pieces only, and writes to the response.
        The result cannot be seeked, so it is served whole; the player can still play straight through.
        """
        command = [FFMPEG, '-hide_banner', '-loglevel', 'error', '-i', 'pipe:0',
                   '-map', '0:v:0', '-map', '0:a:0?', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-ac', '2',
                   '-f', 'matroska', 'pipe:1']
        try:
            ffmpeg = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError:
            self.reply(503, {'error': 'Audio conversion is unavailable'})
            return
        self.send_response(200)
        self.send_header('Content-Type', 'video/x-matroska')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        feeder = threading.Thread(target=self.feed, args=(ffmpeg, entry), daemon=True)
        feeder.start()
        try:
            while True:
                chunk = ffmpeg.stdout.read(COPY_CHUNK_BYTES)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass  # The viewer left; stop converting.
        finally:
            ffmpeg.kill()
            ffmpeg.wait()

    def feed(self, ffmpeg, entry):
        """Writes the film to ffmpeg in order, but only the parts whose pieces have arrived."""
        streams = self.server.streams
        position, last_growth = 0, time.time()
        try:
            with Path(entry['path']).open('rb') as handle:
                while position < entry['size']:
                    try:
                        available = streams.downloaded_until(entry, position) - position
                    except ServiceError:
                        available = 0
                    if available <= 0:
                        if time.time() - last_growth > STALL_SECONDS:
                            break
                        time.sleep(QUICK_POLL_SECONDS)
                        continue
                    handle.seek(position)
                    chunk = handle.read(min(available, COPY_CHUNK_BYTES))
                    ffmpeg.stdin.write(chunk)
                    position += len(chunk)
                    last_growth = time.time()
        except (BrokenPipeError, OSError, ValueError):
            pass  # ffmpeg stopped reading; the viewer has gone.
        finally:
            try:
                ffmpeg.stdin.close()
            except OSError:
                pass

    def copy(self, entry, start, end):
        """Copies bytes as qBittorrent writes them, waiting for each piece instead of ending early.

        Waits poll every QUICK_POLL_SECONDS, so a piece that lands is sent almost at once rather than up to a second later.
        """
        path = Path(entry['path'])
        position, last_growth = start, time.time()
        handle = None
        streams = self.server.streams
        while position <= end:
            if handle is None:
                if not path.exists():
                    if time.time() - last_growth > STALL_SECONDS:
                        return
                    time.sleep(QUICK_POLL_SECONDS)
                    continue
                handle = path.open('rb')
            handle.seek(position)
            try:
                # Only finished pieces may be sent. Anything past them is still zeros on disk.
                available = min(streams.downloaded_until(entry, position), end + 1) - position
            except ServiceError:
                available = 0
            if available <= 0:
                if time.time() - last_growth > STALL_SECONDS:
                    return
                time.sleep(QUICK_POLL_SECONDS)
                continue
            chunk = handle.read(min(available, end - position + 1, COPY_CHUNK_BYTES))
            if not chunk:
                time.sleep(QUICK_POLL_SECONDS)
                continue
            self.wfile.write(chunk)
            position += len(chunk)
            last_growth = time.time()
        if handle is not None:
            handle.close()


class LimitedServer(ThreadingHTTPServer):
    daemon_threads = True


def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    streams = Streams()

    def adopt_existing():
        # Off the main thread, so a slow qBittorrent never delays requests from the TV.
        try:
            streams.restore()
        except ServiceError:
            LOG.warning('qBittorrent unavailable at startup; earlier stream torrents will not be swept')

    threading.Thread(target=adopt_existing, daemon=True).start()

    def housekeeping():
        while True:
            time.sleep(600)
            streams.sweep()

    threading.Thread(target=housekeeping, daemon=True).start()
    server = LimitedServer(('0.0.0.0', PORT), Handler)
    server.streams = streams
    server.serve_forever()


if __name__ == '__main__':
    main()
