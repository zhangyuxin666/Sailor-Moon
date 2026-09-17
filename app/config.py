from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_api_key: str = ""
    llm_base_url: str = "https://api.openai.com/v1"
    llm_model: str = "gpt-4o-mini"
    database_url: str = "sqlite:///activity.db"
    worker_poll_seconds: float = 1.0
    worker_max_attempts: int = 3
    timezone: str = "Asia/Shanghai"


settings = Settings()
