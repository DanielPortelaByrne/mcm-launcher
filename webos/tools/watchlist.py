import html
import json
import os
import re
import time
import urllib.request

SOURCE = 'kurstboy'
CACHE = '/var/lib/mcm-home/watchlist.json'
data = {}
try:
    with open(CACHE) as file:
        data = json.load(file)
except (OSError, ValueError):
    pass
if data.get('source') != SOURCE:
    data = {}
if time.time() - data.get('updated', 0) > 21600:
    try:
        request = urllib.request.Request(
            'https://letterboxd.com/' + SOURCE + '/watchlist/',
            headers={'User-Agent': 'MCMHome/1.0 (public TV film picks)'})
        with urllib.request.urlopen(request, timeout=12) as response:
            page = response.read().decode('utf-8')
        films = []
        for tag in re.findall(r'<div\b[^>]*>', page):
            name = re.search(r'data-item-name="([^"]+)"', tag)
            path = re.search(r'data-item-link="(/film/[^\"]+/)"', tag)
            if name and path:
                films.append({'title': html.unescape(name.group(1)), 'path': path.group(1)})
        if films:
            data = {'source': SOURCE, 'films': films, 'updated': time.time()}
            with open(CACHE + '.tmp', 'w') as file:
                json.dump(data, file)
            os.replace(CACHE + '.tmp', CACHE)
    except Exception:
        pass
print(json.dumps(data, ensure_ascii=False))
