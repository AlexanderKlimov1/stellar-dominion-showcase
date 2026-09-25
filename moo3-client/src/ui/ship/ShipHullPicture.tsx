import type { ShipHull } from '../../api/types';
import { t } from '../../i18n';

/**
 * Силуэт корабля в рамке — п. 8, левым верхом окна Ship Design MOO II.
 *
 * В оригинале там стоит картинка корабля со стрелками по бокам: класс один, а рисунков
 * несколько, и игрок выбирает, каким будет его корабль. Спрайтов у игры нет, выбирать
 * не из чего, поэтому стрелок нет тоже, а в рамке — силуэт, нарисованный так же, как
 * корабли на поле боя (`ui/battle/battleVisuals.ts`). Появятся спрайты — встанут сюда.
 *
 * Размер не пропорционален месту корпуса (30 у фрегата против 1200 у Leviathan): по
 * пропорции фрегат стал бы точкой. Ряд подобран так, чтобы все шесть классов различались
 * на глаз.
 */

/** Доля поля, которую занимает силуэт каждого размера корпуса (1..6). */
const SCALE = [0.3, 0.42, 0.55, 0.68, 0.84, 1];

export function ShipHullPicture({
  hull,
  compact = false,
}: {
  hull: ShipHull | null;
  /** Уменьшенная рамка для списка проектов: там силуэт — примета, а не картина. */
  compact?: boolean;
}) {
  const size = hull ? SCALE[Math.max(1, Math.min(6, hull.size)) - 1] : 0;
  const base = compact ? 60 : 150;
  const width = Math.round(size * base);
  const height = Math.round(size * base * 0.46);

  return (
    <div
      className={`flex items-center justify-center border border-space-700 bg-space-950/60 ${
        compact ? 'h-14' : 'h-40'
      }`}
    >
      {hull === null ? (
        <span className="text-18 text-ink-faint">{compact ? '—' : t('design.noHull')}</span>
      ) : (
        <svg
          width={width + 24}
          height={height + 8}
          viewBox={`0 0 ${width + 24} ${height + 8}`}
          aria-label={hull.name}
        >
          {/*
            Нос вправо: корабль на поле боя тоже смотрит в сторону противника, и силуэт
            не должен читаться иначе, чем то, что игрок увидит в бою.
          */}
          <polygon
            points={`4,4 ${width},4 ${width + 20},${height / 2 + 4} ${width},${height + 4} 4,${height + 4}`}
            className="fill-space-800 stroke-accent"
            strokeWidth={1}
          />
        </svg>
      )}
    </div>
  );
}
