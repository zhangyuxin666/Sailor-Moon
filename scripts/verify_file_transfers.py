"""独立PostgreSQL+独立端口验收文件传输；不连接现有业务库。"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import uuid
import httpx

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--war',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--java',default='java',help='建议指定JDK21/bin/java.exe真实路径，避免Windows启动器派生子进程')
args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
if out.is_relative_to(ROOT): raise SystemExit('验收数据须放在工作区外，避免推送测试文件或私人数据')
war=args.war.resolve()
assert war.is_file(),war
for port in (18543,18403):
    with socket.socket() as sock:
        assert sock.connect_ex(('127.0.0.1',port))!=0,f'端口{port}已使用；未改动现有服务'
name='sm-files-'+uuid.uuid4().hex[:8]
base='http://127.0.0.1:18403'
checks=[]
def passed(label): checks.append(label);print('PASS',label,flush=True)
def until(work,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            if work():return
        except (httpx.HTTPError,OSError):pass
        time.sleep(.5)
    raise AssertionError('等待服务就绪超时，请查看输出目录内app.log')
def sha(data):return hashlib.sha256(data).hexdigest()
client=httpx.Client(base_url=base,timeout=30,trust_env=False,headers={'Origin':base})
process=None;db_started=False
try:
    subprocess.run(['docker','run','-d','--rm','--name',name,'--label','io.sailor-moon.file-test=true',
        '-p','127.0.0.1:18543:5432','-e','POSTGRES_DB=filetest','-e','POSTGRES_USER=filetest',
        '-e','POSTGRES_PASSWORD=offline-file-test','pgvector/pgvector:pg16'],check=True,capture_output=True)
    db_started=True
    until(lambda:subprocess.run(['docker','exec',name,'pg_isready','-U','filetest','-d','filetest'],capture_output=True).returncode==0)
    env=dict(os.environ,SERVER_PORT='18403',PUBLIC_BASE_URL=base,
        SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:18543/filetest',SPRING_DATASOURCE_USERNAME='filetest',SPRING_DATASOURCE_PASSWORD='offline-file-test',
        AI_SERVICE_URL='http://127.0.0.1:9',AI_INTERNAL_TOKEN='offline-file-test-ai',QQ_GATEWAY_URL='http://127.0.0.1:9',QQ_GATEWAY_TOKEN='offline-file-test-qq',
        QQ_BOT_APP_ID='',QQ_BOT_APP_SECRET='',STORAGE_LOCAL_PATH=str(out/'uploads'),UPLOAD_MAX_MB='5',USER_STORAGE_QUOTA_MB='200',DB_READ_ENABLED='false')
    with (out/'app.log').open('wb') as log:
        process=subprocess.Popen([args.java,'-Dfile.encoding=UTF-8','-jar',str(war)],cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT)
    until(lambda:client.get('/health/ready').is_success)
    response=client.post('/auth/bootstrap',json={'username':'file-demo','password':'Offline-demo-123456','display_name':'文件传输演示','organization_name':'独立验收组织','accept_privacy':True})
    assert response.status_code==200,response.text
    passed('全新PostgreSQL迁移完成，独立测试账号登录')
    payload=(bytes(range(256))*7168)+b'tail-acceptance'
    chunk_size=256*1024
    init=client.post('/files/uploads',json={'filename':'integrity.bin','size':len(payload),'sha256':sha(payload),'chunk_size':chunk_size,'content_type':'application/octet-stream'})
    assert init.status_code==200,init.text
    upload=init.json()['upload_id']
    pieces=[payload[i:i+chunk_size] for i in range(0,len(payload),chunk_size)]
    def put(index,data=None):
        return client.put(f'/files/uploads/{upload}/chunks/{index}',content=pieces[index] if data is None else data,
            headers={'Content-Type':'application/octet-stream','X-Chunk-Sha256':sha(pieces[index])})
    corrupt=bytes([pieces[1][0]^255])+pieces[1][1:]
    bad=put(1,corrupt)
    assert bad.status_code==400 and bad.json()['code']=='CHUNK_CHECKSUM_MISMATCH' and bad.json()['chunk_index']==1,bad.text
    passed('损坏分片被400拒收，并返回CHUNK_CHECKSUM_MISMATCH及准确编号')
    for i in reversed(range(len(pieces))):
        if i!=1: assert put(i).status_code==200
    status=client.get(f'/files/uploads/{upload}').json()
    assert status['uploaded']==[i for i in range(len(pieces)) if i!=1]
    missing=client.post(f'/files/uploads/{upload}/complete')
    assert missing.status_code==409 and missing.json()['missing']==[1],missing.text
    assert put(1).status_code==200
    passed('乱序上传、已传分片查询和缺片定点重传正常')
    disk=out/'uploads/chunks'/upload/'000002.part'
    disk.write_bytes(b'X'*len(pieces[2]))
    bad_disk=client.post(f'/files/uploads/{upload}/complete')
    assert bad_disk.status_code==409 and bad_disk.json()['missing']==[2],bad_disk.text
    assert put(2).status_code==200
    done=client.post(f'/files/uploads/{upload}/complete');assert done.status_code==200,done.text
    asset=done.json()['file_id'];assert done.json()['sha256']==sha(payload)
    again=client.post(f'/files/uploads/{upload}/complete');assert again.json()['file_id']==asset,again.text
    passed('磁盘分块损坏可精确修复，合并整文件哈希一致，重复complete幂等')
    batch=client.post('/files/batch',files=[('files',('one.txt',b'one','text/plain')),('files',('two.txt',b'two','text/plain')),('files',('empty.txt',b'','text/plain'))])
    assert batch.status_code==200 and batch.json()['total']==3 and batch.json()['failed']==1,batch.text
    passed('一次multipart请求批量上传，逐文件成功/失败结果正确')
    url=f'/files/{asset}/download'
    head=client.head(url);assert head.status_code==200 and not head.content and int(head.headers['content-length'])==len(payload)
    etag=head.headers['etag']
    first=client.get(url,headers={'Range':'bytes=0-999'});rest=client.get(url,headers={'Range':'bytes=1000-','If-Range':etag})
    assert first.status_code==rest.status_code==206 and sha(first.content+rest.content)==sha(payload)
    assert client.get(url,headers={'Range':'bytes=-5'}).content==payload[-5:]
    assert client.get(url,headers={'Range':f'bytes={len(payload)}-'}).status_code==416
    assert client.get(url,headers={'Range':'bytes=1000-','If-Range':'"stale"'}).status_code==200
    assert client.get(url,headers={'If-None-Match':etag}).status_code==304
    assert httpx.get(base+url,trust_env=False).status_code==403
    passed('HEAD、206/416、后缀Range、If-Range防混拼、304及匿名访问控制正确')
    barrier=threading.Barrier(24);active=0;peak=0;lock=threading.Lock()
    def request_range(index):
        global active,peak
        with httpx.Client(base_url=base,cookies=client.cookies,timeout=30,trust_env=False) as worker:
            if index<24:barrier.wait(timeout=30)
            start=(index%6)*chunk_size;end=min(start+chunk_size-1,len(payload)-1)
            with lock:active+=1;peak=max(peak,active)
            try:
                result=worker.get(url,headers={'Range':f'bytes={start}-{end}','If-Range':etag})
                assert result.status_code==206 and result.content==payload[start:end+1]
                return len(result.content)
            finally:
                with lock:active-=1
    started=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        byte_count=sum(pool.map(request_range,range(120)))
    elapsed=time.perf_counter()-started
    load={'requests':120,'workers':24,'observed_in_flight':peak,'bytes':byte_count,'seconds':round(elapsed,3),'requests_per_second':round(120/elapsed,2)}
    passed('24工作线程/120次并发Range请求全部内容正确，无跨请求字节串扰')
    report={'checks':checks,'passed':len(checks),'load':load,'isolated_database':name,'production_data_touched':False}
    (out/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
finally:
    client.close()
    if process is not None:
        if os.name=='nt' and process.poll() is None:
            subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,check=False)
        elif process.poll() is None: process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    if db_started:subprocess.run(['docker','stop',name],check=False,capture_output=True,timeout=30)
