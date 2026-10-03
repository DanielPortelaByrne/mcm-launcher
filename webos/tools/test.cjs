const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const path=require('node:path');
const app=path.join(__dirname,'../app');
const calls=[];
const apps=Array.from({length:12},(_,i)=>({id:'test.app'+i,title:'App '+String(i).padStart(2,'0')}));
apps.push({id:'com.webos.app.hdmi2',title:'PlayStation 5'},{id:'youtube.leanback.v4',title:'YouTube'},{id:'com.webos.app.hdmi1',title:'Fire TV Stick'});
const dom=new JSDOM(fs.readFileSync(path.join(app,'index.html'),'utf8'),{runScripts:'outside-only',url:'https://mcm.local/'});
const w=dom.window;
w.HTMLElement.prototype.scrollIntoView=function(){};
w.HTMLElement.prototype.scrollTo=function(o){this.scrollTop=o.top;};
w.HTMLElement.prototype.getClientRects=function(){return [{}];};
w.PalmServiceBridge=class{
  call(uri,payload){
    const data=JSON.parse(payload);calls.push({uri,data});
    let response={returnValue:true,stdoutString:'timingServiceResponse Got response: {"returnValue":true}'};
    if(data.command?.includes('list-apps.py'))response.stdoutString=JSON.stringify({apps});
    if(data.command?.includes('watchlist.py'))response.stdoutString=JSON.stringify({source:'kurstboy',films:[{title:'Watchlist film',path:'/film/test/'}]});
    if(data.command?.includes('film-art.py'))response.stdoutString=JSON.stringify({image:'data:image/jpeg;base64,aGVsbG8='});
    queueMicrotask(()=>this.onservicecallback(JSON.stringify(response)));
  }
};
w.localStorage.setItem('mcm.home',JSON.stringify({profile:'Eva',painting:2,order:['test.app0'],hidden:['com.webos.app.hdmi1']}));
w.eval(fs.readFileSync(path.join(app,'data.js'),'utf8'));
w.eval(fs.readFileSync(path.join(app,'app.js'),'utf8'));
const tick=()=>new Promise(r=>setTimeout(r,0));
const q=s=>w.document.querySelector(s);
async function run(){
  assert.equal(JSON.parse(fs.readFileSync(path.join(app,'appinfo.json'),'utf8')).handlesRelaunch,false,'webOS must foreground MCM automatically on Home relaunch');
  await tick();
  assert.equal(q('#shelf').querySelectorAll('[data-app]').length,7);
  assert.equal(q('#more').querySelectorAll('[data-app]').length,8);
  assert.equal(q('#projects'),null);assert.equal(q('#listening'),null);assert.equal(w.MCM.prefs.profile,'Bandit and Aries');assert.equal(w.MCM.prefs.painting,2);assert.equal(q('#film-title').textContent,'Watchlist film');assert(q('#film-source').textContent.includes('Karsten Runquist'));assert(q('#film').classList.contains('with-art'));assert(q('#film').style.backgroundImage.includes('data:image/jpeg')); assert(!w.document.body.textContent.includes('EVA'));w.MCM.action('film');assert(q('[data-action="letterboxd"]'));w.MCM.action('close-panel');
  assert.deepEqual(Array.from(q('#shelf').querySelectorAll('[data-app]')).slice(0,3).map(b=>b.dataset.app),['youtube.leanback.v4','com.webos.app.hdmi1','com.webos.app.hdmi2']);
  for(const id of ['com.webos.app.hdmi1','com.webos.app.hdmi2']) {const b=q('#shelf [data-app="'+id+'"]');assert(b.querySelector('img'));b.click();await tick();assert(calls.some(c=>c.data.command?.includes(id)&&c.data.command.includes('applicationmanager/launch')));}
  w.MCM.action('apps');assert.equal(q('#app-grid').children.length,15);
  w.MCM.action('close-panel');assert.equal(q('#panel').hidden,true);
  w.MCM.action('search');q('#search').value='App 10';q('#search').dispatchEvent(new w.Event('input'));assert.equal(q('#app-grid').children.length,1);
  w.MCM.action('close-panel');w.MCM.action('edit');
  q('[data-choice="hide"][data-id="test.app0"]').click();assert(w.MCM.prefs.hidden.includes('test.app0'));assert.equal(q('#shelf [data-app="test.app0"]'),null);
  q('[data-choice="hide"][data-id="test.app0"]').click();assert(!w.MCM.prefs.hidden.includes('test.app0'));
  q('[data-choice="up"][data-id="test.app1"]').click();assert.equal(w.MCM.prefs.order.indexOf('test.app1'),w.MCM.prefs.order.indexOf('test.app0')-1);
  w.MCM.action('close-panel');w.MCM.action('profiles');assert.equal(q('#panel').querySelectorAll('[data-profile]').length,1);q('[data-profile="Bandit and Aries"]').click();assert.equal(q('#profile').textContent,'B&A');
  w.MCM.action('art');q('[data-painting="2"]').click();assert.equal(w.MCM.prefs.painting,2);
  w.MCM.action('art-mode');assert.equal(w.MCM.state.art,true);w.MCM.action('art-next');assert.equal(w.MCM.prefs.painting,3);w.MCM.action('art-exit');assert.equal(w.MCM.state.art,false);
  w.MCM.action('apps');q('#panel').dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',keyCode:461,bubbles:true}));assert.equal(q('#panel').hidden,true);
  w.MCM.action('apps');w.document.dispatchEvent(new w.CustomEvent('webOSRelaunch'));assert.equal(q('#panel').hidden,true,'Home relaunch returns to the top-level home screen');await tick();
  q('#shelf [data-app]').click();await tick();assert(calls.some(c=>c.data.command?.includes('applicationmanager/launch')));
  const before=calls.length;const shortButton=q('#shelf [data-app]');shortButton.focus();
  shortButton.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true,cancelable:true}));
  assert.equal(calls.length,before,'OK must wait for release so a hold does not launch early');
  shortButton.dispatchEvent(new w.KeyboardEvent('keyup',{key:'Enter',keyCode:13,bubbles:true,cancelable:true}));await tick();
  assert(calls.length>before,'Short OK launches the app on release');
  const appButton=q('#shelf [data-app]');appButton.focus();
  appButton.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));
  await new Promise(r=>setTimeout(r,700));assert.equal(w.MCM.state.panel,'app-options');
  q('#panel').dispatchEvent(new w.KeyboardEvent('keyup',{key:'Enter',keyCode:13,bubbles:true}));
  assert.equal(JSON.parse(w.localStorage.getItem('mcm.home')).profile,'Bandit and Aries');
  dom.window.close();console.log('Passed: installed apps, search, hide/show/reorder, profiles, art, Back, launching, long press, saved preferences.');
}
run().catch(e=>{console.error(e);dom.window.close();process.exitCode=1;});
