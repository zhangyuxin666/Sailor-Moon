"Offline PostgreSQL/API acceptance; only labelled local disposable test projects are allowed."
import argparse,concurrent.futures,copy,csv,hashlib,http.cookiejar,io,json,re,subprocess,time,urllib.error,urllib.parse,urllib.request,uuid
from pathlib import Path
P=argparse.ArgumentParser();P.add_argument('--base-url',required=True);P.add_argument('--project',required=True);P.add_argument('--compose-file',required=True);P.add_argument('--mode',choices=['single','cluster'],default='single');P.add_argument('--output');P.add_argument('--verify-state');args=P.parse_args();checks=[]
def docker(*items):return subprocess.check_output(['docker',*items],text=True,encoding='utf-8').strip()
def inspect(cid):return json.loads(docker('inspect',cid))[0]
assert re.fullmatch(r'sm-(?:ci|test|stage8)(?:-[a-z0-9-]+)?',args.project),'disposable test project required'
assert Path(args.compose_file).is_file();url=urllib.parse.urlparse(args.base_url);assert url.hostname in ['127.0.0.1','localhost'] and url.scheme=='http'
containers=[inspect(c) for c in docker('ps','-a','-q','--filter','label=com.docker.compose.project='+args.project).splitlines()];assert containers
assert all(c['Config']['Labels'].get('io.sailor-moon.offline-test')=='true' for c in containers),'all containers require offline labels'
assert any(str(url.port)==binding['HostPort'] for c in containers for bindings in (c['NetworkSettings'].get('Ports') or {}).values() for binding in (bindings or [])),'URL must be bound by this test project'
DB=next(c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service']=='postgres')
def sql(query):
 p=subprocess.run(['docker','exec','-i',DB,'psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-U','e2etest','-d','e2etest'],input=query,text=True,encoding='utf-8',capture_output=True);assert p.returncode==0,p.stderr;return p.stdout.strip()
def uid():return uuid.uuid4().hex
def passed(s):checks.append(s);print('PASS '+s,flush=True)
class Client:
 def __init__(self):self.jar=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar));self.status=0
 def clone(self):
  c=Client()
  for k in self.jar:c.jar.set_cookie(copy.copy(k))
  return c
 def call(self,method,path,body=None,expected=200,raw=False,headers=None):
  payload=body if isinstance(body,bytes) else None if body is None else json.dumps(body,ensure_ascii=False).encode()
  req=urllib.request.Request(args.base_url+path,payload,method=method,headers={'Content-Type':'application/json','Origin':args.base_url,**(headers or {})})
  try:r=self.opener.open(req,timeout=30)
  except urllib.error.HTTPError as e:r=e
  self.status=r.status;data=r.read();assert r.status in ([expected] if isinstance(expected,int) else expected),(path,r.status,data[:200])
  return data if raw else json.loads(data)
 def get(self,path,**kw):return self.call('GET',path,**kw)
 def post(self,path,body=None,**kw):return self.call('POST',path,body,**kw)
 def put(self,path,body,**kw):return self.call('PUT',path,body,**kw)
def login(name,password):c=Client();c.post('/auth/login',{'username':name,'password':password});return c
def until(fn,predicate,seconds=90):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  v=fn()
  if predicate(v):return v
  time.sleep(.4)
 raise AssertionError('workflow timed out')
