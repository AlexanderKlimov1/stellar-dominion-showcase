import type { Planet, StarSystem } from '../../api/types';
import { AsteroidRing, climateColor, planetImage, planetRadius } from './planetVisuals';
import { useGameStore } from '../../state/gameStore';
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
 * <b>Колония помечена флажком цвета хозяина</b> — слева сверху от планеты, как на схеме
 * системы оригинала (`docs/moo2/colonybase-target.png`: зелёный вымпел у Земли). Без него
 * заселённую планету было не отличить от свободной: снимок у обеих один и тот же. Флажок
 * ставится и заставе, и чужой колонии — своим цветом каждого: «чья планета» читается так же,
 * как название звезды на карте галактики.
 *
 * Подписей у планет нет: в MOO II их тоже нет — планету называет карточка, которая
 * всплывает при наведении (`PlanetInfo`, `onHover`). Системной подсказки браузера у
 * планеты нет: она легла бы поверх карточки и повторила бы её первую строку.
 */

/** В какой половине поля стоит указатель — карточка планеты встаёт в ДРУГУЮ. */
export type FieldSide = 'left' | 'right';

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

/** Где на схеме стоит планета — в единицах её `viewBox`. */
interface PlanetSpot {
  cx: number;
  cy: number;
}

export function OrbitDiagram({
  system,
  selectedPlanetId,
  onSelect,
  onOpen,
  onHover,
}: {
  system: StarSystem;
  selectedPlanetId: string | null;
  onSelect: (planetId: string) => void;
  /** Двойной клик по планете — вход на экран управления колонией (п. 4.1). */
  onOpen: (planetId: string) => void;
  /**
   * Указатель пришёл на планету или ушёл с неё — п. 4.1: по нему всплывает и гаснет
   * карточка планеты. `side` — половина поля, где стоит указатель: карточка встаёт в
   * другую, чтобы не закрыть ту самую планету, на которую смотрят.
   */
  onHover?: (planetId: string | null, side: FieldSide) => void;
}) {
  const phase = systemPhase(system.id);
  const players = useGameStore((state) => state.players);
  const colorOf = (planet: Planet): string | null =>
    players.find((player) => player.id === planet.ownerPlayerId)?.color ?? null;
  useT();

  return (
    /*
      Схема ВПИСЫВАЕТСЯ в отведённое поле, а не растягивает его. Прежде она считала свою
      высоту от ширины (`h-auto w-full`) и тем задавала высоту всему окну — окно от этого
      открывалось с полосой прокрутки. Теперь размер даёт поле, а `viewBox` с обычным
      `preserveAspectRatio` держит пропорции 1,75 внутри него: остаток ширины уходит в поля
      по краям, как у картины в раме.
    */
    <svg
      viewBox={`0 0 ${FIELD_WIDTH} ${FIELD_HEIGHT}`}
      className="h-full w-full"
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
          spot={goldenSpot(planet, phase)}
          selected={planet.id === selectedPlanetId}
          ownerColor={planet.ownerPlayerId ? colorOf(planet) : null}
          onSelect={onSelect}
          onOpen={onOpen}
          onHover={onHover}
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

/** Место планеты по золотому углу — там, где она стоит, если ей ничто не мешает. */
function goldenSpot(planet: Planet, phase: number): PlanetSpot {
  return spotAt(planet.orbit, phase + GOLDEN_ANGLE * planet.orbit);
}

function spotAt(orbit: number, degrees: number): PlanetSpot {
  const rx = orbitRx(orbit);
  const angle = (degrees * Math.PI) / 180;
  return { cx: CENTER_X + rx * Math.cos(angle), cy: CENTER_Y + rx * ORBIT_TILT * Math.sin(angle) };
}

function PlanetOnOrbit({
  planet,
  spot,
  selected,
  ownerColor,
  onSelect,
  onOpen,
  onHover,
}: {
  planet: Planet;
  /** Где стоит планета — по золотому углу от номера орбиты. */
  spot: PlanetSpot;
  selected: boolean;
  /** Цвет хозяина планеты; пусто — планета ничья, и флажка нет. */
  ownerColor: string | null;
  onSelect: (planetId: string) => void;
  onOpen: (planetId: string) => void;
  onHover?: (planetId: string | null, side: FieldSide) => void;
}) {
  const rx = orbitRx(planet.orbit);
  const ry = rx * ORBIT_TILT;
  const { cx, cy } = spot;
  const radius = planetRadius(planet.size);
  const belt = planet.climate === 'ASTEROID_BELT';
  const image = planetImage(planet.climate, planet.id);
  /*
    Половина поля считается по УКАЗАТЕЛЮ, а не по центру планеты: пояс астероидов — это
    вся орбита целиком, и центра, по которому судить, у него нет. У клавиатуры указателя
    нет вовсе — там судит место самой планеты.
  */
  const sideOf = (clientX: number, target: Element): FieldSide => {
    const box = target.closest('svg')?.getBoundingClientRect();
    return box && clientX < box.left + box.width / 2 ? 'left' : 'right';
  };
  const ownSide: FieldSide = cx < CENTER_X ? 'left' : 'right';

  return (
    <g
      className="cursor-pointer"
      onClick={() => onSelect(planet.id)}
      onDoubleClick={() => onOpen(planet.id)}
      onMouseEnter={(event) => onHover?.(planet.id, sideOf(event.clientX, event.currentTarget))}
      onMouseLeave={() => onHover?.(null, ownSide)}
      onFocus={() => onHover?.(planet.id, ownSide)}
      onBlur={() => onHover?.(null, ownSide)}
      role="button"
      aria-label={`${planet.name} — ${planet.sizeLabel}, ${planet.climateLabel}`}
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          onSelect(planet.id);
        }
      }}
    >
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
      ) : image ? (
        /*
          Снимок настоящего тела — `planetImage`. Прозрачность за диском уже вырезана в самом
          файле, поэтому ни маски, ни `clipPath` здесь не нужно: квадрат снимка и есть
          кружок планеты.
        */
        <image
          href={image}
          x={cx - radius}
          y={cy - radius}
          width={radius * 2}
          height={radius * 2}
          preserveAspectRatio="xMidYMid meet"
        />
      ) : (
        <circle cx={cx} cy={cy} r={radius} fill={climateColor(planet.climate)} />
      )}

      {planet.homeworld ? (
        <circle cx={cx} cy={cy} r={radius + 5} fill="none" stroke="#ffd447" strokeWidth={1.5} />
      ) : null}

      {selected && !belt ? <SelectionBrackets cx={cx} cy={cy} radius={radius} /> : null}

      {ownerColor ? <OwnerFlag cx={cx} cy={cy} radius={belt ? 8 : radius} color={ownerColor} /> : null}
    </g>
  );
}

