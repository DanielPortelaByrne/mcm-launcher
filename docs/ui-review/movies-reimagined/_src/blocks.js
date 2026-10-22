/* 22 search */
const kbRows=['1234567890','qwertyuiop','asdfghjkl','zxcvbnm'];
const kbd=(focus)=>{let k='';kbRows.forEach((r,ri)=>[...r].forEach((c,ci)=>{k+=`<div class="key ${c==focus?'f':''}" style="left:${176+ci*98+[0,0,49,98][ri]}px;top:${300+ri*98}px;width:90px">${c}</div>`}));
 const y=300+4*98;
 k+=`<div class="key w" style="left:176px;top:${y}px;width:190px">${ic('x',24)}&nbsp;Clear</div><div class="key w" style="left:378px;top:${y}px;width:580px">Space</div><div class="key w" style="left:970px;top:${y}px;width:186px">${ic('bksp',26)}&nbsp;Delete</div>`;return k};
const trending=()=>`<div class="abs" style="left:1232px;top:230px;width:592px"><div class="eyebrow" style="color:var(--dim)">${ic('trend',22)}Trending searches</div>
 ${[['tenzing','Tenzing','Film · 2026'],['backrooms','Backrooms','Film · 2026'],['westworld','Westworld','Series · 2016']].map(r=>`<div style="display:flex;align-items:center;gap:22px;margin-top:18px;height:104px;border-radius:24px;padding:0 20px;background:var(--glass);border:1px solid var(--line)"><div style="width:58px;height:78px;border-radius:9px;background:url(${img(r[0])}) center/cover"></div><div><div style="font-size:27px;font-weight:500">${r[1]}</div><div style="font-size:18px;color:var(--dim);margin-top:5px">${r[2]}</div></div><span style="margin-left:auto;color:var(--dim)">${ic('chev',26)}</span></div>`).join('')}
 <div class="eyebrow" style="color:var(--dim);margin-top:40px">Browse by theme</div>
 <div style="display:flex;flex-wrap:wrap;gap:12px;margin-top:18px">${['Mountaineering','Mind-benders','True stories','Heist','Slow burn','Sci-Fi'].map(t=>`<span class="chip" style="height:50px;font-size:21px">${t}</span>`).join('')}</div></div>`;
S['22-search']=()=>`<div class="screen"><div class="amb"></div>${ARCH}${rail('search')}
<div class="abs" style="left:176px;top:64px;right:96px;height:112px;border-radius:56px;background:rgba(255,242,222,.09);border:1px solid rgba(237,190,86,.5);box-shadow:0 0 0 6px rgba(237,190,86,.1),0 20px 50px rgba(0,0,0,.4);display:flex;align-items:center;padding:0 14px 0 42px;gap:22px;backdrop-filter:blur(20px)">
 <span style="color:var(--saff)">${ic('search',40)}</span><span style="width:3px;height:48px;background:var(--saff);border-radius:2px"></span><span style="font-size:36px;color:#8f8272;font-weight:300">Search films, series and documentaries</span>
 <span style="margin-left:auto;display:flex;align-items:center;gap:20px"><span style="font-size:18px;color:var(--muted);letter-spacing:.04em">Say a title, actor or theme</span><span class="mic">${ic('mic',38)}</span></span></div>
<div class="abs" style="left:176px;top:222px"><div class="eyebrow" style="color:var(--dim)"><i style="opacity:.5"></i>Type to search</div></div>
${kbd('q')}${trending()}
<div class="abs" style="left:176px;bottom:48px;display:flex;gap:36px;font-size:18px;color:var(--dim);letter-spacing:.04em">${key('MIC','Hold on your remote to speak')}${key('OK','Select')}${key('▲','Voice search')}</div>
<div class="grain"></div></div>`;

