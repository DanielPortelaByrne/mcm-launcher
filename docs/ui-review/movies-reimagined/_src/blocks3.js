/* ---------- player (31/32/33) ---------- */
const CH=[['Kathmandu',0],['The approach',850],['Khumbu Icefall',2020],['Camp IV',3500],['The ridge',4740],['Summit',5910],['Descent',6720]]; // seconds
const TOTAL=7534; // 2:05:34
const fmt=s=>{s=Math.round(s);const h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(x).padStart(2,'0')};
const chOf=s=>{let i=0;CH.forEach((c,k)=>{if(s>=c[1])i=k});return i};
const wy=(x,o)=>o.y+o.amp*Math.sin((x-o.x0)/o.wl*2*Math.PI);
const wpath=(o,xe)=>{let d=`M${o.x0} ${wy(o.x0,o).toFixed(1)}`;for(let x=o.x0+4;x<=xe;x+=4)d+=` L${x} ${wy(x,o).toFixed(1)}`;return d+` L${xe} ${wy(xe,o).toFixed(1)}`};
let _sid=0;
const waveScrub=(o)=>{const id='c'+(++_sid);const px=o.x0+(o.x1-o.x0)*o.p;const full=wpath(o,o.x1);const n=RC.length;
 const layer=(d,w)=>RC.map((c,i)=>`<path d="${d}" stroke="${c}" stroke-width="${w*(n-i)/n}" stroke-linejoin="round" stroke-linecap="round"/>`).join('');
 const dots=CH.slice(1).map(c=>{const x=o.x0+(o.x1-o.x0)*c[1]/TOTAL;return `<circle cx="${x}" cy="${wy(x,o).toFixed(1)}" r="7" fill="#0b0807" stroke="#f6ecdb" stroke-width="3" opacity="${x<px?1:.7}"/>`}).join('');
 const ty=wy(px,o);
 return `<svg class="abs" style="left:0;top:0;pointer-events:none" width="1920" height="1080" viewBox="0 0 1920 1080" fill="none">
 <defs><clipPath id="${id}"><rect x="0" y="0" width="${px}" height="1080"/></clipPath></defs>
 <g opacity=".22">${layer(full,o.th)}</g>
 <g clip-path="url(#${id})" style="filter:drop-shadow(0 0 14px rgba(240,138,37,.4))">${layer(full,o.th)}</g>
 ${dots}
 <circle cx="${px}" cy="${ty}" r="30" fill="#0b0807" opacity=".35"/>
 <circle cx="${px}" cy="${ty}" r="24" fill="#c2281d"/><circle cx="${px}" cy="${ty}" r="19" fill="#e2562b"/><circle cx="${px}" cy="${ty}" r="14" fill="#f08a25"/><circle cx="${px}" cy="${ty}" r="9" fill="#f6bf33"/><circle cx="${px}" cy="${ty}" r="4.5" fill="#f6ecdb"/>
 </svg>`};
const TH=i=>`img/th-${i+1}.jpg`;
const topbar=(extra='')=>`<div class="abs" style="left:96px;top:64px"><div class="eyebrow" style="font-size:15px"><i></i>${extra||'Now playing'}</div><div class="title" style="font-size:60px;margin-top:14px">Tenzing</div><div style="font-size:21px;color:var(--muted);margin-top:12px">2026 · Drama, History · 2h 5m</div></div>
<div class="abs" style="right:96px;top:80px;font-size:22px;color:var(--muted);font-weight:500">9:41</div>`;
const still=(dim)=>`<div class="bd" style="inset:0;background-image:url(${img('tenzing-bd')});background-position:center"></div>
<div class="abs" style="inset:0;background:linear-gradient(180deg,rgba(8,6,5,.85) 0%,rgba(8,6,5,${dim}) 26%,rgba(8,6,5,${dim}) 52%,rgba(8,6,5,.95) 100%)"></div>`;
const times=(cur,y)=>`<div class="abs" style="left:96px;right:96px;top:${y}px;display:flex;justify-content:space-between;font-size:23px;color:var(--cream);font-weight:500">${tl(fmt(cur))}<span style="color:var(--muted)">${tl(fmt(TOTAL-cur))}&nbsp;left</span></div>`;

