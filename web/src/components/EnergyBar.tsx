/**
 * 右下角体力条：满条显示，贴图是星露谷原版 E 条抠图。
 */
export default function EnergyBar() {
  return (
    <div className="game-energy" aria-label="体力 100 / 100">
      <img src="/assets/stardew/ui/energy.png" alt="" draggable={false} />
    </div>
  );
}