/* 23 voice */
const wave=(n,maxh)=>Array.from({length:n},(_,i)=>{const h=Math.max(14,Math.round(maxh*(0.25+0.75*Math.abs(Math.sin(i*.55)*Math.cos(i*.17+1)))*Math.exp(-Math.pow((i-n/2)/(n/2.6),2)*.5)));const c=RC[4-(i%5)];return `<i style="display:block;width:12px;height:${h}px;border-radius:6px;background:${c}"></i>`}).join('');
S['23-search-voice-listening']=()=>`<div class="screen"><div class="amb"></div>${ARCH}${rail('search')}
<div class="abs" style="left:176px;top:64px;right:96px;height:112px;border-radius:56px;background:rgba(255,242,222,.09);border:1px solid rgba(226,86,43,.7);box-shadow:0 0 0 6px rgba(226,86,43,.14),0 20px 50px rgba(0,0,0,.4);display:flex;align-items:center;padding:0 14px 0 42px;gap:22px;backdrop-filter:blur(20px)">
 <span style="color:var(--terra)">${ic('search',40)}</span><span style="font-size:38px;color:var(--cream)">Tenzing<span style="color:var(--dim)"> mountain</span></span>
 <span style="margin-left:auto;display:flex;align-items:center;gap:20px"><span style="font-size:18px;color:var(--terra);letter-spacing:.14em;font-weight:600">LISTENING</span><span class="mic">${ic('mic',38)}</span></span></div>
<div class="abs" style="left:176px;right:96px;top:230px;display:flex;flex-direction:column;align-items:center">
 <div style="position:relative;width:440px;height:440px;display:flex;align-items:center;justify-content:center">
  ${[4,3,2,1,0].map(i=>`<div style="position:absolute;border-radius:50%;width:${190+i*62}px;height:${190+i*62}px;background:${RC[i]}"></div>`).join('')}
  <div style="position:absolute;width:190px;height:190px;border-radius:50%;background:#f6ecdb;display:flex;align-items:center;justify-content:center;color:#1b0d06;box-shadow:0 10px 40px rgba(0,0,0,.5)">${ic('mic',84)}</div></div>
 <div style="display:flex;align-items:center;gap:8px;height:90px;margin-top:6px">${wave(44,86)}</div>
 <div class="title" style="font-size:50px;margin-top:14px">“Tenzing… mountain”</div>
 <div style="font-size:21px;color:var(--muted);margin-top:14px">Release the mic button when you’re done · Back to cancel</div></div>
<div class="grain"></div></div>`;

/* 30 loading */
S['30-stream-loading-overlay']=()=>`<div class="screen">
<div class="bd mk" style="top:0;bottom:0;right:0;width:1060px;background-image:url(${img('tenzing-bd-r')});filter:brightness(.95) saturate(1.05)"></div>
<div class="abs" style="inset:0;background:linear-gradient(90deg,var(--bg) 0%,var(--bg) 20%,rgba(11,8,7,.7) 42%,rgba(11,8,7,.2) 100%)"></div>
<div class="abs" style="inset:0;background:linear-gradient(180deg,rgba(11,8,7,.3),transparent 30%,rgba(11,8,7,.9) 100%)"></div>
<div style="position:absolute;inset:0;-webkit-mask-image:linear-gradient(180deg,#000 22%,transparent 50%);mask-image:linear-gradient(180deg,#000 22%,transparent 50%)">${ribbon('M300 -60V150A80 80 0 0 0 380 230H560A80 80 0 0 1 640 310V560',100,{op:.95})}</div>
<div class="abs" style="left:112px;bottom:120px;width:900px">
 <div class="eyebrow"><span style="width:12px;height:12px;border-radius:6px;background:var(--saff);box-shadow:0 0 0 7px rgba(237,190,86,.2)"></span>Now loading</div>
 <div class="title" style="font-size:132px;margin-top:26px">Tenzing</div>
 <div style="font-size:28px;color:#d7cbb9;margin-top:28px;font-weight:300">Finding a copy of Tenzing. This can take a minute.</div>
 <div style="margin-top:40px;width:780px;height:16px;border-radius:8px;background:rgba(246,236,219,.1);position:relative;overflow:hidden"><i style="position:absolute;left:10%;width:34%;top:0;bottom:0;border-radius:8px;background:var(--rb);-webkit-mask-image:linear-gradient(90deg,transparent,#000 25%,#000 75%,transparent);mask-image:linear-gradient(90deg,transparent,#000 25%,#000 75%,transparent)"></i></div>
 <div style="display:flex;gap:44px;margin-top:34px;font-size:21px">
  <div style="color:var(--saff);display:flex;gap:12px;align-items:center"><b style="width:10px;height:10px;border-radius:5px;background:var(--saff);display:block"></b>Finding peers</div>
  <div style="color:var(--dim);display:flex;gap:12px;align-items:center"><b style="width:10px;height:10px;border-radius:5px;background:var(--dim);opacity:.5;display:block"></b>Connecting</div>
  <div style="color:var(--dim);display:flex;gap:12px;align-items:center"><b style="width:10px;height:10px;border-radius:5px;background:var(--dim);opacity:.5;display:block"></b>Buffering</div></div>
 <div style="display:flex;gap:16px;margin-top:36px"><span class="chip" style="height:50px;font-size:20px"><span style="color:var(--dim)">Seeds</span>&nbsp;0</span><span class="chip" style="height:50px;font-size:20px"><span style="color:var(--dim)">Peers</span>&nbsp;0</span><span class="chip" style="height:50px;font-size:20px"><span style="color:var(--dim)">Speed</span>&nbsp;—</span></div>
</div>
<div class="abs" style="right:96px;bottom:110px;display:flex;align-items:center;gap:14px;color:var(--muted);font-size:20px"><span style="border:1px solid var(--line);border-radius:12px;padding:8px 16px;background:var(--glass)">Back</span>to cancel</div>
<div class="grain"></div></div>`;

