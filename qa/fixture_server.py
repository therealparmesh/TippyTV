from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit,parse_qs
from datetime import datetime,timezone
from pathlib import Path
import json,time,threading,os
ROOT=Path(os.environ.get('JELLYFIN_QA_MEDIA','/tmp/jellyfin-live-fixture'))
ORIGIN=os.environ.get('JELLYFIN_QA_ORIGIN','http://10.0.2.2:18798')
PORT=int(os.environ.get('JELLYFIN_QA_PORT','18798'))
start=time.time()
state={'open':{},'opened':0,'closed':0,'guide':0,'favorites':set(),'writes':0}
lock=threading.Lock()
channels=[{'Id':'a','Name':'Amber One','ChannelNumber':'1'},{'Id':'b','Name':'Blue Two','ChannelNumber':'2'}]
channels += [{'Id':f'c{i}','Name':f'Channel {i:04d}','ChannelNumber':str(i)} for i in range(3,1204)]
def iso(epoch):return datetime.fromtimestamp(epoch,timezone.utc).isoformat()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def result(self,status,data=None,kind='application/json'):
        if data is None: raw=b''
        elif isinstance(data,bytes): raw=data
        else:raw=json.dumps(data).encode()
        self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(raw)));self.end_headers()
        try:self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):
        u=urlsplit(self.path);q=parse_qs(u.query);path=u.path
        if path=='/qa/status':
            with lock: out={**state,'open':len(state['open']),'favorites':sorted(state['favorites'])}
            return self.result(200,out)
        if path=='/LiveTv/Channels':
            index=int(q.get('startIndex',['0'])[0]);count=int(q.get('limit',['256'])[0])
            with lock: rows=[{**x,'UserData':{'IsFavorite':x['Id'] in state['favorites']}} for x in channels[index:index+count]]
            return self.result(200,{'Items':rows,'TotalRecordCount':len(channels)})
        if path=='/LiveTv/Programs':
            with lock:state['guide']+=1
            ids=q.get('channelIds',['a,b'])[0].split(',')
            now=time.time();begin=(int(now)//1800)*1800
            rows=[{'ChannelId':x,'Name':('Morning magazine' if x=='a' else 'Live bulletin') if n==0 else f'Upcoming show {n}',
                'StartDate':iso(begin+1800*n),'EndDate':iso(begin+1800*(n+1))}
                for n in range(7) for x in ids if x in ('a','b')]
            page=int(q.get('startIndex',['0'])[0]);size=int(q.get('limit',['160'])[0])
            return self.result(200,{'Items':rows[page:page+size]})
        if path in ('/a.m3u8','/b.m3u8'):
            n=max(4,int((time.time()-start)/2)); file=path[1]
            lines=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:2',f'#EXT-X-MEDIA-SEQUENCE:{n-4}']
            for i in range(n-4,n+1):
                if i%12==0: lines.append('#EXT-X-DISCONTINUITY')
                lines.extend(['#EXTINF:2.0,',f'{file}{i%12}.ts'])
            return self.result(200,('\n'.join(lines)+'\n').encode(),'application/vnd.apple.mpegurl')
        name=path.lstrip('/')
        if '/' not in name and name.endswith('.ts') and (ROOT/name).is_file():
            return self.result(200,(ROOT/name).read_bytes(),'video/mp2t')
        return self.result(404,{})
    def do_POST(self):
        u=urlsplit(self.path);q=parse_qs(u.query);path=u.path
        size=int(self.headers.get('Content-Length','0'))
        if size:self.rfile.read(size)
        if path=='/Users/AuthenticateByName':
            return self.result(200,{'AccessToken':'fixture-token','User':{'Id':'fixture-user'}})
        if path.startswith('/Items/') and path.endswith('/PlaybackInfo'):
            channel=path.split('/')[2]
            if channel not in ('a','b'): return self.result(200,{'MediaSources':[]})
            with lock:
                state['opened']+=1; key=str(state['opened']);state['open'][key]=channel
            return self.result(200,{'PlaySessionId':key,'MediaSources':[{'Id':channel,'IsRemote':True,'Container':'hls','SupportsDirectPlay':True,'LiveStreamId':key,'Path':f'{ORIGIN}/{channel}.m3u8','TranscodingUrl':f'/{channel}.m3u8'}]})
        if path=='/LiveStreams/Close':
            key=q.get('liveStreamId',[''])[0]
            with lock:
                if state['open'].pop(key,None) is not None:state['closed']+=1
            return self.result(204)
        if '/FavoriteItems/' in path:
            with lock:state['favorites'].add(path.split('/')[-1]);state['writes']+=1
            return self.result(200,{})
        return self.result(404,{})
    def do_DELETE(self):
        path=urlsplit(self.path).path
        if '/FavoriteItems/' in path:
            with lock:state['favorites'].discard(path.split('/')[-1]);state['writes']+=1
        return self.result(204)
if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
