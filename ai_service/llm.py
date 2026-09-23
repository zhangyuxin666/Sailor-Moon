import json
import logging
import re
from datetime import datetime, timedelta

import httpx

from .config import settings
from .models import ActivityPlan, FormField, TaskItem, ToolIntent, WorkDraft, WorkflowConfig

logger = logging.getLogger(__name__)


def _extract_json(value: str) -> dict:
    match = re.search(r"\{.*\}", value, re.DOTALL)
    if not match:
        raise ValueError("模型输出中没有 JSON 对象")
    return json.loads(match.group(0))


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

    def plan(self, raw_input: str, assignees: list[str], rag_context: str = "") -> ActivityPlan:
        if settings.llm_api_key:
            try:
                system = """你是活动策划 Agent。严格输出 JSON：title、description、event_time(ISO 8601)、materials、tasks、form_fields、remind_minutes_before。tasks 元素含 title、assignee；form_fields 元素含 name、label、type、required、options。不要输出 JSON 之外的内容。"""
                context = (f"\n可分配成员：{'、'.join(assignees)}。任务负责人只能从名单中选择。"
                           if assignees else "\n当前没有可分配成员，tasks 必须返回空数组。")
                knowledge = f"\n可参考知识库：\n{rag_context}" if rag_context else ""
                plan = ActivityPlan.model_validate(_extract_json(self._chat(system, raw_input + context + knowledge)))
                return self._attach_tool_intents(plan)
            except Exception as exception:
                logger.warning("Remote plan failed; using offline fallback: %s", exception)
        return self._attach_tool_intents(self._fallback_plan(raw_input, assignees))

    def analyze(self, raw_input: str, class_context: str, rag_context: str = "") -> WorkDraft:
        if settings.llm_api_key:
            try:
                system = """你是班级事务规划 Agent。严格输出 JSON，字段：intent_type(assignment/activity/survey/notice)、complexity(simple/standard/complex)、title、summary、description、deadline、event_time、reasoning、missing_information、form_fields、workflow。workflow 包含 create_plan、create_form、assign_tasks、create_calendar、schedule_reminder、publish_message、collect_submission，值为布尔。作业/文件收集使用 assignment；单纯通知用 notice；信息收集用 survey；真正的多人活动才用 activity。"""
                user = f"班级信息：{class_context}\n用户需求：{raw_input}"
                if rag_context:
                    user += f"\n相关知识库：\n{rag_context}"
                return WorkDraft.model_validate(_extract_json(self._chat(system, user)))
            except Exception as exception:
                logger.warning("Remote analysis failed; using offline fallback: %s", exception)
        return self._fallback_analyze(raw_input)

    def recap(self, title: str, stats: dict, tasks: str, rag_context: str = "") -> str:
        if settings.llm_api_key:
            try:
                return self._chat(
                    "你是活动复盘助手。用简洁中文基于真实数据输出复盘；不要虚构。",
                    json.dumps({"title": title, "stats": stats, "tasks": tasks, "knowledge": rag_context}, ensure_ascii=False),
                )
            except Exception as exception:
                logger.warning("Remote recap failed; using offline fallback: %s", exception)
        return f"《{title}》活动复盘\n- 报名数据：共 {stats.get('count', 0)} 人报名\n- 任务执行：{tasks}\n- 改进：下次可提前开放报名并在活动后收集反馈"

    def reply(self, message: str, activity_context: str, rag_context: str = "") -> str:
        if settings.llm_api_key:
            try:
                system = "你是社团/班级活动管家。用简短自然的中文回答，只能基于提供的活动数据和知识库；不要声称执行了未执行的操作。"
                return self._chat(system, f"活动数据：{activity_context}\n知识库：{rag_context}\n群成员消息：{message}")
            except Exception as exception:
                logger.warning("Remote reply failed; using offline fallback: %s", exception)
        return f"收到你的问题：“{message}”。我是活动管家，可以帮助查看报名、任务、提醒和执行进度。发送“帮助”可查看快捷指令。"

    def _fallback_plan(self, raw_input: str, assignees: list[str]) -> ActivityPlan:
        names = assignees
        title = raw_input.strip()[:20] or "未命名活动"
        event_time = (datetime.now() + timedelta(days=3)).replace(microsecond=0).isoformat()
        tasks = ["场地预约", "宣传推送", "物料采购", "现场签到"]
        return ActivityPlan(
            title=title,
            description=f"基于「{raw_input}」生成的活动方案：签到、主题环节、互动与收尾。",
            event_time=event_time,
            materials=["横幅", "签到表", "饮用水", "音响设备"],
            tasks=[TaskItem(title=task, assignee=names[index % len(names)]) for index, task in enumerate(tasks)] if names else [],
            form_fields=[
                FormField(name="attendance", label="是否确认参加", type="select", required=True, options=["是", "否"]),
                FormField(name="note", label="备注或特殊需求"),
            ],
        )

    def _fallback_analyze(self, raw_input: str) -> WorkDraft:
        text = raw_input.strip()
        deadline = (datetime.now() + timedelta(days=3)).replace(microsecond=0).isoformat()
        if any(word in text for word in ("作业", "文件", "报告", "材料")):
            return WorkDraft(
                intent_type="assignment", complexity="simple", title=text[:30], description=text,
                summary="发布一项全班作业并收集提交，不需要额外分工。", deadline=deadline,
                reasoning="需求核心是收集每位成员的提交。", missing_information=["请确认截止时间"],
                workflow=WorkflowConfig(collect_submission=True, publish_message=True),
            )
        if any(word in text for word in ("问卷", "统计", "收集信息", "报名")):
            return WorkDraft(
                intent_type="survey", complexity="standard", title=text[:30], description=text,
                summary="创建信息收集表并发布链接。", reasoning="需求重点是结构化收集信息。",
                form_fields=[FormField(name="response", label="请填写相关信息", required=True)],
                workflow=WorkflowConfig(create_form=True, publish_message=True),
            )
        if any(word in text for word in ("通知", "告诉大家", "群里说")):
            return WorkDraft(
                intent_type="notice", complexity="simple", title=text[:30], description=text,
                summary="向班级群发布一条通知。", reasoning="需求只涉及消息发布。",
                workflow=WorkflowConfig(create_plan=False, publish_message=True),
            )
        return WorkDraft(
            intent_type="activity", complexity="complex", title=text[:30], description=text,
            summary="生成完整活动方案并执行报名、分工、日历和提醒。", event_time=deadline,
            reasoning="需求包含多人活动组织。", missing_information=["请确认活动时间"],
            workflow=WorkflowConfig(create_form=True, assign_tasks=True, create_calendar=True,
                                    schedule_reminder=True, publish_message=True),
        )

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
        if plan.event_time:
            intents.extend([
                ToolIntent(tool="create_calendar", arguments={"event_time": plan.event_time}),
                ToolIntent(tool="schedule_reminder", arguments={"minutes_before": plan.remind_minutes_before}),
            ])
        intents.append(ToolIntent(tool="publish_message", arguments={"title": plan.title}))
        return plan.model_copy(update={"tool_intents": intents})
