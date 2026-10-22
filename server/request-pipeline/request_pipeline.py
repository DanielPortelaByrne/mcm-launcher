#!/usr/bin/env python3
"""Reconcile explicit Seerr requests; expose their real search state to the TV.

No API keys leave this process. User API calls authenticate with the existing
Seerr session, and manual retries are limited to requests owned by that user.
"""
import collections
import datetime as dt
import http.cookiejar
import json
import logging
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger('request-pipeline')
STACK = Path(os.environ.get('MEDIA_STACK', '/home/deck/.local/share/media-stack'))
ACTIVE = {'queued', 'started'}
BACKOFF = (900, 3600, 21600, 86400)
SOURCE_GRACE = 1800
MIN_PROGRESS_RATE = 64 * 1024
QUEUE_REJECTIONS = (
    'Release in queue already meets cutoff:', 'Quality for release in queue already meets cutoff:',
    'Release in queue is of equal or higher preference:', 'Release in queue is of equal or higher revision:',
    'Release in queue meets quality cutoff:', 'Release in queue meets Custom Format cutoff:',
    'Release in queue has an equal or higher Custom Format score:',
    'Release in queue has Custom Format score within Custom Format score increment:',
    'Release in queue and Quality Profile ',
)


def timestamp(value):
    try:
        return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (ValueError, TypeError, AttributeError):
        return 0


def request_key(request, season=None):
    return str(request['id']) + (':' + str(season) if season is not None else '')


def retry_delay(attempt):
    return BACKOFF[min(max(attempt - 1, 0), len(BACKOFF) - 1)]


def archival_profile(record, profiles):
    """Only adjust our default policy, preserving any deliberately custom profile."""
    standard = next((p for p in profiles if p['name'] == 'MCM 1080p preferred - 720p fallback'), None)
    archive = next((p for p in profiles if p['name'] == 'MCM 1080p preferred - older TV fallback'), None)
    if standard and archive and 0 < (record.get('year') or 9999) < 2005 and record.get('qualityProfileId') == standard['id']:
        return archive['id']
    return None


def poor_sources(torrent):
    """Only active, incomplete downloads with no complete connected source."""
    return bool(torrent and torrent.get('state') in ('metaDL', 'stalledDL', 'downloading')
        and not torrent.get('force_start') and 0 <= torrent.get('progress', 1) < 1
        and torrent.get('num_seeds', 1) == 0 and 0 <= torrent.get('availability', 1) < 1
        and 0 <= torrent.get('dlspeed', MIN_PROGRESS_RATE) < MIN_PROGRESS_RATE)


def release_name(value):
    return re.sub(r'[^a-z0-9]', '', (value or '').lower())


def recovery_releases(rows, app, season, selected, covered, excluded):
    """Honor every rejection except the old download already occupying the queue.

    No forced overrides, multi-season packs, unrequested episodes or owned files.
    Within the user's resolution policy, exact seed counts precede source/repack
    labels and size. Reported seeds remain a hint, verified by an actual trial.
    """
    result = []
    for row in rows:
        reasons = row.get('rejections', [])
        if any(not reason.startswith(QUEUE_REJECTIONS) for reason in reasons):
            continue
        if row.get('rejected') and not reasons:
            continue
        if (row.get('seeders') or 0) < 1 or release_name(row.get('title')) in excluded:
            continue
        quality = (row.get('quality') or {}).get('quality') or {}
        if not 0 < quality.get('resolution', 0) <= 1080:
            continue
        if app == 'sonarr':
            if row.get('seasonNumber') != season:
                continue
            if row.get('fullSeason'):
                if not selected or any(not e.get('monitored') or e.get('hasFile') or e['id'] not in covered for e in selected):
                    continue
            else:
                numbers = set(row.get('episodeNumbers') or [])
                allowed = {e.get('episodeNumber') for e in selected if e['id'] in covered and e.get('monitored') and not e.get('hasFile')}
                if not numbers or not numbers <= allowed:
                    continue
        result.append(row)
    def rank(row):
        resolution = row['quality']['quality']['resolution']
        tier = 3 if resolution >= 1080 else 2 if resolution >= 720 else 1
        return (-tier, -(row.get('seeders') or 0), -(row.get('customFormatScore') or 0), row.get('size') or 0)
    return sorted(result, key=rank)


def episode_plan(episodes, queued, season):
    """Unmonitoring is cancellation. Never remonitor or re-fetch an owned file."""
    selected = [e for e in episodes if e.get('seasonNumber') == season]
    monitored = [e for e in selected if e.get('monitored')]
    queued_ids = {q.get('episodeId') for q in queued}
    pack_queued = any(q.get('seasonNumber') == season and not q.get('episodeId') for q in queued)
    missing = [e['id'] for e in monitored if not e.get('hasFile') and e['id'] not in queued_ids and not pack_queued]
    return selected, monitored, missing


