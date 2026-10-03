import { useMemo } from 'react';
import type { ColonyProject } from '../../api/types';
import { climateColor, surfaceImage } from './planetVisuals';
import {
  BuildingDefs, BuildingSprite, ColonyCentreSprite, MINEFIELD, OrbitalSprite,
  colonyCentreLevel, isOrbital, isShield, onGround,
} from './buildingArt';

/**
 * Поверхность планеты с застройкой — п. 10, область посередине экрана колонии MOO II
 * (`docs/moo2/colony.png`).
 *
 * В оригинале это самая большая картина экрана: звёздное небо, гряда гор на горизонте,
 * земля и здания объёмными фигурками в перспективе, соединённые дорогами. Так же и здесь,
 * с одной подменой: <b>земля под горизонтом — НАСТОЯЩИЙ СНИМОК</b> ({@link surfaceImage},
 * `assets/surfaces/`), а не заливка цветом климата. Небо со звёздами и гряда гор остаются
 * нарисованными, здания — рисунками `buildingArt`.
 *
 * <b>Почему снимок только ниже горизонта.</b> Фотография поверхности снята днём, и её небо
 * спорило бы со звёздным небом сцены; а гряда гор, нарисованная поверх снимка, даёт ту самую
 * линию горизонта, по которой снимок и обрезан. Стык закрыт дымкой (`cs-haze`): без неё
 * граница читается склейкой, а не далью. Мир без снимка (климат, на котором колоний не
 * бывает) рисуется по-старому, землёй цвета климата, — ветка сохранена целиком.
 *
 * <b>Свет в сцене один — слева сверху.</b> От него считаются тени и блики зданий
 * (`buildingArt`), по нему же отобраны и развёрнуты снимки: снятый при солнце справа
 * зеркалится при обработке. Тени снимка и тени построек обязаны идти в одну сторону, иначе
 * постройка читается наклейкой поверх фотографии.
 *
 * <b>Даль бледнее ближнего</b> (`haze`): чем дальше ряд, тем прозрачнее фигурки. Это
 * воздушная перспектива, и она же примиряет рисованное здание с фотографией — у горизонта
 * снимок сам уведён в дымку.
 *
 * <b>Посередине стоит САМА КОЛОНИЯ</b> ({@link ColonyCentreSprite}) — поселение, которое
 * есть всегда и которое не продаётся: у него нет ни кода в справочнике, ни цены, ни
 * правого щелчка. Без него свежая колония выглядела пустым полем, и сцена читалась как
 * «тут ничего нет», хотя тут живут люди. Посёлок занимает ПЕРВОЕ место застройки — то,
 * что ближе всего к середине переднего плана, — и постройки обрастают его, как и положено
 * посёлку; от него же расходятся дороги.
 *
 * <b>Места зданий — перспективная сетка.</b> Четыре ряда от горизонта к зрителю, дальние
 * мельче и плотнее; застраивается сперва середина переднего плана, потом края и глубина —
 * первые постройки встают там, куда смотрит глаз, а поздние обрастают их, как посёлок.
 * Место здания выводится из его ПОРЯДКОВОГО НОМЕРА в застройке, а не из случая: иначе
 * постройки перескакивали бы с места на место при каждом открытии экрана.
 *
 * <b>Случайность — от идентификатора планеты</b> (горы, звёзды, сдвиги мест): у каждого
 * мира свой пейзаж, и он один и тот же при каждом открытии. `Math.random` здесь невозможен:
 * сцена перерисовывается на каждое наведение мыши.
 *
 * Не на земле три вещи: орбитальная постройка (база, станция, крепость) висит в небе —
 * низко, у самого горизонта: верх неба на широком экране срезается, а полоса над горами
 * видна при любых пропорциях окна, —
 * планетарный щит накрывает поселение сводом, а Сеть охотников видна минами над горизонтом.
 * Продаются они тем же правым щелчком — по своему рисунку.
 */

/** Размер сцены в её собственных единицах; на экран она ложится по ширине. */
const W = 1000;
const H = 400;
const HORIZON = 150;

