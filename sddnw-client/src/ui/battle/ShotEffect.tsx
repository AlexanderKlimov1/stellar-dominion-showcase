import { useEffect, type ReactNode } from 'react';
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
  /**
   * Отрезок полёта ракеты, а не удар (backlog-promo, пункт 30): ракета не долетела в этот
   * круг и остаётся на поле. Рисуется ярко, как летящая, и без вспышки — бить ещё некого.
   */
  leg?: boolean;
  color: string;
  /** Задержка перед началом: залпы одной пачки идут друг за другом, а не разом. */
  delayMs: number;
  /**
   * Место ракеты в рою, от середины: 0 — одиночная, ±1, ±2… — соседние. По нему ракеты
   * одного залпа расходятся веером, а не летят одна в другой.
   */
  spread?: number;
}

/** Сколько горит вспышка попадания, миллисекунды. */
const IMPACT_MS = 280;

/**
 * Когда выстрел долетает до цели, считая от начала его анимации: луч бьёт мгновенно,
 * первый снаряд очереди долетает на 85 % своего срока, ракета — в конце дуги.
 */
function arrivalMs(shot: Shot): number {
  const life = LIFETIME_MS[shot.kind];
  return shot.delayMs + (shot.kind === 'BEAM' ? 60 : shot.kind === 'PROJECTILE' ? life * 0.85 : life);
}

/**
 * Вспышка в точке удара — п. 8. Только у попавшего выстрела: промах уходит мимо, и
 * вспышка на цели врала бы, что он её задел. Ядро белое, ореол — цветом стороны, чтобы
 * было видно, ЧЕЙ выстрел попал.
 */
function Impact({ x, y, color, startMs }: { x: number; y: number; color: string; startMs: number }) {
  const style = {
    animation: `sddnw-impact ${IMPACT_MS}ms ${startMs}ms ease-out both`,
    transformBox: 'fill-box' as const,
    transformOrigin: 'center',
  };
  return (
    <g>
      <circle cx={x} cy={y} r={CELL * 0.42} fill={color} opacity={0.5} style={style} />
      <circle cx={x} cy={y} r={CELL * 0.16} fill="#ffffff" style={style} />
    </g>
  );
}

/** Сколько держится жар в точке попадания луча — дольше самого луча: металл остывает. */
const BEAM_HEAT_MS = 700;

/** Искр рассеивания вдоль луча. */
const BEAM_SPARKS = 7;

/** Детерминированный «случай» от строки и номера: перерисовка не должна трясти искры. */
function jitter(seed: string, index: number): number {
  let hash = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    hash = Math.imul(hash ^ seed.charCodeAt(i), 16777619);
  }
  hash = Math.imul(hash ^ (index + 1) * 2654435761, 16777619);
  return ((hash >>> 0) % 1000) / 1000;
}

/**
 * Луч — backlog-promo, пункт 30, просьба хозяина проекта: сердцевина мощная, к краям свет
 * рассеивается, а концы луча НАГРЕВАЮТСЯ — у ствола слабее, в точке попадания сильнее.
 *
 * Луч рисуется в своей системе координат — от ствола вдоль оси x, — поэтому поперечный
 * градиент (прозрачно → цвет стороны → белая сердцевина → цвет → прозрачно) ложится поперёк
 * луча при любом наклоне. Ореол размыт фильтром, а вдоль краёв от луча отлетают искры —
 * рассеянный свет. Место искр выводится из идентификатора выстрела, а не из `Math.random`:
 * сцена перерисовывается на каждое движение мыши. Промах — без жара на цели: луч прошёл мимо
 * и ничего не нагрел.
 */