def same_command(command, app, record_id, season, missing):
    if command.get('status') not in ACTIVE:
        return False
    body = command.get('body') or {}
    name = command.get('name') or body.get('name')
    if app == 'radarr':
        return name == 'MoviesSearch' and record_id in body.get('movieIds', [])
    if name == 'SeasonSearch':
        return body.get('seriesId') == record_id and body.get('seasonNumber') == season
    if name == 'SeriesSearch':
        return body.get('seriesId') == record_id
    return name == 'EpisodeSearch' and bool(set(body.get('episodeIds') or []) & set(missing))


class ServiceError(Exception):
    pass


class Pipeline:
    def __init__(self, stack=STACK):
        self.stack = stack
        self.home = stack / 'request-pipeline'
        self.home.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.state_path = self.home / 'state.json'
        try:
            self.state = json.loads(self.state_path.read_text())
        except (OSError, ValueError):
            self.state = {}
        self.lock = threading.RLock()
        self.wake = threading.Event()
        self.manual = set()
        self.last_manual = {}
        self.healthy = False
        self.last_poll = 0
        self.scan_pending = False
        self.last_scan = 0
        self.qbit_lock = threading.RLock()
        self.qbit_jar = http.cookiejar.CookieJar()
        self.qbit_client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.qbit_jar))
        self.qbit_retry_at = 0
        self.torrents = {}

    def api(self, app, path, method='GET', data=None, cookie=None, timeout=15):
        port = {'seerr': 5055, 'sonarr': 8989, 'radarr': 7878}[app]
        headers = {'Accept': 'application/json', 'Content-Type': 'application/json'}
        if cookie is not None:
            headers['Cookie'] = cookie
        elif app == 'seerr':
            settings = json.loads((self.stack / 'seerr/settings.json').read_text())
            headers['X-Api-Key'] = settings['main']['apiKey']
        else:
            headers['X-Api-Key'] = ET.parse(self.stack / app / 'config.xml').findtext('ApiKey')
        req = urllib.request.Request('http://127.0.0.1:' + str(port) + path,
            data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except (urllib.error.URLError, ValueError, OSError) as error:
            # Never log response bodies, URLs with credentials, or session cookies.
            raise ServiceError(app + ' HTTP ' + str(getattr(error, 'code', 'unreachable'))) from None

    def requests(self, cookie=None):
        result = []
        for skip in range(0, 10000, 100):
            page = self.api('seerr', '/api/v1/request?take=100&skip=' + str(skip) + '&sort=added', cookie=cookie)
            rows = page.get('results', [])
            result.extend(rows)
            if len(rows) < 100:
                break
        return result

    def qbit(self, path, data=None):
        """Use the existing download-client secret only here, on the server."""
        base = 'http://127.0.0.1:8090'
        def send(endpoint, form=None):
            request = urllib.request.Request(base + endpoint,
                data=urllib.parse.urlencode(form).encode() if form is not None else None,
                headers={'Referer': base + '/'})
            return self.qbit_client.open(request, timeout=10)
        with self.qbit_lock:
            try:
                with send(path, data) as result:
                    raw = result.read()
                    return result.status, json.loads(raw) if raw[:1] in (b'[', b'{') else None
            except urllib.error.HTTPError as error:
                if error.code not in (401, 403):
                    return error.code, None
            except (urllib.error.URLError, ValueError, OSError):
                raise ServiceError('qBittorrent unavailable') from None
            if time.time() < self.qbit_retry_at:
                raise ServiceError('qBittorrent authentication unavailable')
            try:
                with sqlite3.connect('file:' + str(self.stack / 'sonarr/sonarr.db') + '?mode=ro', uri=True) as db:
                    row = db.execute("SELECT Settings FROM DownloadClients WHERE Implementation='QBittorrent' AND Enable=1 LIMIT 1").fetchone()
                if not row:
                    raise ServiceError('qBittorrent not configured')
                config = json.loads(row[0])
                with send('/api/v2/auth/login', {'username': config['username'], 'password': config['password']}) as response:
                    response.read()
                # A real cookie jar supports legacy SID and qBittorrent 5.2 QBT_SID_<port>.
                if not any(c.name == 'SID' or re.fullmatch(r'QBT_SID_[0-9]+', c.name) for c in self.qbit_jar):
                    raise ServiceError('qBittorrent did not issue a session')
                with send(path, data) as result:
                    raw = result.read()
                    return result.status, json.loads(raw) if raw[:1] in (b'[', b'{') else None
            except Exception:
                self.qbit_retry_at = time.time() + 60
                raise ServiceError('qBittorrent authentication unavailable') from None

    def clear_stale_media(self, kind, tmdb_id):
        """Explicit admin repair of imported monitoring with no request or files.

        Clearing Seerr alone is temporary: its next Arr scan imports monitored
        seasons again. Stop that empty entry's monitoring before clearing only
        the Seerr record. Never remove an Arr entry, torrent or media file.
        """
        app, resource = ('sonarr', 'series') if kind == 'tv' else ('radarr', 'movie')
        media_path = '/api/v1/' + kind + '/' + str(tmdb_id)

        def candidate():
            info = self.api('seerr', media_path).get('mediaInfo') or {}
            if (not info.get('id') or info.get('status') not in (2, 3)
                    or info.get('status4k', 1) != 1 or info.get('requests') != []
                    or info.get('downloadStatus') != [] or info.get('downloadStatus4k', []) != []):
                return None
            if any(s.get('status') not in (1, 2, 3) or s.get('status4k', 1) != 1 for s in info.get('seasons', [])):
                return None
            # Check every user's requests, not the caller's filtered list.
            if any(r.get('type') == kind and r.get('media', {}).get('tmdbId') == tmdb_id for r in self.requests()):
                return None
            return info

        info = candidate()
        if info is None:
            return False
        records = self.api(app, '/api/v3/' + resource)
        matches = [r for r in records if r.get('tmdbId') == tmdb_id]
        if len(matches) > 1:
            return False
        record = matches[0] if matches else None

        def idle():
            if record:
                current = self.api(app, '/api/v3/' + resource + '/' + str(record['id']))
                if current.get('tmdbId') != tmdb_id:
                    return False
                if kind == 'tv':
                    stats = current.get('statistics') or {}
                    if stats.get('episodeFileCount', 0) or stats.get('sizeOnDisk', 0):
                        return False
                    episodes = self.api(app, '/api/v3/episode?seriesId=' + str(record['id']))
                    if any(e.get('hasFile') or e.get('episodeFileId', 0) or e.get('monitored') for e in episodes):
                        return False
                    episode_ids = {e['id'] for e in episodes}
                else:
                    if current.get('hasFile') or current.get('movieFileId', 0) or current.get('movieFile'):
                        return False
                    episode_ids = set()
                for command in self.api(app, '/api/v3/command'):
                    if command.get('status') not in ACTIVE:
                        continue
                    body = command.get('body') or {}
                    if (body.get('seriesId') == record['id'] or record['id'] in body.get('movieIds', [])
                            or episode_ids.intersection(body.get('episodeIds') or [])):
                        return False
            for page in range(1, 101):
                queue = self.api(app, '/api/v3/queue?pageSize=200&page=' + str(page) + '&includeSeries=true&includeMovie=true')
                rows = queue['records']  # Missing/malformed status must fail closed.
                if any((record and q.get('seriesId' if kind == 'tv' else 'movieId') == record['id'])
                        or (q.get(resource) or {}).get('tmdbId') == tmdb_id
                        or (q.get('series' if kind == 'tv' else 'movie') or {}).get('tmdbId') == tmdb_id for q in rows):
                    return False
                if page * 200 >= queue['totalRecords']:
                    break
            else:
                return False
            code, torrents = self.qbit('/api/v2/torrents/info')
            if code != 200 or not isinstance(torrents, list):
                raise ServiceError('qBittorrent status unavailable')
            title = release_name((record or {}).get('title'))
            if not title:
                detail = self.api('seerr', media_path)
                title = release_name(detail.get('name') or detail.get('title'))
            # Also protect an orphaned client download no longer in Arr's queue.
            return bool(title) and not any(title in release_name(t.get('name')) for t in torrents)

        if not idle():
            return False
        # Keep a private rollback snapshot before changing monitoring.
        backup = self.home / ('stale-' + kind + '-' + str(tmdb_id) + '-' + str(time.time_ns()) + '.json')
        with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            json.dump({'media': info, 'record': record}, stream)
        if record:
            record = self.api(app, '/api/v3/' + resource + '/' + str(record['id']))
            if kind == 'tv':
                # Already-monitored episodes may represent work outside Seerr.
                # Such entries require normal cancellation, not this stale repair.
                episodes = self.api(app, '/api/v3/episode?seriesId=' + str(record['id']))
                if any(e.get('monitored') for e in episodes):
                    return False
                for season in record['seasons']:
                    season['monitored'] = False
            record['monitored'] = False
            fresh = candidate()
            if fresh is None or fresh['id'] != info['id'] or not idle():
                return False
            self.api(app, '/api/v3/' + resource + '/' + str(record['id']), 'PUT', record)
        fresh = candidate()
        if fresh is None or fresh['id'] != info['id'] or not idle():
            return False
        self.api('seerr', '/api/v1/media/' + str(info['id']), 'DELETE')
        return True

    def allowed_hashes(self, cookie, user):
        admin = bool(int(user.get('permissions', 0)) & 2)  # Seerr Permission.ADMIN
        owned = [] if admin else [r for r in self.requests(cookie=cookie) if r.get('requestedBy', {}).get('id') == user['id']]
        hashes = set()
        for app,kind in [('sonarr','tv'),('radarr','movie')]:
            for page in range(1, 101):
                queue = self.api(app, '/api/v3/queue?pageSize=200&page=' + str(page) + '&includeSeries=true&includeMovie=true&includeEpisode=true')
                rows = queue.get('records', [])
                for row in rows:
                    record = row.get('series' if kind == 'tv' else 'movie') or {}
                    season = (row.get('episode') or {}).get('seasonNumber', row.get('seasonNumber'))
                    permitted = admin or any(r['type'] == kind and r.get('media', {}).get('tmdbId') == record.get('tmdbId') and (kind == 'movie' or season in [s['seasonNumber'] for s in r.get('seasons', [])]) for r in owned)
                    value = row.get('downloadId') or ''
                    if permitted and re.fullmatch(r'(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})', value):
                        hashes.add(value.lower())
                if page * 200 >= queue.get('totalRecords', 0) or not rows:
                    break
        return hashes

    def torrent_states(self, allowed):
        code, rows = self.qbit('/api/v2/torrents/info')
        if code != 200 or not isinstance(rows, list):
            raise ServiceError('qBittorrent status unavailable')
        fields = ('hash','state','priority','force_start','progress','dlspeed','num_seeds','availability','size','amount_left','eta')
        return [{k:t.get(k) for k in fields} for t in rows if t.get('hash','').lower() in allowed]

    def prioritize(self, value, action):
        code, rows = self.qbit('/api/v2/torrents/info?hashes=' + value)
        torrent = next((r for r in (rows or []) if r.get('hash','').lower() == value), None)
        if code != 200:
            return 'FAILED'
        if not torrent or torrent.get('progress', 0) >= 1:
            return 'NOT_FOUND'
        if action == 'now':
            code,_ = self.qbit('/api/v2/torrents/setForceStart', {'hashes':value,'value':'true'})
            return 'DONE' if 200 <= code < 300 else 'FAILED'
        code,_ = self.qbit('/api/v2/torrents/topPrio', {'hashes':value})
        if code == 409:
            return 'QUEUE_DISABLED'
        if not 200 <= code < 300:
            return 'FAILED'
        if torrent.get('force_start'):
            code,_ = self.qbit('/api/v2/torrents/setForceStart', {'hashes':value,'value':'false'})
            if not 200 <= code < 300:
                return 'FAILED'
        code,_ = self.qbit('/api/v2/torrents/start', {'hashes':value})
        if code in (404,405):
            code,_ = self.qbit('/api/v2/torrents/resume', {'hashes':value})
        return 'DONE' if 200 <= code < 300 else 'FAILED'

    def persist(self):
        with self.lock:
            temporary = self.home / 'state.tmp'
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'w') as stream:
                json.dump(self.state, stream)
            temporary.replace(self.state_path)

    def status(self, key, **values):
        with self.lock:
            self.state.setdefault(key, {}).update(values)
            return dict(self.state[key])

    def reconcile(self):
        requests = self.requests()
        approved = [r for r in requests if r.get('status') == 2 and r.get('media', {}).get('status') != 5]
        used_keys = set()
        try:
            code, torrents = self.qbit('/api/v2/torrents/info')
            self.torrents = {t['hash'].lower():t for t in torrents} if code == 200 and isinstance(torrents,list) else {}
        except ServiceError:
            self.torrents = {}  # Search reconciliation still works without downloader diagnostics.
        # No backlog scan: only media explicitly requested through Seerr is searched.
        for app, kind in [('sonarr', 'tv'), ('radarr', 'movie')]:
            selected = [r for r in approved if r.get('type') == kind and not r.get('is4k')]
            if not selected:
                continue
            records = self.api(app, '/api/v3/' + ('series' if kind == 'tv' else 'movie'))
            records = {r.get('tmdbId'): r for r in records}
            queue = []
            for page in range(1, 101):
                result = self.api(app, '/api/v3/queue?pageSize=200&page=' + str(page) + '&includeUnknownSeriesItems=false&includeUnknownMovieItems=false')
                queue.extend(result.get('records', []))
                if len(queue) >= result.get('totalRecords', 0) or not result.get('records'):
                    break
            commands = self.api(app, '/api/v3/command')
            profiles = self.api(app, '/api/v3/qualityprofile') if kind == 'tv' else []
            episode_cache = {}
            for request in selected:
                record = records.get(request.get('media', {}).get('tmdbId'))
                if record and kind == 'tv':
                    profile = archival_profile(record, profiles)
                    if profile is not None:
                        record['qualityProfileId'] = profile
                        self.api(app, '/api/v3/series/' + str(record['id']), 'PUT', record)
                seasons = [s['seasonNumber'] for s in request.get('seasons', [])] if kind == 'tv' else [None]
                for season in seasons:
                    key = request_key(request, season)
                    used_keys.add(key)
                    if not record:
                        self.status(key, state='handoff_failed', message='Request approved, but missing from ' + app.title(), nextSearchAt=None)
                        continue
                    if kind == 'tv' and record['id'] not in episode_cache:
                        episode_cache[record['id']] = self.api(app, '/api/v3/episode?seriesId=' + str(record['id']))
                    relevant = [q for q in queue if q.get('seriesId' if kind == 'tv' else 'movieId') == record['id']]
                    self.reconcile_task(request, record, season, relevant, episode_cache.get(record['id'], []), commands)
        # Deleted/completed requests cannot be resurrected by retained retry state.
        with self.lock:
            self.state = {k: v for k, v in self.state.items() if k in used_keys}
            self.manual.intersection_update({str(r['id']) for r in approved})
            self.last_poll = time.time()
            self.healthy = True
        if self.scan_pending and time.time() - self.last_scan >= 60:
            # Coalesce completed episode imports into one lightweight availability scan.
            self.api('seerr', '/api/v1/settings/jobs/jellyfin-recently-added-scan/run', 'POST', {})
            self.last_scan = time.time()
            self.scan_pending = False
        self.persist()

    def reconcile_task(self, request, record, season, queue, episodes, commands):
        now = time.time()
        key = request_key(request, season)
        app = 'sonarr' if request['type'] == 'tv' else 'radarr'
        if app == 'sonarr':
            selected, monitored, missing = episode_plan(episodes, queue, season)
            matching_queue = [q for q in queue if q.get('seasonNumber') == season or any(e['id'] == q.get('episodeId') for e in selected)]
            if not selected:
                self.status(key, state='metadata', message='Waiting for episode details')
                return
            if not monitored:
                self.status(key, state='cancelled', message='No episodes are monitored; request is stopped', nextSearchAt=None)
                return
        else:
            selected, monitored, matching_queue = [], [], queue
            missing = [] if record.get('hasFile') or queue else [record['id']]
            if not record.get('monitored'):
                self.status(key, state='cancelled', message='Request is stopped', nextSearchAt=None)
                return
            if not record.get('isAvailable', True):
                self.status(key, state='unreleased', message='Waiting for the release date', nextSearchAt=None)
                return
        removed = self.recover_sources(key, app, record, season, matching_queue, selected)
        if removed:
            matching_queue = [q for q in matching_queue if (q.get('downloadId') or '').lower() not in removed]
            if app == 'sonarr':
                missing = episode_plan(episodes, matching_queue, season)[2]
            else:
                missing = [] if record.get('hasFile') or matching_queue else [record['id']]
        if not missing:
            if matching_queue:
                message = self.status(key).get('recoveryMessage') or 'Release sent to the download client'
                self.status(key, state='in_download_client', message=message, nextSearchAt=None)
            else:
                old = self.status(key)
                if old.get('state') != 'downloaded' or not old.get('syncRequestedAt'):
                    self.scan_pending = True
                    self.status(key, syncRequestedAt=now)
                self.status(key, state='downloaded', message='Downloaded; waiting for Jellyfin to scan', nextSearchAt=None)
            return
        old = self.status(key)
        active = next((c for c in commands if same_command(c, app, record['id'], season, missing)), None)
        if active:
            self.status(key, state='searching', message='Searching indexers now', commandId=active['id'], lastSearchAt=old.get('lastSearchAt') or now, finishedAt=None)
            return
        previous = next((c for c in commands if c['id'] == old.get('commandId')), None)
        if old.get('state') == 'searching':
            # Allow the download-client queue to catch up after command completion.
            finished = old.get('finishedAt') or now
            if now - finished < 30:
                self.status(key, finishedAt=finished)
                return
            failed = previous is not None and previous.get('status') == 'failed'
            attempts = old.get('attempts', 0) + 1
            self.status(key, state='search_failed' if failed else 'no_release',
                message='Search failed; retry scheduled' if failed else 'No acceptable release found; retry scheduled',
                attempts=attempts, nextSearchAt=now + retry_delay(attempts), finishedAt=None)
            old = self.status(key)
        with self.lock:
            manual = str(request['id']) in self.manual
        if not manual and old.get('nextSearchAt', 0) and now < old['nextSearchAt']:
            return
        # Let Seerr's immediate search run first; repair a missed handoff after 90s.
        if not manual and not old.get('lastSearchAt') and now - timestamp(request.get('createdAt')) < 90:
            self.status(key, state='requested', message='Request accepted; waiting for the initial search')
            return
        if old.get('lastSearchAt', 0) > now - 60:
            return
        if app == 'sonarr':
            if not matching_queue and not any(e.get('hasFile') for e in selected):
                body = dict(name='SeasonSearch', seriesId=record['id'], seasonNumber=season)
            else:
                body = dict(name='EpisodeSearch', episodeIds=missing)
        else:
            body = dict(name='MoviesSearch', movieIds=[record['id']])
        command = self.api(app, '/api/v3/command', 'POST', body)
        commands.append(command)
        self.status(key, state='searching', message='Searching indexers now', commandId=command['id'], lastSearchAt=now, nextSearchAt=None, finishedAt=None)
        with self.lock:
            self.manual.discard(str(request['id']))
        LOG.info('Search submitted: request=%s season=%s command=%s', request['id'], season, command['id'])

    def recovery_remove(self, app, item, blocklist=True):
        # Prevent native automatic re-search racing our verified alternative.
        self.api(app, '/api/v3/queue/' + str(item['id'])
            + '?removeFromClient=true&blocklist=' + str(blocklist).lower() + '&skipRedownload=true', 'DELETE')

    def recover_sources(self, key, app, record, season, queue, selected, allow_early=False):
        now = time.time()
        old = self.status(key)
        self.status(key, recoveryMessage=None)
        by_hash = {(q.get('downloadId') or '').lower(): q for q in queue if q.get('downloadId')}
        samples = {}
        for value in by_hash:
            torrent = self.torrents.get(value)
            if not poor_sources(torrent):
                continue
            previous = old.get('sourceHealth', {}).get(value)
            # Poll outages/paused periods must not count as actively stalled time.
            if not previous or now - previous.get('seen', 0) > 120:
                previous = dict(since=now, completed=torrent.get('completed', 0))
            elapsed = now - previous['since']
            if elapsed > 0 and (torrent.get('completed', 0) - previous['completed']) / elapsed >= MIN_PROGRESS_RATE:
                previous = dict(since=now, completed=torrent.get('completed', 0))
            samples[value] = dict(previous, seen=now)
        failed = {name: at for name, at in old.get('failedReleases', {}).items() if now - at < 7 * 86400}
        self.status(key, sourceHealth=samples, failedReleases=failed, emptySourcesSince={})
        trial = old.get('recoveryTrial')
        if trial:
            return self.check_trial(key, app, queue, trial, failed, now)
        window = old.get('replacementWindowAt', 0)
        attempts = old.get('replacementAttempts', 0) if now - window < 86400 else 0
        if attempts >= 2 or now < old.get('nextRecoverySearchAt', 0):
            return set()
        # allow_early is for an explicitly requested operator trial; normal worker
        # polls always observe the full grace period and all safety checks.
        due = [value for value, sample in samples.items() if allow_early or now - sample['since'] >= SOURCE_GRACE]
        if not due:
            return set()
        value = due[0]
        item = by_hash[value]
        covered = {q.get('episodeId') for q in queue if (q.get('downloadId') or '').lower() == value}
        if app == 'sonarr' and not any(covered):
            covered = {e['id'] for e in selected}  # Native season-level queue item.
        query = '?movieId=' + str(record['id']) if app == 'radarr' else (
            '?seriesId=' + str(record['id']) + '&seasonNumber=' + str(season)
            if covered == {e['id'] for e in selected} else '?episodeId=' + str(next(iter(covered))))
        self.status(key, nextRecoverySearchAt=now + 900,
            recoveryMessage='Sources are incomplete; checking alternative releases')
        try:
            rows = self.api(app, '/api/v3/release' + query, timeout=90)
        except ServiceError:
            self.status(key, recoveryMessage='Alternative search unavailable; keeping current download and retrying')
            return set()
        excluded = set(failed) | {release_name(q.get('title')) for q in queue}
        excluded.update(release_name(self.torrents[h].get('name')) for h in by_hash if h in self.torrents)
        candidates = recovery_releases(rows, app, season, selected, covered, excluded)
        if not candidates:
            self.status(key, recoveryMessage='Sources are incomplete; no acceptable alternative found; retry scheduled')
            return set()
        # The old torrent may have recovered during the indexer search.
        code, fresh = self.qbit('/api/v2/torrents/info?hashes=' + value)
        current = next((t for t in fresh or [] if t.get('hash', '').lower() == value), None)
        if code != 200 or not poor_sources(current):
            return set()
        # Recheck cancellation/imports before starting another release.
        if app == 'sonarr':
            episodes = self.api(app, '/api/v3/episode?seriesId=' + str(record['id']))
            owned = {e['id'] for e in episodes if e.get('hasFile') or not e.get('monitored')}
            if covered & owned:
                return set()
        else:
            movie = self.api(app, '/api/v3/movie/' + str(record['id']))
            if movie.get('hasFile') or not movie.get('monitored'):
                return set()
        candidate = candidates[0]
        trial = dict(original=value, title=candidate['title'], startedAt=time.time(),
            knownHashes=list(self.torrents), healthySince=None)
        # Persist intent before POST: a restart/timeout cannot create repeated trials.
        self.status(key, recoveryTrial=trial, replacementAttempts=attempts + 1,
            replacementWindowAt=window if now - window < 86400 else now)
        self.persist()
        try:
            self.api(app, '/api/v3/release', 'POST', dict(guid=candidate['guid'], indexerId=candidate['indexerId']))
        except ServiceError:
            # An HTTP timeout may have succeeded upstream; resolve it from the queue.
            self.status(key, recoveryMessage='Checking whether the alternative reached the download client')
            return set()
        self.status(key, recoveryMessage='Trying an alternative release; current partial download is preserved')
        LOG.info('Alternative trial submitted for task=%s', key)
        return set()

    def check_trial(self, key, app, queue, trial, failed, now):
        original = trial['original']
        item = next((q for q in queue if release_name(q.get('title')) == release_name(trial['title'])
            and (q.get('downloadId') or '').lower() != original), None)
        value = (item.get('downloadId') or '').lower() if item else trial.get('hash')
        torrent = self.torrents.get(value)
        if not torrent:
            if now - trial['startedAt'] >= 300:
                failed[release_name(trial['title'])] = now
                self.status(key, recoveryTrial=None, failedReleases=failed,
                    recoveryMessage='Alternative did not reach the client; current download preserved')
            return set()
        trial['hash'] = value
        # Do not remove a torrent that already existed before this trial.
        if value in trial['knownHashes']:
            failed[release_name(trial['title'])] = now
            self.status(key, recoveryTrial=None, failedReleases=failed)
            return set()
        if torrent.get('state') == 'queuedDL' and not torrent.get('force_start') and not trial.get('queuedPrioritized'):
            # Give this bounded recovery trial the next normal download slot.
            # Never force-start it, resume a paused item, or pause other torrents.
            code, _ = self.qbit('/api/v2/torrents/topPrio', {'hashes': value})
            trial['queuedPrioritized'] = 200 <= code < 300
        complete = torrent.get('progress', 0) >= 1
        protected = torrent.get('force_start') or torrent.get('state') not in ('metaDL', 'stalledDL', 'downloading')
        if protected and not complete:
            trial.pop('poorSince', None)
            trial['healthySince'] = None
            self.status(key, recoveryTrial=trial, recoveryMessage='Alternative queued or paused; current download preserved')
            return set()
        healthy = (torrent.get('num_seeds', 0) > 0 and torrent.get('availability', 0) >= 1
            and torrent.get('dlspeed', 0) >= 128 * 1024)
        previous = trial.get('healthySince') if now - trial.get('seenAt', 0) <= 120 else None
        trial['healthySince'] = (previous or now) if healthy else None
        if complete or (trial['healthySince'] and now - trial['healthySince'] >= 120):
            old_item = next((q for q in queue if (q.get('downloadId') or '').lower() == original), None)
            code, fresh = self.qbit('/api/v2/torrents/info?hashes=' + original)
            old_torrent = next((t for t in fresh or [] if t.get('hash', '').lower() == original), None)
            redundant = bool(complete and old_torrent and old_torrent.get('state') in ('metaDL', 'stalledDL', 'downloading')
                and not old_torrent.get('force_start') and old_torrent.get('progress', 1) < 1)
            if old_item and code == 200 and (poor_sources(old_torrent) or redundant):
                # Recheck the trial too, so a single old snapshot cannot discard data.
                code, fresh = self.qbit('/api/v2/torrents/info?hashes=' + value)
                new = next((t for t in fresh or [] if t.get('hash', '').lower() == value), {})
                verified = new.get('progress', 0) >= 1 or (new.get('num_seeds', 0) > 0
                    and new.get('availability', 0) >= 1 and new.get('dlspeed', 0) >= 128 * 1024
                    and not new.get('force_start') and new.get('state') == 'downloading')
                if code == 200 and verified:
                    self.recovery_remove(app, old_item, blocklist=poor_sources(old_torrent))
                    if poor_sources(old_torrent):
                        failed[release_name(old_item.get('title'))] = now
                    self.status(key, recoveryTrial=None, failedReleases=failed,
                        recoveryMessage='Switched to an alternative with verified transfer')
                    self.persist()
                    LOG.info('Verified alternative replaced unhealthy torrent for task=%s', key)
                    return {original}
            # Keep tracking a trial if the original briefly recovered or a fresh
            # check failed. Otherwise a later stall could spawn another duplicate.
            trial['seenAt'] = now
            self.status(key, recoveryTrial=trial if old_item else None,
                recoveryMessage='Alternative has sources; retaining current download until safe to switch')
            return set()
        if poor_sources(torrent):
            since = trial.get('poorSince')
            if not since or now - trial.get('seenAt', 0) > 120:
                since = now
                trial['poorCompleted'] = torrent.get('completed', 0)
            elapsed = now - since
            rate = (torrent.get('completed', 0) - trial.get('poorCompleted', 0)) / max(elapsed, 1)
            trial['poorSince'] = since if rate < MIN_PROGRESS_RATE else now
            if rate >= MIN_PROGRESS_RATE:
                trial['poorCompleted'] = torrent.get('completed', 0)
            if elapsed >= SOURCE_GRACE and rate < MIN_PROGRESS_RATE and item:
                # Fresh state immediately before removal; a newly healthy trial stays.
                code, fresh = self.qbit('/api/v2/torrents/info?hashes=' + value)
                current = next((t for t in fresh or [] if t.get('hash', '').lower() == value), None)
                if code == 200 and poor_sources(current):
                    self.recovery_remove(app, item)
                    failed[release_name(trial['title'])] = now
                    self.status(key, recoveryTrial=None, failedReleases=failed,
                        recoveryMessage='Alternative also lacked sources; current download preserved; retry scheduled')
                    return {value}
        else:
            trial.pop('poorSince', None)
        trial['seenAt'] = now
        self.status(key, recoveryTrial=trial,
            recoveryMessage='Checking alternative transfer; current partial download is preserved')
        return set()

    def summaries(self, requests):
        result = {}
        order = ['handoff_failed','search_failed','searching','no_release','requested','metadata','unreleased','in_download_client','downloaded','cancelled']
        with self.lock:
            for request in requests:
                keys = [request_key(request, s['seasonNumber']) for s in request.get('seasons', [])] if request['type'] == 'tv' else [request_key(request)]
                tasks = [self.state[k] for k in keys if k in self.state and self.state[k].get('state') in order]
                if tasks:
                    chosen = min(tasks, key=lambda t: order.index(t['state']))
                    result[str(request['id'])] = {k:chosen.get(k) for k in ['state','message','lastSearchAt','nextSearchAt']}
        return dict(healthy=self.healthy and time.time()-self.last_poll < 120, lastPollAt=self.last_poll, requests=result)

    def retry(self, request):
        if request.get('status') != 2 or request.get('media', {}).get('status') == 5:
            return False
        now = time.time()
        with self.lock:
            if now - self.last_manual.get(request['id'], 0) < 60:
                return False
            self.last_manual[request['id']] = now
            self.manual.add(str(request['id']))
        self.wake.set()
        return True

    def run(self):
        while True:
            try:
                self.reconcile()
            except Exception as error:
                self.healthy = False
                LOG.warning('Reconciliation failed: %s', str(error) if isinstance(error, ServiceError) else type(error).__name__)
            self.wake.wait(30)
            self.wake.clear()


