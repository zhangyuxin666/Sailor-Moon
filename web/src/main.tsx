/**
 * 应用入口：挂载 React 根组件，包裹 AuthProvider 与 ToastProvider。
 */
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './lib/auth';
import { ToastProvider } from './components/Toast';
import './styles/global.css';
import './styles/stardew.css';
import './styles/stardew-home.css';
import './styles/valley-character.css';
import './styles/game-hud.css';
import './styles/one-screen.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <App />
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