/* 31 player */
const tl=(t)=>`<span style="font-variant-numeric:tabular-nums">${t}</span>`;
S['31-player-controls']=()=>`<div class="screen">
<div class="bd" style="inset:0;background-image:url(${img('tenzing-bd')});background-position:center"></div>
<div class="abs" style="inset:0;background:linear-gradient(180deg,rgba(8,6,5,.85) 0%,rgba(8,6,5,.12) 26%,rgba(8,6,5,.1) 52%,rgba(8,6,5,.94) 100%)"></div>
<div class="abs" style="left:96px;top:64px"><div class="eyebrow" style="font-size:15px"><i></i>Now playing</div><div class="title" style="font-size:60px;margin-top:14px">Tenzing</div><div style="font-size:21px;color:var(--muted);margin-top:12px">2026 · Drama, History · 2h 5m</div></div>
<div class="abs" style="right:96px;top:80px;font-size:22px;color:var(--muted);font-weight:500">9:41</div>
<div class="abs" style="left:0;right:0;top:640px;display:flex;justify-content:center;align-items:center;gap:100px;color:var(--cream)">
 <span style="opacity:.9">${ic('prev',44,1)}</span><span style="opacity:.9">${ic('b10',64)}</span>
 <span style="width:112px;height:112px;border-radius:56px;background:var(--cream);color:#1b120c;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 9px #f6bf33,0 0 0 18px #f08a25,0 0 0 27px #e2562b,0 0 0 36px #c2281d,0 0 0 45px #5d1710,0 24px 70px rgba(0,0,0,.55)">${ic('pause',50,1)}</span>
 <span style="opacity:.9">${ic('f10',64)}</span><span style="opacity:.3">${ic('next',44,1)}</span></div>
<div class="abs" style="left:96px;right:96px;top:872px">
 <div style="position:relative;height:20px">
  <div style="position:absolute;inset:0;border-radius:10px;background:var(--rb);opacity:.2"></div>
  <div style="position:absolute;left:0;top:0;bottom:0;width:33%;border-radius:10px;background:var(--rb);box-shadow:0 0 30px rgba(240,138,37,.35)"></div>
  ${[18,52,71,88].map(p=>`<i style="position:absolute;left:${p}%;top:-4px;width:4px;height:28px;background:rgba(11,8,7,.8);border-radius:2px"></i>`).join('')}
  <i style="position:absolute;left:calc(33% - 22px);top:-12px;width:44px;height:44px;border-radius:22px;background:radial-gradient(circle,#f6ecdb 0 24%,#f6bf33 24% 42%,#f08a25 42% 60%,#e2562b 60% 80%,#c2281d 80%);box-shadow:0 6px 24px rgba(0,0,0,.5)"></i></div>
 <div style="display:flex;justify-content:space-between;margin-top:26px;font-size:23px;color:var(--cream);font-weight:500">${tl('41:17')}<span style="color:var(--muted)">${tl('1:24:17')}&nbsp;left</span></div></div>
<div class="abs" style="left:96px;bottom:44px;display:flex;gap:16px"><span class="chip" style="height:54px;font-size:20px">${ic('cc',28)}Subtitles · Off</span><span class="chip" style="height:54px;font-size:20px">${ic('vol',26)}English · 5.1</span></div>
<div class="abs" style="right:96px;bottom:44px"><span class="chip" style="height:54px;width:54px;padding:0;justify-content:center">${ic('sliders',28)}</span></div>
<div class="grain"></div></div>`;

/* 00 logo */
const lock=(sz,fg)=>`<div style="display:flex;align-items:center;gap:${sz*.3}px">${markSvg(sz)}<div><div class="serif" style="font-size:${sz*.62}px;letter-spacing:-.02em;font-weight:500;color:${fg};line-height:.95">Cinema</div><div style="font-size:${sz*.16}px;letter-spacing:.34em;color:${fg};opacity:.6;font-weight:600;margin-top:${sz*.1}px">NOW SHOWING</div></div></div>`;
const sizes=(bg)=>`<div style="display:flex;gap:44px;align-items:center">${markSvg(120)}<div style="width:120px;height:120px;border-radius:30px;background:${bg};display:flex;align-items:center;justify-content:center;box-shadow:inset 0 0 0 1px rgba(128,128,128,.25)">${markSvg(84)}</div>${markSvg(56)}${markSvg(36)}</div>`;
S['00-logo']=()=>`<div class="screen">
<div class="amb"></div>
<div class="abs" style="left:0;top:0;width:960px;height:1080px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:90px">${lock(190,'#f6ecdb')}${sizes('#1a1310')}</div>
<div class="abs" style="left:960px;top:0;width:960px;height:1080px;background:#ecd9b8;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:90px">${lock(190,'#2a140c')}${sizes('#2a140c')}</div>
</div>`;