/** Ряды мест: высота основания, масштаб фигурок и сколько мест в ряду. */
const ROWS = [
  { y: 196, scale: 0.46, count: 7 },
  { y: 238, scale: 0.6, count: 7 },
  { y: 292, scale: 0.78, count: 7 },
  { y: 360, scale: 1, count: 6 },
];

/**
 * Воздушная перспектива: насколько видна постройка на своей глубине.
 *
 * У горизонта снимок сам уведён в дымку при обработке, и полновесная фигурка там торчала бы
 * из фона чернотой. Считается по высоте основания, а не по номеру ряда: ряды сдвигаются
 * жребием планеты, и постройка одного ряда бывает ближе постройки другого.
 */
const haze = (y: number) => 0.74 + 0.26 * Math.min(1, Math.max(0, (y - HORIZON) / (H - HORIZON)));

/** Точка, от которой расходится застройка: середина переднего плана. */
const CENTRE = { x: 500, y: 300 };

/** Детерминированный жребий от строки: mulberry32 от её хеша. */
function randomFrom(seed: string) {
  let h = 1779033703;
  for (let i = 0; i < seed.length; i += 1) {
    h = Math.imul(h ^ seed.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  let a = h >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Смешение цвета `#rrggbb` с другим в доле `share` второго. */
function mix(a: string, b: string, share: number) {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `#${pa.map((v, i) => Math.round(v + (pb[i] - v) * share).toString(16).padStart(2, '0')).join('')}`;
}

/** Все места сцены по очереди застройки: ближние к середине переднего плана — первыми. */
function slots(random: () => number) {
  const all = ROWS.flatMap((row, rowIndex) =>
    Array.from({ length: row.count }).map((_, i) => {
      const span = 760 - rowIndex * 40;
      const x = W / 2 - span / 2 + (span * (i + 0.5)) / row.count + (random() - 0.5) * 30;
      return { x, y: row.y + (random() - 0.5) * 8, scale: row.scale };
    }));
  const sorted = all.sort((p, q) =>
    Math.hypot(p.x - CENTRE.x, (p.y - CENTRE.y) * 2.2) - Math.hypot(q.x - CENTRE.x, (q.y - CENTRE.y) * 2.2));
  /*
    Первое место — САМ ПОСЁЛОК, и стоит он ровно в середине переднего плана, без жребия.
    Прочие места сдвинуты случаем от идентификатора планеты, и посёлок вместе с ними
    уезжал к краю: у одной планеты он оказывался слева, у другой справа, и сцена теряла
    середину. Посёлок — её якорь: вокруг него встают постройки и от него расходятся дороги,
    поэтому место у него назначенное, а не выпавшее.
  */
  sorted[0] = { x: CENTRE.x, y: CENTRE.y + 26, scale: 0.92 };
  return sorted;
}

/** Гряда гор по горизонту: ломаная от края до края. */
function ridge(random: () => number, base: number, height: number, step: number) {
  const points = [`0,${HORIZON + 4}`];
  for (let x = 0; x <= W; x += step) {
    points.push(`${x},${base - random() * height}`);
  }
  points.push(`${W},${HORIZON + 4}`);
  return points.join(' ');
}

export function ColonySurface({
  seed, climate, buildings, population, own, onSell, onHover, onHoverCentre,
}: {
  /** Идентификатор планеты: от него пейзаж и сдвиги мест. */
  seed: string;
  climate: string;
  buildings: ColonyProject[];
  /** Жители колонии ЦЕЛЫМИ (не тысячами): от них растёт посёлок — п. 10. */
  population: number;
  own: boolean;
  /** Правый щелчок по постройке; точка — в координатах окна, у неё встаёт окошко продажи. */
  onSell: (building: ColonyProject, at: { x: number; y: number }) => void;
  onHover: (building: ColonyProject | null) => void;
  /** Указатель вошёл на посёлок или ушёл с него: над ним встаёт карточка планеты. */
  onHoverCentre: (over: boolean) => void;
}) {
  const land = climateColor(climate);
  const photo = surfaceImage(climate);
  const scene = useMemo(() => {
    const random = randomFrom(seed);
    return {
      stars: Array.from({ length: 140 }).map(() => ({
        x: random() * W, y: random() * (HORIZON - 20), r: random() < 0.08 ? 1.4 : 0.7, o: 0.3 + random() * 0.6,
      })),
      far: ridge(random, HORIZON - 8, 34, 38),
      near: ridge(random, HORIZON + 4, 22, 55),
      patches: Array.from({ length: 36 }).map(() => {
        const y = HORIZON + 20 + random() * (H - HORIZON - 20);
        const depth = (y - HORIZON) / (H - HORIZON);
        return { x: random() * W, y, rx: 20 + depth * 70 * random(), ry: 3 + depth * 10 * random(), dark: random() < 0.6 };
      }),
      places: slots(random),
    };
  }, [seed]);

  const ground = buildings.filter((building) => onGround(building.code));
  const orbital = buildings.filter((building) => isOrbital(building.code));
  const shield = buildings.filter((building) => isShield(building.code));
  const minefield = buildings.find((building) => building.code === MINEFIELD);
  // В небе висит старшая ступень лестницы: станция заменяет базу, крепость — обе.
  const station = ['star-fortress', 'battle-station', 'star-base']
    .map((code) => orbital.find((building) => building.code === code))
    .find(Boolean);
  const stationLevel = station ? (3 - ['star-fortress', 'battle-station', 'star-base'].indexOf(station.code)) as 1 | 2 | 3 : 1;

  // Первое место занято самим посёлком: постройки встают вокруг него, а не вместо него.
  const centre = scene.places[0];
  const placed = ground.map((building, index) => ({
    building,
    place: scene.places[(index + 1) % scene.places.length],
  }));
  // Дороги: каждое здание тянется к ближайшему из поставленных раньше, а первое — к
  // посёлку. Оттого дороги и расходятся от середины: посёлок там и стоит.
  const roads = placed.map((current, i) => {
    const earlier = [{ place: centre }, ...placed.slice(0, i)];
    const nearest = earlier.reduce((best, other) =>
      Math.hypot(other.place.x - current.place.x, other.place.y - current.place.y)
        < Math.hypot(best.place.x - current.place.x, best.place.y - current.place.y) ? other : best);
    return { from: nearest.place, to: current.place };
  });
  // Посёлок рисуется вместе с постройками, по своей глубине: стоящее ближе закрывает
  // дальнее, и он тут не исключение.
  const drawOrder = [
    ...placed.map((one) => ({ ...one, centre: false })),
    { building: null, place: centre, centre: true },
  ].sort((p, q) => p.place.y - q.place.y);

  const handlers = (building: ColonyProject) => ({
    onMouseEnter: () => onHover(building),
    onMouseLeave: () => onHover(null),
    onContextMenu: (event: React.MouseEvent) => {
      if (!own) {
        return;
      }
      // Меню браузера здесь ни к чему: правый щелчок — это ход игрока.
      event.preventDefault();
      onSell(building, { x: event.clientX, y: event.clientY });
    },
    // Подсветка под указателем — `.cs-hit` в index.css: постройка должна отзываться.
    className: 'cs-hit',
    style: { cursor: own ? 'context-menu' : 'default' },
  });

  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMax slice" className="block h-full w-full"
         role="img" aria-label={buildings.map((building) => building.name).join(', ')}>
      <defs>
        <BuildingDefs />
        <linearGradient id="cs-sky" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#02030a" />
          <stop offset="1" stopColor={mix(land, '#0a0f1f', 0.8)} />
        </linearGradient>
        <linearGradient id="cs-ground" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={mix(land, '#1b2233', 0.65)} />
          <stop offset="0.35" stopColor={mix(land, '#101010', 0.55)} />
          <stop offset="1" stopColor={mix(land, '#050505', 0.72)} />
        </linearGradient>
        {/* Дымка на стыке снимка с небом: снимок обрезан по прямой, и без неё видна склейка. */}
        <linearGradient id="cs-haze" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={mix(land, '#0a1020', 0.7)} stopOpacity={0.95} />
          <stop offset="1" stopColor={mix(land, '#0a1020', 0.7)} stopOpacity={0} />
        </linearGradient>
      </defs>

      <rect x={0} y={0} width={W} height={H} fill="url(#cs-sky)" />
      {scene.stars.map((star, i) => (
        <circle key={i} cx={star.x} cy={star.y} r={star.r} fill="#dfe8ff" opacity={star.o} />
      ))}

      {minefield ? (
        <g {...handlers(minefield)}>
          <title>{minefield.name}</title>
          {Array.from({ length: 18 }).map((_, i) => (
            <g key={i} transform={`translate(${60 + i * 50} ${78 + ((i * 37) % 44)})`}>
              <circle r={3} fill="#5d584f" />
              <circle r={1.2} fill="#ff6b5a" />
            </g>
          ))}
        </g>
      ) : null}

      {station ? (
        <g transform="translate(770 104)" {...handlers(station)}>
          <title>{station.name}</title>
          <OrbitalSprite level={stationLevel} />
        </g>
      ) : null}

      {/* Гряда на горизонте — силуэт, а не рельеф: у дальних гор воздух съедает и цвет, и
          контраст, и рисовать им склоны значило бы приблизить их к зрителю. */}
      <polygon points={scene.far} fill={mix(land, '#252d40', 0.8)} opacity={0.88} />
      {photo ? (
        <>
          {/* Снимок растягивается на всю землю: он обрезан ровно в этом отношении сторон,
              поэтому `none` ничего не искажает, а `slice` сцены режет его по краям. */}
          <image href={photo} x={0} y={HORIZON} width={W} height={H - HORIZON}
                 preserveAspectRatio="none" />
          <rect x={0} y={HORIZON} width={W} height={34} fill="url(#cs-haze)" />
        </>
      ) : (
        <>
          <polygon points={scene.near} fill={mix(land, '#1e2230', 0.5)} />
          <rect x={0} y={HORIZON} width={W} height={H - HORIZON} fill="url(#cs-ground)" />
          {scene.patches.map((patch, i) => (
            <ellipse key={i} cx={patch.x} cy={patch.y} rx={patch.rx} ry={patch.ry}
                     fill={patch.dark ? '#000' : '#fff'} opacity={patch.dark ? 0.12 : 0.05} />
          ))}
        </>
      )}

      {roads.map((road, i) => (
        <line key={i} x1={road.from.x} y1={road.from.y} x2={road.to.x} y2={road.to.y}
              stroke="#1c1a17" strokeOpacity={0.8}
              strokeWidth={4 * ((road.from.y + road.to.y) / 2 / H)} strokeDasharray="7 3" />
      ))}

      {drawOrder.map(({ building, place, centre: isCentre }) => (isCentre ? (
        /*
          Сам посёлок: обработчиков продажи у него нет вовсе — колонию не продают. Под
          курсором он подсвечивается и вызывает карточку планеты (`PlanetInfo`): игрок вправе
          знать, что это за место. Системной подсказки (`<title>`) нет — легла бы поверх карточки.
        */
        <g key="colony-centre" transform={`translate(${place.x} ${place.y}) scale(${place.scale})`}
           opacity={haze(place.y)} className="cs-hit"
           onMouseEnter={() => { onHover(null); onHoverCentre(true); }}
           onMouseLeave={() => onHoverCentre(false)}>
          <ColonyCentreSprite level={colonyCentreLevel(population)} />
        </g>
      ) : (
        <g key={building!.code} transform={`translate(${place.x} ${place.y}) scale(${place.scale})`}
           opacity={haze(place.y)} {...handlers(building!)}>
          <title>{building!.name}</title>
          <BuildingSprite code={building!.code} />
        </g>
      )))}

      {shield.map((building, i) => (
        <g key={building.code} {...handlers(building)}>
          <title>{building.name}</title>
          <path d={`M40,${H} A${460 - i * 20},${250 - i * 16} 0 0 1 ${W - 40},${H}`}
                fill={building.code === 'planetary-barrier-shield' ? '#7fe0ff' : '#9ec9ff'}
                fillOpacity={building.code === 'planetary-barrier-shield' ? 0.1 : 0.06}
                stroke="#9fe6ff" strokeOpacity={0.55} strokeWidth={2} pointerEvents="stroke" />
        </g>
      ))}
    </svg>
  );
}