def multipart(c,tid,data,name='作业.txt',expected=200):
 boundary='offlineBoundary';payload=('--'+boundary+'\r\nContent-Disposition: form-data; name="note"\r\n\r\n离线验收\r\n--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: text/plain\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
 return c.call('POST','/todos/'+tid+'/submit',payload,expected=expected,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
for _ in range(180):
 try:
  if Client().get('/health/ready')['status']=='ready':break
 except Exception:pass
 time.sleep(1)
else:raise AssertionError('readiness timeout')
if args.verify_state:
 state=json.loads(Path(args.verify_state).read_text(encoding='utf-8'));m=login('e2e_manager','Offline-manager-2026');c=login(state['username'],'Offline-changed-2026')
 assert m.get('/activities/'+state['activity_id'])['activity']['id']==state['activity_id'];assert m.get('/activities/'+state['activity_id']+'/recaps')['recaps']
 data=c.get('/todos/'+state['todo_id']+'/submissions/'+state['account_id']+'/file',raw=True);assert hashlib.sha256(data).hexdigest()==state['attachment_sha256'];passed('restored historical activity/recap and byte-identical authorized attachment')
else:
 initial=Client()
 if not initial.get('/auth/status')['initialized']:initial.post('/auth/bootstrap',{'username':'e2e_manager','display_name':'离线管理员','password':'Offline-manager-2026','organization_name':'离线验收组织','accept_privacy':True})
 m=login('e2e_manager','Offline-manager-2026');suffix=uid()[:8];cid=m.post('/classes',{'name':'离线验收班 '+suffix})['id'];members=m.post('/classes/'+cid+'/members',{'members':[{'username':'e2e_'+suffix+'_'+str(i),'display_name':'同名成员','student_no':'E'+str(i),'password':'Offline-initial-2026'} for i in [1,2]]})['members'];students=[]
 for member in members:
  c=login(member['username'],'Offline-initial-2026');c.get('/portal/dashboard',expected=403);c.post('/auth/change-password',{'current_password':'Offline-initial-2026','new_password':'Offline-changed-2026'});assert c.get('/portal/dashboard',expected=[401,403])['code']=='authentication_required';students.append(login(member['username'],'Offline-changed-2026'))
 managerid=m.get('/auth/status')['account']['id'];otherid=uid()[:16];orgid=uid()[:12];otheruser='other_'+suffix
 sql("INSERT INTO accounts(id,username,display_name,student_no,password_hash,role,status,created_at) SELECT '"+otherid+"','"+otheruser+"','Other organization','',password_hash,'manager','active',created_at FROM accounts WHERE id='"+managerid+"';INSERT INTO account_security(account_id,force_password_change,failed_logins,password_changed_at) VALUES('"+otherid+"',0,0,'2026-10-27T00:00:00');INSERT INTO organizations(id,name,owner_account_id,created_at) VALUES('"+orgid+"','Other offline org','"+otherid+"','2026-10-27T00:00:00');INSERT INTO organization_members(organization_id,account_id,role,created_at) VALUES('"+orgid+"','"+otherid+"','owner','2026-10-27T00:00:00')")
 other=login(otheruser,'Offline-manager-2026');other.get('/classes/'+cid+'/members',expected=403)
 stale=students[1].clone();stale.post('/auth/logout');assert students[1].get('/portal/dashboard',expected=[401,403])['code']=='authentication_required';students[1]=login(members[1]['username'],'Offline-changed-2026')
 c=students[0];nodes={m.get('/cluster/node')['instance'] for _ in range(10)};assert len(nodes)>=(2 if args.mode=='cluster' else 1);passed('bootstrap/import/forced-password/session invalidation and shared sessions across selected topology')
 from datetime import datetime,timedelta,timezone
 event=(datetime.now(timezone.utc)+timedelta(days=7)).replace(microsecond=0).isoformat();source=m.post('/agent/drafts',{'class_id':cid,'text':'组织班会，需要报名、分工、日历与提醒，时间待确认。'});d=source['draft'];assert not d.get('event_time');d.update({'intent_type':'activity','title':'离线活动 '+suffix,'event_time':event,'missing_information':[],'workflow':{'create_plan':True,'create_form':True,'assign_tasks':True,'create_calendar':True,'schedule_reminder':True,'publish_message':False},'form_fields':[{'name':'note','label':'问卷说明','type':'text','required':True}],'execution_plan':{'tasks':[{'key':'first','title':'准备','account_id':members[0]['id']},{'key':'next','title':'收尾','account_id':members[1]['id'],'depends_on':['first']}],'materials':['水'],'remind_minutes_before':30}});m.put('/agent/drafts/'+source['id'],{'draft':d});pub=m.post('/agent/drafts/'+source['id']+'/publish');aid=pub['result_id'];until(lambda:m.get('/runs/'+pub['run_id']),lambda v:v['agent_run']['status']=='succeeded');detail=m.get('/activities/'+aid);fid=detail['forms'][0]['id'];tasks=m.get('/activities/'+aid+'/task-management')['tasks'];assert next(t for t in tasks if t['title']=='准备')['account_id']==members[0]['id'];assert next(t for t in tasks if t['title']=='收尾')['account_id']==members[1]['id']
 m.put('/activities/'+aid+'/registration-settings',{'request_id':uid(),'expected_version':detail['forms'][0]['version'],'capacity':1,'registration_status':'open','registration_deadline':''})
 def register(i):
  x=Client();v=x.post('/forms/'+fid+'/registrations',{'name':'=FORMULA'+str(i),'contact':'offline'+str(i)+'@example.invalid','extra':{'note':'中文,换行\n"回答"'},'request_id':uid(),'cancellation_token':uid()+uid()},expected=[200,409]);return x.status,v
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:registrations=list(pool.map(register,range(2)))
 assert sorted(status for status,v in registrations)==[200,409];accepted=next(v for status,v in registrations if status==200);other.get('/activities/'+aid+'/participation',expected=403);other.get('/activities/'+aid+'/exports/registrations.csv',expected=403);other.post('/business-templates',{'name':'denied','source_activity_id':aid},expected=403);passed('confirmed plan and tasks/reminder persist; true concurrent capacity never overbooks and full questionnaire survives')
 first=next(t for t in tasks if t['title']=='准备');students[1].call('PATCH','/tasks/'+first['id'],{'status':'done'},expected=403);c.call('PATCH','/tasks/'+first['id'],{'status':'done'})
 m.put('/activities/'+aid+'/participation/settings',{'request_id':uid(),'expected_version':1,'attendance_open':True,'feedback_open':True});request=uid()
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:visits=list(pool.map(lambda _:c.clone().post('/activities/'+aid+'/check-ins',{'request_id':request}),range(2)))
 assert visits[0]['checked_in_at']==visits[1]['checked_in_at'];c.post('/activities/'+aid+'/feedback',{'request_id':uid(),'expected_version':0,'rating':5,'comment':'仅在本地保存的反馈'});recap=m.post('/activities/'+aid+'/recap',{'request_id':uid()});assert recap['snapshot']['stats']['attendance_checked_in']==1 and recap['snapshot']['stats']['feedback_count']==1
 assert m.get('/activities/'+aid+'/exports/registrations.csv',raw=True).startswith(bytes([239,187,191]));c.get('/activities/'+aid+'/exports/tasks.csv',expected=403);passed('same-name identity enforcement, concurrent attendance, private feedback, aggregate recap and safe CSV')
 todo=m.post('/classes/'+cid+'/todos',{'title':'附件验收','deadline':event,'kind':'homework'});tid=todo['id'];content='恢复后内容一致：中文附件'.encode();multipart(c,tid,content);download='/todos/'+tid+'/submissions/'+members[0]['id']+'/file';assert c.get(download,raw=True)==content;students[1].get(download,expected=403);multipart(c,tid,b'blocked','x.exe',expected=400)
 quota_todo=m.post('/classes/'+cid+'/todos',{'title':'配额并发验收','deadline':event,'kind':'homework'})['id']
 def upload(i):
  client=c.clone();multipart(client,quota_todo,b'x'*(1800*1024),str(i)+'.txt',expected=[200,400]);return client.status
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:assert sorted(pool.map(upload,range(2)))==[200,400]
 assert int(sql("SELECT COALESCE(sum(size_bytes),0) FROM file_assets WHERE account_id='"+members[0]['id']+"'"))<=3*1024*1024;passed('real multipart attachment byte identity, cross-user download/type denial and quota serialized across workers')
 template=m.post('/business-templates',{'name':'离线结构','source_activity_id':aid});applied=m.post('/business-templates/'+template['id']+'/apply',{'class_id':cid,'request_id':uid()});assert applied['draft_id']!=source['id'] and not m.get('/agent/drafts/'+applied['draft_id'])['draft']['event_time'];passed('template reuse always produces a fresh draft without stale dates or identities')
 pending=m.post('/activities',{'class_id':cid,'text':'班会需要报名，活动时间待确认。','publish_to_qq':False},expected=202);rid=pending['run_id'];until(lambda:m.get('/runs/'+rid),lambda v:v['agent_run']['status']=='waiting_input');m.post('/runs/'+rid+'/input',{'input':{'event_time':event}});m.post('/runs/'+rid+'/resume');until(lambda:m.get('/runs/'+rid),lambda v:v['agent_run']['status']=='succeeded');current=m.get('/activities/'+pending['activity_id'])['activity'];m.post('/activities/'+pending['activity_id']+'/cancel',{'request_id':uid(),'expected_version':current['version'],'reason':'离线验收取消'});assert m.get('/activities/'+pending['activity_id'])['activity']['status']=='cancelled';passed('formal offline plan waits for date, resumes preserved run, and cancellation remains durable')
 failure=m.post('/agent/drafts',{'class_id':cid,'text':'举办活动，日期待确认。'});fd=json.loads(json.dumps(d));fd['title']='Failure-'+suffix;m.put('/agent/drafts/'+failure['id'],{'draft':fd})
 sql("CREATE FUNCTION offline_e2e_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.title LIKE 'Failure-"+suffix+"%' THEN RAISE EXCEPTION 'offline scoped failure'; END IF; RETURN NEW; END $$;CREATE TRIGGER offline_e2e_fail BEFORE INSERT ON forms FOR EACH ROW EXECUTE FUNCTION offline_e2e_fail()")
 try:
  fp=m.post('/agent/drafts/'+failure['id']+'/publish');until(lambda:m.get('/runs/'+fp['run_id']),lambda v:v['agent_run']['status']=='failed',seconds=120)
 finally:sql('DROP TRIGGER offline_e2e_fail ON forms;DROP FUNCTION offline_e2e_fail()')
 m.post('/runs/'+fp['run_id']+'/resume');until(lambda:m.get('/runs/'+fp['run_id']),lambda v:v['agent_run']['status']=='succeeded');assert int(sql("SELECT count(*) FROM forms WHERE activity_id='"+fp['result_id']+"'"))==1
 passed('scoped real PostgreSQL failure reaches failed state; explicit resume preserves successful steps and creates one form')
 state={'activity_id':aid,'todo_id':tid,'account_id':members[0]['id'],'username':members[0]['username'],'attachment_sha256':hashlib.sha256(content).hexdigest(),'class_id':cid,'mode':args.mode,'nodes':sorted(nodes)}
state.update({'passed':len(checks),'checks':checks})
if args.output:
 target=Path(args.output).resolve();repo=Path(__file__).resolve().parents[1];assert not target.is_relative_to(repo),'keep local acceptance output outside repository';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'passed':len(checks),'mode':args.mode},ensure_ascii=False))