function Beam({
  shot,
  from,
  to,
  opacity,
  life,
  impact,
}: {
  shot: Shot;
  from: { x: number; y: number };
  to: { x: number; y: number };
  opacity: number;
  life: number;
  impact: ReactNode;
}) {
  const length = Math.max(1, Math.hypot(to.x - from.x, to.y - from.y));
  const angle = (Math.atan2(to.y - from.y, to.x - from.x) * 180) / Math.PI;
  const key = shot.id.replace(/[^A-Za-z0-9_-]/g, '');
  const width = CELL * 0.34;
  const beamStyle = { animation: `sddnw-beam ${life}ms ${shot.delayMs}ms ease-out both` };
  const heatAt = shot.delayMs + 60;
  return (
    <g>
      <defs>
        <linearGradient id={`beam-x-${key}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={shot.color} stopOpacity={0} />
          <stop offset="0.3" stopColor={shot.color} stopOpacity={0.55} />
          <stop offset="0.5" stopColor="#ffffff" stopOpacity={1} />
          <stop offset="0.7" stopColor={shot.color} stopOpacity={0.55} />
          <stop offset="1" stopColor={shot.color} stopOpacity={0} />
        </linearGradient>
        <radialGradient id={`beam-heat-${key}`}>
          <stop offset="0" stopColor="#ffffff" stopOpacity={1} />
          <stop offset="0.3" stopColor="#fff1b8" stopOpacity={0.95} />
          <stop offset="0.6" stopColor="#ff9a3c" stopOpacity={0.6} />
          <stop offset="1" stopColor={shot.color} stopOpacity={0} />
        </radialGradient>
        <filter id={`beam-blur-${key}`} x="-20%" y="-300%" width="140%" height="700%">
          <feGaussianBlur stdDeviation={CELL * 0.08} />
        </filter>
      </defs>

      <g transform={`translate(${from.x} ${from.y}) rotate(${angle})`} style={beamStyle}>
        {/* Рассеяние: широкий размытый ореол — к краям свет уходит в дымку. */}
        <rect x={0} y={-width} width={length} height={width * 2} fill={shot.color}
          opacity={0.18 * opacity} filter={`url(#beam-blur-${key})`} />
        {/* Тело луча: поперёк — от прозрачного края через цвет к белой середине. */}
        <rect x={0} y={-width / 2} width={length} height={width} fill={`url(#beam-x-${key})`}
          opacity={opacity} />
        {/* Сердцевина — тонкая и белая: в ней вся мощь луча. */}
        <line x1={0} y1={0} x2={length} y2={0} stroke="#ffffff" strokeWidth={1.6}
          opacity={opacity} strokeLinecap="round" />
        {/* Искры рассеивания отлетают от краёв луча в обе стороны. */}
        {Array.from({ length: BEAM_SPARKS }, (_, index) => {
          const along = 0.1 + 0.8 * jitter(key, index);
          const side = jitter(key, index + 50) < 0.5 ? -1 : 1;
          const drift = (0.25 + 0.5 * jitter(key, index + 100)) * CELL * side;
          return (
            <circle key={index} cx={length * along} cy={(side * width) / 3} r={1.1}
              fill={shot.color} opacity={0.8 * opacity}
              style={{
                animation: `sddnw-projectile ${life}ms ${shot.delayMs + 40}ms ease-out both`,
                ['--fly-x' as string]: `${(jitter(key, index + 150) - 0.5) * CELL * 0.3}px`,
                ['--fly-y' as string]: `${drift}px`,
              }} />
          );
        })}
      </g>

      {/* Жар у ствола — слабый: луч только родился. */}
      <circle cx={from.x} cy={from.y} r={CELL * 0.24} fill={`url(#beam-heat-${key})`}
        opacity={0.55 * opacity}
        style={{ animation: `sddnw-beam ${life}ms ${shot.delayMs}ms ease-out both` }} />
      {/* Жар в точке попадания — сильный и держится дольше луча: металл раскалён. */}
      {shot.hit ? (
        <circle cx={to.x} cy={to.y} r={CELL * 0.6} fill={`url(#beam-heat-${key})`}
          style={{
            animation: `sddnw-heat ${BEAM_HEAT_MS}ms ${heatAt}ms ease-out both`,
            transformBox: 'fill-box',
            transformOrigin: 'center',
          }} />
      ) : null}
      {impact}
    </g>
  );
}

export function ShotEffect({ shot, onDone }: { shot: Shot; onDone: () => void }) {
  useEffect(() => {
    // Выстрел уходит с поля, когда догорела и вспышка попадания, а не когда долетел.
    const heat = shot.kind === 'BEAM' ? shot.delayMs + 60 + BEAM_HEAT_MS : 0;
    const done = Math.max(LIFETIME_MS[shot.kind] + shot.delayMs, arrivalMs(shot) + IMPACT_MS, heat) + 120;
    const timer = window.setTimeout(onDone, done);
    return () => window.clearTimeout(timer);
  }, [shot, onDone]);

  const from = { x: shot.fromX * CELL + CELL / 2, y: shot.fromY * CELL + CELL / 2 };
  const to = { x: shot.toX * CELL + CELL / 2, y: shot.toY * CELL + CELL / 2 };
  const opacity = shot.hit || shot.leg ? 1 : 0.45;
  const life = LIFETIME_MS[shot.kind];
  const impact = shot.hit ? <Impact x={to.x} y={to.y} color={shot.color} startMs={arrivalMs(shot)} /> : null;

  if (shot.kind === 'BEAM') {
    return <Beam shot={shot} from={from} to={to} opacity={opacity} life={life} impact={impact} />;
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
              animation: `sddnw-projectile ${life}ms ${shot.delayMs + index * 90}ms linear forwards`,
              ['--fly-x' as string]: `${(to.x - from.x) * 0.86}px`,
              ['--fly-y' as string]: `${(to.y - from.y) * 0.86}px`,
            }}
          />
        ))}
        {impact}
      </g>
    );
  }

  // Ракета: идёт по дуге — от прямой линии её отличает именно траектория.
  const midX = (from.x + to.x) / 2;
  const midY = (from.y + to.y) / 2;
  const normalX = -(to.y - from.y);
  const normalY = to.x - from.x;
  const length = Math.max(1, Math.hypot(normalX, normalY));
  // Рой расходится веером: у каждой ракеты свой изгиб, и все сходятся на цели.
  const bend = Math.min(CELL * 1.6, length * 0.25) + (shot.spread ?? 0) * CELL * 0.45;
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
        style={{ animation: `sddnw-missile-trail ${life}ms ${shot.delayMs}ms ease-in forwards` }}
      />
      {/*
        Ракета — тело с огнём сопла, а не точка: её поворачивает по дуге `rotate="auto"`,
        и носом она смотрит туда, куда летит. Нос — по оси x, огонь сзади.
      */}
      {/*
        До начала движения `animateMotion` держит ракету в точке (0, 0) — в левом верхнем
        углу поля, — и всю задержку залпа она висела бы там. Поэтому ракета скрыта, пока
        не придёт её миг.
      */}
      <g opacity={opacity} visibility="hidden">
        <set attributeName="visibility" to="visible" begin={`${shot.delayMs}ms`} fill="freeze" />
        {/*
          Ракета вдвое крупнее прежней: в клетке 44 точки девятиточечное тело терялось, и
          пуск было не отличить от снаряда.
        */}
        <circle cx={-12} cy={0} r={6} fill="#ffb347" opacity={0.55} />
        <circle cx={-10} cy={0} r={3} fill="#fff1b8" />
        <polygon points="-8,-5 -4,-2.6 -4,2.6 -8,5" fill="#8d877c" />
        <rect x={-8} y={-2.8} width={17} height={5.6} rx={2.6} fill="#d6d0c2" stroke="#4c4841" strokeWidth={0.6} />
        <polygon points="9,-2.8 15,0 9,2.8" fill={shot.color} />
        <animateMotion
          dur={`${life}ms`}
          begin={`${shot.delayMs}ms`}
          fill="freeze"
          path={path}
          rotate="auto"
        />
      </g>
      {impact}
    </g>
  );
}
