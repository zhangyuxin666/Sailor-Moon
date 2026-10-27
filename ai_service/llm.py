import json
import logging
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

from .config import settings
from .models import (ActivityPlan, AssigneeCandidate, FormField, TaskItem,
                     ToolIntent, WorkDraft, WorkflowConfig, GenerationInfo)

logger = logging.getLogger(__name__)


def _extract_json(value: str) -> dict:
    match = re.search(r"\{.*\}", value, re.DOTALL)
    if not match:
        raise ValueError("模型输出中没有 JSON 对象")
    return json.loads(match.group(0))


def _normalize_work_draft(payload: dict) -> dict:
    normalized = dict(payload)
    for key in ("description", "deadline", "event_time", "reasoning"):
        normalized.setdefault(key, "")
    normalized.setdefault("missing_information", [])

    fields = []
    for index, item in enumerate(normalized.get("form_fields") or []):
        field = dict(item) if isinstance(item, dict) else {"label": str(item)}
        name = str(field.get("name") or "")
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,30}", name):
            name = f"field_{index + 1}"
        field["name"] = name
        field["label"] = str(field.get("label") or f"问题 {index + 1}")
        field["type"] = field.get("type") if field.get("type") in {"text", "select"} else "text"
        field["required"] = bool(field.get("required", False))
        field["options"] = field.get("options") if isinstance(field.get("options"), list) else []
        fields.append(field)
    normalized["form_fields"] = fields

    workflow = WorkflowConfig().model_dump()
    if isinstance(normalized.get("workflow"), dict):
        workflow.update(normalized["workflow"])
    normalized["workflow"] = workflow
    return normalized


