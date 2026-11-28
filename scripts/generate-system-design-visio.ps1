param(
    [string]$OutputDirectory = "docs/diagrams/system-design",
    [string]$VisioFile = "docs/diagrams/ActivityAssistant-SystemDesign.vsdx"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$output = Join-Path $root $OutputDirectory
$visioPath = Join-Path $root $VisioFile
New-Item -ItemType Directory -Force -Path $output | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $visioPath) | Out-Null

$script:visio = New-Object -ComObject Visio.Application
$script:visio.Visible = $false
try { $script:visio.ScreenUpdating = $false } catch { }
try { $script:visio.EventsEnabled = 0 } catch { }
try { $script:visio.ShowChanges = 0 } catch { }
try { $script:visio.DeferRecalc = $true } catch { }
$script:doc = $script:visio.Documents.Add("")
$script:pages = @()

function Set-Cell($shape, [string]$cell, [string]$formula) {
    try { $shape.CellsU($cell).FormulaU = $formula } catch { }
}

function Add-Box($page, [double]$x, [double]$y, [double]$w, [double]$h,
                 [string]$text, [string]$fill = "RGB(226,239,218)",
                 [string]$line = "RGB(84,130,53)", [double]$font = 10) {
    $shape = $page.DrawRectangle($x-$w/2, $y-$h/2, $x+$w/2, $y+$h/2)
    $shape.Text = $text
    Set-Cell $shape "FillForegnd" $fill
    Set-Cell $shape "LineColor" $line
    Set-Cell $shape "LineWeight" "1.2 pt"
    Set-Cell $shape "Rounding" "0.08 in"
    Set-Cell $shape "Char.Size" "$font pt"
    Set-Cell $shape "Para.HorzAlign" "1"
    Set-Cell $shape "VerticalAlign" "1"
    return $shape
}

function Add-Container($page, [double]$x, [double]$y, [double]$w, [double]$h,
                       [string]$title, [string]$fill = "RGB(248,250,244)") {
    $shape = $page.DrawRectangle($x-$w/2, $y-$h/2, $x+$w/2, $y+$h/2)
    $shape.Text = $title
    Set-Cell $shape "FillForegnd" $fill
    Set-Cell $shape "FillTransparency" "22%"
    Set-Cell $shape "LineColor" "RGB(120,120,120)"
    Set-Cell $shape "LinePattern" "2"
    Set-Cell $shape "Char.Size" "11 pt"
    Set-Cell $shape "Para.HorzAlign" "0"
    Set-Cell $shape "VerticalAlign" "0"
    return $shape
}

function Add-Arrow($page, $from, $to, [string]$label = "", [string]$color = "RGB(80,80,80)") {
    $x1 = [double]$from.CellsU("PinX").ResultIU
    $y1 = [double]$from.CellsU("PinY").ResultIU
    $x2 = [double]$to.CellsU("PinX").ResultIU
    $y2 = [double]$to.CellsU("PinY").ResultIU
    $line = $page.DrawLine($x1, $y1, $x2, $y2)
    Set-Cell $line "LineColor" $color
    Set-Cell $line "LineWeight" "1.1 pt"
    Set-Cell $line "EndArrow" "13"
    if ($label) {
        $line.Text = $label
        Set-Cell $line "Char.Size" "8 pt"
    }
    return $line
}

function Add-Title($page, [string]$title, [string]$subtitle) {
    $main = $page.DrawRectangle(0.45, 10.65, 13.55, 11.35)
    $main.Text = "$title`n$subtitle"
    Set-Cell $main "FillForegnd" "RGB(72,105,58)"
    Set-Cell $main "LineColor" "RGB(72,105,58)"
    Set-Cell $main "Char.Color" "RGB(255,255,255)"
    Set-Cell $main "Char.Size" "16 pt"
    Set-Cell $main "Para.HorzAlign" "1"
    return $main
}

function New-Page([string]$name, [string]$title, [string]$subtitle) {
    if ($script:pages.Count -eq 0) { $page = $script:visio.ActivePage } else { $page = $script:doc.Pages.Add() }
    $page.Name = $name
    $page.PageSheet.CellsU("PageWidth").FormulaU = "14 in"
    $page.PageSheet.CellsU("PageHeight").FormulaU = "11.7 in"
    Add-Title $page $title $subtitle | Out-Null
    $script:pages += $page
    return $page
}

