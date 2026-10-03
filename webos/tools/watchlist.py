import html
import json
import os
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SOURCE = 'kurstboy-high-rated'
MIN_RATING = 4.0
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
            'https://letterboxd.com/kurstboy/watchlist/',
            headers={'User-Agent': 'MCMHome/1.0 (public TV film picks)'})
        with urllib.request.urlopen(request, timeout=12) as response:
            page = response.read().decode('utf-8')
        films = []
        for tag in re.findall(r'<div\b[^>]*>', page):
            name = re.search(r'data-item-name="([^"]+)"', tag)
            path = re.search(r'data-item-link="(/film/[^\"]+/)"', tag)
            if name and path:
                films.append({'title': html.unescape(name.group(1)), 'path': path.group(1)})
        def rated_film(film):
            try:
                request = urllib.request.Request('https://letterboxd.com' + film['path'], headers={'User-Agent': 'MCMHome/1.0 (public TV film picks)'})
                with urllib.request.urlopen(request, timeout=8) as response:
                    details = response.read().decode('utf-8')
                rating = re.search(r'"ratingValue"\s*:\s*([0-9.]+)', details)
                if rating and MIN_RATING <= float(rating.group(1)) <= 5:
                    return dict(film, rating=float(rating.group(1)))
            except Exception:
                pass
            return None
        with ThreadPoolExecutor(max_workers=4) as pool:
            films = [film for film in pool.map(rated_film, films) if film]
        films.sort(key=lambda film: film['rating'], reverse=True)
        if films:
            data = {'source': SOURCE, 'films': films, 'updated': time.time()}
            with open(CACHE + '.tmp', 'w') as file:
                json.dump(data, file)
            os.replace(CACHE + '.tmp', CACHE)
    except Exception:
        pass
print(json.dumps(data, ensure_ascii=False))