class Handler(BaseHTTPRequestHandler):
    server_version = 'MCM-Pipeline'
    sys_version = ''

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_):
        pass  # No session headers or request paths in logs.

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authenticate(self):
        cookie = self.headers.get('Cookie', '')
        if len(cookie) > 8192 or not re.search(r'(?:^|;\s*)connect\.sid=', cookie):
            self.reply(401, {'error':'Sign in to Seerr'})
            return None
        try:
            user = self.server.pipeline.api('seerr', '/api/v1/auth/me', cookie=cookie)
            if not user.get('id'):
                self.reply(401, {'error':'Sign in to Seerr'})
                return None
            return cookie, user
        except ServiceError:
            self.reply(401, {'error':'Seerr session unavailable'})
            return None

    def do_GET(self):
        if self.path == '/health':
            self.reply(200, {'ok':True})
            return
        if self.path not in ('/requests','/torrents'):
            self.reply(404, {'error':'Not found'})
            return
        auth = self.authenticate()
        if not auth:
            return
        try:
            if self.path == '/torrents':
                allowed = self.server.pipeline.allowed_hashes(*auth)
                self.reply(200, self.server.pipeline.torrent_states(allowed))
                return
            requests = self.server.pipeline.requests(cookie=auth[0])
            self.reply(200, self.server.pipeline.summaries(requests))
        except ServiceError:
            self.reply(502, {'error':'Request service unavailable'})

    def do_POST(self):
        match = re.fullmatch(r'/requests/([1-9][0-9]*)/search', self.path)
        torrent_match = re.fullmatch(r'/torrents/([a-fA-F0-9]{40}|[a-fA-F0-9]{64})/(next|now)', self.path)
        stale_match = re.fullmatch(r'/media/(tv|movie)/([1-9][0-9]*)/clear-stale', self.path)
        if not match and not torrent_match and not stale_match:
            self.reply(404, {'error':'Not found'})
            return
        if self.headers.get('X-MCM-Action') != '1':
            self.reply(403, {'error':'Action header required'})
            return
        auth = self.authenticate()
        if not auth:
            return
        try:
            if stale_match:
                if not int(auth[1].get('permissions', 0)) & 2:
                    self.reply(403, {'error':'Only an admin can clear imported monitoring'})
                    return
                cleared = self.server.pipeline.clear_stale_media(stale_match[1], int(stale_match[2]))
                self.reply(200 if cleared else 409, {'cleared':cleared})
                return
            if torrent_match:
                value = torrent_match[1].lower()
                if value not in self.server.pipeline.allowed_hashes(*auth):
                    self.reply(403, {'error':'This download is not permitted'})
                    return
                result = self.server.pipeline.prioritize(value, torrent_match[2])
                self.reply(200, {'result':result})
                return
            request = self.server.pipeline.api('seerr', '/api/v1/request/' + match[1], cookie=auth[0])
            if request.get('requestedBy', {}).get('id') != auth[1]['id']:
                self.reply(403, {'error':'Only the requester can retry'})
                return
            accepted = self.server.pipeline.retry(request)
            self.reply(202 if accepted else 409, {'accepted':accepted})
        except ServiceError:
            self.reply(502, {'error':'Request service unavailable'})


class LimitedServer(ThreadingHTTPServer):
    """Bound concurrent authenticated upstream lookups on the LAN endpoint."""
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(*args, **kwargs)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    pipeline = Pipeline()
    worker = threading.Thread(target=pipeline.run, daemon=True)
    worker.start()
    server = LimitedServer(('0.0.0.0', 5056), Handler)
    server.pipeline = pipeline
    server.serve_forever()


if __name__ == '__main__':
    main()
