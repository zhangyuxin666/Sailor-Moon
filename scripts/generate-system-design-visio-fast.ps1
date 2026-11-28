param(
    [string]$OutputDirectory = "docs/diagrams/system-design",
    [string]$VisioFile = "docs/diagrams/ActivityAssistant-SystemDesign.vsdx"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$output = Join-Path $root $OutputDirectory
$svgOutput = Join-Path $root "docs/diagrams/system-design-svg"
$visioPath = Join-Path $root $VisioFile
New-Item -ItemType Directory -Force -Path $output,$svgOutput | Out-Null

function Node([int]$x,[int]$y,[int]$w,[int]$h,[string]$text,[string]$fill="#E2EFDA",[string]$stroke="#548235") {
    [pscustomobject]@{x=$x;y=$y;w=$w;h=$h;text=$text;fill=$fill;stroke=$stroke}
}
function Edge([int]$from,[int]$to,[string]$label="") { [pscustomobject]@{from=$from;to=$to;label=$label} }
function Escape([string]$value) { [System.Security.SecurityElement]::Escape($value) }

function New-Svg([string]$title,[string]$subtitle,$nodes,$edges,[string]$path) {
    $body = [System.Text.StringBuilder]::new()
    [void]$body.AppendLine('<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="850" viewBox="0 0 1400 850">')
    [void]$body.AppendLine('<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#555"/></marker><filter id="shadow"><feDropShadow dx="2" dy="3" stdDeviation="2" flood-opacity="0.18"/></filter></defs>')
    [void]$body.AppendLine('<rect width="1400" height="850" fill="#FAFCF7"/><rect x="20" y="18" width="1360" height="82" rx="14" fill="#48693A"/>')
    [void]$body.AppendLine("<text x=`"700`" y=`"52`" text-anchor=`"middle`" font-family=`"Microsoft YaHei`" font-size=`"28`" font-weight=`"bold`" fill=`"white`">$(Escape $title)</text>")
    [void]$body.AppendLine("<text x=`"700`" y=`"80`" text-anchor=`"middle`" font-family=`"Microsoft YaHei`" font-size=`"15`" fill=`"#E9F2E4`">$(Escape $subtitle)</text>")
    foreach($edge in $edges) {
        $a=$nodes[$edge.from]; $b=$nodes[$edge.to]
        $x1=$a.x+$a.w/2; $y1=$a.y+$a.h/2; $x2=$b.x+$b.w/2; $y2=$b.y+$b.h/2
        [void]$body.AppendLine("<line x1=`"$x1`" y1=`"$y1`" x2=`"$x2`" y2=`"$y2`" stroke=`"#666`" stroke-width=`"2`" marker-end=`"url(#arrow)`"/>")
        if($edge.label){$mx=($x1+$x2)/2;$my=($y1+$y2)/2-7;[void]$body.AppendLine("<text x=`"$mx`" y=`"$my`" text-anchor=`"middle`" font-family=`"Microsoft YaHei`" font-size=`"12`" fill=`"#555`">$(Escape $edge.label)</text>")}
    }
    foreach($node in $nodes) {
        [void]$body.AppendLine("<rect x=`"$($node.x)`" y=`"$($node.y)`" width=`"$($node.w)`" height=`"$($node.h)`" rx=`"14`" fill=`"$($node.fill)`" stroke=`"$($node.stroke)`" stroke-width=`"2`" filter=`"url(#shadow)`"/>")
        $lines=@($node.text -split '\|'); $base=$node.y+$node.h/2-(($lines.Count-1)*11)
        for($i=0;$i -lt $lines.Count;$i++){$weight=if($i -eq 0){"bold"}else{"normal"};$size=if($i -eq 0){16}else{13};$yy=$base+$i*24;[void]$body.AppendLine("<text x=`"$($node.x+$node.w/2)`" y=`"$yy`" text-anchor=`"middle`" dominant-baseline=`"middle`" font-family=`"Microsoft YaHei`" font-size=`"$size`" font-weight=`"$weight`" fill=`"#263322`">$(Escape $lines[$i])</text>")}
    }
    [void]$body.AppendLine('</svg>')
    [IO.File]::WriteAllText($path,$body.ToString(),[Text.UTF8Encoding]::new($true))
}

$diagrams = @()
$n=@(
 Node 70 220 210 100 '浏览器|React + TypeScript' '#DDEBF7' '#2E5F91'; Node 70 500 210 90 '公共报名页|回执与取消凭证' '#DDEBF7' '#2E5F91';
 Node 390 210 270 110 'Spring Boot / Tomcat|认证·权限·事务·编排'; Node 390 420 270 95 '后台Worker|租约·心跳·重试·恢复'; Node 390 630 270 95 'FastAPI AI/RAG|切分·Embedding·检索·LLM' '#EADFF4' '#7030A0';
 Node 810 180 250 105 'PostgreSQL + pgvector|唯一业务事实源' '#FFF2CC' '#BF9000'; Node 1090 330 210 90 'RabbitMQ|后端领域事件' '#FCE4D6' '#C65911'; Node 810 510 240 90 '文件存储|上传与分片' '#FFF2CC' '#BF9000'; Node 1090 650 210 90 'QQ Gateway|QQ开放平台' '#F2F2F2' '#595959')
$e=@(Edge 0 2 'HTTPS/JSON + SSE';Edge 1 2 '公开API';Edge 2 3 '持久任务';Edge 2 4 '内部令牌';Edge 2 5 'Spring JDBC';Edge 3 5 '事务/租约';Edge 4 5 '向量检索';Edge 2 6 '发布/消费';Edge 2 7 '授权读写';Edge 3 8 '幂等投递')
$diagrams += [pscustomobject]@{name='总体架构';file='01-overall-architecture';title='活动管家总体架构';sub='浏览器只访问Spring Boot；AI、消息与数据服务位于内部边界';nodes=$n;edges=$e}

$n=@();$labels=@('1 需求输入|选择班级并描述活动','2 AI分析|意图识别 + RAG + 草案','3 人工确认|修改时间、任务与流程','4 发布入队|保存快照和后台任务','5 工具执行|表单→任务→提醒→QQ','6 参与执行|报名、任务、签到、反馈','7 数据观察|RabbitMQ + SSE大屏','8 活动复盘|统计快照、正文与版本');for($i=0;$i -lt 8;$i++){if($i -lt 4){$x=40+$i*335;$y=220}else{$x=1045-($i-4)*335;$y=500};$fill=if($i -in 1,7){'#EADFF4'}elseif($i -eq 6){'#FCE4D6'}elseif($i -eq 2){'#FFF2CC'}else{'#E2EFDA'};$n+=Node $x $y 275 105 $labels[$i] $fill '#6B7A5A'};$n+=Node 200 700 360 70 '缺少信息|waiting_input → 补充后恢复' '#FFE6E6' '#C00000';$n+=Node 840 700 360 70 '执行失败|有限重试；取消不可覆盖' '#FFE6E6' '#C00000'
$e=@();for($i=0;$i -lt 7;$i++){$e+=Edge $i ($i+1)};$e+=Edge 2 8 '校验失败';$e+=Edge 4 9 '工具失败'
$diagrams += [pscustomobject]@{name='活动全流程';file='02-activity-workflow';title='一句话发起活动的业务全流程';sub='AI生成建议，人工确认后由可靠Worker执行';nodes=$n;edges=$e}

$n=@(Node 40 180 200 80 '管理员/Agent' '#DDEBF7' '#2E5F91';Node 300 180 200 80 'Spring Boot';Node 560 180 200 80 'FastAPI' '#EADFF4' '#7030A0';Node 820 180 200 80 'Embedding服务' '#FCE4D6' '#C65911';Node 1080 180 240 80 'PostgreSQL/pgvector' '#FFF2CC' '#BF9000';Node 40 390 200 80 '提交知识资料';Node 300 390 200 80 '组织权限校验';Node 560 390 200 80 '切分重叠chunks';Node 820 390 200 80 '生成1536维向量';Node 1080 390 240 80 '保存资料/分片/版本';Node 40 650 200 80 '策划或检索请求';Node 300 650 200 80 '服务端确定组织';Node 560 650 200 80 '查询向量 + Top-K';Node 820 650 200 80 '片段/分数/来源';Node 1080 650 240 80 '草案保存引用快照')
$e=@(Edge 0 5;Edge 5 6;Edge 6 7;Edge 7 8;Edge 8 9;Edge 10 11;Edge 11 12;Edge 12 13;Edge 13 14;Edge 7 2 '内部API';Edge 8 3 '远程或本地降级';Edge 9 4 'pgvector')
$diagrams += [pscustomobject]@{name='RAG时序';file='03-rag-sequence';title='RAG索引与检索时序';sub='组织隔离、向量来源一致性和可追溯引用';nodes=$n;edges=$e}

$n=@(Node 30 200 190 90 '业务表写入|活动/任务/报名';Node 250 200 180 90 'V13触发器|pg_notify';Node 460 200 200 90 'AnalyticsNotifier|JDBC LISTEN';Node 700 200 190 90 'RabbitMQ|activity.events';Node 930 140 190 80 'API实例A消费者';Node 930 280 190 80 'API实例B消费者';Node 1150 210 210 90 'requestBroadcast|300ms防抖';Node 900 500 210 90 'AnalyticsService|统计快照SQL';Node 610 500 190 90 'SseEmitter|snapshot + ping';Node 320 500 210 90 'React数据大屏|setData重新渲染';Node 40 680 270 75 '断线降级|15秒轮询，重连停止')
$e=@(Edge 0 1;Edge 1 2;Edge 2 3;Edge 3 4;Edge 3 5;Edge 4 6;Edge 5 6;Edge 6 7;Edge 7 8;Edge 8 9;Edge 9 10 'onerror')
$diagrams += [pscustomobject]@{name='实时大屏';file='04-realtime-analytics';title='数据大屏实时更新时序';sub='PostgreSQL通知 → RabbitMQ扇出 → SSE；断线时轮询降级';nodes=$n;edges=$e}

$n=@(Node 30 200 200 90 '用户浏览器|HTTPS + SSE' '#DDEBF7' '#2E5F91';Node 280 200 210 90 'Caddy / Nginx|TLS与负载均衡' '#F2F2F2' '#595959';Node 570 140 210 90 'Tomcat API 1|Spring Boot WAR';Node 570 280 210 90 'Tomcat API 2|Spring Boot WAR';Node 850 140 210 90 'FastAPI AI/RAG' '#EADFF4' '#7030A0';Node 850 280 210 90 'RabbitMQ' '#FCE4D6' '#C65911';Node 570 510 240 95 'PostgreSQL + pgvector|持久卷' '#FFF2CC' '#BF9000';Node 850 510 210 90 '上传文件持久卷' '#FFF2CC' '#BF9000';Node 1120 390 210 90 'QQ Gateway|可选profile' '#F2F2F2' '#595959';Node 1080 650 260 90 '外部平台|QQ/短信/微信/模型' '#FFE6E6' '#C00000')
$e=@(Edge 0 1;Edge 1 2;Edge 1 3;Edge 2 4;Edge 3 4;Edge 2 5;Edge 3 5;Edge 2 6;Edge 3 6;Edge 2 7;Edge 3 7;Edge 2 8;Edge 8 9;Edge 4 9)
$diagrams += [pscustomobject]@{name='部署拓扑';file='05-deployment-topology';title='生产/集群部署拓扑';sub='公网入口与内部服务隔离，状态保存在共享数据设施';nodes=$n;edges=$e}

$n=@(Node 30 180 210 80 'organizations|组织' '#DDEBF7' '#2E5F91';Node 300 180 210 80 'accounts|账号' '#DDEBF7' '#2E5F91';Node 30 340 210 80 'classes|班级' '#DDEBF7' '#2E5F91';Node 300 340 210 80 '成员关系|组织/班级成员' '#DDEBF7' '#2E5F91';Node 570 180 210 80 'agent_drafts|确认前草案' '#EADFF4' '#7030A0';Node 570 340 210 80 'activities|活动';Node 840 180 220 80 'agent_runs / steps|执行与步骤';Node 840 340 220 80 'background_jobs|任务租约';Node 30 550 220 80 'forms / registrations|表单与报名' '#FFF2CC' '#BF9000';Node 300 550 220 80 'tasks|负责人/依赖' '#FFF2CC' '#BF9000';Node 570 550 230 80 '参与数据|签到/反馈' '#FFF2CC' '#BF9000';Node 850 550 230 80 '提醒与投递|messages/deliveries' '#FCE4D6' '#C65911';Node 1120 180 230 80 'rag_documents/chunks|资料与向量' '#EADFF4' '#7030A0';Node 1120 340 230 80 'activity_recaps|快照与复盘版本' '#EADFF4' '#7030A0';Node 1120 550 230 80 'audit/idempotency|审计与幂等' '#F2F2F2' '#595959')
$e=@(Edge 0 2;Edge 0 3;Edge 1 3;Edge 2 3;Edge 3 4;Edge 4 5;Edge 5 6;Edge 6 7;Edge 5 8;Edge 5 9;Edge 5 10;Edge 5 11;Edge 0 12;Edge 5 13;Edge 1 14;Edge 5 14)
$diagrams += [pscustomobject]@{name='核心数据模型';file='06-core-data-model';title='核心数据模型（领域级ER图）';sub='主要实体与关系；完整字段以Flyway V1～V14为准';nodes=$n;edges=$e}

foreach($d in $diagrams){New-Svg $d.title $d.sub $d.nodes $d.edges (Join-Path $svgOutput ($d.file+'.svg'))}

$visio=$null;$doc=$null
try {
    $visio=New-Object -ComObject Visio.Application;$visio.Visible=$false
    try{$visio.ScreenUpdating=$false}catch{}
    $doc=$visio.Documents.Add("")
    for($i=0;$i -lt $diagrams.Count;$i++){
        $page=if($i -eq 0){$visio.ActivePage}else{$doc.Pages.Add()};$page.Name=$diagrams[$i].name
        $page.PageSheet.CellsU('PageWidth').FormulaU='14 in';$page.PageSheet.CellsU('PageHeight').FormulaU='8.5 in'
        $shape=$page.Import((Join-Path $svgOutput ($diagrams[$i].file+'.svg')))
        $shape.CellsU('PinX').FormulaU='7 in';$shape.CellsU('PinY').FormulaU='4.25 in';$shape.CellsU('Width').FormulaU='13.7 in';$shape.CellsU('Height').FormulaU='8.32 in'
    }
    $doc.SaveAs($visioPath)
    for($i=0;$i -lt $diagrams.Count;$i++){$doc.Pages.Item($i+1).Export((Join-Path $output ($diagrams[$i].file+'.png')))}
} finally {
    if($doc){try{$doc.Close()}catch{}}
    if($visio){try{$visio.Quit()}catch{}}
    if($doc){[void][Runtime.InteropServices.Marshal]::ReleaseComObject($doc)}
    if($visio){[void][Runtime.InteropServices.Marshal]::ReleaseComObject($visio)}
    [GC]::Collect();[GC]::WaitForPendingFinalizers()
}
Write-Host "Visio generated: $visioPath"
