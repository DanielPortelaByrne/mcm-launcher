"""Apply MCM resolution tiers and equal indexer priority; keep private backups."""
import copy
import datetime as dt
import json
import os
import urllib.request
import xml.etree.ElementTree as ET
from request_pipeline import Pipeline, STACK


def resolution_profile(profile):
    result = copy.deepcopy(profile)
    def leaves(items):
        return [leaf for item in items for leaf in (leaves(item['items']) if item.get('items') else [item])]
    qualities = leaves(result['items'])
    disabled = [q for q in qualities if not q.get('allowed')]
    allowed = [q for q in qualities if q.get('allowed')]
    tiers = []
    for number, name, low, high in [(1000, 'MCM SD fallback', 1, 719),
            (1001, 'MCM 720p', 720, 1079), (1002, 'MCM 1080p', 1080, 1080)]:
        items = [q for q in allowed if low <= q['quality'].get('resolution', 0) <= high]
        if items:
            tiers.append(dict(id=number, name=name, items=items, allowed=True))
    included = {q['quality']['id'] for tier in tiers for q in tier['items']}
    # Refuse to accidentally discard a deliberately enabled quality.
    if included != {q['quality']['id'] for q in allowed}:
        raise ValueError('Profile contains qualities outside the MCM resolution policy')
    result['items'] = disabled + tiers
    result['cutoff'] = tiers[-1]['id']
    return result


def main():
    pipeline = Pipeline()
    backup = STACK / 'pipeline-backups' / ('selection-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(parents=True, mode=0o700)
    def save(name, value):
        fd = os.open(backup / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream)
    for app in ('sonarr', 'radarr'):
        profiles = pipeline.api(app, '/api/v3/qualityprofile')
        save(app + '-profiles.json', profiles)
        for profile in profiles:
            if profile['name'] in ('MCM 1080p preferred - 720p fallback', 'MCM 1080p preferred - older TV fallback'):
                updated = resolution_profile(profile)
                if updated != profile:
                    pipeline.api(app, '/api/v3/qualityprofile/' + str(profile['id']), 'PUT', updated)
                print(app, profile['id'], 'resolution tiers applied; upgrade policy preserved', flush=True)
    key = ET.parse(STACK / 'prowlarr/config.xml').findtext('ApiKey')
    def prowlarr(path, method='GET', data=None):
        request = urllib.request.Request('http://127.0.0.1:9696/api/v1/' + path,
            data=json.dumps(data).encode() if data is not None else None, method=method,
            headers={'X-Api-Key': key, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    indexers = prowlarr('indexer')
    save('prowlarr-indexers.json', indexers)
    for indexer in indexers:
        if indexer.get('priority') != 25:
            indexer['priority'] = 25
            prowlarr('indexer/' + str(indexer['id']) + '?forceSave=true', 'PUT', indexer)
        print(indexer['name'], 'priority 25', flush=True)
    command = prowlarr('command', 'POST', dict(name='ApplicationIndexerSync'))
    print('Indexer sync command', command['id'], flush=True)
    print('Private rollback backup:', backup, flush=True)


if __name__ == '__main__':
    main()
