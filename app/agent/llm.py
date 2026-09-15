import json
import re
from datetime import datetime, timedelta

import httpx

from ..config import settings
from ..models.schemas import ActivityPlan, TaskItem
from . import prompts


class LLMClient:
    """LLM 抽象：生成活动策划、生成复盘总结。"""

    def generate_plan(self, raw_input: str) -> ActivityPlan:
        raise NotImplementedError

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
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

    def generate_plan(self, raw_input: str) -> ActivityPlan:
        content = self._chat(prompts.PLAN_SYSTEM_PROMPT, raw_input)
        return ActivityPlan(**json.loads(_extract_json(content)))

    def generate_recap(self, activity_title: str, stats: dict, task_summary: str) -> str:
        payload = json.dumps(
            {"title": activity_title, "stats": stats, "tasks": task_summary},
            ensure_ascii=False,
        )
        return self._chat(prompts.RECAP_SYSTEM_PROMPT, payload)


class MockLLMClient(LLMClient):
    """离线规则式实现：未配置 API Key 时使用，输出确定，方便开发和测试。"""

    def generate_plan(self, raw_input: str) -> ActivityPlan:
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
                TaskItem(title="场地预约", assignee="张三"),
                TaskItem(title="宣传推送", assignee="李四"),
                TaskItem(title="物料采购", assignee="王五"),
                TaskItem(title="现场签到", assignee="赵六"),
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


def get_llm() -> LLMClient:
    """配置了 API Key 用真实 LLM，否则回退离线 Mock。"""
    if settings.llm_api_key:
        return OpenAICompatibleClient(
            settings.llm_api_key, settings.llm_base_url, settings.llm_model
        )
    return MockLLMClient()