try {
    # Page 1: overall architecture
    $p = New-Page "总体架构" "活动管家总体架构" "浏览器只访问Spring Boot；AI、消息与数据服务位于内部边界"
    Add-Container $p 2.0 6.0 3.2 8.4 "客户端层" "RGB(235,245,255)" | Out-Null
    Add-Container $p 6.0 6.0 4.2 8.4 "业务与智能服务层" "RGB(245,250,235)" | Out-Null
    Add-Container $p 10.8 6.0 5.0 8.4 "数据、消息与外部集成层" "RGB(255,247,230)" | Out-Null
    $browser = Add-Box $p 2.0 8.3 2.2 0.9 "浏览器`nReact + TypeScript" "RGB(221,235,247)" "RGB(46,95,145)"
    $public = Add-Box $p 2.0 5.8 2.2 0.9 "公共报名页面`n回执/取消凭证" "RGB(221,235,247)" "RGB(46,95,145)"
    $spring = Add-Box $p 6.0 8.2 3.0 1.1 "Spring Boot / Tomcat`n认证·权限·事务·编排" "RGB(226,239,218)" "RGB(84,130,53)" 11
    $worker = Add-Box $p 6.0 5.7 3.0 1.0 "后台Worker`n租约·心跳·重试·恢复" "RGB(226,239,218)" "RGB(84,130,53)"
    $fastapi = Add-Box $p 6.0 3.2 3.0 1.0 "FastAPI AI/RAG`n切分·Embedding·检索·LLM" "RGB(234,224,244)" "RGB(112,48,160)"
    $pg = Add-Box $p 10.1 8.7 2.7 1.0 "PostgreSQL 16 + pgvector`n唯一业务事实源" "RGB(255,242,204)" "RGB(191,144,0)"
    $rabbit = Add-Box $p 12.0 6.4 2.2 0.9 "RabbitMQ`n领域事件" "RGB(252,228,214)" "RGB(198,89,17)"
    $files = Add-Box $p 9.7 4.4 2.4 0.9 "文件存储`n上传与分片" "RGB(255,242,204)" "RGB(191,144,0)"
    $qq = Add-Box $p 12.0 3.3 2.2 0.9 "QQ Gateway`nQQ开放平台" "RGB(242,242,242)" "RGB(89,89,89)"
    Add-Arrow $p $browser $spring "HTTPS/JSON + SSE" | Out-Null
    Add-Arrow $p $public $spring "公开表单API" | Out-Null
    Add-Arrow $p $spring $worker "持久化任务" | Out-Null
    Add-Arrow $p $spring $fastapi "内部令牌" | Out-Null
    Add-Arrow $p $spring $pg "Spring JDBC" | Out-Null
    Add-Arrow $p $worker $pg "事务/租约" | Out-Null
    Add-Arrow $p $fastapi $pg "向量检索" | Out-Null
    Add-Arrow $p $spring $rabbit "发布/消费" | Out-Null
    Add-Arrow $p $spring $files "授权读写" | Out-Null
    Add-Arrow $p $worker $qq "幂等投递" | Out-Null

    # Page 2: business workflow
    $p = New-Page "活动全流程" "一句话发起活动的业务全流程" "AI生成建议，人工确认后由可靠Worker执行"
    $items = @(
        @("1 需求输入", "管理员选择班级并描述活动", "RGB(221,235,247)"),
        @("2 AI分析", "意图识别 + RAG + 结构化草案", "RGB(234,224,244)"),
        @("3 人工确认", "修改时间、任务、表单和流程开关", "RGB(255,242,204)"),
        @("4 发布入队", "保存确认快照、运行和后台任务", "RGB(226,239,218)"),
        @("5 工具执行", "表单→任务→日历→提醒→QQ", "RGB(226,239,218)"),
        @("6 参与执行", "报名、材料、任务、签到、反馈", "RGB(221,235,247)"),
        @("7 数据观察", "RabbitMQ事件 + SSE实时大屏", "RGB(252,228,214)"),
        @("8 活动复盘", "统计快照 + AI正文 + 历史版本", "RGB(234,224,244)")
    )
    $shapes = @()
    for ($i=0; $i -lt $items.Count; $i++) {
        $row = [math]::Floor($i / 4); $col = $i % 4
        $x = if ($row -eq 0) { 1.9 + 3.35*$col } else { 12.0 - 3.35*$col }
        $y = if ($row -eq 0) { 8.1 } else { 4.4 }
        $shapes += Add-Box $p $x $y 2.75 1.25 ($items[$i][0]+"`n"+$items[$i][1]) $items[$i][2] "RGB(90,90,90)" 9
    }
    for ($i=0; $i -lt $shapes.Count-1; $i++) { Add-Arrow $p $shapes[$i] $shapes[$i+1] | Out-Null }
    $wait = Add-Box $p 4.5 2.0 3.0 0.85 "缺少必要信息 → waiting_input → 补充后恢复" "RGB(255,230,230)" "RGB(192,0,0)" 8
    $retry = Add-Box $p 9.5 2.0 3.0 0.85 "执行失败 → 有限重试；取消状态不可被覆盖" "RGB(255,230,230)" "RGB(192,0,0)" 8
    Add-Arrow $p $shapes[2] $wait "校验失败" "RGB(192,0,0)" | Out-Null
    Add-Arrow $p $shapes[4] $retry "工具失败" "RGB(192,0,0)" | Out-Null

    # Page 3: RAG sequence
    $p = New-Page "RAG时序" "RAG索引与检索时序" "组织隔离、向量来源一致性和可追溯引用"
    $actors = @()
    $names = @("管理员/Agent", "Spring Boot", "FastAPI", "Embedding服务", "PostgreSQL/pgvector")
    $colors = @("RGB(221,235,247)","RGB(226,239,218)","RGB(234,224,244)","RGB(252,228,214)","RGB(255,242,204)")
    for ($i=0;$i -lt 5;$i++) { $actors += Add-Box $p (1.6+2.7*$i) 9.5 2.15 0.75 $names[$i] $colors[$i] "RGB(90,90,90)" 9 }
    for ($i=0;$i -lt 5;$i++) { $line=$p.DrawLine(1.6+2.7*$i,9.1,1.6+2.7*$i,1.3); Set-Cell $line "LinePattern" "2"; Set-Cell $line "LineColor" "RGB(150,150,150)" }
    $events = @(
        @(0,1,8.5,"提交知识资料"), @(1,2,7.8,"内部令牌 + 组织ID"), @(2,2,7.1,"切分为重叠chunks"),
        @(2,3,6.4,"批量生成1536维向量"), @(3,2,5.8,"向量 + profile"), @(2,4,5.2,"保存文档/分片/版本"),
        @(0,1,4.3,"发起策划或检索"), @(1,2,3.7,"query + 服务端组织ID"), @(2,3,3.1,"生成查询向量"),
        @(2,4,2.5,"余弦Top-K：本组织+公共"), @(4,2,1.9,"片段/分数/来源/版本"), @(2,1,1.4,"上下文与引用")
    )
    foreach($e in $events) {
        $line=$p.DrawLine(1.6+2.7*$e[0],$e[2],1.6+2.7*$e[1],$e[2]); Set-Cell $line "EndArrow" "13"; $line.Text=$e[3]; Set-Cell $line "Char.Size" "7.5 pt"
    }

    # Page 4: analytics
    $p = New-Page "实时大屏" "数据大屏实时更新时序" "PostgreSQL通知 → RabbitMQ扇出 → SSE；断线时轮询降级"
    $db = Add-Box $p 1.5 8.3 2.1 0.9 "业务表写入`n活动/任务/报名/签到" "RGB(255,242,204)" "RGB(191,144,0)"
    $trigger = Add-Box $p 4.0 8.3 2.0 0.9 "V13触发器`npg_notify" "RGB(255,242,204)" "RGB(191,144,0)"
    $listener = Add-Box $p 6.5 8.3 2.1 0.9 "AnalyticsNotifier`n独立JDBC LISTEN" "RGB(226,239,218)" "RGB(84,130,53)"
    $mq = Add-Box $p 9.0 8.3 2.0 0.9 "RabbitMQ`nactivity.events" "RGB(252,228,214)" "RGB(198,89,17)"
    $consumerA = Add-Box $p 11.7 9.2 2.3 0.8 "API实例A消费者" "RGB(226,239,218)" "RGB(84,130,53)"
    $consumerB = Add-Box $p 11.7 7.2 2.3 0.8 "API实例B消费者" "RGB(226,239,218)" "RGB(84,130,53)"
    $debounceA = Add-Box $p 9.3 5.3 2.2 0.9 "requestBroadcast`n300ms防抖" "RGB(234,224,244)" "RGB(112,48,160)"
    $query = Add-Box $p 6.2 5.3 2.3 0.9 "AnalyticsService`n统计快照SQL" "RGB(226,239,218)" "RGB(84,130,53)"
    $sse = Add-Box $p 3.4 5.3 2.1 0.9 "SseEmitter`nsnapshot + ping" "RGB(221,235,247)" "RGB(46,95,145)"
    $ui = Add-Box $p 1.4 3.3 2.2 0.9 "React数据大屏`nsetData重新渲染" "RGB(221,235,247)" "RGB(46,95,145)"
    $poll = Add-Box $p 4.4 2.0 3.1 0.85 "SSE断开 → 15秒轮询；重连后停止轮询" "RGB(255,230,230)" "RGB(192,0,0)" 8
    foreach($pair in @(@($db,$trigger),@($trigger,$listener),@($listener,$mq),@($mq,$consumerA),@($mq,$consumerB),@($consumerA,$debounceA),@($consumerB,$debounceA),@($debounceA,$query),@($query,$sse),@($sse,$ui))) { Add-Arrow $p $pair[0] $pair[1] | Out-Null }
    Add-Arrow $p $ui $poll "onerror" "RGB(192,0,0)" | Out-Null

    # Page 5: deployment
    $p = New-Page "部署拓扑" "生产/集群部署拓扑" "公网入口与内部服务隔离，状态保存在共享数据设施"
    $internet = Add-Box $p 1.5 8.6 2.1 0.9 "用户浏览器`nHTTPS" "RGB(221,235,247)" "RGB(46,95,145)"
    $proxy = Add-Box $p 4.2 8.6 2.1 0.9 "Caddy / Nginx`nTLS与负载均衡" "RGB(242,242,242)" "RGB(89,89,89)"
    Add-Container $p 8.5 6.2 9.2 8.0 "Docker内部网络" "RGB(245,250,245)" | Out-Null
    $a1 = Add-Box $p 7.0 8.5 2.4 0.9 "Tomcat API 1`nSpring Boot WAR" "RGB(226,239,218)" "RGB(84,130,53)"
    $a2 = Add-Box $p 10.0 8.5 2.4 0.9 "Tomcat API 2`nSpring Boot WAR" "RGB(226,239,218)" "RGB(84,130,53)"
    $ai = Add-Box $p 12.3 6.4 2.2 0.9 "FastAPI AI/RAG" "RGB(234,224,244)" "RGB(112,48,160)"
    $mq = Add-Box $p 9.4 6.3 2.2 0.9 "RabbitMQ" "RGB(252,228,214)" "RGB(198,89,17)"
    $pg = Add-Box $p 6.5 5.5 2.4 1.0 "PostgreSQL + pgvector`n持久卷" "RGB(255,242,204)" "RGB(191,144,0)"
    $upload = Add-Box $p 9.4 3.7 2.2 0.9 "上传文件持久卷" "RGB(255,242,204)" "RGB(191,144,0)"
    $qqg = Add-Box $p 12.3 3.7 2.2 0.9 "QQ Gateway`n可选profile" "RGB(242,242,242)" "RGB(89,89,89)"
    $external = Add-Box $p 12.2 1.9 2.5 0.9 "外部平台`nQQ/短信/微信/模型" "RGB(255,230,230)" "RGB(192,0,0)"
    Add-Arrow $p $internet $proxy | Out-Null; Add-Arrow $p $proxy $a1 | Out-Null; Add-Arrow $p $proxy $a2 | Out-Null
    foreach($api in @($a1,$a2)) { Add-Arrow $p $api $pg | Out-Null; Add-Arrow $p $api $mq | Out-Null; Add-Arrow $p $api $ai | Out-Null; Add-Arrow $p $api $upload | Out-Null }
    Add-Arrow $p $a1 $qqg | Out-Null; Add-Arrow $p $qqg $external | Out-Null; Add-Arrow $p $ai $external | Out-Null

    # Page 6: core data model
    $p = New-Page "核心数据模型" "核心数据模型（领域级ER图）" "展示主要实体与关系；完整字段以Flyway V1～V14为准"
    $org = Add-Box $p 2.0 9.0 2.2 0.85 "organizations`n组织" "RGB(221,235,247)" "RGB(46,95,145)"
    $account = Add-Box $p 5.0 9.0 2.2 0.85 "accounts`n账号" "RGB(221,235,247)" "RGB(46,95,145)"
    $class = Add-Box $p 2.0 7.2 2.2 0.85 "classes`n班级" "RGB(221,235,247)" "RGB(46,95,145)"
    $members = Add-Box $p 5.0 7.2 2.2 0.85 "成员关系`n组织/班级成员" "RGB(221,235,247)" "RGB(46,95,145)"
    $draft = Add-Box $p 8.0 9.0 2.2 0.85 "agent_drafts`n确认前草案" "RGB(234,224,244)" "RGB(112,48,160)"
    $activity = Add-Box $p 8.0 7.2 2.2 0.85 "activities`n活动" "RGB(226,239,218)" "RGB(84,130,53)"
    $run = Add-Box $p 11.0 9.0 2.2 0.85 "agent_runs / steps`n执行与步骤" "RGB(226,239,218)" "RGB(84,130,53)"
    $job = Add-Box $p 11.0 7.2 2.2 0.85 "background_jobs`n任务租约" "RGB(226,239,218)" "RGB(84,130,53)"
    $form = Add-Box $p 2.0 4.8 2.2 0.85 "forms / registrations`n表单与报名" "RGB(255,242,204)" "RGB(191,144,0)"
    $task = Add-Box $p 5.0 4.8 2.2 0.85 "tasks`n任务/负责人/依赖" "RGB(255,242,204)" "RGB(191,144,0)"
    $part = Add-Box $p 8.0 4.8 2.4 0.85 "participants/check_ins/feedback`n参与、签到、反馈" "RGB(255,242,204)" "RGB(191,144,0)" 8
    $delivery = Add-Box $p 11.0 4.8 2.3 0.85 "reminders/messages/deliveries`n提醒与投递" "RGB(252,228,214)" "RGB(198,89,17)" 8
    $rag = Add-Box $p 4.0 2.2 2.6 0.9 "rag_documents/chunks`n资料、分片与向量" "RGB(234,224,244)" "RGB(112,48,160)"
    $recap = Add-Box $p 8.0 2.2 2.4 0.9 "activity_recaps`n统计快照与复盘版本" "RGB(234,224,244)" "RGB(112,48,160)"
    $audit = Add-Box $p 11.2 2.2 2.3 0.9 "audit_logs / idempotency`n审计与幂等" "RGB(242,242,242)" "RGB(89,89,89)" 8
    foreach($pair in @(@($org,$class),@($org,$members),@($account,$members),@($class,$members),@($members,$draft),@($draft,$activity),@($activity,$run),@($run,$job),@($activity,$form),@($activity,$task),@($activity,$part),@($activity,$delivery),@($activity,$recap),@($org,$rag),@($account,$audit),@($activity,$audit))) { Add-Arrow $p $pair[0] $pair[1] | Out-Null }

    try { $script:visio.DeferRecalc = $false } catch { }
    $script:doc.SaveAs($visioPath)
    $exports = @(
        "01-overall-architecture.png", "02-activity-workflow.png", "03-rag-sequence.png",
        "04-realtime-analytics.png", "05-deployment-topology.png", "06-core-data-model.png"
    )
    for($i=0;$i -lt $script:pages.Count;$i++) {
        $script:pages[$i].Export((Join-Path $output $exports[$i]))
    }
}
finally {
    if ($script:doc) { try { $script:doc.Close() } catch { } }
    if ($script:visio) { try { $script:visio.Quit() } catch { } }
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($script:doc) | Out-Null
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($script:visio) | Out-Null
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

Write-Host "Visio generated: $visioPath"
Write-Host "PNG exports: $output"
