import type { Planet, StarSystem } from '../../api/types';
import { AsteroidRing, climateColor, planetRadius } from './planetVisuals';
import { t, useT } from '../../i18n';

/**
 * Схема звёздной системы — п. 4.1, разметкой окна «Star System» MOO II.
 *
 * Пропорции сняты со скриншота оригинала и держатся на нём целиком:
 *
 *  • поле — прямоугольник 840 × 480; в оригинале 455 × 260, то же отношение 1,75;
 *  • звезда стоит ровно в его центре, и она маленькая: ярче планет, но мельче их;
 *  • орбиты — полные эллипсы, сплюснутые по вертикали в 0,53 раза: система показана
 *    под углом, а не сверху, и планета обходит звезду кругом;
 *  • радиус орбиты растёт с номером ровным шагом, но начинается не от нуля — в
 *    оригинале rx = 30,5 + 34·n при полуширине поля 227,5, отсюда доли ниже. Пятая
 *    орбита занимает 88% полуширины: дальше поля уже не остаётся, а орбит в игре
 *    как раз пять — п. 4.1;
 *  • планета по величине близка к шагу между орбитами: в MOO II это крупный шар,
 *    а не точка на нитке.
 *
 * Планета сидит на своей орбите под произвольным углом, как в оригинале, а не в ряд
 * с соседями. Угол считается от номера орбиты золотым углом (137,5°): соседние орбиты
 * расходятся больше чем на треть круга, и планеты не наезжают друг на друга. Начальный
 * поворот системы взят от её идентификатора — расстановка у каждой звезды своя и не
 * меняется между перерисовками.
 *
 * Подписей у планет нет: в MOO II их тоже нет — выбранную называет карточка в углу
 * поля. Название остаётся всплывающей подсказкой: она ничего не занимает на схеме.
 */

/** Поле схемы в единицах viewBox; отношение сторон — как в оригинале. */
const FIELD_WIDTH = 840;
const FIELD_HEIGHT = 480;
const CENTER_X = FIELD_WIDTH / 2;
const CENTER_Y = FIELD_HEIGHT / 2;

/** Радиус первой орбиты и шаг до следующей — долями полуширины поля, как в оригинале. */
const ORBIT_BASE = 0.134 * CENTER_X;
const ORBIT_STEP = 0.1495 * CENTER_X;
/** Во сколько раз орбита сплюснута по вертикали — наклон системы к наблюдателю. */
const ORBIT_TILT = 0.53;
/** Золотой угол: им разводятся планеты соседних орбит. */
const GOLDEN_ANGLE = 137.5;

/** Звезда: искра вчетверо мельче планеты, вокруг — свечение и четыре луча. */
const STAR_CORE = 9;
const STAR_GLOW = 26;

const orbitRx = (orbit: number): number => ORBIT_BASE + ORBIT_STEP * orbit;

/** Поворот всей системы — от её идентификатора, чтобы у каждой звезды он был свой. */
const systemPhase = (id: string): number => {
  let hash = 0;
  for (let index = 0; index < id.length; index += 1) {
    hash = (hash * 31 + id.charCodeAt(index)) % 360;
  }
  return hash;
};

export function OrbitDiagram({
  system,
  selectedPlanetId,
  onSelect,
  onOpen,
}: {
  system: StarSystem;
  selectedPlanetId: string | null;
  onSelect: (planetId: string) => void;
  /** Двойной клик по планете — вход на экран управления колонией (п. 4.1). */
  onOpen: (planetId: string) => void;
}) {
  const phase = systemPhase(system.id);
  useT();

  return (
    <svg
      viewBox={`0 0 ${FIELD_WIDTH} ${FIELD_HEIGHT}`}
      className="h-auto w-full"
      aria-label={t('orbit.aria', { name: system.name ?? '' })}
    >
      <defs>
        <radialGradient id="star-core">
          <stop offset="0%" stopColor="#ffffff" />
          <stop offset="45%" stopColor={system.colorHex} />
          <stop offset="100%" stopColor={system.colorHex} stopOpacity="0" />
        </radialGradient>
      </defs>

      {/* Орбиты идут первыми: в оригинале планета перекрывает линию, а не наоборот. */}
      {system.planets.map((planet) => {
        const rx = orbitRx(planet.orbit);
        return (
          <ellipse
            key={planet.id}
            cx={CENTER_X}
            cy={CENTER_Y}
            rx={rx}
            ry={rx * ORBIT_TILT}
            fill="none"
            stroke={planet.id === selectedPlanetId ? '#7dd3fc' : '#243049'}
            strokeWidth={1}
            strokeDasharray="3 4"
          />
        );
      })}

      <Star color={system.colorHex} />

      {system.planets.map((planet) => (
        <PlanetOnOrbit
          key={planet.id}
          planet={planet}
          phase={phase}
          selected={planet.id === selectedPlanetId}
          onSelect={onSelect}
          onOpen={onOpen}
        />
      ))}
    </svg>
  );
}

