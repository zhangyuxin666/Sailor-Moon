import { useEffect, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';

interface Props { children: ReactNode; loading: boolean }

/** Show the workspace entry over a full-screen parallax landscape. */
export default function StardewLoginScene({ children, loading }: Props) {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const requested = params.get('screen');
  const screen = requested === 'title' || requested === 'account' || requested === 'manual' ? requested : 'splash';
  const setScreen = (next: 'title' | 'account' | 'manual') => setParams({ screen: next }, { state: location.state });
  const [reducedMotion, setReducedMotion] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [hidden, setHidden] = useState(() => document.hidden);
  const [manuallyPaused, setManuallyPaused] = useState(false);
  const scene = useRef<HTMLElement>(null);
  const enterButton = useRef<HTMLButtonElement>(null);
  const frame = useRef<number | null>(null);
  const paused = reducedMotion || hidden || manuallyPaused;

  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onPreference = () => setReducedMotion(preference.matches);
    const onVisibility = () => setHidden(document.hidden);
    preference.addEventListener('change', onPreference);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      preference.removeEventListener('change', onPreference);
      document.removeEventListener('visibilitychange', onVisibility);
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    };
  }, []);

  useEffect(() => {
    if (screen === 'splash') scene.current?.focus();
    else if (screen === 'title') enterButton.current?.focus();
    else scene.current?.querySelector<HTMLElement>('.valley-stage-content h2')?.focus();
  }, [screen]);

  const resetPerspective = () => {
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = null;
    scene.current?.style.setProperty('--scene-x', '0px');
    scene.current?.style.setProperty('--scene-y', '0px');
  };

  const movePerspective = (event: PointerEvent<HTMLElement>) => {
    if (paused || event.pointerType !== 'mouse' || screen === 'account') return;
    const box = event.currentTarget.getBoundingClientRect();
    const x = (event.clientX - box.left) / box.width - .5;
    const y = (event.clientY - box.top) / box.height - .5;
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      scene.current?.style.setProperty('--scene-x', `${x * 18}px`);
      scene.current?.style.setProperty('--scene-y', `${y * 10}px`);
      frame.current = null;
    });
  };

  const openSignIn = () => {
    resetPerspective();
    setScreen('account');
  };

  return <main key={screen} ref={scene} tabIndex={-1} className={`valley-home valley-screen-${screen}`} data-motion={paused ? 'paused' : 'playing'}
    onClick={() => { if (screen === 'splash') setScreen('title'); }}
    onPointerMove={movePerspective} onPointerLeave={resetPerspective}
    onKeyDown={(event) => {
      if (screen === 'splash' && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); setScreen('title'); }
      if (event.key === 'Escape' && screen !== 'splash') setScreen('title');
      if (event.key.toLowerCase() === 'p' && screen !== 'account') setManuallyPaused((value) => !value);
    }}>
    <h1 className="valley-visually-hidden">活动管家 · 山谷日记</h1>
    <p id="valley-scene-instructions" className="valley-visually-hidden">
      点击场景或按 Enter 打开标题菜单。按 P 可暂停或继续场景动效。
    </p>
    <div className="valley-home-scenery" aria-hidden="true">
      <div className="valley-home-gradient" />
      <div className="valley-home-stars" />
      <div className="valley-home-clouds valley-home-clouds-far" />
      <div className="valley-home-clouds valley-home-clouds-near" />
      {[4, 5, 6, 7, 8].map((layer) => <div key={layer}
        className={`valley-home-mountain valley-home-mountain-${layer}`} />)}
      <div className="valley-home-tree valley-home-tree-left" />
      <div className="valley-home-tree valley-home-tree-right" />
      <div className="valley-home-farm" />
      <img className="valley-home-bird valley-home-bird-one" src="/assets/stardew/bird.gif" alt="" />
      <img className="valley-home-bird valley-home-bird-two" src="/assets/stardew/bird.gif" alt="" />
    </div>
    {(screen === 'splash' || screen === 'title') && <>
      <div className={screen === 'title' ? 'valley-title-sign' : 'valley-splash-title'}>
        <img className="valley-home-logo" src="/assets/stardew/activity-manager-logo.png" alt="活动管家" fetchPriority="high" draggable={false} />
      </div>
      {screen === 'title' && <>
        <header className="valley-title-toolbar"><span><img src="/assets/stardew/chicken.png" alt="" />活动管家</span><button type="button" aria-pressed={paused} onClick={() => setManuallyPaused(v => !v)}>{paused ? '▶ 继续背景' : 'Ⅱ 暂停背景'}</button></header>
        <p className="valley-title-caption">星露谷里的每一天，都值得认真安排。<span aria-hidden="true">✦</span></p>
        <p className="valley-title-invitation">一封新的委托，正在等你。</p>
        <nav className="valley-title-menu" aria-label="山谷入口">
          <button ref={enterButton} type="button" onClick={openSignIn} disabled={loading}><strong>LOGIN</strong><span>登录山谷</span><img src="/assets/stardew/chicken.png" alt="" /></button>
          <button type="button" onClick={() => setScreen('manual')}><strong>JOURNAL</strong><span>山谷手册</span><b aria-hidden="true">▤</b></button>
        </nav>
        <footer className="valley-title-footer"><span>ACTIVITY KEEPER · VALLEY JOURNAL</span><Link to="/privacy" target="_blank">隐私政策与用户协议</Link></footer>
      </>}
    </>}
    {screen === 'account' && <section className="valley-stage-content" aria-label="山谷人物档案">
      <button type="button" className="valley-page-back" onClick={() => setScreen('title')}>← 返回标题</button>
      {children}
    </section>}
    {screen === 'manual' && <section className="valley-stage-content valley-manual">
      <button type="button" className="valley-page-back" onClick={() => setScreen('title')}>← 返回标题</button>
      <p className="portal-eyebrow">THE VALLEY JOURNAL</p><h2 tabIndex={-1}>山谷手册</h2>
      <p className="valley-manual-lead">把琐碎交给日记，把时间留给相聚。</p>
      <div className="valley-manual-grid">
        <article><h3>01 · 选择你的身份</h3><p>管理者创建组织、班级与活动；参与者查看分配给自己的任务、提交材料；游客无需账号，浏览示例山谷。</p></article>
        <article><h3>02 · 建立人物档案</h3><p>在登录页切换“创建用户”，填写姓名、用户名和密码。新管理者拥有自己的组织，不会加入别人的组织；参与者注册不会自动加入任何班级。</p></article>
        <article><h3>03 · 开始协作</h3><p>登录后从工作台查看待办。管理者可导入班级成员，并在活动中安排任务与收集表单。已有班级账号请直接登录，避免重复注册。</p></article>
        <article><h3>04 · 轻松游览</h3><p>人物外观仅保存在当前浏览器。按 P 暂停背景，按 Esc 返回标题；支持键盘操作及系统减少动态效果设置。</p></article>
      </div>
    </section>}
  </main>;
}
