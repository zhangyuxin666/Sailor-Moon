import json
import logging
import re
from datetime import datetime, timedelta

import httpx

from ..config import settings
from ..models.schemas import ActivityPlan, FormField, TaskItem, WorkDraft, WorkflowConfig
from . import prompts

logger = logging.getLogger(__name__)


class LLMClient:
    """LLM 抽象：生成活动策划、生成复盘总结。"""

    def generate_plan(self, raw_input: str, available_assignees: list[str] | None = None) -> ActivityPlan:
        raise NotImplementedError

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
        raise NotImplementedError

    def generate_assistant_reply(self, message: str, activity_context: str) -> str:
        raise NotImplementedError

    def analyze_work_request(self, raw_input: str, class_context: str) -> WorkDraft:
        raise NotImplementedError


def _extract_json(text: str) -> str:
    """从 LLM 输出中提取 JSON（容忍 ```json 代码块包裹）。"""
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        raise ValueError(f"LLM 输出中未找到 JSON: {text[:200]}")
    return m.group(0)


class OpenAICompatibleClient(LLMClient):
    """OpenAI 兼容 chat 接口（httpx 直连，不依赖 SDK）。"""

    def __init__(self, api_key: str, base_url: str, model: str):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model

    def _chat(self, system: str, user: str) -> str:
        resp = httpx.post(
            f"{self.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={
                "model": self.model,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                "temperature": 0.3,
            },
            timeout=30,
        )
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    def generate_plan(self, raw_input: str, available_assignees: list[str] | None = None) -> ActivityPlan:
        assignee_context = ""
        if available_assignees:
            assignee_context = (
                "\n可分配的真实成员名单：" + "、".join(available_assignees) +
                "。tasks.assignee 必须且只能从该名单中选择，可让一人承担多项任务。"
            )
        content = self._chat(prompts.PLAN_SYSTEM_PROMPT, raw_input + assignee_context)
        return ActivityPlan(**json.loads(_extract_json(content)))

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
        payload = json.dumps(
            {"title": activity_title, "stats": stats, "tasks": task_summary},
            ensure_ascii=False,
        )
        return self._chat(prompts.RECAP_SYSTEM_PROMPT, payload)

    def generate_assistant_reply(self, message: str, activity_context: str) -> str:
        system = (
            "你是社团/班级的活动管家，正在 QQ 群里协助组织活动。"
            "请用简短、自然、友好的中文回答，优先结合提供的实时活动数据。"
            "你可以解释策划、任务、报名和提醒状态，但不要声称执行了尚未实际执行的操作。"
            "需要用户在网页操作时，明确告诉他打开活动管家网页。"
        )
        return self._chat(system, f"当前活动数据：\n{activity_context}\n\n群成员消息：{message}")

    def analyze_work_request(self, raw_input: str, class_context: str) -> WorkDraft:
        system = """你是班级事务规划 Agent。先理解用户真实意图，再决定最小可用流程，不要把所有需求都当复杂活动。
严格输出 JSON，字段：intent_type(assignment/activity/survey/notice)、complexity(simple/standard/complex)、title、summary、description、deadline、event_time、reasoning、missing_information、form_fields、workflow。
workflow 字段包含 create_plan、create_form、assign_tasks、create_calendar、schedule_reminder、publish_message、collect_submission，均为布尔值。
规则：收作业/收文件属于 assignment+simple，只需发布、提交、统计与必要提醒，assign_tasks=false；单纯通知属于 notice+simple；只收集信息属于 survey+standard；真正的线下/线上活动才属于 activity，按需要启用问卷、分工、日历和提醒。缺失的信息可以合理推断，但把需要用户确认的内容写进 missing_information。"""
        content = self._chat(system, f"班级信息：{class_context}\n用户需求：{raw_input}")
        return WorkDraft(**json.loads(_extract_json(content)))


class FallbackLLMClient(LLMClient):
    """Use the offline rules when the configured remote model is unavailable."""

    def __init__(self, primary: LLMClient, fallback: LLMClient):
        self.primary = primary
        self.fallback = fallback

    def _call(self, method: str, *args):
        try:
            return getattr(self.primary, method)(*args)
        except Exception as exc:
            logger.warning(
                "Remote LLM %s failed (%s); using offline fallback",
                method,
                type(exc).__name__,
            )
            return getattr(self.fallback, method)(*args)

    def generate_plan(self, raw_input: str, available_assignees: list[str] | None = None) -> ActivityPlan:
        return self._call("generate_plan", raw_input, available_assignees)

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
        return self._call("generate_recap", activity_title, stats, task_summary)

    def generate_assistant_reply(self, message: str, activity_context: str) -> str:
        return self._call("generate_assistant_reply", message, activity_context)

    def analyze_work_request(self, raw_input: str, class_context: str) -> WorkDraft:
        return self._call("analyze_work_request", raw_input, class_context)


