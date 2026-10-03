import base64,json,mimetypes,os,subprocess

def luna(uri,payload):
    p=subprocess.run(['luna-send','-t','1',uri,json.dumps(payload)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
    for line in (p.stdout+p.stderr).decode('utf-8','replace').splitlines():
        start=line.find('{')
        if start>=0:
            try:return json.loads(line[start:])
            except ValueError:pass
    return {}

apps=luna('luna://com.webos.applicationManager/listLaunchPoints',{}).get('launchPoints',[])
if not apps:apps=luna('luna://com.webos.applicationManager/listApps',{}).get('apps',[])
result=[]
for app in apps:
    appid=app.get('appId') or app.get('id')
    if not appid or not app.get('title') or app.get('visible') is False or app.get('hidden') is True:continue
    icon=app.get('icon','')
    if icon.startswith('file://'):icon=icon[7:]
    candidates=[icon]
    if not icon.startswith('/'):
        for prefix in ['/media/developer/apps/usr/palm/applications/','/media/cryptofs/apps/usr/palm/applications/','/usr/palm/applications/']:
            candidates.append(prefix+appid+'/'+icon)
    image=None
    for candidate in candidates:
        try:
            if os.path.isfile(candidate) and os.path.getsize(candidate)<250000:
                with open(candidate,'rb') as f:image='data:'+str(mimetypes.guess_type(candidate)[0] or 'image/png')+';base64,'+base64.b64encode(f.read()).decode('ascii')
                break
        except OSError:pass
    result.append({'id':appid,'title':app['title'],'iconData':image})
print(json.dumps({'apps':result},ensure_ascii=False))