/**
 * Флажок хозяина — древко слева сверху от планеты и вымпел, смотрящий вправо, как на схеме
 * системы оригинала. Вымпел — цветом империи, древко светлое: на тёмном поле оно и держит
 * флажок видимым, какого бы цвета ни была империя. Мышь флажок не ловит — щелчок остаётся
 * планете.
 */
function OwnerFlag({ cx, cy, radius, color }: { cx: number; cy: number; radius: number; color: string }) {
  // Размер — в единицах поля 840 × 480, которое окно системы ужимает вдвое: мельче
  // тридцати единиц флажок терялся среди уголков выбора и кольца родного мира.
  const baseX = cx - radius * 0.85;
  const baseY = cy - radius * 0.2;
  const height = Math.max(34, radius * 1.6);
  const topY = baseY - height;
  const pennant = Math.max(24, radius * 1.1);
  return (
    <g pointerEvents="none">
      <line x1={baseX} y1={baseY} x2={baseX} y2={topY} stroke="#d6dde8" strokeWidth={2.5} />
      <polygon
        points={`${baseX},${topY} ${baseX + pennant},${topY + pennant * 0.36} ${baseX},${topY + pennant * 0.72}`}
        fill={color}
        stroke="#05070f"
        strokeWidth={1.2}
      />
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
