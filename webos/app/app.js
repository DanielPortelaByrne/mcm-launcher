(function () {
  'use strict';
  var DATA = window.MCM_DATA;
  var ID = 'com.daniel.mcm.home';
  var state = { apps: [], film: null, recipe: null, panel: null, art: false, hold: null, held: false, pressed: null, beforePanel: null };
  var prefs;
  try { prefs = JSON.parse(localStorage.getItem('mcm.home') || '{}'); } catch (_) { prefs = {}; }
  prefs.hidden = prefs.hidden || []; prefs.order = prefs.order || [];
  prefs.profile = 'Bandit and Aries'; prefs.painting = Number(prefs.painting) || 0;
  var bridges = [], toastTimer, artTimer;
  var $ = function (s) { return document.querySelector(s); };
  function save() { localStorage.setItem('mcm.home', JSON.stringify(prefs)); }
  function escape(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function quote(s) { return "'" + String(s).replace(/'/g, "'\\''") + "'"; }
  function toast(message) { clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').hidden = false; toastTimer = setTimeout(function () { $('#toast').hidden = true; }, 4500); }
  function luna(uri, payload) {
    return new Promise(function (resolve, reject) {
      var Constructor = window.PalmServiceBridge || window.webOSServiceBridge;
      if (!Constructor) { reject(new Error('TV service unavailable in this preview')); return; }
      var bridge = new Constructor(); bridges.push(bridge);
      var timer = setTimeout(function () { finish(new Error('TV service timed out')); }, 45000);
      function finish(error, response) { clearTimeout(timer); bridges = bridges.filter(function (b) { return b !== bridge; }); if (error) reject(error); else resolve(response); }
      bridge.onservicecallback = function (message) {
        var response; try { response = JSON.parse(message); } catch (_) { finish(new Error('Unreadable TV response')); return; }
        if (response.returnValue === false || response.error) finish(new Error(response.errorText || response.error || 'TV request failed'));
        else finish(null, response);
      };
      bridge.call(uri, JSON.stringify(payload || {}));
    });
  }
  function exec(command) { return luna('luna://org.webosbrew.hbchannel.service/exec', {command:command}).then(function (r) { return r.stdoutString || ''; }); }
  function parseResult(text) {
    var lines = text.trim().split('\n');
    for (var i = lines.length - 1; i >= 0; i--) { var start = lines[i].indexOf('{'); if (start >= 0) { try { return JSON.parse(lines[i].slice(start)); } catch (_) {} } }
    throw new Error('No response from the TV');
  }
  function rootLuna(uri, payload) { return exec('luna-send -t 1 ' + quote(uri) + ' ' + quote(JSON.stringify(payload || {})) + ' 2>&1').then(parseResult).then(function (r) { if (r.returnValue === false) throw new Error(r.errorText || 'TV request failed'); return r; }); }
  function launch(id, params) {
    if (!/^[a-zA-Z0-9_.-]+$/.test(id)) return Promise.reject(new Error('Invalid app identifier'));
    return rootLuna('luna://com.webos.service.applicationmanager/launch', {id:id, params:params || {}}).catch(function (error) { toast('Could not open app: ' + error.message); throw error; });
  }
  function openUrl(url) { if (!/^https?:\/\//.test(url)) return; launch('com.webos.app.browser', {target:url}).catch(function () {}); }
  function appsSorted(includeHidden) {
    return state.apps.filter(function (a) { return includeHidden || prefs.hidden.indexOf(a.id) < 0; }).sort(function (a,b) {
      var ai = prefs.order.indexOf(a.id), bi = prefs.order.indexOf(b.id);
      if (ai < 0) ai = 10000; if (bi < 0) bi = 10000;
      return ai - bi || a.title.localeCompare(b.title);
    });
  }
  function icon(app) { return app.iconData ? '<img src="' + escape(app.iconData) + '" alt="">' : escape(app.title.slice(0,1)); }
  function appButton(app, wide) { return '<button class="app-button' + (wide ? ' wide-app' : '') + '" data-app="' + escape(app.id) + '" aria-label="' + escape(app.title) + '"><span class="disc">' + icon(app) + '</span><span><span class="label">' + escape(app.title) + '</span>' + (wide ? '<small>OPEN APP</small>' : '') + '</span></button>'; }
  function renderApps() {
    var visible = appsSorted(false);
    $('#shelf').innerHTML = visible.slice(0,7).map(function (a) { return appButton(a,false); }).join('') + '<button class="app-button" data-action="apps"><span class="disc">⋮</span><span class="label">All apps</span></button>';
    $('#more').innerHTML = visible.slice(7).map(function (a) { return appButton(a,true); }).join('');
    $('#more-section').hidden = visible.length <= 7;
  }
  function refreshApps() {
    return exec('python3 /var/lib/mcm-home/list-apps.py').then(function (text) {
      var result = JSON.parse(text); if (!Array.isArray(result.apps)) throw new Error('App list unavailable');
      state.apps = result.apps.filter(function (a) { return a.id !== ID && a.title && !/^com\.webos\.app\.(home|seniorhome|settings|livetv|inputs|adoverlay|acroverlay|tinybrowser)/.test(a.id); });
      renderApps();
    }).catch(function (e) { $('#shelf').innerHTML = '<button class="pill" data-action="refresh-apps">Retry app discovery</button><p class="muted">' + escape(e.message) + '</p>'; });
  }
  function painting(index) {
    prefs.painting = (index + DATA.paintings.length) % DATA.paintings.length;
    $('#painting').style.backgroundImage = 'url("assets/' + DATA.paintings[prefs.painting].file + '")';
    $('#art-caption').textContent = DATA.paintings[prefs.painting].title;
    save();
  }
  function heading(title, extra) { return '<div class="panel-header"><h2>' + escape(title) + '</h2><span class="spacer"></span>' + (extra || '') + '<button data-action="close-panel">Back</button></div>'; }
  function panel(kind, html, focusSelector) {
    if (!state.panel) state.beforePanel = document.activeElement;
    state.panel = kind; $('#panel').innerHTML = html; $('#panel').hidden = false; $('#home').classList.add('obscured'); document.body.classList.add('panel-open'); $('#panel').scrollTop = 0;
    var target = $('#panel').querySelector(focusSelector || 'button,input'); if (target) target.focus();
  }
  function closePanel() { $('#panel').hidden = true; $('#panel').innerHTML = ''; state.panel = null; $('#home').classList.remove('obscured'); document.body.classList.remove('panel-open'); var target = state.beforePanel; if (target && target.isConnected && !target.closest('[hidden]')) target.focus(); else $('.nav').focus(); }
  function appDirectory(search) {
    var title = search ? 'Find an app' : 'All apps';
    var html = heading(title,'<button data-action="edit">Edit your apps</button>') + (search ? '<input id="search" type="search" placeholder="Search your apps" aria-label="Search your apps">' : '') + '<div class="grid" id="app-grid">' + appsSorted(false).map(function (a) { return appButton(a,false); }).join('') + '</div>';
    panel(search ? 'search' : 'apps',html,search ? '#search' : null);
    if (search) $('#search').addEventListener('input',function () { var term = this.value.toLowerCase(); $('#app-grid').innerHTML = appsSorted(false).filter(function (a) { return a.title.toLowerCase().indexOf(term) >= 0; }).map(function (a) { return appButton(a,false); }).join('') || '<p class="empty">No apps match this search.</p>'; });
  }
  function appOptions(id) {
    var a = state.apps.find(function (x) { return x.id === id; }); if (!a) return;
    panel('app-options', heading(a.title) + '<div class="option-list"><button data-choice="open" data-id="' + escape(id) + '">Open app</button><button data-choice="pin" data-id="' + escape(id) + '">Move to Your apps</button><button data-choice="hide" data-id="' + escape(id) + '">' + (prefs.hidden.indexOf(id) >= 0 ? 'Show on home' : 'Hide from home') + '</button><button data-action="edit">Organise all apps</button></div>');
  }
  function editApps() {
    var items = appsSorted(true);
    panel('edit',heading('Edit your apps') + '<p class="panel-copy">Your apps holds seven. Move apps up or down to arrange the shelf. Hidden apps stay installed.</p>' + items.map(function (a) { return '<div class="edit-item"><span class="label">' + escape(a.title) + (prefs.hidden.indexOf(a.id) >= 0 ? ' · Hidden' : '') + '</span><button data-choice="up" data-id="' + escape(a.id) + '" aria-label="Move ' + escape(a.title) + ' up">↑</button><button data-choice="down" data-id="' + escape(a.id) + '" aria-label="Move ' + escape(a.title) + ' down">↓</button><button data-choice="hide" data-id="' + escape(a.id) + '">' + (prefs.hidden.indexOf(a.id) >= 0 ? 'Show' : 'Hide') + '</button></div>'; }).join(''));
  }
  function changeApp(choice,id) {
    if (choice === 'open') { launch(id).catch(function () {}); return; }
    var items = appsSorted(true).map(function (a) { return a.id; }), i = items.indexOf(id);
    if (i < 0) return;
    if (choice === 'hide') { var hi = prefs.hidden.indexOf(id); if (hi < 0) prefs.hidden.push(id); else prefs.hidden.splice(hi,1); }
    else { items.splice(i,1); items.splice(choice === 'pin' ? 0 : Math.max(0,Math.min(items.length,i + (choice === 'up' ? -1 : 1))),0,id); prefs.order = items; if (choice === 'pin') prefs.hidden = prefs.hidden.filter(function (x) { return x !== id; }); }
    save(); renderApps();
    if (state.panel === 'edit') { editApps(); var btn = $('#panel').querySelector('[data-choice="' + choice + '"][data-id="' + id + '"]'); if (btn) btn.focus(); }
    else { closePanel(); toast('Home updated'); }
  }
  function showProfiles() { panel('profiles',heading("Who's watching?") + '<p class="panel-copy">Choose the name shown on your home screen.</p><div class="panel-actions">' + DATA.profiles.map(function (name) { return '<button class="profile-button" data-profile="' + escape(name) + '">' + escape(name) + (prefs.profile === name ? ' ✓' : '') + '</button>'; }).join('') + '</div>'); }
  function gallery() { panel('gallery',heading('The painting collection','<button data-action="art-mode">Art mode</button>') + '<div class="grid gallery">' + DATA.paintings.map(function (p,i) { return '<button class="painting-card" data-painting="' + i + '"><img src="assets/' + escape(p.file) + '" alt=""><span>' + escape(p.title) + (i === prefs.painting ? ' ✓' : '') + '</span></button>'; }).join('') + '</div>'); }
  function enterArt() { if (state.panel) closePanel(); state.art = true; document.body.classList.add('art-open'); $('#art-mode').hidden = false; clearInterval(artTimer); if (prefs.artRotate !== false) artTimer = setInterval(function () { painting(prefs.painting+1); },60000); }
  function leaveArt() { state.art = false; clearInterval(artTimer); document.body.classList.remove('art-open'); $('#art-mode').hidden = true; $('[data-action="art"]').focus(); }
  function settings() { panel('settings',heading('Make it yours') + '<div class="option-list"><button data-action="edit">Organise apps<small>Arrange Your apps, More apps, and Hidden.</small></button><button data-action="art">Choose your painting<small>Thirteen paintings from your MCM collection.</small></button><button data-action="art-toggle">Art slideshow: ' + (prefs.artRotate === false ? 'Off' : 'On') + '<small>Change the painting every minute in Art mode.</small></button><button data-action="profiles">Who’s watching?</button><button data-action="tv-settings">TV settings</button><button data-action="stock">Open LG Home</button><button data-action="restore-home">Restore the LG Home button<small>Stops MCM’s Home-button mapping.</small></button></div>'); }
  function info(title,subtitle,paragraphs) { panel('info',heading(title) + (subtitle ? '<p class="eyebrow">' + escape(subtitle) + '</p>' : '') + paragraphs.filter(Boolean).map(function (p) { return '<p class="panel-copy">' + escape(p) + '</p>'; }).join('')); }
  function personal() {
    var day = Math.floor(Date.now()/86400000), film = DATA.films[day%DATA.films.length];
    state.film = film; state.recipe = DATA.recipes[day%DATA.recipes.length]; updateFilm(); updateRecipe();
    $('#tonight').innerHTML = '<button data-action="film">Watch &middot; ' + escape(film.title) + '</button><button data-action="recipe">Cook &middot; ' + escape(state.recipe.title) + '</button>';
  }

  function updateRecipe() { $('#recipe').innerHTML = '<span class="eyebrow">FROM THE MESA RECIPE BOX</span><strong>' + escape(state.recipe.title) + '</strong><small>' + escape(state.recipe.cuisine || '') + '</small><small>OK to open · Hold OK to shuffle</small>'; }
  function updateFilm() { $('#film-title').textContent = state.film.title + (state.film.year ? ' (' + state.film.year + ')' : ''); $('#film-source').textContent = 'From your MCM picks'; }
  function filmPanel() { var f=state.film; panel('film',heading(f.title) + '<p class="panel-copy">A pick from the MCM film collection.</p><div class="panel-actions"><button data-action="film-search">Find on the TV</button><button data-action="film-shuffle">Another film</button></div>'); }
  function inputs() { rootLuna('luna://com.webos.service.eim/getAllInputStatus',{}).then(function (r) { panel('inputs',heading('Inputs') + '<div class="option-list">' + (r.devices || []).map(function (d) { return '<button data-input="' + escape(d.appId) + '">' + escape(d.label || d.appId) + '</button>'; }).join('') + '<button data-action="inputs-app">Open TV input menu</button></div>'); }).catch(function () { launch('com.webos.app.inputs').catch(function () {}); }); }
  function action(name) {
    switch(name) {
      case 'home': if(state.art)leaveArt(); if(state.panel)closePanel(); $('#home').scrollTo({top:0,behavior:'smooth'}); $('.nav').focus(); break;
      case 'apps': appDirectory(false); break; case 'search': appDirectory(true); break;
      case 'profiles': showProfiles(); break; case 'close-panel': closePanel(); break;
      case 'live': launch('com.webos.app.livetv').catch(function () {}); break;
      case 'inputs': inputs(); break; case 'inputs-app': launch('com.webos.app.inputs').catch(function () {}); break;
      case 'tv-settings': launch('com.webos.app.settings').catch(function () {}); break;
      case 'stock': launch('com.webos.app.home').catch(function () {}); break;
      case 'settings': settings(); break; case 'edit': editApps(); break; case 'art': gallery(); break;
      case 'art-mode': enterArt(); break; case 'art-exit': leaveArt(); break;
      case 'art-prev': painting(prefs.painting-1); break; case 'art-next': painting(prefs.painting+1); break;
      case 'art-toggle': prefs.artRotate=prefs.artRotate === false; save(); settings(); break;
      case 'refresh-apps': refreshApps(); break;
      case 'recipe': info(state.recipe.title,state.recipe.cuisine,[state.recipe.descriptor,'From the MESA recipe collection. Full recipes are not yet connected in the original launcher.']); break;
      case 'film': filmPanel(); break;
      case 'film-shuffle': var pool=DATA.films; state.film=pool[Math.floor(Math.random()*pool.length)]; updateFilm(); filmPanel(); break;
      case 'film-search': launch('com.webos.app.voiceweb',{query:state.film.title}).catch(function () { openUrl('https://www.justwatch.com/uk/search?q='+encodeURIComponent(state.film.title)); }); break;
      case 'restore-home': panel('restore',heading('Restore LG Home')+'<p class="panel-copy">The Home button will open LG Home again. MCM stays installed and can still be opened from Apps.</p><div class="panel-actions"><button data-action="confirm-restore">Restore LG Home button</button></div>'); break;
      case 'confirm-restore': exec('/var/lib/mcm-home/restore-home.sh').then(function(){toast('LG Home button restored');}).catch(function(e){toast(e.message);}); break;
    }
  }
  document.addEventListener('click',function(e){
    if(state.held){state.held=false;return;}
    var b=e.target.closest('button'); if(!b)return;
    if(b.dataset.app)launch(b.dataset.app).catch(function(){});
    else if(b.dataset.action)action(b.dataset.action);
    else if(b.dataset.choice)changeApp(b.dataset.choice,b.dataset.id);
    else if(b.dataset.profile){prefs.profile='Bandit and Aries';save();$('#profile').textContent='B&A';$('#profile').setAttribute('aria-label',prefs.profile);closePanel();}
    else if(b.dataset.painting){painting(Number(b.dataset.painting));gallery();}
    else if(b.dataset.input)launch(b.dataset.input).catch(function(){});
  });
  function focusables() { var root=state.panel?$('#panel'):state.art?$('#art-mode'):$('#home'); return Array.from(root.querySelectorAll('button,input')).filter(function(b){return !b.disabled&&!b.closest('[hidden]')&&b.getClientRects().length;}); }
  function move(direction) {
    var from=document.activeElement, list=focusables(); if(!list.length)return;
    if(list.indexOf(from)<0){list[0].focus();return;}
    var r=from.getBoundingClientRect(), x=r.left+r.width/2,y=r.top+r.height/2;
    var target=null,best=Infinity;
    list.forEach(function(b){if(b===from)return;var t=b.getBoundingClientRect(),dx=t.left+t.width/2-x,dy=t.top+t.height/2-y;var primary=direction==='left'?-dx:direction==='right'?dx:direction==='up'?-dy:dy;var secondary=direction==='left'||direction==='right'?Math.abs(dy):Math.abs(dx);if(primary<=8)return;var score=primary+secondary*3;if(score<best){best=score;target=b;}});
    if(target){target.focus({preventScroll:true});target.scrollIntoView({behavior:'smooth',block:'nearest',inline:'nearest'});}
  }
  document.addEventListener('keydown',function(e){
    var key=e.key || '', code=e.keyCode;
    if(code===461||key==='Escape'){e.preventDefault();if(state.panel)closePanel();else if(state.art)leaveArt();else if($('#home').scrollTop>30)action('home');return;}
    var direction={37:'left',38:'up',39:'right',40:'down'}[code];
    if(direction){if(document.activeElement.tagName==='INPUT'&&(direction==='left'||direction==='right'))return;e.preventDefault();if(state.art){painting(prefs.painting+(direction==='left'?-1:1));return;}move(direction);return;}
    if(code===13||key==='Enter'){
      var b=document.activeElement;
      if(b.tagName!=='BUTTON')return;
      e.preventDefault();
      if(e.repeat||state.pressed)return;
      state.pressed=b;state.held=false;
      if(b.dataset.app||b.id==='recipe')state.hold=setTimeout(function(){state.held=true;if(b.dataset.app)appOptions(b.dataset.app);else{state.recipe=DATA.recipes[Math.floor(Math.random()*DATA.recipes.length)];updateRecipe();toast('Another recipe from the box');}},650);
    }
  });
  document.addEventListener('keyup',function(e){if(e.keyCode===13||e.key==='Enter'){clearTimeout(state.hold);if(!state.pressed)return;e.preventDefault();var b=state.pressed;state.pressed=null;if(state.held){state.held=false;}else if(b.isConnected)b.click();}});
  document.addEventListener('webOSRelaunch',function(){action('home');refreshApps();},true);
  document.addEventListener('visibilitychange',function(){if(!document.hidden){refreshApps();}});
  window.addEventListener('error',function(e){toast('MCM: '+e.message);});
  function clock(){ $('#clock').textContent=new Date().toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'}); }
  painting(prefs.painting);$('#profile').textContent='B&A';$('#profile').setAttribute('aria-label',prefs.profile);clock();setInterval(clock,30000);personal();$('.nav').focus();refreshApps();
  window.MCM = {state:state,prefs:prefs,action:action,refreshApps:refreshApps,move:move,launch:launch};
})();
