import { useEffect, useRef, useState } from 'react';
import { CharacterSprite, DEFAULT_APPEARANCE, readAppearance, type CharacterAppearance } from './ValleyCharacterEditor';

interface Props { username?: string; displayName?: string }
interface Position { x: number; y: number }

export default function WorkspacePet({ username, displayName }: Props) {
  const [appearance, setAppearance] = useState<CharacterAppearance>(DEFAULT_APPEARANCE);
  const [position, setPosition] = useState<Position>(() => {
    try { return JSON.parse(localStorage.getItem('valley-pet:position') || 'null') || { x: 24, y: 24 }; }
    catch { return { x: 24, y: 24 }; }
  });
  const [walking, setWalking] = useState(true);
  const [collapsed, setCollapsed] = useState(false);
  const drag = useRef<{ startX: number; startY: number; originX: number; originY: number } | null>(null);

  useEffect(() => {
    try {
      const saved = username ? localStorage.getItem(`valley-character:${username}`) : null;
      const draft = localStorage.getItem('valley-character:draft');
      setAppearance(readAppearance(JSON.parse(saved || draft || 'null')));
    } catch { setAppearance(DEFAULT_APPEARANCE); }
  }, [username]);

  const beginDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest('button')) return;
    drag.current = { startX: event.clientX, startY: event.clientY, originX: position.x, originY: position.y };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const moveDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return;
    const next = {
      x: Math.max(8, Math.min(window.innerWidth - 120, drag.current.originX - (event.clientX - drag.current.startX))),
      y: Math.max(8, Math.min(window.innerHeight - 150, drag.current.originY - (event.clientY - drag.current.startY))),
    };
    setPosition(next);
  };
  const endDrag = () => {
    drag.current = null;
    try { localStorage.setItem('valley-pet:position', JSON.stringify(position)); } catch { /* optional */ }
  };

  return <div className={`valley-workspace-pet ${collapsed ? 'collapsed' : ''}`}
    style={{ right: position.x, bottom: position.y }} onPointerDown={beginDrag} onPointerMove={moveDrag}
    onPointerUp={endDrag} onPointerCancel={endDrag} aria-label="工作台像素桌宠">
    <div className="pet-toolbar">
      <button type="button" title={walking ? '暂停动作' : '继续动作'} onClick={() => setWalking(value => !value)}>{walking ? 'Ⅱ' : '▶'}</button>
      <button type="button" title={collapsed ? '展开桌宠' : '收起桌宠'} onClick={() => setCollapsed(value => !value)}>{collapsed ? '♣' : '×'}</button>
    </div>
    {!collapsed && <>
      <span className="pet-name">{displayName || username || '山谷伙伴'}</span>
      <div className="pet-sprite"><CharacterSprite value={appearance} direction={2} walking={walking} /></div>
      <span className="pet-shadow" />
      <small>拖动我</small>
    </>}
  </div>;
}
