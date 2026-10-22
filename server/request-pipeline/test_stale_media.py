import copy
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from request_pipeline import Handler, LimitedServer, Pipeline, ServiceError


class StaleMediaTest(unittest.TestCase):
    def fixture(self, folder, kind='tv'):
        p = Pipeline(Path(folder))
        self.info = dict(id=34, status=3, status4k=1, requests=[], downloadStatus=[], downloadStatus4k=[],
                         seasons=[dict(seasonNumber=1, status=3, status4k=1)])
        self.record = dict(id=4, tmdbId=76331, title='Succession', monitored=True,
                           seasons=[dict(seasonNumber=1, monitored=True)], statistics=dict(episodeFileCount=0), hasFile=False)
        self.episodes = [dict(id=1, monitored=False, hasFile=False, seasonNumber=1)]
        self.commands, self.queue, self.torrents, self.requests, self.writes = [], [], [], [], []
        self.after_put = None
        resource = 'series' if kind == 'tv' else 'movie'

        def api(app, path, method='GET', data=None, **kwargs):
            if method != 'GET':
                self.writes.append((app, path, method, copy.deepcopy(data)))
                if method == 'PUT':
                    self.record = copy.deepcopy(data)
                    if self.after_put:
                        self.after_put()
                return {}
            if app == 'seerr':
                return dict(name='Succession', mediaInfo=copy.deepcopy(self.info))
            if path == '/api/v3/' + resource:
                return [copy.deepcopy(self.record)]
            if path.startswith('/api/v3/' + resource + '/'):
                return copy.deepcopy(self.record)
            if path.startswith('/api/v3/episode?'):
                return copy.deepcopy(self.episodes)
            if path.startswith('/api/v3/command'):
                return self.commands
            if path.startswith('/api/v3/queue?'):
                return dict(records=self.queue, totalRecords=len(self.queue))
            raise AssertionError(path)
        p.api = api
        p.requests = lambda: self.requests
        p.qbit = lambda *a: (200, self.torrents)
        return p

    def test_empty_imported_seasons_are_unmonitored_before_only_seerr_record_is_cleared(self):
        with tempfile.TemporaryDirectory() as folder:
            p = self.fixture(folder)
            self.assertTrue(p.clear_stale_media('tv', 76331))
            self.assertEqual([('sonarr', '/api/v3/series/4', 'PUT'), ('seerr', '/api/v1/media/34', 'DELETE')],
                             [w[:3] for w in self.writes])
            self.assertFalse(self.record['monitored'])
            self.assertFalse(self.record['seasons'][0]['monitored'])
            backups = list(p.home.glob('stale-tv-76331-*.json'))
            self.assertEqual(1, len(backups))
            self.assertTrue(json.loads(backups[0].read_text())['record']['monitored'])

    def test_real_request_from_any_user_blocks_repair_before_any_write(self):
        with tempfile.TemporaryDirectory() as folder:
            p = self.fixture(folder)
            self.requests = [dict(type='tv', requestedBy=dict(id=99), media=dict(tmdbId=76331))]
            self.assertFalse(p.clear_stale_media('tv', 76331))
            self.assertEqual([], self.writes)

    def test_files_monitored_episodes_searches_queue_and_orphan_torrents_are_preserved(self):
        for protection in ('file', 'monitor', 'search', 'episode_search', 'queue', 'torrent'):
            with self.subTest(protection=protection), tempfile.TemporaryDirectory() as folder:
                p = self.fixture(folder)
                if protection == 'file': self.episodes[0]['hasFile'] = True
                if protection == 'monitor': self.episodes[0]['monitored'] = True
                if protection == 'search': self.commands = [dict(status='started', body=dict(seriesId=4))]
                if protection == 'episode_search': self.commands = [dict(status='queued', body=dict(episodeIds=[1]))]
                if protection == 'queue': self.queue = [dict(seriesId=4)]
                if protection == 'torrent': self.torrents = [dict(name='Succession.S01.1080p')]
                self.assertFalse(p.clear_stale_media('tv', 76331))
                self.assertEqual([], self.writes)

    def test_available_partial_4k_downloads_and_missing_request_metadata_are_preserved(self):
        for change in (dict(status=4), dict(status=5), dict(status4k=3), dict(downloadStatus=[{}]),
                       dict(requests=[dict(id=1)]), dict(requests=None)):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as folder:
                p = self.fixture(folder)
                self.info.update(change)
                self.assertFalse(p.clear_stale_media('tv', 76331))
                self.assertEqual([], self.writes)

    def test_new_request_or_file_during_repair_prevents_media_clear(self):
        for change in ('request', 'file'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as folder:
                p = self.fixture(folder)
                def arrived():
                    if change == 'request': self.requests = [dict(type='tv', media=dict(tmdbId=76331))]
                    else: self.episodes[0]['hasFile'] = True
                self.after_put = arrived
                self.assertFalse(p.clear_stale_media('tv', 76331))
                self.assertEqual(['PUT'], [w[2] for w in self.writes])

    def test_unavailable_downloader_never_changes_monitoring(self):
        with tempfile.TemporaryDirectory() as folder:
            p = self.fixture(folder)
            p.qbit = lambda *a: (503, None)
            with self.assertRaises(ServiceError): p.clear_stale_media('tv', 76331)
            self.assertEqual([], self.writes)

    def test_movie_reset_preserves_other_records_and_rejects_owned_file(self):
        with tempfile.TemporaryDirectory() as folder:
            p = self.fixture(folder, 'movie')
            self.record['hasFile'] = True
            self.assertFalse(p.clear_stale_media('movie', 76331))
            self.assertEqual([], self.writes)
            self.record['hasFile'] = False
            self.assertTrue(p.clear_stale_media('movie', 76331))
            self.assertEqual('/api/v3/movie/4', self.writes[0][1])

    def test_clear_endpoint_requires_action_header_session_and_admin(self):
        class Fake:
            def api(self, *args, **kwargs): return dict(id=1, permissions=0)
            def clear_stale_media(self, *args): raise AssertionError('Must require admin')
        server = LimitedServer(('127.0.0.1', 0), Handler)
        server.pipeline = Fake()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = 'http://127.0.0.1:' + str(server.server_port) + '/media/tv/76331/clear-stale'
            for headers, code in (({}, 403), ({'X-MCM-Action': '1'}, 401),
                                  ({'X-MCM-Action': '1', 'Cookie': 'connect.sid=test'}, 403)):
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(urllib.request.Request(url, method='POST', headers=headers))
                self.assertEqual(code, caught.exception.code)
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
