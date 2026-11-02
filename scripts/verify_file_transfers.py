"""独立PostgreSQL+独立端口验收文件传输；不连接现有业务库，可同时生成真实浏览器录屏。"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import html
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
parser.add_argument('--record',action='store_true')
parser.add_argument('--java',default='java',help='建议指定JDK21/bin/java.exe真实路径，避免Windows启动器派生子进程')
parser.add_argument('--name',default='文件上传下载演示_待填班级组号')
args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
if out.is_relative_to(ROOT): raise SystemExit('验收数据和视频须放在工作区外，避免推送测试文件或私人数据')
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
    if args.record:
        from playwright.sync_api import sync_playwright,expect
        large=out/'browser-large.bin';large.write_bytes(bytes([37])*12*1024*1024+b'browser-tail')
        small1=out/'small-a.txt';small1.write_text('小文件甲',encoding='utf-8')
        small2=out/'small-b.txt';small2.write_text('小文件乙',encoding='utf-8')
        with sync_playwright() as playwright:
            # 使用本机Chrome，无需另行下载整个测试浏览器。
            browser=playwright.chromium.launch(channel='chrome')
            context=browser.new_context(viewport={'width':1360,'height':1000},record_video_dir=out/'video',record_video_size={'width':1360,'height':1000},accept_downloads=True)
            context.request.post(base+'/auth/login',data={'username':'file-demo','password':'Offline-demo-123456'},headers={'Origin':base})
            page=context.new_page();errors=[]
            page.on('pageerror',lambda error:errors.append(str(error)))
            page.goto(base+'/file-center');expect(page.get_by_role('heading',name='文件中心')).to_be_visible()
            cdp=context.new_cdp_session(page);cdp.send('Network.enable')
            cdp.send('Network.emulateNetworkConditions',{'offline':False,'latency':15,'downloadThroughput':512*1024,'uploadThroughput':512*1024})
            page.get_by_role('checkbox',name='演示：故意损坏一个分片并观察自动重传').check()
            page.get_by_label('分片上传文件').set_input_files(str(large))
            expect(page.get_by_label('传输日志')).to_contain_text('CHUNK_CHECKSUM_MISMATCH',timeout=45000)
            page.wait_for_timeout(5500);page.get_by_role('button',name='暂停上传',exact=True).click()
            expect(page.get_by_role('button',name='继续上传',exact=True)).to_be_enabled(timeout=15000)
            before=context.request.get(base+'/files/mine').json()['files']
            page.screenshot(path=out/'01-upload-paused.png',full_page=True)
            page.reload();expect(page.get_by_text('待续传：',exact=False)).to_be_visible()
            page.get_by_label('分片上传文件').set_input_files(str(large))
            expect(page.get_by_label('传输日志')).to_contain_text('恢复服务端已确认',timeout=15000)
            expect(page.get_by_text('服务器合并与整文件校验已完成',exact=True)).to_be_visible(timeout=90000)
            passed('浏览器故意损坏自动重传，暂停后刷新重选原文件完成上传')
            page.screenshot(path=out/'02-upload-complete.png',full_page=True)
            page.get_by_label('批量上传文件').set_input_files([str(small1),str(small2)])
            expect(page.get_by_text('small-a.txt：成功',exact=True)).to_be_visible(timeout=15000)
            expect(page.get_by_text('small-b.txt：成功',exact=True)).to_be_visible()
            passed('浏览器批量上传与进度结果展示正常')
            row=page.get_by_role('row').filter(has=page.get_by_role('cell',name='browser-large.bin',exact=True))
            row.get_by_role('button',name='下载 / 续传',exact=True).click()
            page.wait_for_timeout(6500);page.get_by_role('button',name='暂停下载',exact=True).click()
            expect(page.get_by_role('button',name='继续下载 / 再次保存')).to_be_enabled(timeout=15000)
            cache=page.evaluate('''async () => { const db=await new Promise((ok,no)=>{const r=indexedDB.open('sailor-moon-transfers',1);r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});return await new Promise(ok=>{const r=db.transaction('transfers').objectStore('transfers').getAll();r.onsuccess=()=>ok(r.result.filter(x=>x.kind==='download'))}) }''')
            assert cache and cache[0]['received']>0,cache
            page.screenshot(path=out/'03-download-paused.png',full_page=True)
            page.reload();expect(page.get_by_role('heading',name='文件中心')).to_be_visible()
            row=page.get_by_role('row').filter(has=page.get_by_role('cell',name='browser-large.bin',exact=True))
            with page.expect_download(timeout=90000) as pending:
                row.get_by_role('button',name='4连接并发下载',exact=True).click()
            downloaded=out/'browser-downloaded.bin';pending.value.save_as(downloaded)
            assert hashlib.file_digest(downloaded.open('rb'),'sha256').hexdigest()==hashlib.file_digest(large.open('rb'),'sha256').hexdigest()
            passed('下载真实字节跨刷新保留，4连接补齐剩余块，导出文件SHA-256一致')
            page.screenshot(path=out/'04-download-verified.png',full_page=True)
            assert not errors,errors
            # 在同一真实录屏后半段展示生产源码片段与逐点讲解，便于补录真人配音。
            scenes=[
                ('1. 大文件分片与损坏重传','按2MiB切片，并发3片上传；整文件增量SHA-256只占固定缓冲。服务器发现哈希不一致返回400及分片编号；合并前磁盘损坏返回409和missing列表，只补这些片。','src/main/java/com/activityassistant/service/StorageService.java','public String mergeChunks',36),
                ('2. 小文件批量上传','同一multipart请求重复files字段，后端逐文件处理并返回结果。进度来自XHR上传事件；发送完成后仍需等待服务器处理，不把网络100%当作业务成功。','web/src/pages/Files.tsx','async function uploadBatch',19),
                ('3. 上传与下载断点续传','上传恢复会话编号和整文件指纹，再查询服务端uploaded。下载把真实Blob分块持久化到IndexedDB，并与确认字节数在同一事务提交；刷新后使用Range和If-Range只获取缺块。','web/src/lib/transfer-store.ts','export async function saveBlock',25),
                ('4. 两类进度条','上传=已确认字节+正在发送的字节；下载=持久化字节+当前接收字节。合并、磁盘写入和整文件校验结束后才显示业务完成。失败重试不重复累计字节。','web/src/lib/file-transfer.ts','export function progress',14),
                ('5. 高并发下载','单个文件的4路Range只是客户端并行。服务端使用独立FileChannel、固定大小流式缓冲、Java21虚拟线程，以及每节点128个下载准入限制；Nginx与共享存储支持横向扩展。实际24线程120次Range均逐字节校验。','src/main/java/com/activityassistant/api/FileController.java','if (!downloads.tryAcquire())',28)]
            for title,description,file,needle,count in scenes:
                text=(ROOT/file).read_text(encoding='utf-8');start=text.index(needle);snippet='\n'.join(text[start:].splitlines()[:count])
                page.goto('about:blank');page.set_content(f'<meta charset="utf-8"><style>body{{background:#101827;color:#eaf0f9;font:22px sans-serif;padding:32px}}h1{{color:#80d9ba}}p{{line-height:1.6}}pre{{background:#1e293b;border-radius:12px;padding:20px;font:16px/1.4 Consolas;white-space:pre-wrap}}small{{color:#b0c0d8}}</style><h1>{html.escape(title)}</h1><p>{html.escape(description)}</p><small>生产源码：{html.escape(file)}</small><pre>{html.escape(snippet)}</pre>')
                page.wait_for_timeout(8500)
            video=page.video
            context.close();video.save_as(out/(args.name+'.webm'));browser.close()
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
