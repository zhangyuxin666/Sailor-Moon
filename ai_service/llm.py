import json
import logging
import re
from datetime import datetime, timedelta

import httpx

from .config import settings
from .draft_output import (FORM_GUIDANCE, apply_step_preferences, boolean, form_fields,
                           normalize_task, reminder_minutes, requests_task_assignment)
from .draft_time import SHANGHAI, TIME_GUIDANCE, model_time, request_time, resolved_draft_time
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

    normalized["form_fields"] = form_fields(normalized.get("form_fields"))

    workflow = WorkflowConfig().model_dump()
    if isinstance(normalized.get("workflow"), dict):
        for step in workflow.keys() & normalized["workflow"].keys():
            workflow[step] = boolean(normalized["workflow"][step])
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
        now = datetime.now(SHANGHAI)
        assign_tasks = requests_task_assignment(raw_input)
        if settings.llm_api_key:
            try:
                system = """你是活动策划 Agent。严格输出 JSON：title、description、event_time(ISO 8601)、materials、tasks、form_fields、remind_minutes_before。只列活动实际需要的物料和准备工作，日常班会不默认需要场地预约、宣传、采购或签到；用户不需要分工时 tasks 为空。tasks 元素含 title、description、deadline(ISO时间或空)、priority(low/normal/high/urgent)、key(唯一标识)、depends_on(前置任务key数组)、account_id、assignee；account_id 必须取自候选名单或留空待分配，不能按姓名猜账号；form_fields 元素含 name、label、type、required、options。不要输出 JSON 之外的内容。"""
                context = ("\n可分配成员：" + json.dumps(
                    [member.model_dump() for member in assignees], ensure_ascii=False)
                           + "。任务负责人只能使用名单中的 account_id。"
                           if assignees else "\n当前没有可分配成员，保留所需任务，account_id 留空且 assignee 写待分配。")
                knowledge = f"\n可参考知识库：\n{rag_context}" if rag_context else ""
                user = f"当前时间（Asia/Shanghai）：{now.isoformat(timespec='seconds')}\n用户需求：{raw_input}"
                payload = _extract_json(self._chat(system + TIME_GUIDANCE + FORM_GUIDANCE, user + context + knowledge))
                payload["event_time"] = resolved_draft_time(raw_input, now, payload.get("event_time"))
                payload["form_fields"] = form_fields(payload.get("form_fields"))
                payload["remind_minutes_before"] = reminder_minutes(raw_input, payload.get("remind_minutes_before"))
                if not assign_tasks:
                    payload["tasks"] = []
                for task in payload.get("tasks", []):
                    if isinstance(task, dict):
                        normalize_task(task)
                plan = ActivityPlan.model_validate(payload)
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
        event_time = request_time(raw_input, now)
        plan = self._fallback_plan(raw_input, assignees).model_copy(update={
            "event_time": event_time,
            "remind_minutes_before": reminder_minutes(raw_input),
            "generation": self._fallback_info([] if event_time else ["event_time"]),
        })
        return self._attach_tool_intents(plan)

    def analyze(self, raw_input: str, class_context: str, rag_context: str = "") -> WorkDraft:
        now = datetime.now(SHANGHAI)
        if settings.llm_api_key:
            try:
                system = """你是班级事务规划 Agent。严格输出 JSON，字段：intent_type(assignment/activity/survey/notice)、complexity(simple/standard/complex)、title、summary、description、deadline、event_time、reasoning、missing_information、form_fields、workflow。workflow 包含 create_plan、create_form、assign_tasks、create_calendar、schedule_reminder、publish_message、collect_submission，值为布尔。

意图判断必须按用户的核心目标，而不是按局部关键词：
1. 举办分享会、班会、讲座、比赛、团建、招新、志愿服务等线下或线上集体事件，一律使用 activity；“报名”“统计人数”只是活动步骤，不能因此判成 survey。
2. 收作业、报告、文件或逐人完成情况使用 assignment。
3. 只有核心目标本身是问卷、投票或结构化信息收集时才使用 survey。
4. 只需要发布一条消息且无需报名、分工或执行流程时使用 notice。

步骤按实际需求启用。只有用户需要分工、指定负责人或安排具体准备工作时才启用 assign_tasks；班会、分享会等普通活动不自动分工。只有确实需要物料清单时才启用 create_plan。只有需要登记报名或回答问卷时才启用 create_form。仅在用户要求日历或提醒时分别启用 create_calendar 或 schedule_reminder。普通作业、问卷和活动默认站内发布，有群通知需求才启用 publish_message；notice 发布到 QQ 群。

title 必须是 4 至 20 个汉字左右的简短名词短语，例如“AI工具分享会”，不得复制整句需求，不得包含时间、地点、人数或“预计”等说明。summary 用一句话概括目标，description 保留完整要求。活动时间写 event_time，作业截止时间写 deadline。按需求启用对应 workflow，用户明确不要的步骤设为 false。多个备选时间没有定下来时，时间留空并在 missing_information 中列出要确认的问题。不要输出 JSON 之外的内容。"""
                user = f"当前时间（Asia/Shanghai）：{now.isoformat(timespec='seconds')}\n班级信息：{class_context}\n用户需求：{raw_input}"
                if rag_context:
                    user += f"\n相关知识库：\n{rag_context}"
                generated = _extract_json(self._chat(system + TIME_GUIDANCE + FORM_GUIDANCE, user))
                payload = _normalize_work_draft(generated)
                supplied_steps = generated.get("workflow")
                supplied_steps = supplied_steps if isinstance(supplied_steps, dict) else {}
                workflow = payload["workflow"]
                workflow["create_plan"] = boolean(supplied_steps.get("create_plan"))
                workflow["assign_tasks"] = payload.get("intent_type") == "activity" and requests_task_assignment(raw_input)
                workflow["publish_message"] = payload.get("intent_type") == "notice" or self._requests_group_message(raw_input)
                apply_step_preferences(payload["workflow"], raw_input)
                for field in ("event_time", "deadline"):
                    payload[field] = model_time(payload.get(field))
                time_field = {"activity": "event_time", "assignment": "deadline"}.get(payload.get("intent_type"))
                if time_field:
                    payload[time_field] = resolved_draft_time(raw_input, now, payload[time_field])
                draft = WorkDraft.model_validate(payload)
                if draft.intent_type == "activity" and (len(draft.title) > 20 or draft.title.strip() == raw_input.strip()):
                    draft = draft.model_copy(update={"title": self._fallback_title(raw_input)})
                return draft.model_copy(update={"generation": GenerationInfo(mode="model",fallback_reason="",pending_fields=["event_time"] if draft.intent_type=="activity" and not draft.event_time else ["deadline"] if draft.intent_type=="assignment" and not draft.deadline else [])})
            except Exception as exception:
                logger.warning("Remote analysis failed; using offline fallback: %s", exception)
        draft = self._fallback_analyze(raw_input)
        workflow = WorkflowConfig.model_validate(apply_step_preferences(draft.workflow.model_dump(), raw_input))
        draft = draft.model_copy(update={"workflow": workflow})
        time_field = {"activity": "event_time", "assignment": "deadline"}.get(draft.intent_type)
        resolved = request_time(raw_input, now) if time_field else ""
        if resolved:
            draft = draft.model_copy(update={time_field: resolved, "missing_information": []})
        pending = [time_field] if time_field and not resolved else []
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
        tasks = []
        if requests_task_assignment(raw_input):
            known_tasks = {
                "场地": "场地预约", "宣传": "宣传推送", "采购": "物料采购",
                "签到": "现场签到", "主持": "活动主持", "布置": "现场布置", "设备": "设备准备",
            }
            tasks = [title for keyword, title in known_tasks.items() if keyword in raw_input]
            if not tasks:
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
        publish_to_group = self._requests_group_message(text)
        activity_words = ("活动", "分享会", "班会", "讲座", "比赛", "团建", "招新", "志愿", "晚会", "聚餐", "秋游")
        first_clause = re.split(r"[，。；,;]", text, maxsplit=1)[0]
        starts_with_activity = re.search(
            rf"(?:组织|举办|举行|开展|安排|开)(?:[^，。；,;]{{0,20}})(?:{'|'.join(activity_words)})",
            first_clause,
        )
        if not starts_with_activity and any(word in text for word in ("作业", "文件", "报告", "材料")):
            return WorkDraft(
                intent_type="assignment", complexity="simple", title=title, description=text,
                summary="发布一项全班作业并收集提交，不需要额外分工。", deadline=deadline,
                reasoning="需求核心是收集每位成员的提交。", missing_information=["请确认截止时间"],
                workflow=WorkflowConfig(create_plan=False, collect_submission=True, publish_message=publish_to_group),
            )
        if any(word in text for word in activity_words):
            return self._fallback_activity(text, title, publish_to_group)
        if any(word in text for word in ("问卷", "统计", "收集信息", "投票")):
            return WorkDraft(
                intent_type="survey", complexity="standard", title=title, description=text,
                summary="创建信息收集表并发布链接。", reasoning="需求重点是结构化收集信息。",
                form_fields=[FormField(name="response", label="请填写相关信息", required=True)],
                workflow=WorkflowConfig(create_plan=False, create_form=True, publish_message=publish_to_group),
            )
        if any(word in text for word in ("通知", "告诉大家", "群里说")):
            return WorkDraft(
                intent_type="notice", complexity="simple", title=title, description=text,
                summary="向班级群发布一条通知。", reasoning="需求只涉及消息发布。",
                workflow=WorkflowConfig(create_plan=False, publish_message=True),
            )
        return self._fallback_activity(text, title, publish_to_group)

    @staticmethod
    def _fallback_activity(text: str, title: str, publish_to_group: bool) -> WorkDraft:
        assign_tasks = requests_task_assignment(text)
        return WorkDraft(
            intent_type="activity", complexity="standard" if assign_tasks else "simple",
            title=title, description=text, summary="创建活动，可按需启用报名和提醒。",
            missing_information=["请确认活动时间"],
            workflow=WorkflowConfig(create_plan="物料" in text, create_form="报名" in text,
                                    assign_tasks=assign_tasks, create_calendar="日历" in text,
                                    schedule_reminder="提醒" in text, publish_message=publish_to_group),
        )

    @staticmethod
    def _requests_group_message(text: str) -> bool:
        return bool(re.search(r"QQ\s*群|发(?:到|送到)?群|群里(?:发|通知|提醒)|群发", text, re.IGNORECASE))

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
