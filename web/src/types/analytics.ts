export interface AnalyticsClass { id: string; name: string }
export interface AnalyticsSummary {
  activities: number;
  active_activities: number;
  members: number;
  registrations: number;
  task_completion_rate: number;
  attendance_rate: number;
}
export interface TrendPoint { date: string; activities: number; registrations: number }
export interface StatusPoint { status: string; value: number }
export interface ParticipationStats {
  participants: number;
  checked_in: number;
  feedback_count: number;
  average_rating: number;
}
export interface AnalyticsAlert { code: string; title: string; count: number; description: string }
export interface AnalyticsOverview {
  generated_at: string;
  days: number;
  classes: AnalyticsClass[];
  summary: AnalyticsSummary;
  trend: TrendPoint[];
  activity_status: StatusPoint[];
  task_status: StatusPoint[];
  participation: ParticipationStats;
  alerts: AnalyticsAlert[];
}