class AiEngine:
    def _chat(self, system: str, user: str) -> str:
        if not settings.llm_api_key:
            raise RuntimeError("未配置远程模型")
        payload = {
            "model": settings.llm_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.3,
        }
        if "JSON" in system:
            payload["response_format"] = {"type": "json_object"}
        response = httpx.post(
            f"{settings.llm_base_url.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {settings.llm_api_key}"},
            json=payload,
            timeout=30,
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    def plan(self, raw_input: str, assignees: list[AssigneeCandidate], rag_context: str = "") -> ActivityPlan:
        if settings.llm_api_key:
            try:
                system = """你是活动策划 Agent。严格输出 JSON：title、description、event_time(ISO 8601)、materials、tasks、form_fields、remind_minutes_before。tasks 元素含 title、description、deadline(ISO时间或空)、priority(low/normal/high/urgent)、key(唯一标识)、depends_on(前置任务key数组)、account_id、assignee；account_id 必须取自候选名单或留空待分配，不能按姓名猜账号；form_fields 元素含 name、label、type、required、options。不要输出 JSON 之外的内容。"""
                context = ("\n可分配成员：" + json.dumps(
                    [member.model_dump() for member in assignees], ensure_ascii=False)
                           + "。任务负责人只能使用名单中的 account_id。"
                           if assignees else "\n当前没有可分配成员，保留所需任务，account_id 留空且 assignee 写待分配。")
                knowledge = f"\n可参考知识库：\n{rag_context}" if rag_context else ""
                plan = ActivityPlan.model_validate(_extract_json(self._chat(system, raw_input + context + knowledge)))
                members = {member.account_id: member for member in assignees}
                normalized = []
                for task in plan.tasks:
                    if task.account_id not in members:
                        normalized.append(task.model_copy(update={"account_id": "", "assignee": "待分配", "unassigned_reason": "负责人未指定或不属于当前班级"}))
                    else:
                        normalized.append(task.model_copy(update={"assignee": members[task.account_id].display_name}))
                return self._attach_tool_intents(plan.model_copy(update={"tasks": normalized,"generation": GenerationInfo(mode="model",fallback_reason="",pending_fields=[] if plan.event_time else ["event_time"])}))
            except Exception as exception:
                logger.warning("Remote plan failed; using offline fallback: %s", exception)
        return self._attach_tool_intents(self._fallback_plan(raw_input, assignees)).model_copy(update={"generation": self._fallback_info(["event_time"])})

    def analyze(self, raw_input: str, class_context: str, rag_context: str = "") -> WorkDraft:
        if settings.llm_api_key:
            try:
                system = """你是班级事务规划 Agent。严格输出 JSON，字段：intent_type(assignment/activity/survey/notice)、complexity(simple/standard/complex)、title、summary、description、deadline、event_time、reasoning、missing_information、form_fields、workflow。workflow 包含 create_plan、create_form、assign_tasks、create_calendar、schedule_reminder、publish_message、collect_submission，值为布尔。

意图判断必须按用户的核心目标，而不是按局部关键词：
1. 举办分享会、班会、讲座、比赛、团建、招新、志愿服务等线下或线上集体事件，一律使用 activity；“报名”“统计人数”只是活动步骤，不能因此判成 survey。
2. 收作业、报告、文件或逐人完成情况使用 assignment。
3. 只有核心目标本身是问卷、投票或结构化信息收集时才使用 survey。
4. 只需要发布一条消息且无需报名、分工或执行流程时使用 notice。

title 必须是 4 至 20 个汉字左右的简短名词短语，例如“AI工具分享会”，不得复制整句需求，不得包含时间、地点、人数或“预计”等说明。summary 用一句话概括目标，description 保留完整要求。出现明确或相对时间时，基于用户提供的当前时间解析为 ISO 8601；活动时间写 event_time，作业截止时间写 deadline。活动若要求报名、分工、日历或提醒，应分别启用对应 workflow。不要输出 JSON 之外的内容。"""
                now = datetime.now(ZoneInfo("Asia/Shanghai")).replace(microsecond=0).isoformat()
                user = f"当前时间（Asia/Shanghai）：{now}\n班级信息：{class_context}\n用户需求：{raw_input}"
                if rag_context:
                    user += f"\n相关知识库：\n{rag_context}"
                payload = _normalize_work_draft(_extract_json(self._chat(system, user)))
                draft = WorkDraft.model_validate(payload)
                if draft.intent_type == "activity" and (len(draft.title) > 20 or draft.title.strip() == raw_input.strip()):
                    draft = draft.model_copy(update={"title": self._fallback_title(raw_input)})
                return draft.model_copy(update={"generation": GenerationInfo(mode="model",fallback_reason="",pending_fields=["event_time"] if draft.intent_type=="activity" and not draft.event_time else ["deadline"] if draft.intent_type=="assignment" and not draft.deadline else [])})
            except Exception as exception:
                logger.warning("Remote analysis failed; using offline fallback: %s", exception)
        draft=self._fallback_analyze(raw_input)
        pending=["deadline"] if draft.intent_type=="assignment" else ["event_time"] if draft.intent_type=="activity" else []
        return draft.model_copy(update={"generation":self._fallback_info(pending)})

    @staticmethod
    def _fallback_info(pending=None):
        return GenerationInfo(mode="offline",fallback_reason="model_unavailable" if settings.llm_api_key else "not_configured",pending_fields=pending or [])

    def recap_result(self, title: str, stats: dict, tasks: str) -> dict:
        allowed={"registration_active","registration_cancelled","tasks_total","tasks_done","tasks_unassigned","reminders_total","attendance_configured","attendance_eligible","attendance_checked_in","attendance_absent","feedback_count","feedback_rating_sum"}
        safe={key:value for key,value in stats.items() if key in allowed and type(value) is int and value>=0}
        for key in ("steps","deliveries"):
            safe[key]={k:v for k,v in stats.get(key,{}).items() if k in {"pending","running","queued","done","failed","sent","unknown","cancelled","sending","skipped","succeeded"} and type(v) is int and v>=0} if isinstance(stats.get(key),dict) else {}
        safe["feedback_rating_counts"]={str(k):v for k,v in stats.get("feedback_rating_counts",{}).items() if str(k) in {"1","2","3","4","5"} and type(v) is int and v>=0} if isinstance(stats.get("feedback_rating_counts"),dict) else {}
        stats=safe
        fallback=f"活动复盘（统计快照）\n- 有效报名：{stats.get('registration_active',0)}；已取消：{stats.get('registration_cancelled',0)}\n- 任务完成：{stats.get('tasks_done',0)}/{stats.get('tasks_total',0)}；待分配：{stats.get('tasks_unassigned',0)}\n- 到场：{stats.get('attendance_checked_in',0)}/{stats.get('attendance_eligible',0)}；反馈：{stats.get('feedback_count',0)}份\n- 步骤和投递明细以保存的结构化快照为准。"
        if settings.llm_api_key:
            try:
                text=self._chat("你是复盘助手。只根据提供的聚合快照写观察与改进建议，不添加人数、比率、个人或其他事实；结构化快照才是统计依据。",json.dumps({"aggregates":stats},ensure_ascii=False))
                if not isinstance(text,str) or not text.strip():raise ValueError("empty recap")
                return {"recap":text[:20000],"generation":GenerationInfo(mode="model",fallback_reason="").model_dump()}
            except Exception:
                logger.warning("Remote recap unavailable; using aggregate fallback")
        return {"recap":fallback,"generation":self._fallback_info().model_dump()}

    def recap(self,title: str,stats: dict,tasks: str,rag_context: str="") -> str:
        return self.recap_result(title,stats,tasks)["recap"]

    def reply(self, message: str, activity_context: str, rag_context: str = "") -> str:
        if settings.llm_api_key:
            try:
                system = "你是社团/班级活动管家。用简短自然的中文回答，只能基于提供的活动数据和知识库；不要声称执行了未执行的操作。"
                return self._chat(system, f"活动数据：{activity_context}\n知识库：{rag_context}\n群成员消息：{message}")
            except Exception as exception:
                logger.warning("Remote reply failed; using offline fallback: %s", exception)
        return f"收到你的问题：“{message}”。我是活动管家，可以帮助查看报名、任务、提醒和执行进度。发送“帮助”可查看快捷指令。"

    def _fallback_plan(self, raw_input: str, assignees: list[AssigneeCandidate]) -> ActivityPlan:
        names = assignees
        title = raw_input.strip()[:20] or "未命名活动"
        event_time = ""
        tasks = ["场地预约", "宣传推送", "物料采购", "现场签到"]
        return ActivityPlan(
            title=title,
            description=f"基于「{raw_input}」生成的活动方案：签到、主题环节、互动与收尾。",
            event_time=event_time,
            materials=["横幅", "签到表", "饮用水", "音响设备"],
            tasks=[TaskItem(title=task,
                            account_id=names[index % len(names)].account_id if names else "",
                            assignee=names[index % len(names)].display_name if names else "待分配",
                            unassigned_reason="" if names else "当前没有可分配成员")
                   for index, task in enumerate(tasks)],
            form_fields=[
                FormField(name="attendance", label="是否确认参加", type="select", required=True, options=["是", "否"]),
                FormField(name="note", label="备注或特殊需求"),
            ],
        )

    def _fallback_analyze(self, raw_input: str) -> WorkDraft:
        text = raw_input.strip()
        deadline = ""
        title = self._fallback_title(text)
        if any(word in text for word in ("作业", "文件", "报告", "材料")):
            return WorkDraft(
                intent_type="assignment", complexity="simple", title=title, description=text,
                summary="发布一项全班作业并收集提交，不需要额外分工。", deadline=deadline,
                reasoning="需求核心是收集每位成员的提交。", missing_information=["请确认截止时间"],
                workflow=WorkflowConfig(collect_submission=True, publish_message=True),
            )
        activity_words = ("活动", "分享会", "班会", "讲座", "比赛", "团建", "招新", "志愿", "晚会", "聚餐", "秋游")
        if any(word in text for word in activity_words):
            return WorkDraft(
                intent_type="activity", complexity="complex", title=title, description=text,
                summary="生成完整活动方案并执行报名、分工、日历和提醒。", event_time=deadline,
                reasoning="需求核心是组织多人活动，报名和统计属于活动执行步骤。", missing_information=["请确认活动时间"],
                workflow=WorkflowConfig(create_form="报名" in text, assign_tasks=True, create_calendar=True,
                                        schedule_reminder="提醒" in text, publish_message=True),
            )
        if any(word in text for word in ("问卷", "统计", "收集信息", "投票")):
            return WorkDraft(
                intent_type="survey", complexity="standard", title=title, description=text,
                summary="创建信息收集表并发布链接。", reasoning="需求重点是结构化收集信息。",
                form_fields=[FormField(name="response", label="请填写相关信息", required=True)],
                workflow=WorkflowConfig(create_form=True, publish_message=True),
            )
        if any(word in text for word in ("通知", "告诉大家", "群里说")):
            return WorkDraft(
                intent_type="notice", complexity="simple", title=title, description=text,
                summary="向班级群发布一条通知。", reasoning="需求只涉及消息发布。",
                workflow=WorkflowConfig(create_plan=False, publish_message=True),
            )
        return WorkDraft(
            intent_type="activity", complexity="complex", title=title, description=text,
            summary="生成完整活动方案并执行报名、分工、日历和提醒。", event_time=deadline,
            reasoning="需求包含多人活动组织。", missing_information=["请确认活动时间"],
            workflow=WorkflowConfig(create_form=True, assign_tasks=True, create_calendar=True,
                                    schedule_reminder=True, publish_message=True),
        )

    @staticmethod
    def _fallback_title(text: str) -> str:
        for pattern in (
            r"(?:组织|举办|开展|策划)(?:一次|一场|一个)?([^，。；,;]{2,20}?(?:分享会|班会|讲座|比赛|团建|招新|活动|晚会|聚餐|秋游))",
            r"([^，。；,;]{2,20}?(?:分享会|班会|讲座|比赛|团建|招新|活动|晚会|聚餐|秋游))",
        ):
            match = re.search(pattern, text)
            if match:
                return match.group(1).strip()
        return text[:20] or "未命名事务"

    def _attach_tool_intents(self, plan: ActivityPlan) -> ActivityPlan:
        if plan.tool_intents:
            return plan
        intents = [ToolIntent(
            tool="create_form",
            arguments={"title": f"{plan.title}报名表", "fields": [field.model_dump() for field in plan.form_fields]},
        )]
        if plan.tasks:
            intents.append(ToolIntent(
                tool="assign_tasks",
                arguments={"tasks": [task.model_dump() for task in plan.tasks]},
            ))
        intents.extend([
            ToolIntent(tool="create_calendar", arguments={"event_time": plan.event_time}),
            ToolIntent(tool="schedule_reminder", arguments={"minutes_before": plan.remind_minutes_before}),
        ])
        intents.append(ToolIntent(tool="publish_message", arguments={"title": plan.title}))
        return plan.model_copy(update={"tool_intents": intents})
