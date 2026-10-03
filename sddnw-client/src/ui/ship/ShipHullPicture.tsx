import type { ShipHull } from '../../api/types';
import { t } from '../../i18n';
import { ShipPicture } from './shipArt';

/**
 * Корабль в рамке — п. 8, левым верхом окна Ship Design MOO II (`docs/moo2/ship-design.png`).
 *
 * В оригинале там стоит картинка корабля со стрелками по бокам: класс один, а рисунков
 * несколько, и игрок выбирает, каким будет его корабль. У нас рисунок на класс один
 * (`shipArt.tsx`) — тот же, что на поле боя и на экране флота, — поэтому стрелок нет:
 * выбирать не из чего. Появится несколько рисунков на класс — стрелки встанут сюда.
 *
 * Размер не пропорционален месту корпуса (30 у фрегата против 1200 у Leviathan): по
 * пропорции фрегат стал бы точкой. Ряд подобран так, чтобы все шесть классов различались
 * на глаз; различает их и сам силуэт.
 *
 * Цвет полосы — акцентный, тот же, каким свои корабли помечены на поле боя
 * (`battleVisuals.sideColor`): в окне дизайна корабль всегда свой.
 */

/** Доля рамки, которую занимает рисунок каждого размера корпуса (1..6). */
const SCALE = [0.42, 0.52, 0.64, 0.76, 0.88, 1];
const OWN_COLOR = '#7dd3fc';

export function ShipHullPicture({
  hull,
  compact = false,
}: {
  hull: ShipHull | null;
  /** Уменьшенная рамка для списка проектов: там рисунок — примета, а не картина. */
  compact?: boolean;
}) {
  const share = hull ? SCALE[Math.max(1, Math.min(6, hull.size)) - 1] : 0;
  const base = compact ? 52 : 150;

  return (
    <div
      className={`flex items-center justify-center border border-space-700 bg-space-950/60 ${
        compact ? 'h-14' : 'h-40'
      }`}
    >
      {hull === null ? (
        <span className="text-18 text-ink-faint">{compact ? '—' : t('design.noHull')}</span>
      ) : (
        <ShipPicture hullCode={hull.code} hullSize={hull.size} color={OWN_COLOR}
                     size={Math.round(share * base)} label={hull.name} />
      )}
    </div>
  );
}