/* 31 – playing, controls up */
S['31-player-controls']=()=>`<div class="screen">${still(.12)}${topbar()}
<div class="abs" style="left:0;right:0;top:640px;display:flex;justify-content:center;align-items:center;gap:100px;color:var(--cream)">
 <span style="opacity:.9">${ic('prev',44,1)}</span><span style="opacity:.9">${ic('b10',64)}</span>
 <span style="width:112px;height:112px;border-radius:56px;background:var(--cream);color:#1b120c;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 9px #f6bf33,0 0 0 18px #f08a25,0 0 0 27px #e2562b,0 0 0 36px #c2281d,0 0 0 45px #5d1710,0 24px 70px rgba(0,0,0,.55)">${ic('pause',50,1)}</span>
 <span style="opacity:.9">${ic('f10',64)}</span><span style="opacity:.3">${ic('next',44,1)}</span></div>
${waveScrub({x0:96,x1:1824,y:900,amp:13,wl:150,p:2477/TOTAL,th:30})}
<div class="abs" style="left:96px;top:944px;font-size:17px;color:var(--saff);letter-spacing:.14em;font-weight:600">CH 3 · KHUMBU ICEFALL</div>
${times(2477,950).replace('left:96px;right:96px','left:96px;right:96px;justify-content:flex-end')}
<div class="abs" style="left:96px;bottom:40px;display:flex;gap:16px"><span class="chip" style="height:54px;font-size:20px">${ic('cc',28)}Subtitles · Off</span><span class="chip" style="height:54px;font-size:20px">${ic('vol',26)}English · 5.1</span><span class="chip" style="height:54px;font-size:20px">${ic('film',26)}4K HDR</span></div>
<div class="abs" style="right:96px;bottom:40px"><span class="chip" style="height:54px;width:54px;padding:0;justify-content:center">${ic('sliders',28)}</span></div>
<div class="grain"></div></div>`;

/* 32 – scrubbing with frame preview */
S['32-player-scrubbing']=()=>{const t=4780,p=t/TOTAL,x0=96,x1=1824,px=x0+(x1-x0)*p;const ci=chOf(t);const cw=420,ch=236;let cx=Math.min(Math.max(px-cw/2,96),1824-cw);
 const ghost=(dx,i,op)=>`<div class="abs" style="left:${cx+dx}px;top:${560+ (ch-150)/2}px;width:266px;height:150px;border-radius:14px;background:url(${TH(i)}) center/cover;opacity:${op};box-shadow:0 10px 30px rgba(0,0,0,.5),inset 0 0 0 1px rgba(255,242,222,.15)"></div>`;
 return `<div class="screen"><div class="bd" style="inset:0;background-image:url(${img('tenzing-bd')});background-position:center;filter:brightness(.8)"></div>
<div class="abs" style="inset:0;background:linear-gradient(180deg,rgba(8,6,5,.85) 0%,rgba(8,6,5,.15) 24%,rgba(8,6,5,.3) 48%,rgba(8,6,5,.95) 100%)"></div>
${topbar('Scrubbing')}
<div class="abs" style="left:96px;right:96px;top:196px;height:20px"></div>
${ghost(-300,3,.55)}${ghost(cw+34,5,.55)}
<div class="abs" style="left:${cx}px;top:560px;width:${cw}px;height:${ch}px;border-radius:20px;background:url(${TH(4)}) center/cover;box-shadow:0 0 0 4px var(--cream),10px 10px 0 4px #f6bf33,20px 20px 0 4px #f08a25,0 40px 80px rgba(0,0,0,.65)">
 <div class="abs" style="left:0;right:0;bottom:0;height:90px;border-radius:0 0 20px 20px;background:linear-gradient(180deg,transparent,rgba(8,6,5,.85))"></div>
 <div class="abs serif" style="left:20px;bottom:14px;font-size:44px;color:var(--cream);letter-spacing:-.01em">${fmt(t)}</div></div>
<div class="abs" style="left:${cx}px;top:${560+ch+34}px;width:${cw}px;text-align:center"><div style="font-size:15px;letter-spacing:.2em;color:var(--saff);font-weight:600">CHAPTER ${ci+1} OF ${CH.length}</div><div class="serif" style="font-size:30px;margin-top:6px">${CH[ci][0]}</div></div>
${waveScrub({x0,x1,y:910,amp:13,wl:150,p,th:30})}
<div class="abs" style="left:${px-1.5}px;top:${560+ch+8}px;width:3px;height:${910-(560+ch+8)-34}px;background:linear-gradient(180deg,rgba(246,236,219,0),rgba(246,236,219,.8))"></div>
<div class="abs" style="left:${x0+(x1-x0)*2477/TOTAL-70}px;top:956px;width:140px;text-align:center;font-size:15px;letter-spacing:.12em;color:var(--muted)">RESUME POINT · 41:17</div>
<div class="abs" style="left:96px;right:96px;top:1000px;display:flex;justify-content:space-between;font-size:18px;color:var(--dim)">${tl('0:00')}<span style="display:flex;gap:28px">${key('◀ ▶','Scrub')}${key('OK','Play from here')}${key('Back','Cancel')}</span>${tl(fmt(TOTAL))}</div>
<div class="grain"></div></div>`};

