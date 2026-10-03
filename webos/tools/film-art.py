import base64
import hashlib
import html
import json
import os
import re
import sys
import urllib.request
from html.parser import HTMLParser


class Metadata(HTMLParser):
    image = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'meta' and attrs.get('property') == 'og:image':
            self.image = attrs.get('content')


result = {}
try:
    path = sys.argv[1]
    if not re.fullmatch(r'/film/[a-zA-Z0-9_-]+/', path):
        raise ValueError('Invalid film path')
    cache_dir = '/var/lib/mcm-home/film-art'
    os.makedirs(cache_dir, exist_ok=True)
    cache = os.path.join(cache_dir, hashlib.sha256(path.encode()).hexdigest() + '.json')
    if os.path.isfile(cache):
        with open(cache) as file:
            result = json.load(file)
    else:
        headers = {'User-Agent': 'MCMHome/1.0 (public TV film picks)'}
        request = urllib.request.Request('https://letterboxd.com' + path, headers=headers)
        with urllib.request.urlopen(request, timeout=12) as response:
            page = response.read().decode('utf-8')
        metadata = Metadata()
        metadata.feed(page)
        url = metadata.image or ''
        if not re.match(r'https://(?:a|s)\.ltrbxd\.com/', url):
            raise ValueError('No film artwork available')
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=12) as response:
            content_type = response.headers.get_content_type()
            image = response.read(1500001)
        if not content_type.startswith('image/') or len(image) > 1500000:
            raise ValueError('Unsupported artwork')
        result = {'image': 'data:' + content_type + ';base64,' + base64.b64encode(image).decode('ascii')}
        with open(cache + '.tmp', 'w') as file:
            json.dump(result, file)
        os.replace(cache + '.tmp', cache)
        files = sorted((os.path.join(cache_dir, name) for name in os.listdir(cache_dir) if name.endswith('.json')), key=os.path.getmtime)
        for old in files[:-40]:
            os.remove(old)
except Exception:
    pass
print(json.dumps(result))
