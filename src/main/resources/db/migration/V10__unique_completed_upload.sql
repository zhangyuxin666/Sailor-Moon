-- 完成请求重试只返回原文件；数据库约束防止同一会话产生重复文件。
CREATE UNIQUE INDEX IF NOT EXISTS ux_file_assets_upload ON file_assets(upload_id) WHERE upload_id IS NOT NULL;