/* 33 – paused, rich info */
const spec=(l,v,sub)=>`<div><div style="font-size:14px;letter-spacing:.16em;color:var(--dim);font-weight:600">${l}</div><div style="font-size:25px;margin-top:6px;font-weight:500">${v}</div>${sub?`<div style="font-size:17px;color:var(--muted);margin-top:3px">${sub}</div>`:''}</div>`;
const bitrate=(w,h)=>{const pts=Array.from({length:61},(_,i)=>{const x=i*w/60;const y=h*(.5+.28*Math.sin(i*.35)+.14*Math.sin(i*1.1+1)+.06*Math.sin(i*2.7));return [x,y]});const d=pts.map((p,i)=>(i?'L':'M')+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' ');
 return `<svg width="${w}" height="${h+10}" viewBox="0 -5 ${w} ${h+10}" fill="none">${RC.map((c,i)=>`<path d="${d}" stroke="${c}" stroke-width="${10*(5-i)/5+1}" stroke-linejoin="round" stroke-linecap="round"/>`).join('')}</svg>`};
S['33-player-paused']=()=>{const cur=2477;const ci=chOf(cur);
 return `<div class="screen"><div class="bd" style="inset:0;background-image:url(${img('tenzing-bd')});background-position:center;filter:blur(6px) brightness(.7) saturate(.9);transform:scale(1.04)"></div>
<div class="abs" style="inset:0;background:linear-gradient(90deg,rgba(8,6,5,.94) 0%,rgba(8,6,5,.78) 45%,rgba(8,6,5,.5) 100%)"></div>
<div class="abs" style="inset:0;background:linear-gradient(180deg,rgba(8,6,5,.5),transparent 22%,transparent 50%,rgba(8,6,5,.96) 100%)"></div>
<div class="abs" style="left:96px;top:70px;width:900px">
 <div class="eyebrow"><span style="display:inline-flex;width:30px;height:30px;border-radius:15px;background:var(--rb);align-items:center;justify-content:center;color:#1b0d06">${ic('pause',16,1)}</span>Paused</div>
 <div class="title" style="font-size:104px;margin-top:22px">Tenzing</div>
 <div style="margin-top:22px">${meta(['<span class="cert">12</span>','2026','2h 5m','Drama · History · Thriller',imdb('7.5')])}</div>
 <div class="syn" style="margin-top:20px;width:800px;font-size:24px">The untold and inspiring tale of one of the most important mountain climbers in history who, with Edmund Hillary, became the first person to reach the summit of Mount Everest.</div>
 <div style="margin-top:28px;display:flex;gap:18px;align-items:center"><div class="btn primary">${ic('play',30,1)}Resume</div><div class="btn">${ic('back',28)}Start over</div><span style="font-size:20px;color:var(--muted);margin-left:14px">Ends at <b style="color:var(--cream);font-weight:600">11:34 PM</b></span></div>
</div>
<!-- stream card -->
<div class="abs" style="left:1130px;top:70px;width:694px;border-radius:30px;background:rgba(20,14,11,.62);border:1px solid var(--line);backdrop-filter:blur(26px);padding:30px 36px 28px;box-shadow:0 30px 80px rgba(0,0,0,.5)">
 <div style="display:flex;align-items:center;justify-content:space-between"><div class="eyebrow" style="font-size:15px"><i></i>Stream</div><span style="display:flex;align-items:center;gap:10px;font-size:18px;color:#8fd6a8;font-weight:500"><b style="width:10px;height:10px;border-radius:5px;background:#5fd08a;display:block;box-shadow:0 0 0 5px rgba(95,208,138,.2)"></b>Healthy</span></div>
 <div style="display:grid;grid-template-columns:1fr 1fr;gap:26px 30px;margin-top:26px">
  ${spec('RESOLUTION','2160p · 4K','HDR10 · 23.976 fps')}${spec('VIDEO','HEVC 10-bit','18.4 Mbps')}
  ${spec('AUDIO','English · Atmos 7.1','E-AC-3 · 768 kbps')}${spec('SUBTITLES','Off','12 languages available')}
  ${spec('SIZE','14.8 GB','Buffered 3m 20s ahead')}${spec('NETWORK','142 peers · 96 seeds','24.6 MB/s down')}</div>
 <div style="margin-top:28px;padding-top:22px;border-top:1px solid var(--line)"><div style="display:flex;justify-content:space-between;font-size:14px;letter-spacing:.16em;color:var(--dim);font-weight:600;margin-bottom:12px"><span>BITRATE · LAST 60 S</span><span style="color:var(--saff)">PEAK 31 MBPS</span></div>${bitrate(620,52)}</div>
</div>
<!-- chapters -->
<div class="abs rowhead" style="left:96px;top:600px;width:1728px"><h2>Chapters</h2><span class="ct">${CH.length}</span></div>
${CH.slice(0,6).map((c,k)=>{const x=96+k*292;const on=k==ci;return `<div class="abs" style="left:${x}px;top:672px;width:268px;${on?'transform:scale(1.06);transform-origin:50% 30%;z-index:3':''}"><div style="width:268px;height:151px;border-radius:16px;background:url(${TH(k)}) center/cover;box-shadow:${on?'0 0 0 4px var(--cream),0 0 0 9px rgba(237,190,86,.28),0 24px 50px rgba(0,0,0,.6)':'0 12px 30px rgba(0,0,0,.5),inset 0 0 0 1px rgba(255,242,222,.08)'};position:relative;${on?'':'opacity:.8'}">
  <span style="position:absolute;left:10px;top:10px;background:rgba(11,8,7,.7);backdrop-filter:blur(8px);border-radius:9px;padding:4px 10px;font-size:15px;font-weight:700;color:var(--cream)">${k+1}</span>
  ${on?`<span style="position:absolute;right:10px;top:10px;background:var(--rb);border-radius:9px;padding:4px 10px;font-size:13px;font-weight:700;letter-spacing:.14em;color:#1b0d06">NOW</span><i style="position:absolute;left:0;bottom:0;height:5px;width:${((cur-c[1])/(CH[k+1][1]-c[1])*100).toFixed(0)}%;background:var(--rb)"></i>`:''}</div>
  <div style="margin-top:14px;font-size:21px;font-weight:${on?600:500};color:${on?'var(--cream)':'#c4b6a3'};white-space:nowrap">${c[0]}</div><div style="font-size:16px;color:var(--dim);margin-top:3px">${fmt(c[1])}</div></div>`}).join('')}
${waveScrub({x0:96,x1:1824,y:948,amp:11,wl:150,p:cur/TOTAL,th:26})}
<div class="abs" style="left:96px;right:96px;top:984px;display:flex;justify-content:space-between;font-size:21px;color:var(--cream);font-weight:500">${tl(fmt(cur))}<span style="color:var(--muted)">${tl(fmt(TOTAL-cur))}&nbsp;left</span></div>
<div class="grain"></div></div>`};

