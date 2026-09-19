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
    qq_bot_app_id: str = ""
    qq_bot_app_secret: str = ""
    qq_gateway_token: str = ""
    public_base_url: str = "http://127.0.0.1:8001"
    environment: str = "development"
    cookie_secure: bool = False
    session_days: int = 7
    upload_max_mb: int = 20
    user_storage_quota_mb: int = 200
    storage_backend: str = "local"
    storage_local_path: str = "data/uploads"
    s3_endpoint_url: str = ""
    s3_bucket: str = ""
    s3_access_key: str = ""
    s3_secret_key: str = ""
    s3_region: str = "auto"
    allow_legacy_api: bool = False


settings = Settings()
