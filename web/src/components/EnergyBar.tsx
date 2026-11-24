/**
 * 右下角体力条：满条显示，贴图是星露谷原版 E 条抠图。
 */
export default function EnergyBar() {
  return (
    <div className="game-energy" aria-label="体力 100 / 100">
      <picture>
        <source srcSet="/assets/stardew/ui/energy.webp" type="image/webp" />
        <img src="/assets/stardew/ui/energy.png" width={52} height={294} alt="" draggable={false} />
      </picture>
    </div>
  );
}
