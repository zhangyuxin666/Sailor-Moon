import { useEffect, useRef, useState } from 'react';

export interface CharacterAppearance {
  body: 'male' | 'female'; hairStyle: number; outfit: number;
  skinColor: string; hairColor: string; eyeColor: string; shirtColor: string; pantsColor: string;
}
export const DEFAULT_APPEARANCE: CharacterAppearance = {
  body: 'male', hairStyle: 3, outfit: 1, skinColor: '#ecc09e', hairColor: '#744b35',
  eyeColor: '#547f71', shirtColor: '#5e8e9e', pantsColor: '#4d536d',
};
export const HAIR_STYLES = ['bob', 'bangs', 'bangslong', 'bedhead', 'buzzcut', 'cowlick', 'curly_long', 'curly_short', 'curtains', 'long', 'afro', 'bangs_bun'];
const HAIR_NAMES = ['齐耳短发', '轻盈刘海', '长刘海', '蓬松短发', '清爽寸头', '俏皮翘发', '波浪长发', '卷曲短发', '中分短发', '自然长发', '蓬松卷发', '刘海丸子头'];
const OUTFITS = ['日常短袖', '纽扣衬衫', '长袖上衣', '复古长衫'];

export function readAppearance(saved: unknown): CharacterAppearance {
  const value = saved && typeof saved === 'object' ? saved as Record<string, unknown> : {};
  const result = { ...DEFAULT_APPEARANCE };
  result.body = value.body === 'female' ? 'female' : 'male';
  for (const key of ['hairStyle', 'outfit'] as const) {
    const number = Number(value[key]);
    if (Number.isInteger(number) && number >= 0 && number < (key === 'hairStyle' ? HAIR_STYLES.length : OUTFITS.length)) result[key] = number;
  }
  for (const key of ['skinColor', 'hairColor', 'eyeColor', 'shirtColor', 'pantsColor'] as const) {
    if (typeof value[key] === 'string' && /^#[0-9a-f]{6}$/i.test(value[key])) result[key] = value[key];
  }
  return result;
}

const images = new Map<string, Promise<HTMLImageElement>>();
function loadImage(name: string) {
  let promise = images.get(name);
  if (!promise) {
    promise = new Promise<HTMLImageElement>((resolve, reject) => {
      const img = new Image(); img.onload = () => resolve(img); img.onerror = () => { images.delete(name); reject(new Error('人物素材暂未加载完成')); };
      img.src = `/assets/character/${name}.png`;
    });
    images.set(name, promise);
  }
  return promise;
}

/** Draw original 64px LPC frames; palette changes preserve each layer's light and shadow. */
function CharacterSprite({ value, direction, walking }: { value: CharacterAppearance; direction: number; walking: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true, timer: ReturnType<typeof setInterval> | undefined;
    setError('');
    const layers: [string, string][] = [
      [`body-${value.body}`, value.skinColor], [`pants-${value.body}`, value.pantsColor],
      [`shoes-${value.body}`, '#634733'], [`shirt-${value.body}-${value.outfit}`, value.shirtColor],
      [`head-${value.body}`, value.skinColor], ['eyes', value.eyeColor], [`hair-${HAIR_STYLES[value.hairStyle]}`, value.hairColor],
    ];
    Promise.all(layers.map(([name]) => loadImage(name))).then(loaded => {
      if (!active || !canvas.current) return;
      const context = canvas.current.getContext('2d');
      if (!context) return;
      const tile = document.createElement('canvas'); tile.width = 64; tile.height = 64;
      const temporary = tile.getContext('2d', { willReadFrequently: true });
      if (!temporary) return;
      context.imageSmoothingEnabled = false;
      let frame = 0;
      const draw = () => {
        if (!active) return;
        context.clearRect(0, 0, 64, 64);
        loaded.forEach((img, index) => {
          temporary.clearRect(0, 0, 64, 64);
          temporary.drawImage(img, frame * 64, direction * 64, 64, 64, 0, 0, 64, 64);
          const pixels = temporary.getImageData(0, 0, 64, 64);
          const data = pixels.data;
          const target = layers[index][1].match(/[0-9a-f]{2}/gi)!.map(v => parseInt(v, 16));
          let peak = 1;
          for (let i = 0; i < data.length; i += 4) if (data[i + 3]) peak = Math.max(peak, data[i] * .3 + data[i + 1] * .59 + data[i + 2] * .11);
          for (let i = 0; i < data.length; i += 4) {
            if (!data[i + 3]) continue;
            const light = (data[i] * .3 + data[i + 1] * .59 + data[i + 2] * .11) / peak;
            for (let channel = 0; channel < 3; channel++) data[i + channel] = Math.round(target[channel] * (.18 + .82 * light));
          }
          temporary.putImageData(pixels, 0, 0);
          context.drawImage(tile, 0, 0);
        });
        canvas.current?.setAttribute('data-frame', String(frame));
      };
      draw();
      if (walking) timer = setInterval(() => {
        if (document.hidden || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        frame = frame % 8 + 1; draw();
      }, 125);
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; if (timer) clearInterval(timer); };
  }, [value, direction, walking]);
  return <>{error ? <p role="alert">{error}</p> : <canvas ref={canvas} width={64} height={64} className="valley-farmer-sprite" role="img" aria-label="人物预览" data-direction={direction} />}</>;
}

interface Props { value: CharacterAppearance; onChange: (value: CharacterAppearance) => void; name: string; role: string }
export default function ValleyCharacterEditor({ value, onChange, name, role }: Props) {
  const [direction, setDirection] = useState(2);
  const [walking, setWalking] = useState(false);
  const [tab, setTab] = useState<'hair' | 'outfit' | 'color'>('hair');
  const update = (patch: Partial<CharacterAppearance>) => onChange({ ...value, ...patch });
  const shift = (key: 'hairStyle' | 'outfit', amount: number) => {
    const count = key === 'hairStyle' ? HAIR_STYLES.length : OUTFITS.length;
    update({ [key]: (value[key] + amount + count) % count });
  };
  const colors: { key: 'skinColor' | 'hairColor' | 'eyeColor' | 'shirtColor' | 'pantsColor'; label: string; presets: string[] }[] = [
    { key: 'skinColor', label: '肤色', presets: ['#f8dfc4', '#ecc09e', '#d5a17e', '#ae7859', '#795039', '#51372d'] },
    { key: 'hairColor', label: '发色', presets: ['#352b32', '#744b35', '#bc7742', '#e5bd65', '#a55647', '#a5a6af'] },
    { key: 'eyeColor', label: '瞳色', presets: ['#514136', '#547f71', '#5b86a0', '#967740', '#7b608b', '#30363a'] },
    { key: 'shirtColor', label: '上衣', presets: ['#668b4d', '#5e8e9e', '#b26056', '#9472a3', '#ddbb6b', '#ded5bd'] },
    { key: 'pantsColor', label: '裤装', presets: ['#4d536d', '#765f49', '#46583d', '#37444a', '#9b8062', '#bead8c'] },
  ];
  return <aside className="valley-detailed-editor" aria-label="人物外观编辑">
    <div className="valley-editor-topline"><span>人物装扮</span><span>01 / VALLEY</span></div>
    <div className="valley-farmer-stage">
      <span className="valley-stage-sun" aria-hidden="true" />
      <div className="valley-farmer-platform" />
      <CharacterSprite value={value} direction={direction} walking={walking} />
      <span className="valley-preview-name">{name || '山谷新朋友'}<small>{role}</small></span>
    </div>
    <div className="valley-turn-controls" role="group" aria-label="预览方向">
      {['背面', '左侧', '正面', '右侧'].map((label, i) => <button type="button" key={label} aria-label={`查看${label}`} aria-pressed={direction === i} onClick={() => setDirection(i)}>{['↑', '←', '↓', '→'][i]}</button>)}
      <button type="button" className="valley-walk-toggle" aria-pressed={walking} onClick={() => setWalking(v => !v)}>{walking ? '站立' : '行走'}</button>
    </div>
    <div className="valley-body-picker" role="group" aria-label="人物体型"><span>体型</span><button type="button" aria-pressed={value.body === 'male'} onClick={() => update({ body: 'male' })}>体型一</button><button type="button" aria-pressed={value.body === 'female'} onClick={() => update({ body: 'female' })}>体型二</button></div>
    <div className="valley-editor-tabs" role="group" aria-label="装扮分类">{(['hair', 'outfit', 'color'] as const).map((id, i) => <button type="button" key={id} aria-pressed={tab === id} onClick={() => setTab(id)}>{['发型', '服装', '配色'][i]}</button>)}</div>
    {tab === 'hair' && <>
      <div className="valley-style-stepper"><button type="button" aria-label="上一种发型" onClick={() => shift('hairStyle', -1)}>◀</button><span>{HAIR_NAMES[value.hairStyle]}<small>{value.hairStyle + 1} / {HAIR_STYLES.length}</small></span><button type="button" aria-label="下一种发型" onClick={() => shift('hairStyle', 1)}>▶</button></div>
      <div className="valley-hair-catalog" role="group" aria-label="发型目录">{HAIR_STYLES.map((id, i) => <button type="button" title={HAIR_NAMES[i]} aria-label={`选择${HAIR_NAMES[i]}`} aria-pressed={value.hairStyle === i} key={id} onClick={() => update({ hairStyle: i })}><span className="valley-hair-thumbnail" style={{ backgroundImage: `url('/assets/character/hair-${id}.png')` }} /><small>{i + 1}</small></button>)}</div>
    </>}
    {tab === 'outfit' && <><div className="valley-style-stepper"><button type="button" aria-label="上一种服装" onClick={() => shift('outfit', -1)}>◀</button><span>{OUTFITS[value.outfit]}<small>{value.outfit + 1} / {OUTFITS.length}</small></span><button type="button" aria-label="下一种服装" onClick={() => shift('outfit', 1)}>▶</button></div><div className="valley-outfit-catalog">{OUTFITS.map((label, i) => <button type="button" key={label} aria-pressed={value.outfit === i} onClick={() => update({ outfit: i })}>{label}</button>)}</div></>}
    <div className={`valley-color-settings ${tab === 'color' ? 'valley-all-colors' : ''}`}>
      {colors.filter(c => tab === 'color' || (tab === 'hair' ? c.key === 'hairColor' : c.key === 'shirtColor')).map(color => <div className="valley-color-row" key={color.key}><span>{color.label}</span><div>{color.presets.map(hex => <button type="button" aria-label={`${color.label}${hex}`} aria-pressed={value[color.key].toLowerCase() === hex} key={hex} style={{ background: hex }} onClick={() => update({ [color.key]: hex })} />)}</div><label title={`自定义${color.label}`}><span className="valley-visually-hidden">自定义{color.label}</span><input type="color" value={value[color.key]} onChange={e => update({ [color.key]: e.target.value })} /></label></div>)}
    </div>
    <div className="valley-editor-actions"><button type="button" onClick={() => { const pick = <T,>(items: T[]) => items[Math.floor(Math.random() * items.length)]; update({ hairStyle: Math.floor(Math.random() * HAIR_STYLES.length), outfit: Math.floor(Math.random() * OUTFITS.length), ...Object.fromEntries(colors.map(c => [c.key, pick(c.presets)])) }); }}>随机装扮 ⚄</button><button type="button" onClick={() => onChange({ ...DEFAULT_APPEARANCE })}>恢复默认</button></div>
    <small className="valley-editor-storage">装扮自动保存在当前浏览器</small>
    <a className="valley-sprite-credits" href="/assets/character/ATTRIBUTION.md" target="_blank" rel="noreferrer">像素素材与作者署名 ↗</a>
  </aside>;
}
