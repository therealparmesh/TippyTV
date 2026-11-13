from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit,parse_qs
from datetime import datetime,timezone
from pathlib import Path
import json,time,threading,os,secrets
ROOT=Path(os.environ.get('JELLYFIN_QA_MEDIA','/tmp/jellyfin-live-fixture'))
ORIGIN=os.environ.get('JELLYFIN_QA_ORIGIN','http://10.0.2.2:18798')
PORT=int(os.environ.get('JELLYFIN_QA_PORT','18798'))
start=time.time()
state={'open':{},'opened':0,'closed':0,'guide':0,'favorites':set(),'writes':0,
       'tokens':set(),'logins':0,'logouts':0,'open_at_logout':None,'replacement':False,'guide_failure':False}
quick_connect={}
lock=threading.Lock()
channels=[{'Id':'a','Name':'Amber One','ChannelNumber':'1'},{'Id':'b','Name':'Blue Two','ChannelNumber':'2'}]
channels += [{'Id':f'c{i}','Name':f'Channel {i:04d}','ChannelNumber':str(i)} for i in range(3,1204)]
def iso(epoch):return datetime.fromtimestamp(epoch,timezone.utc).isoformat()
def timestamp(query,key,default):
    value=query.get(key,[None])[0]
    return datetime.fromisoformat(value.replace('Z','+00:00')).timestamp() if value else default
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
            with lock: out={**state,'open':len(state['open']),'favorites':sorted(state['favorites']),'tokens':len(state['tokens'])}
            return self.result(200,out)
        if path=='/QuickConnect/Connect':
            with lock: attempt=quick_connect.get(q.get('secret',[''])[0])
            return self.result(200,attempt) if attempt else self.result(404)
        if path=='/LiveTv/Channels':
            index=int(q.get('startIndex',['0'])[0]);count=int(q.get('limit',['256'])[0])
            with lock:
                token=self.headers.get('Authorization','').split('Token="')[-1].rstrip('"')
                valid=token in state['tokens']
                lineup=([dict(channels[0],Id='replacement'),*channels[1:]] if state['replacement'] else channels)
                rows=[{**x,'UserData':{'IsFavorite':x['Id'] in state['favorites']}} for x in lineup[index:index+count]]
            return self.result(200,{'Items':rows,'TotalRecordCount':len(lineup)}) if valid else self.result(401)
        if path=='/LiveTv/Programs':
            with lock:
                state['guide']+=1
                failure=state['guide_failure']
            if failure:return self.result(503,{})
            ids=q.get('channelIds',['a,b'])[0].split(',')
            now=time.time();begin=(int(now)//1800)*1800
            first=timestamp(q,'minEndDate',float('-inf'));last=timestamp(q,'maxStartDate',float('inf'))
            rows=[{'ChannelId':x,'Name':('Morning magazine' if x=='a' else 'Live bulletin') if n==0 else f'Upcoming show {n}',
                'StartDate':iso(begin+1800*n),'EndDate':iso(begin+1800*(n+1))}
                for n in range(7) for x in ids if x in ('a','b') and begin+1800*n<last and begin+1800*(n+1)>first]
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
        payload=self.rfile.read(size) if size else b''
        if path=='/QuickConnect/Initiate':
            secret=secrets.token_hex(24)
            with lock:
                attempt={'Code':f'{secrets.randbelow(1000000):06d}','Secret':secret,'Authenticated':False}
                quick_connect[secret]=attempt
            return self.result(200,attempt)
        if path=='/qa/approve':
            code=q.get('code',[''])[0]
            with lock:
                match=next((a for a in quick_connect.values() if a['Code']==code),None)
                if match:match['Authenticated']=True
            return self.result(204 if match else 404)
        if path in ('/Users/AuthenticateByName','/Users/AuthenticateWithQuickConnect'):
            fields=json.loads(payload)
            if path=='/Users/AuthenticateByName' and fields.get('Pw')=='reject': return self.result(401)
            with lock:
                if path=='/Users/AuthenticateWithQuickConnect':
                    secret=fields.get('Secret')
                    if not quick_connect.get(secret,{}).get('Authenticated'): return self.result(401)
                    del quick_connect[secret]
                state['logins']+=1; token='fixture-token-'+str(state['logins']);state['tokens'].add(token)
            return self.result(200,{'AccessToken':token,'User':{'Id':'fixture-user'}})
        if path=='/Sessions/Logout':
            token=self.headers.get('Authorization','').split('Token="')[-1].rstrip('"')
            with lock:
                valid=token in state['tokens']
                if valid:
                    state['tokens'].remove(token);state['logouts']+=1;state['open_at_logout']=len(state['open'])
            return self.result(204 if valid else 401)
        if path=='/qa/guide_failure':
            with lock:state['guide_failure']=q.get('enabled',['1'])[0]=='1'
            return self.result(200,{})
        if path=='/qa/replacement':
            with lock:state['replacement']=q.get('enabled',['1'])[0]=='1'
            return self.result(204)
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
