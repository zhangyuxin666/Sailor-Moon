/**
 * 全局 TypeScript 类型定义
 * 对照后端 Spring Boot API 返回形状，确保前端调用时类型安全。
 */

/** 登录账号信息 */
export interface Account {
  username: string;
  display_name: string;
  role: 'manager' | 'participant';
  [key: string]: unknown;
}

/** /auth/status 返回 */
export interface AuthStatus {
  account: Account | null;
  initialized: boolean;
}

/** 班级信息 */
export interface ClassInfo {
  id: string;
  name: string;
  member_count?: number;
}

/** 待办/作业摘要（出现在 /portal/dashboard 的 todos 列表中） */
export interface TodoSummary {
  id: string;
  title: string;
  class_name: string;
  deadline: string;
  /** 管理者视角：总人数 */
  total_count?: number;
  /** 管理者视角：已完成人数 */
  done_count?: number;
  /** 参与者视角：我的提交状态 */
  my_status?: 'done' | 'pending';
}

/** /portal/dashboard 返回 */
export interface DashboardData {
  classes: ClassInfo[];
  todos: TodoSummary[];
}

/** Agent 草案的 workflow 各步骤 */
export interface DraftWorkflow {
  create_plan?: boolean;
  create_form?: boolean;
  assign_tasks?: boolean;
  create_calendar?: boolean;
  schedule_reminder?: boolean;
  publish_message?: boolean;
  collect_submission?: boolean;
  [key: string]: boolean | undefined;
}

/** Agent 草案详情 */
export interface DraftDetail {
  intent_type: 'assignment' | 'activity' | 'survey' | 'notice';
  complexity: 'simple' | 'standard' | 'complex';
  title: string;
  description?: string;
  summary?: string;
  deadline?: string;
  event_time?: string;
  missing_information?: string[];
  reasoning?: string;
  workflow: DraftWorkflow;
  [key: string]: unknown;
}

/** Agent 草案列表项 */
export interface DraftItem {
  id: string;
  title: string;
  summary: string;
  class_name: string;
  status: 'draft' | 'published';
  draft: DraftDetail;
}

/** /agent/drafts 返回 */
export interface DraftListResponse {
  drafts: DraftItem[];
}

/** 发布草案返回 */
export interface PublishResult {
  result_type: 'todo' | 'activity' | 'notice' | string;
  result_id?: string;
}

/** 作业/待办详情中的参与者 */
export interface TodoParticipant {
  account_id: string;
  student_no?: string;
  display_name: string;
  status: 'done' | 'pending';
  submitted_at?: string;
  original_filename?: string;
}

/** 作业/待办详情 */
export interface TodoDetail {
  todo: {
    id: string;
    title: string;
    description?: string;
    deadline?: string;
  };
  participants: TodoParticipant[];
  /** 参与者视角：我的提交记录 */
  assignment?: {
    note?: string;
    file_url?: string;
  };
}

/** 班级成员 */
export interface Member {
  id: string;
  student_no?: string;
  display_name: string;
  username: string;
  status: string;
  created_at: string;
}

/** /classes/{id}/members 返回 */
export interface MemberListResponse {
  members: Member[];
}

/** Excel 导入结果 */
export interface ImportResult {
  count: number;
}

/** QQ 集成状态 */
export interface QQStatus {
  configured: boolean;
  groups: Array<{ group_label: string; [key: string]: unknown }>;
  binding_code?: string;
  gateway?: {
    status: 'online' | 'offline' | 'error' | string;
    detail?: string;
  };
  public_base_url?: string;
}

/** 审计日志 */
export interface AuditLog {
  id?: string;
  action: string;
  resource_type?: string;
  resource_id?: string;
  created_at: string;
}

/** /audit-logs 返回 */
export interface AuditLogResponse {
  logs: AuditLog[];
}

/** 活动列表项 */
export interface ActivityItem {
  id: string;
  title: string;
  status: 'queued' | 'running' | 'ready' | 'failed' | 'finished' | string;
  raw_input?: string;
  created_at: string;
}

/** /portal/activities 返回 */
export interface ActivityListResponse {
  activities: ActivityItem[];
}

/** 活动策划 */
export interface ActivityPlan {
  title?: string;
  description?: string;
  event_time?: string;
  materials?: string[];
}

/** 活动任务 */
export interface ActivityTask {
  id: string;
  title: string;
  assignee: string;
  status: 'done' | 'pending';
}

/** 活动表单 */
export interface ActivityForm {
  id: string;
  [key: string]: unknown;
}

/** 活动提醒 */
export interface ActivityReminder {
  id: string;
  remind_at: string;
  message: string;
  status: 'scheduled' | 'sent' | 'cancelled' | string;
}

/** 活动执行步骤 */
export interface ActivityStep {
  step: string;
  detail?: string;
}

/** QQ 投递记录 */
export interface ActivityDelivery {
  kind: 'announcement' | 'reminder' | 'manual' | string;
  content: string;
  status: 'sent' | 'failed' | string;
}

/** 活动详情 */
export interface ActivityDetail {
  activity: {
    id: string;
    title?: string;
    status: string;
    raw_input?: string;
    created_at: string;
  };
  plan?: ActivityPlan;
  tasks?: ActivityTask[];
  forms?: ActivityForm[];
  reminders?: ActivityReminder[];
  steps?: ActivityStep[];
  deliveries?: ActivityDelivery[];
}

/** 报名统计 */
export interface FormStats {
  count: number;
  registrations: Array<{
    name: string;
    contact: string;
    created_at: string;
  }>;
}

/** 公开报名表单字段 */
export interface PublicFormField {
  name: string;
  label: string;
  type: 'text' | 'select' | string;
  required?: boolean;
  options?: string[];
}

/** /public/forms/{id} 返回 */
export interface PublicFormData {
  activity_title: string;
  description: string;
  event_time?: string;
  fields: PublicFormField[];
}

/** 复盘结果 */
export interface RecapResult {
  recap: string;
}