/**
 * Звезда системы. В MOO II она заметно мельче планет: центр отдан не ей, а орбитам,
 * и на месте звезды стоит белая искра с расходящимися лучами.
 */
function Star({ color }: { color: string }) {
  return (
    <g>
      <circle cx={CENTER_X} cy={CENTER_Y} r={STAR_GLOW} fill="url(#star-core)" opacity={0.55} />
      <path
        d={
          `M ${CENTER_X - STAR_GLOW} ${CENTER_Y} H ${CENTER_X + STAR_GLOW} ` +
          `M ${CENTER_X} ${CENTER_Y - STAR_GLOW} V ${CENTER_Y + STAR_GLOW}`
        }
        stroke={color}
        strokeWidth={1.5}
        opacity={0.7}
      />
      <circle cx={CENTER_X} cy={CENTER_Y} r={STAR_CORE} fill={color} />
    </g>
  );
}

function PlanetOnOrbit({
  planet,
  phase,
  selected,
  onSelect,
  onOpen,
}: {
  planet: Planet;
  phase: number;
  selected: boolean;
  onSelect: (planetId: string) => void;
  onOpen: (planetId: string) => void;
}) {
  const rx = orbitRx(planet.orbit);
  const ry = rx * ORBIT_TILT;
  const angle = ((phase + GOLDEN_ANGLE * planet.orbit) * Math.PI) / 180;
  const cx = CENTER_X + rx * Math.cos(angle);
  const cy = CENTER_Y + ry * Math.sin(angle);
  const radius = planetRadius(planet.size);
  const belt = planet.climate === 'ASTEROID_BELT';

  return (
    <g
      className="cursor-pointer"
      onClick={() => onSelect(planet.id)}
      onDoubleClick={() => onOpen(planet.id)}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          onSelect(planet.id);
        }
      }}
    >
      <title>
        {planet.name} — {planet.sizeLabel}, {planet.climateLabel}
      </title>

      {belt ? (
        <>
          <AsteroidRing
            cx={CENTER_X}
            cy={CENTER_Y}
            rx={rx}
            ry={ry}
            color={climateColor(planet.climate)}
            selected={selected}
          />
          {/* По россыпи камней не попасть мышью, поэтому целится сама орбита. */}
          <ellipse
            cx={CENTER_X}
            cy={CENTER_Y}
            rx={rx}
            ry={ry}
            fill="none"
            stroke="transparent"
            strokeWidth={22}
          />
        </>
      ) : (
        <circle cx={cx} cy={cy} r={radius} fill={climateColor(planet.climate)} />
      )}

      {planet.homeworld ? (
        <circle cx={cx} cy={cy} r={radius + 5} fill="none" stroke="#ffd447" strokeWidth={1.5} />
      ) : null}

      {selected && !belt ? <SelectionBrackets cx={cx} cy={cy} radius={radius} /> : null}
    </g>
  );
}

/**
 * Выбранная планета в MOO II обведена не кольцом, а четырьмя уголками по краям —
 * так рамка не сливается с кружком планеты и с линией орбиты под ней.
 */
function SelectionBrackets({ cx, cy, radius }: { cx: number; cy: number; radius: number }) {
  const offset = radius + 9;
  const arm = 9;

  return (
    <g fill="none" stroke="#7dd3fc" strokeWidth={2}>
      {[
        [-1, -1],
        [1, -1],
        [-1, 1],
        [1, 1],
      ].map(([dx, dy]) => (
        <path
          key={`${dx} ${dy}`}
          d={
            `M ${cx + dx * offset} ${cy + dy * (offset - arm)} ` +
            `L ${cx + dx * offset} ${cy + dy * offset} ` +
            `L ${cx + dx * (offset - arm)} ${cy + dy * offset}`
          }
        />
      ))}
    </g>
  );
}
