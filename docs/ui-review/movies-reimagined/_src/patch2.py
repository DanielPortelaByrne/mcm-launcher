import re
s=open('mock.html',encoding='utf8').read()

# ---------- CSS ----------
s=s.replace(":root{--bg:",":root{--rb:linear-gradient(180deg,#f6bf33 0 20%,#f08a25 20% 40%,#e2562b 40% 60%,#c2281d 60% 80%,#6b1a10 80%);--rbv:linear-gradient(90deg,#f6bf33 0 20%,#f08a25 20% 40%,#e2562b 40% 60%,#c2281d 60% 80%,#6b1a10 80%);--bg:",1)
s=s.replace(".eyebrow i{display:inline-block;width:34px;height:2px;background:linear-gradient(90deg,var(--terra),var(--saff))}",".eyebrow i{display:inline-block;width:40px;height:10px;border-radius:5px;background:var(--rb)}")
s=s.replace('.rowhead h2::before{content:"";width:5px;height:28px;border-radius:3px;background:linear-gradient(180deg,var(--terra),var(--saff))}','.rowhead h2::before{content:"";width:14px;height:30px;border-radius:7px;background:var(--rbv)}')
s=s.replace(".seg i{display:block;width:56px;height:4px;border-radius:2px;",".seg i{display:block;width:64px;height:10px;border-radius:5px;")
s=s.replace(".seg i.on::after{content:\"\";position:absolute;inset:0;width:62%;background:linear-gradient(90deg,var(--terra),var(--saff))}",".seg i.on{background:var(--rb)}")
s=s.replace(".dl .bar{height:7px;border-radius:4px;background:rgba(246,236,219,.1);",".dl .bar{height:14px;border-radius:7px;background:rgba(246,236,219,.08);")
s=s.replace(".dl .bar i{position:absolute;left:0;top:0;bottom:0;border-radius:4px;background:linear-gradient(90deg,var(--terra),var(--saff))}",".dl .bar i{position:absolute;left:0;top:0;bottom:0;border-radius:7px;background:var(--rb)}")
s=s.replace(".dl .bar i.ind{left:18%;width:22%;background:linear-gradient(90deg,transparent,var(--saff),transparent)}",".dl .bar i.ind{left:18%;width:22%;background:var(--rb);-webkit-mask-image:linear-gradient(90deg,transparent,#000 30%,#000 70%,transparent);mask-image:linear-gradient(90deg,transparent,#000 30%,#000 70%,transparent)}")
s=s.replace(".rail .nv.on::before{content:\"\";position:absolute;left:-16px;top:20px;width:4px;height:20px;border-radius:2px;background:var(--saff)}",".rail .nv.on::before{content:\"\";position:absolute;left:-17px;top:16px;width:8px;height:32px;border-radius:4px;background:var(--rbv)}")
s=s.replace("</style>",""".mic{width:84px;height:84px;border-radius:42px;display:flex;align-items:center;justify-content:center;color:#1d0d05;background:radial-gradient(circle at 50% 40%,#f6bf33,#e2562b);box-shadow:0 0 0 7px rgba(226,86,43,.25),0 0 0 15px rgba(240,138,37,.12)}
.arches{position:absolute;inset:0;width:1920px;height:1080px;pointer-events:none;-webkit-mask-image:linear-gradient(100deg,transparent 30%,#000 80%);mask-image:linear-gradient(100deg,transparent 30%,#000 80%)}
</style>""",1)

# ---------- JS helpers ----------
helpers = r'''
const RC=['#5d1710','#c2281d','#e2562b','#f08a25','#f6bf33'];
const ribbon=(d,w,o={})=>{const n=RC.length;return `<svg class="abs" style="left:0;top:0;pointer-events:none;${o.style||''}" width="1920" height="1080" viewBox="0 0 1920 1080" fill="none" opacity="${o.op??1}">${RC.map((c,i)=>`<path d="${d}" stroke="${c}" stroke-width="${w*(n-i)/n}" stroke-linejoin="round" stroke-linecap="butt"/>`).join('')}</svg>`};
const ARCH=`<svg class="arches" viewBox="0 0 1920 1080" opacity=".2"><g>
<path d="M1170 1100V560a150 150 0 01300 0v540z" fill="#e0a93a"/><path d="M1400 1100V470a130 130 0 01260 0v630z" fill="#c4532a"/>
<path d="M1640 1100V720a140 140 0 01280 0v380z" fill="#6e2214"/><path d="M1290 1100V820a160 160 0 01320 0v280z" fill="#4d5b2c" opacity=".9"/></g></svg>`;
const markSvg=(sz)=>`<svg width="${sz}" height="${sz}" viewBox="0 0 56 56"><circle cx="28" cy="28" r="27" fill="#6b1a10"/><circle cx="28" cy="28" r="22" fill="#c2281d"/><circle cx="28" cy="28" r="17" fill="#e2562b"/><circle cx="28" cy="28" r="12" fill="#f08a25"/><circle cx="28" cy="28" r="7.2" fill="#f6bf33"/><path d="M25.2 24.2v7.6a1 1 0 001.5.86l6.3-3.8a1 1 0 000-1.72l-6.3-3.8a1 1 0 00-1.5.86z" fill="#1b0d06"/></svg>`;
'''
s=s.replace("const logo=",helpers+"const logoOld=",1)
s=re.sub(r"const logoOld=`.*?`;\n","const logo=markSvg(56);\n",s,count=1,flags=re.S)
s=s.replace("trend:'","mic:'<rect x=\"9\" y=\"3\" width=\"6\" height=\"11.5\" rx=\"3\"/><path d=\"M5.5 11.2a6.5 6.5 0 0013 0M12 17.7V21M8.5 21h7\"/>',\ntrend:'",1)
s=s.replace('<div class="amb"></div><div class="arch"></div><div class="arch b"></div>','<div class="amb"></div>${ARCH}')
s=s.replace('<div class="btn sq">${ic(\'back\',30)}Back</div><div class="btn danger sq">','<div class="btn danger sq">')
s=s.replace('<div class="btn">${ic(\'play\',28,1)}Stream now</div><div class="btn sq">${ic(\'back\',30)}Back</div>','<div class="btn">${ic(\'play\',28,1)}Stream now</div>')
s=s.replace("box-shadow:0 40px 90px rgba(0,0,0,.7),0 0 0 1px rgba(255,242,222,.12)","box-shadow:10px 10px 0 #f6bf33,20px 20px 0 #f08a25,30px 30px 0 #e2562b,40px 40px 0 #c2281d,0 50px 90px rgba(0,0,0,.6)")

# ---------- big blocks ----------
a=s.index("/* 22 search */"); b=s.index("const id=location")
new=open('blocks.js',encoding='utf8').read()
s=s[:a]+new+"\n"+s[b:]
s=s.replace("""<div class="abs" style="left:176px;top:52px"><div class="eyebrow"><i></i>Request queue</div>""","""<div style="position:absolute;inset:0;-webkit-mask-image:linear-gradient(90deg,transparent 45%,#000 80%);mask-image:linear-gradient(90deg,transparent 45%,#000 80%)">${ribbon('M1990 82H1640A62 62 0 0 0 1640 206H1990',90,{op:.85})}</div>
<div class="abs" style="left:176px;top:52px"><div class="eyebrow"><i></i>Request queue</div>""")
open('mock.html','w',encoding='utf8').write(s)
r=open('render.sh').read()
r=r.replace("22-search 30-stream","22-search 23-search-voice-listening 30-stream").replace("31-player-controls)","31-player-controls 00-logo)")
open('render.sh','w').write(r)
print('ok')
