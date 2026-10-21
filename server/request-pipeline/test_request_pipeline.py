import tempfile
import unittest
import json
import threading
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch
from request_pipeline import Handler, LimitedServer, Pipeline, archival_profile, poor_sources, recovery_releases, release_name, episode_plan, retry_delay, same_command
from configure_selection import resolution_profile


class PipelineTest(unittest.TestCase):
    def test_resolution_profile_preserves_allowed_qualities_and_custom_upgrade_policy(self):
        def q(i,r,allowed=True):return dict(quality=dict(id=i,resolution=r),allowed=allowed,items=[])
        original=dict(id=8,items=[q(0,0,False),q(1,480),dict(items=[q(2,480),q(3,480)]),q(4,720),q(5,1080),q(6,2160,False)],cutoff=1,upgradeAllowed=False)
        updated=resolution_profile(original)
        self.assertFalse(updated['upgradeAllowed'])
        self.assertEqual(1002,updated['cutoff'])
        self.assertEqual([0,6,1000,1001,1002],[i.get('id',i.get('quality',{}).get('id')) for i in updated['items']])
        self.assertEqual({1,2,3},{q['quality']['id'] for q in updated['items'][2]['items']})
        self.assertEqual(updated,resolution_profile(updated))
        self.assertEqual(1,original['cutoff'])

    def test_resolution_profile_refuses_to_drop_custom_enabled_4k(self):
        with self.assertRaises(ValueError):resolution_profile(dict(items=[dict(quality=dict(id=1,resolution=2160),allowed=True)]))

    def test_partial_progress_does_not_hide_poor_sources_but_user_actions_are_protected(self):
        t=dict(state='downloading',progress=.2,completed=1024,dlspeed=1000,num_seeds=0,availability=.3)
        self.assertTrue(poor_sources(t))
        for change in [dict(state='queuedDL'),dict(state='pausedDL'),dict(state='stoppedDL'),dict(state='checkingDL'),dict(force_start=True),dict(progress=1),dict(num_seeds=1),dict(dlspeed=65536),dict(availability=1)]:
            self.assertFalse(poor_sources(dict(t,**change)))

    def release(self, **changes):
        return dict(dict(title='Better S02 WEB',guid='g',indexerId=1,seeders=6,size=100,fullSeason=True,seasonNumber=2,
            quality=dict(quality=dict(resolution=480)),rejections=['Release in queue already meets cutoff: DVD v1']), **changes)

    def episodes(self):
        return [dict(id=1,episodeNumber=1,seasonNumber=2,monitored=True,hasFile=False)]

    def fixture(self, folder):
        p=Pipeline(Path(folder));a='a'*40;b='b'*40
        t=dict(hash=a,name='Original S02 DVD',state='stalledDL',progress=.2,completed=100,dlspeed=0,num_seeds=0,availability=.2)
        p.torrents={a:t};p.state['1:2']=dict(sourceHealth={a:dict(since=100,seen=1990,completed=100)})
        queue=[dict(id=9,downloadId=a,title=t['name'],episodeId=1)]
        return p,a,b,t,queue

    def test_recovery_ranks_exact_seeds_before_dvd_labels_but_keeps_resolution_preference(self):
        dvd=self.release(title='DVD',seeders=2,size=900,qualityWeight=302)
        web=self.release(title='WEB',seeders=6,size=400,qualityWeight=201)
        hd=self.release(title='HD',seeders=1,quality=dict(quality=dict(resolution=1080)))
        self.assertEqual([hd,web,dvd],recovery_releases([dvd,web,hd],'sonarr',2,self.episodes(),{1},set()))

    def test_recovery_never_overrides_wrong_season_blocklist_size_quality_or_zero_seeds(self):
        bad=[self.release(title=str(n),rejections=[reason]) for n,reason in enumerate([
            'Wrong season','Release is blocklisted','Too large','Unknown Series','Quality not allowed'])]
        bad += [self.release(seeders=0),self.release(seasonNumber=3),self.release(rejected=True,rejections=[]),
            self.release(quality=dict(quality=dict(resolution=2160)))]
        self.assertEqual([],recovery_releases(bad,'sonarr',2,self.episodes(),{1},set()))

    def test_recovery_does_not_include_owned_unmonitored_or_unrequested_episodes(self):
        row=self.release()
        for episodes in [self.episodes()+[dict(id=2,hasFile=True,monitored=True)],self.episodes()+[dict(id=2,hasFile=False,monitored=False)]]:
            self.assertEqual([],recovery_releases([row],'sonarr',2,episodes,{1,2},set()))
        self.assertEqual([],recovery_releases([row],'sonarr',2,self.episodes(),set(),set()))
        self.assertEqual([],recovery_releases([self.release(fullSeason=False,episodeNumbers=[2])],'sonarr',2,self.episodes(),{1},set()))
        self.assertEqual([row],recovery_releases([row],'sonarr',2,self.episodes(),{1},set()))

    def test_recovery_excludes_failed_release_across_indexers_and_punctuation(self):
        row=self.release(title='Better.S02.WEB')
        self.assertEqual([],recovery_releases([row],'sonarr',2,self.episodes(),{1},{release_name('Better S02 WEB')}))

    def test_recovery_handles_both_arr_queue_rejections_without_ignoring_real_quality_rejections(self):
        for app,reason in [('radarr','Quality for release in queue already meets cutoff: Bluray-1080p'),
                ('sonarr','Release in queue is of equal or higher revision: v2'),
                ('sonarr',"Release in queue and Quality Profile 'MCM' does not allow upgrades")]:
            row=self.release(rejections=[reason])
            self.assertEqual([row],recovery_releases([row],app,2,self.episodes(),{1},set()))
            row['rejections'].append('Quality not wanted in profile')
            self.assertEqual([],recovery_releases([row],app,2,self.episodes(),{1},set()))

    @patch('request_pipeline.time.time', return_value=2000)
    def test_explicit_operator_trial_skips_only_grace_not_protected_states(self, _):
        for state,expected in [('stalledDL',1),('queuedDL',0),('stoppedDL',0),('forcedDL',0)]:
            with tempfile.TemporaryDirectory() as folder:
                p,a,b,t,queue=self.fixture(folder);calls=[]
                p.state['1:2']={};t['state']=state
                p.api=lambda *a,**kw:calls.append(a) or []
                p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes(),allow_early=True)
                self.assertEqual(expected,len(calls))

    @patch('request_pipeline.time.time', return_value=2000)
    def test_partial_recovery_starts_trial_without_removing_original(self, _):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder);calls=[]
            def api(app,path,method='GET',data=None,**kw):
                calls.append((path,method,data))
                if path.startswith('/api/v3/episode?'):return self.episodes()
                return [self.release()] if method=='GET' else {}
            p.api=api;p.qbit=lambda path:(200,[t])
            self.assertEqual(set(),p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes()))
            self.assertEqual('a'*40,p.state['1:2']['recoveryTrial']['original'])
            self.assertEqual([('/api/v3/release','POST',dict(guid='g',indexerId=1))],[c for c in calls if c[1]!='GET'])

    @patch('request_pipeline.time.time', return_value=2000)
    def test_recovery_keeps_original_when_no_alternative_or_it_recovers_during_search(self, _):
        for rows,fresh in [([],None),([self.release()],dict(num_seeds=1)),([self.release()],dict(state='pausedDL'))]:
            with tempfile.TemporaryDirectory() as folder:
                p,a,b,t,queue=self.fixture(folder)
                p.api=lambda app,path,method='GET',**kw:rows if method=='GET' else self.fail('Must not change downloads')
                p.qbit=lambda path:(200,[dict(t,**(fresh or {}))])
                self.assertEqual(set(),p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes()))

    @patch('request_pipeline.time.time', return_value=2000)
    def test_cancellation_during_indexer_search_prevents_trial(self, _):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder)
            def api(app,path,method='GET',**kw):
                if method!='GET':self.fail('Cancelled episodes must not be downloaded')
                return [dict(self.episodes()[0],monitored=False)] if '/episode?' in path else [self.release()]
            p.api=api;p.qbit=lambda path:(200,[t])
            p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes())
            self.assertNotIn('recoveryTrial',p.state['1:2'])

    @patch('request_pipeline.time.time', return_value=2000)
    def test_old_snapshot_and_sustained_fast_progress_cannot_trigger_replacement(self, _):
        for change in [dict(seen=1500),dict(completed=-200000000)]:
            with tempfile.TemporaryDirectory() as folder:
                p,a,b,t,queue=self.fixture(folder)
                p.state['1:2']['sourceHealth'][a].update(change)
                p.api=lambda *a,**kw:self.fail('Must not search')
                self.assertEqual(set(),p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes()))

    @patch('request_pipeline.time.time', return_value=2000)
    def test_recovery_daily_limit_applies_to_partial_trials(self, _):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder)
            p.state['1:2'].update(replacementAttempts=2,replacementWindowAt=100)
            p.api=lambda *a,**kw:self.fail('Daily trial cap exceeded')
            p.recover_sources('1:2','sonarr',dict(id=7),2,queue,self.episodes())

    def test_cutover_requires_sustained_health_and_fresh_checks_of_both_torrents(self):
        for healthy_since,seeds,expect_removed in [(1990,1,False),(1800,0,False),(1800,1,True)]:
            with tempfile.TemporaryDirectory() as folder:
                p,a,b,t,queue=self.fixture(folder);calls=[]
                new=dict(t,hash=b,name='Better S02 WEB',state='downloading',num_seeds=1,availability=1,dlspeed=500000)
                p.torrents[b]=new;queue.append(dict(id=10,downloadId=b,title=new['name']))
                trial=dict(original=a,title=new['name'],startedAt=100,knownHashes=[a],healthySince=healthy_since,seenAt=1990)
                p.qbit=lambda path:(200,[t] if path.endswith(a) else [dict(new,num_seeds=seeds)])
                p.api=lambda *a,**kw:calls.append(a)
                removed=p.check_trial('1:2','sonarr',queue,trial,{},2000)
                self.assertEqual({a} if expect_removed else set(),removed)
                self.assertEqual(1 if expect_removed else 0,len(calls))
                if expect_removed:self.assertIn('skipRedownload=true',calls[0][1])

    def test_queued_trial_does_not_time_out_and_never_removes_existing_hash(self):
        for state,known in [('queuedDL',False),('downloading',True)]:
            with tempfile.TemporaryDirectory() as folder:
                p,a,b,t,queue=self.fixture(folder)
                new=dict(t,hash=b,name='Better S02 WEB',state=state)
                p.torrents[b]=new;queue.append(dict(id=10,downloadId=b,title=new['name']))
                trial=dict(original=a,title=new['name'],startedAt=100,knownHashes=[a,b] if known else [a],poorSince=100)
                p.api=lambda *a,**kw:self.fail('Protected download must stay')
                p.qbit=lambda *a,**kw:(204,None)
                self.assertEqual(set(),p.check_trial('1:2','sonarr',queue,trial,{},2000))

    def test_queued_trial_gets_next_normal_slot_once_without_forcing_or_pausing(self):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder);calls=[]
            p.torrents[b]=dict(t,hash=b,state='queuedDL')
            queue.append(dict(id=10,downloadId=b,title='Better'))
            trial=dict(original=a,title='Better',startedAt=100,knownHashes=[a])
            p.qbit=lambda *args:calls.append(args) or (204,None)
            p.check_trial('1:2','sonarr',queue,trial,{},2000)
            p.check_trial('1:2','sonarr',queue,trial,{},2030)
            self.assertEqual([('/api/v2/torrents/topPrio',{'hashes':b})],calls)

    def test_temporarily_recovered_original_does_not_lose_trial_and_spawn_duplicates(self):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder)
            new=dict(t,hash=b,state='downloading',num_seeds=1,availability=1,dlspeed=500000)
            p.torrents[b]=new;queue.append(dict(id=10,downloadId=b,title='Better'))
            trial=dict(original=a,title='Better',startedAt=100,knownHashes=[a],healthySince=1800,seenAt=1990)
            p.qbit=lambda path:(200,[dict(t,num_seeds=1,availability=1)])
            p.api=lambda *a,**kw:self.fail('Recovered original must stay until safe')
            self.assertEqual(set(),p.check_trial('1:2','sonarr',queue,trial,{},2000))
            self.assertIsNotNone(p.state['1:2']['recoveryTrial'])

    def test_completed_alternative_retires_redundant_partial_without_blocklisting_healthy_source(self):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder);calls=[]
            new=dict(t,hash=b,state='uploading',progress=1)
            p.torrents[b]=new;queue.append(dict(id=10,downloadId=b,title='Better'))
            trial=dict(original=a,title='Better',startedAt=100,knownHashes=[a])
            p.qbit=lambda path:(200,[dict(t,num_seeds=1,availability=1)] if path.endswith(a) else [new])
            p.api=lambda *args:calls.append(args)
            self.assertEqual({a},p.check_trial('1:2','sonarr',queue,trial,{},2000))
            self.assertIn('blocklist=false',calls[0][1])

    def test_failed_trial_is_removed_while_original_partial_download_is_kept(self):
        with tempfile.TemporaryDirectory() as folder:
            p,a,b,t,queue=self.fixture(folder);calls=[]
            new=dict(t,hash=b,name='Better S02 WEB')
            p.torrents[b]=new;queue.append(dict(id=10,downloadId=b,title=new['name']))
            trial=dict(original=a,title=new['name'],startedAt=100,knownHashes=[a],poorSince=100,seenAt=1990,poorCompleted=100)
            p.qbit=lambda path:(200,[new]);p.api=lambda *a,**kw:calls.append(a)
            self.assertEqual({b},p.check_trial('1:2','sonarr',queue,trial,{},2000))
            self.assertIn('/queue/10?',calls[0][1])
            self.assertIn(release_name(new['name']),p.state['1:2']['failedReleases'])

    def test_archival_fallback_only_changes_our_default_for_old_tv(self):
        profiles=[dict(id=7,name='MCM 1080p preferred - 720p fallback'),dict(id=8,name='MCM 1080p preferred - older TV fallback')]
        self.assertEqual(8,archival_profile(dict(year=1996,qualityProfileId=7),profiles))
        self.assertIsNone(archival_profile(dict(year=2020,qualityProfileId=7),profiles))
        self.assertIsNone(archival_profile(dict(year=1996,qualityProfileId=4),profiles))

    def test_only_missing_monitored_unqueued_episodes_are_searched(self):
        episodes = [dict(id=1,seasonNumber=2,monitored=True,hasFile=True),
            dict(id=2,seasonNumber=2,monitored=False,hasFile=False),
            dict(id=3,seasonNumber=2,monitored=True,hasFile=False),
            dict(id=4,seasonNumber=2,monitored=True,hasFile=False),
            dict(id=5,seasonNumber=3,monitored=True,hasFile=False)]
        self.assertEqual([4],episode_plan(episodes,[dict(episodeId=3)],2)[2])

    def test_pack_in_queue_prevents_duplicate_episode_searches(self):
        episodes=[dict(id=1,seasonNumber=2,monitored=True,hasFile=False)]
        self.assertEqual([],episode_plan(episodes,[dict(seasonNumber=2)],2)[2])

    def test_season_commands_are_not_deduplicated_across_different_seasons(self):
        command=dict(id=1,status='started',name='SeasonSearch',body=dict(seriesId=7,seasonNumber=1))
        self.assertTrue(same_command(command,'sonarr',7,1,[1]))
        self.assertFalse(same_command(command,'sonarr',7,2,[2]))

    def test_completed_commands_do_not_block_retries(self):
        command=dict(status='completed',name='SeasonSearch',body=dict(seriesId=7,seasonNumber=1))
        self.assertFalse(same_command(command,'sonarr',7,1,[1]))

    def test_retry_backoff_is_bounded(self):
        self.assertEqual([900,3600,21600,86400,86400],[retry_delay(n) for n in range(1,6)])

    def test_cancelled_request_never_searches_or_remonitors(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder))
            p.api=lambda *a,**kw:self.fail('Cancelled request must not call the server')
            r=dict(id=1,type='tv',createdAt='2020-01-01T00:00:00Z')
            p.reconcile_task(r,dict(id=7),2,[],[dict(id=1,seasonNumber=2,monitored=False,hasFile=False)],[])
            self.assertEqual('cancelled',p.state['1:2']['state'])

    def test_existing_download_uses_episode_search_only_for_uncovered_episodes(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder)); calls=[]
            def api(app,path,method,data):
                calls.append(data)
                return dict(id=3,status='queued',name=data['name'],body=data)
            p.api=api
            r=dict(id=1,type='tv',createdAt='2020-01-01T00:00:00Z')
            episodes=[dict(id=1,seasonNumber=2,monitored=True,hasFile=False),dict(id=2,seasonNumber=2,monitored=True,hasFile=False)]
            p.reconcile_task(r,dict(id=7),2,[dict(episodeId=1)],episodes,[])
            self.assertEqual([dict(name='EpisodeSearch',episodeIds=[2])],calls)

    def test_ready_and_unreleased_movies_do_not_search(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder));p.api=lambda *a,**kw:self.fail('Must not search')
            r=dict(id=1,type='movie')
            p.reconcile_task(r,dict(id=7,monitored=True,hasFile=True),None,[],[],[])
            self.assertEqual('downloaded',p.state['1']['state'])
            p.reconcile_task(r,dict(id=7,monitored=True,hasFile=False,isAvailable=False),None,[],[],[])
            self.assertEqual('unreleased',p.state['1']['state'])

    def test_http_authentication_csrf_and_request_ownership(self):
        class Fake:
            def api(self,app,path,cookie=None):
                if path=='/api/v1/auth/me':return dict(id=1)
                return dict(id=7,requestedBy=dict(id=2))
            def retry(self,request):raise AssertionError('Another user cannot trigger a retry')
        server=LimitedServer(('127.0.0.1',0),Handler);server.pipeline=Fake()
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        url='http://127.0.0.1:'+str(server.server_port)
        try:
            for path,method,headers,code in [('/requests','GET',{},401),('/requests/7/search','POST',{},403),('/requests/7/search','POST',{'Cookie':'connect.sid=test','X-MCM-Action':'1'},403)]:
                req=urllib.request.Request(url+path,method=method,headers=headers)
                with self.assertRaises(urllib.error.HTTPError) as caught:urllib.request.urlopen(req)
                self.assertEqual(code,caught.exception.code)
        finally:
            server.shutdown();server.server_close();thread.join()

    def test_failed_search_waits_for_backoff_instead_of_resubmitting_every_poll(self):
        import time
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder));p.api=lambda *a,**kw:self.fail('Must honor retry time')
            p.state['1:2']=dict(state='no_release',nextSearchAt=time.time()+900)
            p.reconcile_task(dict(id=1,type='tv'),dict(id=7),2,[],[dict(id=1,seasonNumber=2,monitored=True,hasFile=False)],[])
            self.assertEqual('no_release',p.state['1:2']['state'])

    def test_qbit_52_no_content_response_is_success_and_targets_one_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder));calls=[];value='a'*40
            def qbit(path,data=None):
                calls.append((path,data))
                return (200,[dict(hash=value,progress=0.1)]) if data is None else (204,None)
            p.qbit=qbit
            self.assertEqual('DONE',p.prioritize(value,'now'))
            self.assertEqual([('/api/v2/torrents/info?hashes='+value,None),('/api/v2/torrents/setForceStart',{'hashes':value,'value':'true'})],calls)

    def test_queue_disabled_does_not_force_or_resume_a_torrent(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder));calls=[];value='a'*40
            def qbit(path,data=None):
                calls.append(path)
                return (200,[dict(hash=value,progress=0.1)]) if data is None else (409,None)
            p.qbit=qbit
            self.assertEqual('QUEUE_DISABLED',p.prioritize(value,'next'))
            self.assertEqual(2,len(calls))

    def test_torrent_permissions_are_scoped_to_the_requester_and_requested_season(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Pipeline(Path(folder));a='a'*40;b='b'*40
            p.requests=lambda cookie=None:[dict(id=1,type='tv',requestedBy=dict(id=1),media=dict(tmdbId=605),seasons=[dict(seasonNumber=7)])]
            p.api=lambda app,*args,**kw:dict(totalRecords=2,records=[dict(downloadId=a,series=dict(tmdbId=605),episode=dict(seasonNumber=5)),dict(downloadId=b,series=dict(tmdbId=605),episode=dict(seasonNumber=7))]) if app=='sonarr' else dict(totalRecords=0,records=[])
            self.assertEqual({b},p.allowed_hashes('connect.sid=test',dict(id=1,permissions=0)))
            self.assertEqual(set(),p.allowed_hashes('connect.sid=test',dict(id=2,permissions=0)))
            self.assertEqual({a,b},p.allowed_hashes('connect.sid=test',dict(id=1,permissions=2)))


if __name__=='__main__':unittest.main()
