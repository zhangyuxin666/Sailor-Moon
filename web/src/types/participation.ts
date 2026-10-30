export interface ParticipationActivity {
  id: string;
  title: string;
  status: string;
  event_time?: string | null;
  attendance_open: boolean;
  feedback_open: boolean;
  checked_in: boolean;
  feedback_submitted: boolean;
}

export interface ParticipationMember {
  account_id: string;
  display_name: string;
  username?: string;
  student_no?: string;
  eligible?: boolean;
  current_member?: boolean;
  rostered?: boolean;
  checked_in: boolean;
  checked_in_at?: string | null;
  feedback_submitted: boolean;
}

export interface ParticipationManagementData {
  activity_id: string;
  attendance_open: boolean;
  feedback_open: boolean;
  version: number;
  roster_confirmed?: boolean;
  summary: {
    eligible: number;
    checked_in: number;
    absent: number;
    feedback_count: number;
    rating_average: number | null;
    rating_counts: Record<string, number>;
  };
  participants: ParticipationMember[];
  feedback: {
    account_id: string;
    display_name: string;
    rating: number;
    comment: string;
    updated_at?: string;
  }[];
}

export interface MyParticipation {
  activity: { id: string; title: string; status: string; event_time?: string | null };
  attendance_open: boolean;
  feedback_open: boolean;
  eligible: boolean;
  current_member?: boolean;
  rostered?: boolean;
  message?: string;
  checked_in: boolean;
  checked_in_at?: string | null;
  feedback: { rating: number; comment: string; version: number } | null;
}