class MockLLMClient(LLMClient):
    """离线规则式实现：未配置 API Key 时使用，输出确定，方便开发和测试。"""

    def generate_plan(self, raw_input: str, available_assignees: list[str] | None = None) -> ActivityPlan:
        title = raw_input.strip()[:20] or "未命名活动"
        event_time = (datetime.now() + timedelta(days=3)).replace(microsecond=0).isoformat()
        return ActivityPlan(
            title=title,
            description=(
                f"基于「{raw_input}」自动生成的活动方案："
                "开场签到 → 主题环节 → 互动环节 → 合影收尾。"
                "注意提前确认场地设备，活动当天负责人提前 1 小时到场。"
            ),
            event_time=event_time,
            materials=["横幅", "签到表", "饮用水", "音响设备"],
            tasks=[
                TaskItem(title=title, assignee=(available_assignees or ["张三", "李四", "王五", "赵六"])[index % len(available_assignees or ["张三", "李四", "王五", "赵六"])])
                for index, title in enumerate(["场地预约", "宣传推送", "物料采购", "现场签到"])
            ],
            form_fields=[
                FormField(name="attendance", label="是否确认参加", type="select", required=True, options=["是", "否"]),
                FormField(name="note", label="备注或特殊需求", type="text", required=False),
            ],
            remind_minutes_before=60,
        )

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
        return (
            f"《{activity_title}》活动复盘\n"
            f"- 报名数据：共 {stats.get('count', 0)} 人报名\n"
            f"- 任务执行：{task_summary}\n"
            "- 亮点：流程按计划完成\n"
            "- 改进：下次可提前更久开放报名，并增加活动后问卷收集反馈"
        )

    def generate_assistant_reply(self, message: str, activity_context: str) -> str:
        return (
            f"收到你的问题：“{message}”。我是活动管家，可以结合当前活动帮助查看报名、"
            "任务、提醒和执行进度。发送“帮助”可查看快捷指令。"
        )

    def analyze_work_request(self, raw_input: str, class_context: str) -> WorkDraft:
        text = raw_input.strip()
        deadline = (datetime.now() + timedelta(days=3)).replace(microsecond=0).isoformat()
        if any(keyword in text for keyword in ("作业", "文件", "报告", "材料")):
            return WorkDraft(
                intent_type="assignment", complexity="simple", title=text[:30],
                summary="发布一项全班作业并收集提交，不需要额外分工。",
                description=text, deadline=deadline,
                reasoning="需求核心是收集每位成员的提交，使用作业流程最简洁。",
                missing_information=["请确认截止时间"],
                workflow=WorkflowConfig(collect_submission=True, publish_message=True),
            )
        if any(keyword in text for keyword in ("组织", "活动", "秋游", "比赛", "晚会", "宣讲", "聚会")):
            return WorkDraft(
                intent_type="activity", complexity="complex", title=text[:30],
                summary="生成完整活动方案并执行报名、分工、日历和提醒。",
                description=text, event_time=deadline,
                reasoning="需求包含多人参与的活动组织，需要多步骤协作。",
                missing_information=["请确认活动时间"],
                workflow=WorkflowConfig(
                    create_form=True, assign_tasks=True, create_calendar=True,
                    schedule_reminder=True, publish_message=True,
                ),
            )
        if any(keyword in text for keyword in ("问卷", "统计", "收集信息", "报名")):
            return WorkDraft(
                intent_type="survey", complexity="standard", title=text[:30],
                summary="创建信息收集表并发布链接。", description=text,
                reasoning="需求重点是结构化收集信息，不需要任务分工。",
                form_fields=[FormField(name="response", label="请填写相关信息", required=True)],
                workflow=WorkflowConfig(create_form=True, publish_message=True),
            )
        if any(keyword in text for keyword in ("通知", "告诉大家", "群里说")):
            return WorkDraft(
                intent_type="notice", complexity="simple", title=text[:30],
                summary="向班级群发布一条通知。", description=text,
                reasoning="需求只涉及消息发布，不需要创建任务或表单。",
                workflow=WorkflowConfig(create_plan=False, publish_message=True),
            )
        return WorkDraft(
            intent_type="activity", complexity="complex", title=text[:30],
            summary="生成完整活动方案并执行报名、分工、日历和提醒。",
            description=text, event_time=deadline,
            reasoning="需求包含活动组织，需要多步骤协作。",
            missing_information=["请确认活动时间"],
            workflow=WorkflowConfig(
                create_form=True, assign_tasks=True, create_calendar=True,
                schedule_reminder=True, publish_message=True,
            ),
        )


def get_llm() -> LLMClient:
    """配置了 API Key 用真实 LLM，否则回退离线 Mock。"""
    if settings.llm_api_key:
        return FallbackLLMClient(
            OpenAICompatibleClient(
                settings.llm_api_key, settings.llm_base_url, settings.llm_model
            ),
            MockLLMClient(),
        )
    return MockLLMClient()
