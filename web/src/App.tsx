/**
 * 应用路由配置
 * SPA 覆盖全部页面：
 * - /login（公开）
 * - /privacy（公开）
 * - /forms/:id（公开报名）
 * - /portal、/assignments、/settings（需登录）
 * - /agent、/members、/activity、/activity/:id（需管理者）
 * - / 重定向到 /portal（已登录）或 /login（未登录）
 */
import { lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import { RequireAuth, RequireManager } from './components/ProtectedRoute';
import Login from './pages/Login';
import Portal from './pages/Portal';
import PublicForm from './pages/PublicForm';
import Privacy from './pages/Privacy';

const Agent = lazy(() => import('./pages/Agent'));
const Assignments = lazy(() => import('./pages/Assignments'));
const Members = lazy(() => import('./pages/Members'));
const Settings = lazy(() => import('./pages/Settings'));
const ActivityList = lazy(() => import('./pages/ActivityList'));
const ActivityDetail = lazy(() => import('./pages/ActivityDetail'));
const ClassChat = lazy(() => import('./pages/ClassChat'));
const Knowledge = lazy(() => import('./pages/Knowledge'));
const Participation = lazy(() => import('./pages/Participation'));
const Templates = lazy(() => import('./pages/Templates'));
const Analytics = lazy(() => import('./pages/Analytics'));

export default function App() {
  return (
    <Routes>
      {/* 公开页面 */}
      <Route path="/login" element={<Login />} />
      <Route path="/privacy" element={<Privacy />} />
      <Route path="/forms/:id" element={<PublicForm />} />

      {/* 需登录的工作台页面（共用 Layout 侧边栏） */}
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/portal" element={<Portal />} />
        <Route path="/chat" element={<ClassChat />} />
        <Route path="/assignments" element={<Assignments />} />
        <Route path="/participation" element={<Participation />} />
        <Route path="/settings" element={<Settings />} />
      </Route>

      {/* 需管理者的页面 */}
      <Route element={<RequireManager><Layout /></RequireManager>}>
        <Route path="/agent" element={<Agent />} />
        <Route path="/knowledge" element={<Knowledge />} />
        <Route path="/templates" element={<Templates />} />
        <Route path="/members" element={<Members />} />
        <Route path="/activity" element={<ActivityList />} />
        <Route path="/activity/:id" element={<ActivityDetail />} />
        <Route path="/analytics" element={<Analytics />} />
      </Route>

      {/* 根路径重定向 */}
      <Route path="/" element={<Navigate to="/portal" replace />} />

      {/* 404 兜底 */}
      <Route path="*" element={<Navigate to="/portal" replace />} />
    </Routes>
  );
}
