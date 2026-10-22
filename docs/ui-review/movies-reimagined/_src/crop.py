from PIL import Image, ImageEnhance
S='../../movies/'
def op(n): return Image.open(S+n).convert('RGB')
def save(im,name,size=None):
    if size: im=im.resize(size,Image.LANCZOS)
    im.save('img/'+name,quality=92)
a=op('02-movies-rows-toy-story-history.png')
# big posters 260x390 row 1 (y56..446)
for i,n in enumerate(['toystory','harry','mando','sheep','spider']):
    x=264+304*i; save(a.crop((x,56,x+260,446)),n+'.jpg')
b=op('03-movies-history-films-row.png')
for i,n in enumerate(['i-swear','bigshort','spr','wuthering']):
    x=568+304*i; save(b.crop((x,517,x+260,907)),n+'.jpg')
t=op('10-detail-tenzing-in-library.png')
save(t.crop((1524,60,1824,510)),'tenzing.jpg')
for i,n in enumerate(['schindler','kingdom','dunkirk']):
    x=1080+244*i; save(t.crop((x,708,x+200,1008)),n+'.jpg',(260,390))
c=op('12-detail-backrooms-not-in-library.png')
save(c.crop((1524,60,1824,510)),'backrooms.jpg')
for i,n in enumerate(['love','sleep','slither','goodluck','weapons','dune']):
    x=348+244*i; save(c.crop((x,634,x+200,934)),n+'.jpg',(260,390))
d=op('21-downloads.png'); save(d.crop((280,292,392,460)),'westworld.jpg')
# backdrops
e=op('20-my-library-recently-added.png'); save(e.crop((1000,48,1920,708)),'tenzing-bd-a.jpg')
f=op('30-stream-loading-overlay-upstairs.png'); 
f=ImageEnhance.Brightness(f).enhance(2.2); save(f,'tenzing-bd.jpg')
g=op('01-movies-top.png'); g=g.crop((760,60,1920,700)); g=ImageEnhance.Brightness(g).enhance(1.9); save(g,'backrooms-bd.jpg')
# library row posters (top only in screenshot) skip
