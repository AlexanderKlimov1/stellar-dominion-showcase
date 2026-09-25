import { useEffect } from 'react';
import type { WeaponKindCode } from '../../api/types';
import { CELL } from './battleVisuals';

/**
 * Выстрел на поле боя — п. 8: видно, кто в кого стреляет и чем.
 *
 * Три вида оружия рисуются по-разному, как и в оригинале:
 *
 * * <b>луч</b> — прямая линия от ствола до цели, вспыхивает и гаснет: бьёт мгновенно;
 * * <b>снаряд</b> — короткие росчерки, летящие к цели друг за другом: кинетика;
 * * <b>ракета</b> — вытянутое тело со следом, идёт к цели по дуге и медленнее прочих.
 *
 * Промах виден по цвету: попавший выстрел яркий, пустой — тусклый. Так по полю читается
 * не только «кто в кого», но и «попал ли», а журнал справа остаётся расшифровкой, а не
 * единственным источником.
 *
 * Анимация целиком на CSS: браузер считает её сам, без кадрового цикла в React. По концу
 * выстрел убирается — держать его на поле незачем.
 */

/** Сколько живёт выстрел каждого вида, миллисекунды. */
const LIFETIME_MS: Record<WeaponKindCode, number> = {
  BEAM: 420,
  PROJECTILE: 520,
  MISSILE: 760,
};

/** Цвет выстрела: у своих голубой, у чужих красный — как и сами корабли. */
export interface Shot {
  id: string;
  kind: WeaponKindCode;
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  /** Дошёл ли урон до цели: промах рисуется тускло. */
  hit: boolean;
  color: string;
  /** Задержка перед началом: залпы одной пачки идут друг за другом, а не разом. */
  delayMs: number;
}

export function ShotEffect({ shot, onDone }: { shot: Shot; onDone: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onDone, LIFETIME_MS[shot.kind] + shot.delayMs + 120);
    return () => window.clearTimeout(timer);
  }, [shot, onDone]);

  const from = { x: shot.fromX * CELL + CELL / 2, y: shot.fromY * CELL + CELL / 2 };
  const to = { x: shot.toX * CELL + CELL / 2, y: shot.toY * CELL + CELL / 2 };
  const opacity = shot.hit ? 1 : 0.45;
  const life = LIFETIME_MS[shot.kind];

  if (shot.kind === 'BEAM') {
    return (
      <g style={{ animation: `moo3-beam ${life}ms ${shot.delayMs}ms ease-out forwards` }}>
        {/* Широкая мягкая линия под тонкой: так луч читается как луч, а не как черта. */}
        <line
          x1={from.x}
          y1={from.y}
          x2={to.x}
          y2={to.y}
          stroke={shot.color}
          strokeWidth={5}
          opacity={0.25 * opacity}
          strokeLinecap="round"
        />
        <line
          x1={from.x}
          y1={from.y}
          x2={to.x}
          y2={to.y}
          stroke={shot.color}
          strokeWidth={1.5}
          opacity={opacity}
          strokeLinecap="round"
        />
      </g>
    );
  }

  if (shot.kind === 'PROJECTILE') {
    // Три росчерка друг за другом: очередь видна даже на коротком расстоянии.
    return (
      <g>
        {[0, 1, 2].map((index) => (
          <line
            key={index}
            x1={from.x}
            y1={from.y}
            x2={from.x + (to.x - from.x) * 0.14}
            y2={from.y + (to.y - from.y) * 0.14}
            stroke={shot.color}
            strokeWidth={2}
            opacity={opacity}
            strokeLinecap="round"
            style={{
              animation: `moo3-projectile ${life}ms ${shot.delayMs + index * 90}ms linear forwards`,
              ['--fly-x' as string]: `${(to.x - from.x) * 0.86}px`,
              ['--fly-y' as string]: `${(to.y - from.y) * 0.86}px`,
            }}
          />
        ))}
      </g>
    );
  }

  // Ракета: идёт по дуге — от прямой линии её отличает именно траектория.
  const midX = (from.x + to.x) / 2;
  const midY = (from.y + to.y) / 2;
  const normalX = -(to.y - from.y);
  const normalY = to.x - from.x;
  const length = Math.max(1, Math.hypot(normalX, normalY));
  const bend = Math.min(CELL * 1.6, length * 0.25);
  const control = { x: midX + (normalX / length) * bend, y: midY + (normalY / length) * bend };
  const path = `M ${from.x} ${from.y} Q ${control.x} ${control.y} ${to.x} ${to.y}`;

  return (
    <g>
      {/* След: гаснет позади ракеты и показывает, откуда она пришла. */}
      <path
        d={path}
        fill="none"
        stroke={shot.color}
        strokeWidth={1}
        opacity={0.3 * opacity}
        strokeDasharray="4 6"
        style={{ animation: `moo3-missile-trail ${life}ms ${shot.delayMs}ms ease-in forwards` }}
      />
      <circle r={3} fill={shot.color} opacity={opacity}>
        <animateMotion
          dur={`${life}ms`}
          begin={`${shot.delayMs}ms`}
          fill="freeze"
          path={path}
          rotate="auto"
        />
      </circle>
    </g>
  );
}
