import { useEffect, useRef, useState } from 'react';

export interface CharacterAppearance { hair: number; shirt: number; pants: number; shoes: number }
export const DEFAULT_APPEARANCE: CharacterAppearance = { hair: 0, shirt: 0, pants: 0, shoes: 0 };
const LOOK_COUNT = 9;
const LOOK_NAMES = [
  '薄荷背带', '紫发冒险', '莓果黑衫',
  '黑色马甲', '红发礼服', '丁香蔷薇',
  '银灰毛衣', '青柠礼裙', '森林背带',
];

export function readAppearance(saved: unknown): CharacterAppearance {
  const source = saved && typeof saved === 'object' ? saved as Record<string, unknown> : {};
  const result = { ...DEFAULT_APPEARANCE };
  (Object.keys(result) as Array<keyof CharacterAppearance>).forEach(key => {
    const value = Number(source[key]);
    if (Number.isInteger(value) && value >= 0 && value < LOOK_COUNT) result[key] = value;
  });
  return result;
}

const imageCache = new Map<string, Promise<HTMLImageElement>>();
function loadImage(path: string) {
  let promise = imageCache.get(path);
  if (!promise) {
    promise = new Promise((resolve, reject) => {
      const image = new Image(); image.onload = () => resolve(image);
      image.onerror = () => { imageCache.delete(path); reject(new Error('人物素材加载失败')); };
      image.src = path;
    });
    imageCache.set(path, promise);
  }
  return promise;
}

function lookPath(index: number) {
  const safe = Math.min(LOOK_COUNT - 1, Math.max(0, Number(index) || 0));
  return `/assets/character/looks/look-${safe + 1}.png`;
}

const CANVAS_SIZE = 256;

/** 整套造型立绘：素材本身是画好的人物，直接整张绘制即可保证对齐。 */
export function CharacterSprite({ value }: { value: CharacterAppearance; direction?: number; walking?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true; setError('');
    loadImage(lookPath(value.hair))
      .then((image) => {
        if (!active || !canvas.current) return;
        const ctx = canvas.current.getContext('2d'); if (!ctx) return;
        ctx.imageSmoothingEnabled = false;
        ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);
        const scale = CANVAS_SIZE / image.naturalHeight;
        const drawW = image.naturalWidth * scale;
        const drawH = CANVAS_SIZE;
        ctx.drawImage(image, (CANVAS_SIZE - drawW) / 2, 0, drawW, drawH);
      })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : '人物加载失败'); });
    return () => { active = false; };
  }, [value.hair]);
  return error ? <p role="alert">{error}</p> : <canvas ref={canvas} width={CANVAS_SIZE} height={CANVAS_SIZE}
    className="valley-farmer-sprite" role="img" aria-label="Q版人物预览" />;
}

interface Props { value: CharacterAppearance; onChange: (value: CharacterAppearance) => void; name: string; role: string }
export default function ValleyCharacterEditor({ value, onChange, name, role }: Props) {
  const current = Math.min(LOOK_COUNT - 1, Math.max(0, value.hair));
  const select = (index: number) => onChange({ hair: index, shirt: index, pants: index, shoes: index });
  return <aside className="valley-detailed-editor chibi-wardrobe-editor" aria-label="Q版人物装扮编辑">
    <div className="valley-editor-topline"><span>山谷衣橱</span><span>自由搭配</span></div>
    <div className="valley-farmer-stage"><span className="valley-stage-sun" aria-hidden="true" />
      <div className="valley-farmer-platform" /><CharacterSprite value={value} />
      <span className="valley-preview-name">{name || '山谷新朋友'}<small>{role}</small></span></div>
    <div className="valley-editor-tabs wardrobe-tabs" role="tablist" aria-label="造型分类">
      <button type="button" role="tab" aria-selected="true" aria-pressed="true">造型</button>
    </div>
    <div className="wardrobe-item-grid" role="group" aria-label="造型单品">
      {LOOK_NAMES.map((label, index) => <button
        type="button" key={label} aria-label={`选择${label}`} aria-pressed={current === index}
        onClick={() => select(index)}>
        <span className="look-thumb" style={{ backgroundImage: `url(${lookPath(index)})` }} /><small>{label}</small></button>)}
    </div>
    <div className="selected-outfit-summary"><span>{LOOK_NAMES[current]}</span></div>
    <div className="valley-editor-actions"><button type="button" onClick={() => select(Math.floor(Math.random() * LOOK_COUNT))}>随机穿搭 ⚄</button>
      <button type="button" onClick={() => onChange({ ...DEFAULT_APPEARANCE })}>恢复默认</button></div>
    <small className="valley-editor-storage">装扮自动保存在当前浏览器，登录后会成为工作台桌宠</small>
  </aside>;
}
