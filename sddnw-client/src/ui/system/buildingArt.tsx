import type { ReactNode } from 'react';

/**
 * Рисунки зданий колонии — п. 10, поверхностью планеты с экрана колонии MOO II
 * (`docs/moo2/colony.png`).
 *
 * В оригинале здания стоят на пейзаже объёмными фигурками в три четверти: у каждого свой
 * силуэт, по которому его узнают без подписи, — купол лаборатории, трубы завода, стёкла
 * оранжереи. Рисунки оригинала — чужое добро, поэтому здесь свои, собранные кодом из
 * ОБЩИХ деталей: корпус, цистерна, купол, шпиль, тарелка, труба, решётчатая вышка, поле.
 * Детали одни на все тридцать два здания — отсюда единый стиль: тот же металл, та же
 * медь куполов, тот же тёплый свет в окнах. Здание собирается «рецептом» из деталей и
 * узнаётся по назначению: производство — трубы и корпуса, наука — купола и антенны, еда —
 * стекло и зелень, оборона — стволы и шахты.
 *
 * Рисунок лежит вокруг точки опоры (0, 0) — середины основания; вверх — отрицательный y.
 * Типичное здание — около 90 единиц в ширину и 80 в высоту; сцена сама ставит его на место
 * и масштабирует по глубине. Заливки ссылаются на градиенты `BuildingDefs` — их сцена
 * кладёт в свой `<defs>` один раз.
 *
 * <b>СВЕТ ОДИН НА ВСЮ СЦЕНУ, и это главное правило рисунков</b> ({@link SUN}): солнце слева
 * сверху и чуть из-за спины зрителя. Из него выведено всё остальное, и выведено нарочно, а
 * не подобрано на глаз у каждой детали:
 *
 * * освещённая грань светлее теневой, и переход между ними идёт градиентом, а не ступенью;
 * * <b>крыши холоднее стен</b> (`ba-top`): вверх смотрит не солнце, а небо, и отражают они
 *   его синеву — на тёплом металле разница в оттенке читается объёмом лучше, чем разница в
 *   яркости;
 * * <b>у земли темнее</b> (`ba-ao`): в угол между стеной и землёй свет почти не попадает.
 *   Без этой полосы постройка висит над поверхностью, и заметно это даже на фотографии;
 * * <b>тень ПАДАЕТ</b> ({@link shade}) — вправо и вниз, прочь от солнца, длиной по высоте
 *   постройки, с плотным пятном касания у самого основания. Прежде под каждым зданием лежал
 *   одинаковый овал, не знавший ни о свете, ни о размерах;
 * * <b>освещённое ребро ловит блик</b> ({@link SPEC}) — тонкая тёплая линия по левому ребру
 *   и по кромке крыши. Она и делает металл металлом.
 *
 * Свет выбран слева не из вкуса: под фигурками лежит ФОТОГРАФИЯ поверхности
 * (`ColonySurface`, `assets/surfaces/`), и снимки для неё отобраны и при нужде развёрнуты
 * зеркально так, чтобы солнце на них было там же. Меняя {@link SUN}, придётся
 * перевернуть и снимки — иначе тени пойдут навстречу друг другу.
 *
 * Здание, которого в таблице нет (новое в справочнике), получает общий рисунок: корпус с
 * куполом. Пропасть с поверхности оно не должно — построенное игрок обязан видеть.
 */

/** Орбитальные постройки рисуются в небе, а не на поверхности: они и стоят на орбите. */
export const ORBITAL = ['star-base', 'battle-station', 'star-fortress'] as const;
/** Щиты накрывают поселение куполом целиком — это не фигурка на участке, а свод. */
export const SHIELDS = ['planetary-flux-shield', 'planetary-barrier-shield'] as const;
/** Сеть охотников — минное поле вокруг всей системы: видна минами на небе. */
export const MINEFIELD = 'artemis-system-net';

export const isOrbital = (code: string) => (ORBITAL as readonly string[]).includes(code);
export const isShield = (code: string) => (SHIELDS as readonly string[]).includes(code);
export const onGround = (code: string) => !isOrbital(code) && !isShield(code) && code !== MINEFIELD;

// --- свет ---------------------------------------------------------------------------------

/**
 * Солнце сцены: единица длины — доля высоты постройки, знак — экранный (вниз положительно).
 *
 * Отсюда берутся направление градиентов на гранях, сторона блика и — главное — куда и
 * насколько далеко ложится ПАДАЮЩАЯ тень. Число подобрано под низкое солнце: тень заметно
 * длиннее основания, но не уходит за соседний участок застройки.
 */
export const SUN = { x: -0.66, y: -0.75 };

/**
 * Куда ложится тень: прочь от солнца (`-SUN.x`) и ПРИЖАТА К ЗЕМЛЕ — по вертикали она
 * уходит не настолько, насколько солнце стоит высоко, а настолько, насколько земля видна
 * под углом. Доля взята той же, что у косой проекции глубины {@link DY}: тень лежит в той
 * же плоскости, что и крыши, и разойдись эти два числа, она поехала бы мимо постройки.
 */
const CAST = { x: -SUN.x * 0.62, y: 0.24 };

/** Цвет блика на освещённом ребре: свет местной звезды тёплый, как и свет в окнах. */
const SPEC = '#fff4e0';

// --- материалы ------------------------------------------------------------------------

const M = {
  metal: 'url(#ba-metal)',
  metalTop: 'url(#ba-top)',
  metalSide: '#43403a',
  rust: 'url(#ba-rust)',
  rustTop: 'url(#ba-top-rust)',
  rustSide: '#4a2b1a',
  copper: 'url(#ba-copper)',
  glass: 'url(#ba-glass)',
  green: 'url(#ba-green)',
  dark: 'url(#ba-dark)',
  darkTop: 'url(#ba-top-dark)',
  darkSide: '#141311',
  light: '#ffd27a',
  cyan: '#7fe0ff',
  red: '#ff6b5a',
} as const;

/**
 * Градиенты материалов. Свет везде слева сверху ({@link SUN}), и каждый градиент —
 * следствие одного этого: лицевая грань светлеет влево, крыша отражает небо и потому
 * холоднее стен, а `ba-ao` и `ba-bounce` описывают то, что делает с постройкой ЗЕМЛЯ —
 * забирает свет в углу и возвращает тёплый отсвет чуть выше.
 */
export function BuildingDefs() {
  return (
    <>
      <linearGradient id="ba-metal" x1="0" x2="1" y1="0" y2="0">
        <stop offset="0" stopColor="#cdc7b8" />
        <stop offset="0.28" stopColor="#9d978a" />
        <stop offset="0.72" stopColor="#615d55" />
        <stop offset="1" stopColor="#35322d" />
      </linearGradient>
      <linearGradient id="ba-rust" x1="0" x2="1" y1="0" y2="0">
        <stop offset="0" stopColor="#c08a64" />
        <stop offset="0.28" stopColor="#a96c45" />
        <stop offset="0.72" stopColor="#6b3d24" />
        <stop offset="1" stopColor="#3d2214" />
      </linearGradient>

      {/* Крыши: вверх смотрит НЕБО, а не звезда, поэтому они холоднее стен. */}
      <linearGradient id="ba-top" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0" stopColor="#dbe1ec" />
        <stop offset="1" stopColor="#83857d" />
      </linearGradient>
      <linearGradient id="ba-top-rust" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0" stopColor="#bd9575" />
        <stop offset="1" stopColor="#7d5540" />
      </linearGradient>
      <linearGradient id="ba-top-dark" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0" stopColor="#63656a" />
        <stop offset="1" stopColor="#35342f" />
      </linearGradient>

      {/* Угол между стеной и землёй света почти не получает: без этой полосы постройка
          висит над поверхностью — на фотографии это видно сразу. */}
      <linearGradient id="ba-ao" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0" stopColor="#000" stopOpacity="0" />
        <stop offset="1" stopColor="#000" stopOpacity="0.55" />
      </linearGradient>
      {/* А чуть выше земля свет ВОЗВРАЩАЕТ — тёплым отсветом. Полоса узкая и слабая:
          заметной она читается грязью, а незаметной делает низ стены живым. */}
      <linearGradient id="ba-bounce" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0" stopColor="#ffc888" stopOpacity="0" />
        <stop offset="0.55" stopColor="#ffc888" stopOpacity="0.2" />
        <stop offset="1" stopColor="#ffc888" stopOpacity="0" />
      </linearGradient>
      {/* Падающая тень: плотная у основания, растворяется к концу. */}
      <radialGradient id="ba-shadow" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stopColor="#000" stopOpacity="0.46" />
        <stop offset="0.6" stopColor="#000" stopOpacity="0.22" />
        <stop offset="1" stopColor="#000" stopOpacity="0" />
      </radialGradient>
      {/* Тёмная сторона купола: свет уходит к правому краю, как на шаре. */}
      <radialGradient id="ba-dome-shade" cx="0.3" cy="0.24" r="0.82">
        <stop offset="0" stopColor="#000" stopOpacity="0" />
        <stop offset="0.6" stopColor="#000" stopOpacity="0.1" />
        <stop offset="1" stopColor="#000" stopOpacity="0.46" />
      </radialGradient>
      <radialGradient id="ba-copper" cx="0.35" cy="0.3" r="0.8">
        <stop offset="0" stopColor="#f7d6a4" />
        <stop offset="0.45" stopColor="#bb8150" />
        <stop offset="1" stopColor="#5b371f" />
      </radialGradient>
      <radialGradient id="ba-glass" cx="0.35" cy="0.3" r="0.85">
        <stop offset="0" stopColor="#e4f6ff" stopOpacity="0.85" />
        <stop offset="0.5" stopColor="#7fb6d6" stopOpacity="0.5" />
        <stop offset="1" stopColor="#2e5a78" stopOpacity="0.55" />
      </radialGradient>
      <linearGradient id="ba-green" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0" stopColor="#8fdc6a" />
        <stop offset="1" stopColor="#2c6427" />
      </linearGradient>
      <linearGradient id="ba-dark" x1="0" x2="1" y1="0" y2="0">
        <stop offset="0" stopColor="#6a655c" />
        <stop offset="0.6" stopColor="#35322d" />
        <stop offset="1" stopColor="#1c1a17" />
      </linearGradient>
      <radialGradient id="ba-glow" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stopColor="#fff2c4" stopOpacity="0.95" />
        <stop offset="1" stopColor="#ffb347" stopOpacity="0" />
      </radialGradient>
      <radialGradient id="ba-cyan-glow" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stopColor="#e6fbff" stopOpacity="0.95" />
        <stop offset="1" stopColor="#3fc6ff" stopOpacity="0" />
      </radialGradient>
    </>
  );
}

// --- детали -----------------------------------------------------------------------------

/** Косая проекция глубины: задняя грань уходит вправо и вверх. */
const DX = 0.55;
const DY = -0.38;

/** Корпус-параллелепипед: лицо, крыша, правый бок; окна — рядами тёплого света. */
function box(key: string, x: number, w: number, h: number, d: number, tone: 'metal' | 'rust' | 'dark' = 'metal',
             windows = 0, y = 0): ReactNode {
  const face = tone === 'metal' ? M.metal : tone === 'rust' ? M.rust : M.dark;
  const top = tone === 'metal' ? M.metalTop : tone === 'rust' ? M.rustTop : M.darkTop;
  const side = tone === 'metal' ? M.metalSide : tone === 'rust' ? M.rustSide : M.darkSide;
  const l = x - w / 2;
  const r = x + w / 2;
  const dx = d * DX;
  const dy = d * DY;
  const lights: ReactNode[] = [];
  for (let row = 0; row < windows; row += 1) {
    const wy = y - h + 6 + row * 9;
    for (let wx = l + 5; wx < r - 6; wx += 9) {
      lights.push(<rect key={`${key}-w${row}-${wx}`} x={wx} y={wy} width={4} height={3} fill={M.light} opacity={0.85} />);
    }
  }
  // Полосы земли: высокой стене они нужны у самого низа, низкой — почти во всю высоту,
  // поэтому доля от высоты, но с потолком — иначе окклюзия съедала бы сарай целиком.
  const skirt = Math.min(h * 0.38, 22);
  return (
    <g key={key}>
      <polygon points={`${r},${y} ${r},${y - h} ${r + dx},${y - h + dy} ${r + dx},${y + dy}`} fill={side} />
      <rect x={l} y={y - h} width={w} height={h} fill={face} />
      <polygon points={`${l},${y - h} ${r},${y - h} ${r + dx},${y - h + dy} ${l + dx},${y - h + dy}`} fill={top} />
      <rect x={l} y={y - skirt} width={w} height={skirt} fill="url(#ba-ao)" />
      <rect x={l} y={y - skirt * 0.55} width={w} height={skirt * 0.55} fill="url(#ba-bounce)" />
      {/* Блик по освещённому ребру и по кромке крыши: без него грани сходятся «в никуда». */}
      <rect x={l} y={y - h} width={1.5} height={h} fill={SPEC} opacity={0.5} />
      <rect x={l} y={y - h} width={w} height={1.3} fill={SPEC} opacity={0.45} />
      {lights}
    </g>
  );
}

/** Цистерна или башня-цилиндр: бок с округлым светом, крышка светлее. */
function cylinder(key: string, x: number, r: number, h: number, fill: string = M.metal, y = 0,
                  capFill: string = M.metalTop): ReactNode {
  const ry = r * 0.35;
  const skirt = Math.min(h * 0.4, 18);
  return (
    <g key={key}>
      <ellipse cx={x} cy={y} rx={r} ry={ry} fill={M.metalSide} />
      <rect x={x - r} y={y - h} width={r * 2} height={h} fill={fill} />
      {/* Блик круглого бока стоит НЕ по середине, а на трети от освещённого края: там, где
          поверхность повёрнута к звезде, — это и отличает цилиндр от плоской доски. */}
      <rect x={x - r * 0.66} y={y - h} width={Math.max(1.4, r * 0.24)} height={h} fill={SPEC} opacity={0.26} />
      <rect x={x - r} y={y - skirt} width={r * 2} height={skirt} fill="url(#ba-ao)" />
      <ellipse cx={x} cy={y - h} rx={r} ry={ry} fill={capFill} />
      <path d={`M${x - r},${y - h} A${r},${ry} 0 0 1 ${x + r},${y - h}`} fill="none"
            stroke={SPEC} strokeOpacity={0.4} strokeWidth={1.2} />
    </g>
  );
}

/** Купол на ободе: медь по умолчанию, стекло у оранжерей и биосфер. */
function dome(key: string, x: number, r: number, y = 0, fill: string = M.copper, inner?: string): ReactNode {
  return (
    <g key={key}>
      <ellipse cx={x} cy={y} rx={r * 1.04} ry={r * 0.3} fill="#2e2b26" />
      {inner ? <path d={`M${x - r * 0.8},${y} Q${x},${y - r * 0.9} ${x + r * 0.8},${y} Z`} fill={inner} /> : null}
      <path d={`M${x - r},${y} A${r},${r * 0.92} 0 0 1 ${x + r},${y} Z`} fill={fill} />
      {/* Тень шара: свет сходит на нет к правому нижнему краю, а не обрывается кромкой. */}
      <path d={`M${x - r},${y} A${r},${r * 0.92} 0 0 1 ${x + r},${y} Z`} fill="url(#ba-dome-shade)" />
      <path d={`M${x - r * 0.55},${y - r * 0.55} Q${x - r * 0.2},${y - r * 0.85} ${x + r * 0.15},${y - r * 0.82}`}
            stroke={SPEC} strokeOpacity={0.5} strokeWidth={2} fill="none" />
    </g>
  );
}

/** Шпиль с огнём на макушке: антенны, мачты, радиовышки. */
function spire(key: string, x: number, h: number, w = 4, y = 0, lamp: string = M.red): ReactNode {
  return (
    <g key={key}>
      <polygon points={`${x - w / 2},${y} ${x + w / 2},${y} ${x + w * 0.15},${y - h} ${x - w * 0.15},${y - h}`} fill={M.metal} />
      <circle cx={x} cy={y - h} r={2.2} fill={lamp} />
      <circle cx={x} cy={y - h} r={6} fill={lamp === M.red ? 'url(#ba-glow)' : 'url(#ba-cyan-glow)'} opacity={0.5} />
    </g>
  );
}

/** Тарелка на стойке, повёрнутая в небо. */
function dish(key: string, x: number, y: number, r: number): ReactNode {
  return (
    <g key={key}>
      <line x1={x} y1={y} x2={x + r * 0.2} y2={y - r * 0.9} stroke="#5d584f" strokeWidth={2.5} />
      <ellipse cx={x + r * 0.2} cy={y - r * 1.05} rx={r} ry={r * 0.45} fill={M.metalTop}
               transform={`rotate(-28 ${x + r * 0.2} ${y - r * 1.05})`} />
      <ellipse cx={x + r * 0.2} cy={y - r * 1.05} rx={r * 0.7} ry={r * 0.28} fill="#8f897d"
               transform={`rotate(-28 ${x + r * 0.2} ${y - r * 1.05})`} />
      <circle cx={x + r * 0.35} cy={y - r * 1.3} r={1.8} fill={M.cyan} />
    </g>
  );
}

/** Заводская труба с клубами над ней. */
function stack(key: string, x: number, w: number, h: number, y = 0, smoke = '#b9b4ab'): ReactNode {
  return (
    <g key={key}>
      <rect x={x - w / 2} y={y - h} width={w} height={h} fill={M.rust} />
      <rect x={x - w / 2} y={y - h} width={Math.max(1, w * 0.22)} height={h} fill={SPEC} opacity={0.22} />
      <rect x={x - w / 2 - 1} y={y - h} width={w + 2} height={3} fill="#3b2415" />
      {/* Дым освещён той же звездой: край, повёрнутый к ней, светлее. */}
      <circle cx={x + 2} cy={y - h - 7} r={w * 0.7} fill={smoke} opacity={0.45} />
      <circle cx={x + 8} cy={y - h - 15} r={w * 0.9} fill={smoke} opacity={0.3} />
      <circle cx={x + 16} cy={y - h - 22} r={w * 1.1} fill={smoke} opacity={0.18} />
      <circle cx={x + 6} cy={y - h - 16} r={w * 0.42} fill={SPEC} opacity={0.14} />
    </g>
  );
}

/** Решётчатая вышка: копры шахт, буровые, мачты погоды. */
function lattice(key: string, x: number, w: number, h: number, y = 0): ReactNode {
  // Левая опора обращена к звезде, правая — от неё: у решётки нет граней, и объём ей даёт
  // только эта разница.
  const parts: ReactNode[] = [
    <line key="l" x1={x - w / 2} y1={y} x2={x - w * 0.12} y2={y - h} stroke="#b0a99b" strokeWidth={2} />,
    <line key="r" x1={x + w / 2} y1={y} x2={x + w * 0.12} y2={y - h} stroke="#4c483f" strokeWidth={2} />,
  ];
  const steps = 5;
  for (let i = 0; i < steps; i += 1) {
    const a = y - (h * i) / steps;
    const b = y - (h * (i + 1)) / steps;
    const wa = (w / 2) * (1 - (0.76 * i) / steps);
    const wb = (w / 2) * (1 - (0.76 * (i + 1)) / steps);
    parts.push(<line key={`x${i}`} x1={x - wa} y1={a} x2={x + wb} y2={b} stroke="#8a847a" strokeWidth={1.2} />);
    parts.push(<line key={`y${i}`} x1={x + wa} y1={a} x2={x - wb} y2={b} stroke="#8a847a" strokeWidth={1.2} />);
  }
  return <g key={key}>{parts}<circle cx={x} cy={y - h} r={2} fill={M.red} /></g>;
}

/** Плоский участок в перспективе: поля, площадки, взлётная полоса. */
function field(key: string, x: number, w: number, d: number, fill: string, rows = 0, rowColor = '#2c5a24'): ReactNode {
  const l = x - w / 2;
  const dx = d * DX;
  const dy = d * DY;
  const lines: ReactNode[] = [];
  for (let i = 1; i <= rows; i += 1) {
    const f = i / (rows + 1);
    lines.push(<line key={`${key}-r${i}`} x1={l + dx * f} y1={dy * f} x2={l + w + dx * f} y2={dy * f}
                     stroke={rowColor} strokeWidth={1.4} />);
  }
  return (
    <g key={key}>
      <polygon points={`${l},0 ${l + w},0 ${l + w + dx},${dy} ${l + dx},${dy}`} fill={fill} />
      {lines}
    </g>
  );
}

/** Ствол наземной установки, смотрящий в небо. */
function barrel(key: string, x: number, y: number, len: number, angle = -40, w = 4): ReactNode {
  const rad = (angle * Math.PI) / 180;
  const ex = x + Math.cos(rad) * len;
  const ey = y + Math.sin(rad) * len;
  return (
    <g key={key}>
      <line x1={x} y1={y} x2={ex} y2={ey} stroke="#3c3933" strokeWidth={w} strokeLinecap="round" />
      {/* Верхняя треть ствола повёрнута к звезде — она и светится. */}
      <line x1={x} y1={y - w * 0.28} x2={ex} y2={ey - w * 0.28} stroke="#9e988b"
            strokeWidth={w * 0.34} strokeLinecap="round" />
    </g>
  );
}

/** Точка света — окно, огонь, индикатор. */
function glow(key: string, x: number, y: number, r: number, cyan = false): ReactNode {
  return (
    <g key={key}>
      <circle cx={x} cy={y} r={r * 2.4} fill={cyan ? 'url(#ba-cyan-glow)' : 'url(#ba-glow)'} opacity={0.7} />
      <circle cx={x} cy={y} r={r} fill={cyan ? M.cyan : M.light} />
    </g>
  );
}

// --- рецепты ----------------------------------------------------------------------------

/**
 * Рецепт здания — список деталей в порядке отрисовки (дальнее раньше). Ширина и высота
 * рецептов подобраны так, чтобы крупное по смыслу (университет, биржа, шахта ядра) и на
 * поверхности было крупнее мелкого (казармы, мастерская).
 */
const RECIPES: Record<string, () => ReactNode[]> = {
  // Оборона и казармы.
  'marine-barracks': () => [
    box('b', 0, 78, 20, 26, 'metal', 1),
    box('b2', -22, 30, 12, 20, 'rust', 0, -20),
    spire('flag', 34, 44, 2.5),
    <polygon key="banner" points="34,-44 50,-40 34,-35" fill="#c0392b" />,
  ],
  'armor-barracks': () => [
    <path key="hangar" d="M-44,0 L-44,-18 A44,26 0 0 1 44,-18 L44,0 Z" fill={M.metal} />,
    // Свод — та же круглая поверхность, что у купола, и тень по ней сходит так же.
    <path key="hangar-shade" d="M-44,0 L-44,-18 A44,26 0 0 1 44,-18 L44,0 Z" fill="url(#ba-dome-shade)" />,
    <path key="door" d="M-16,0 L-16,-14 A16,10 0 0 1 16,-14 L16,0 Z" fill="#2b2824" />,
    box('tank', 52, 24, 10, 16, 'dark'),
    barrel('gun', 52, -12, 20, -8, 3),
  ],
  'missile-base': () => [
    field('pad', 0, 90, 30, '#5a564e'),
    ...[-28, 0, 28].flatMap((x, i) => [
      cylinder(`s${i}`, x, 9, 10, M.dark, 0, M.darkTop),
      <polygon key={`m${i}`} points={`${x - 5},-10 ${x + 5},-10 ${x},-40`} fill={M.metalTop} />,
      <rect key={`f${i}`} x={x - 5} y={-18} width={10} height={4} fill={M.red} />,
    ]),
  ],
  'ground-batteries': () => [
    box('base', 0, 64, 16, 30, 'dark'),
    dome('turret', 0, 20, -16, M.metal),
    barrel('g1', -4, -26, 46, -34, 5),
    barrel('g2', 6, -24, 46, -30, 5),
  ],
  'fighter-garrison': () => [
    field('strip', 10, 110, 22, '#4a4740', 0),
    <line key="stripe" x1={-40} y1={-4} x2={62} y2={-4} stroke="#e8d27a" strokeWidth={1.5} strokeDasharray="6 5" />,
    <path key="hangar" d="M-50,0 L-50,-14 A24,18 0 0 1 -2,-14 L-2,0 Z" fill={M.metal} />,
    <path key="hangar-shade" d="M-50,0 L-50,-14 A24,18 0 0 1 -2,-14 L-2,0 Z" fill="url(#ba-dome-shade)" />,
    <polygon key="jet" points="30,-12 50,-8 30,-4 34,-8" fill={M.metalTop} />,
  ],

  // Еда.
  'hydroponic-farm': () => [
    box('frame', 0, 80, 26, 30, 'metal'),
    <rect key="glassf" x={-37} y={-24} width={74} height={20} fill={M.glass} />,
    <rect key="plants" x={-35} y={-12} width={70} height={8} fill={M.green} />,
    glow('lamp', -20, -20, 2, true),
    glow('lamp2', 18, -20, 2, true),
  ],
  biospheres: () => [
    dome('d1', -30, 22, 0, M.glass, M.green),
    dome('d2', 18, 30, 0, M.glass, M.green),
    dome('d3', 48, 15, -2, M.glass, M.green),
  ],
  'cloning-center': () => [
    box('lab', -16, 50, 30, 24, 'metal', 2),
    ...[20, 36, 52].flatMap((x, i) => [
      cylinder(`t${i}`, x, 7, 34, M.glass, 0),
      <rect key={`liq${i}`} x={x - 6} y={-24} width={12} height={22} fill="#7cf0a8" opacity={0.55} />,
    ]),
  ],
  'soil-enrichment': () => [
    field('f1', -6, 96, 34, M.green, 4),
    cylinder('silo', 44, 10, 30, M.rust),
    dome('silotop', 44, 10, -30, M.copper),
  ],
  'subterranean-farms': () => [
    field('ground', 0, 80, 28, '#3d4a2f'),
    box('ramp', -12, 40, 12, 18, 'dark'),
    <rect key="gate" x={-26} y={-10} width={28} height={10} fill="#111" />,
    ...[18, 34].map((x, i) => cylinder(`v${i}`, x, 5, 16, M.metal)),
    glow('g', -12, -6, 1.8, true),
  ],
  'weather-controller': () => [
    box('base', 0, 44, 18, 22, 'metal', 1),
    lattice('mast', 0, 24, 78, -18),
    <ellipse key="halo" cx={0} cy={-80} rx={30} ry={8} fill="none" stroke={M.cyan} strokeWidth={1.5} opacity={0.7} />,
    dish('dish', 16, -18, 10),
  ],

  // Производство.
  'automated-factory': () => [
    box('hall', -8, 70, 30, 28, 'rust', 1),
    box('annex', 34, 30, 18, 20, 'metal'),
    stack('s1', -26, 8, 48, -30),
    stack('s2', -8, 8, 40, -30),
  ],
  'robo-miners': () => [
    lattice('rig', -26, 30, 60),
    box('plant', 14, 52, 24, 26, 'metal', 1),
    <polygon key="belt" points="-26,-8 -8,-20 -4,-16 -22,-4" fill="#3b3731" />,
    <polygon key="ore" points="30,0 48,0 40,-12" fill="#7a6a58" />,
  ],
  'deep-core-mine': () => [
    <ellipse key="pit" cx={0} cy={0} rx={48} ry={16} fill="#1a1512" />,
    <ellipse key="core" cx={0} cy={-1} rx={30} ry={9} fill="#ff7a2e" opacity={0.75} />,
    lattice('headframe', 0, 50, 84, -4),
    cylinder('drum', -30, 9, 20, M.rust),
  ],
  recyclotron: () => [
    box('plant', 6, 64, 28, 26, 'metal', 1),
    <polygon key="hopper" points="-40,-40 -10,-40 -18,-20 -32,-20" fill={M.rust} />,
    <rect key="chute" x={-28} y={-20} width={6} height={20} fill="#3b3731" />,
    stack('s', 28, 7, 34, -28, '#d9f0d0'),
  ],

  // Наука.
  'research-laboratory': () => [
    box('wing', 0, 76, 26, 28, 'metal', 1),
    dome('d', -8, 22, -26),
    spire('ant', 30, 50, 3, -26, M.cyan),
  ],
  supercomputer: () => [
    box('tower', 0, 38, 62, 26, 'dark', 6),
    dish('dish', 26, -62, 11),
    glow('core', -8, -30, 2, true),
  ],
  autolab: () => [
    box('cube', 0, 46, 36, 30, 'metal', 2),
    dish('dish', 10, -36, 12),
  ],
  'galactic-cybernet': () => [
    box('base', 0, 60, 18, 26, 'dark', 1),
    spire('mast', 0, 96, 8, -18, M.cyan),
    ...[40, 60, 80].map((h, i) => (
      <ellipse key={`ring${i}`} cx={0} cy={-18 - h} rx={16 - i * 3} ry={4} fill="none" stroke={M.cyan} strokeWidth={1.4} opacity={0.8} />
    )),
    dish('d1', -24, -18, 9),
    dish('d2', 26, -18, 9),
  ],
  'astro-university': () => [
    box('hall', 0, 96, 30, 34, 'metal', 2),
    ...[-36, -18, 0, 18, 36].map((x, i) => <rect key={`col${i}`} x={x - 2.5} y={-30} width={5} height={30} fill="#cfc8b8" />),
    box('drum', 0, 46, 14, 20, 'metal', 0, -30),
    dome('d', 6, 26, -44),
    spire('spire', 6, 20, 3, -68),
  ],

  // Деньги.
  'space-port': () => [
    <ellipse key="pad" cx={-8} cy={-4} rx={46} ry={14} fill="#55514a" />,
    <ellipse key="ring" cx={-8} cy={-4} rx={32} ry={9} fill="none" stroke="#e8d27a" strokeWidth={1.5} />,
    <polygon key="ship" points="-24,-14 8,-10 -24,-6 -18,-10" fill={M.metalTop} />,
    box('tower', 46, 16, 44, 14, 'metal', 3),
    glow('beacon', 46, -48, 2),
  ],
  'stock-exchange': () => [
    box('hall', 0, 82, 32, 30, 'metal', 1),
    <polygon key="pediment" points="-44,-32 44,-32 0,-50" fill="#cfc8b8" />,
    ...[-30, -15, 0, 15, 30].map((x, i) => <rect key={`col${i}`} x={x - 2.5} y={-32} width={5} height={32} fill="#dcd6c7" />),
    dome('gold', 0, 16, -50, 'url(#ba-copper)'),
  ],

  // Экология.
  'pollution-processor': () => [
    ...[-30, -8].map((x, i) => cylinder(`t${i}`, x, 12, 30, M.metal)),
    box('plant', 30, 40, 22, 24, 'metal', 1),
    stack('s', 30, 7, 40, -22, '#e8f6ff'),
  ],
  'atmospheric-renewer': () => [
    ...[-26, 22].flatMap((x, i) => [
      <path key={`ct${i}`} d={`M${x - 20},0 Q${x - 10},-30 ${x - 14},-58 L${x + 14},-58 Q${x + 10},-30 ${x + 20},0 Z`} fill={M.metal} />,
      // Горловина градирни смотрит в небо и потому холоднее её стен — как крыша у корпуса.
      <ellipse key={`cm${i}`} cx={x} cy={-58} rx={14} ry={4} fill={M.metalTop} />,
      <rect key={`ca${i}`} x={x - 20} y={-16} width={40} height={16} fill="url(#ba-ao)" />,
    ]),
    ...[-26, 22].map((x, i) => <circle key={`steam${i}`} cx={x} cy={-68} r={12} fill="#eef6ff" opacity={0.4} />),
  ],
  'nano-disassemblers': () => [
    box('cube', 0, 50, 40, 32, 'dark'),
    ...[-14, 0, 14].map((x, i) => <line key={`gx${i}`} x1={x} y1={-40} x2={x} y2={0} stroke={M.cyan} strokeWidth={0.9} opacity={0.7} />),
    ...[-28, -14].map((y, i) => <line key={`gy${i}`} x1={-25} y1={y} x2={25} y2={y} stroke={M.cyan} strokeWidth={0.9} opacity={0.7} />),
    glow('core', 0, -20, 2.4, true),
  ],
  'core-waste-dump': () => [
    <ellipse key="shaft" cx={0} cy={0} rx={40} ry={13} fill="#141110" />,
    <ellipse key="rim" cx={0} cy={0} rx={40} ry={13} fill="none" stroke="#8a847a" strokeWidth={3} />,
    <ellipse key="glow" cx={0} cy={1} rx={22} ry={6} fill="#b6ff5a" opacity={0.45} />,
    box('crane', 38, 14, 34, 12, 'rust'),
    <line key="arm" x1={38} y1={-34} x2={4} y2={-30} stroke="#6b665c" strokeWidth={3} />,
  ],
};

/** Общий рисунок для здания, которого нет в таблице рецептов. */
const FALLBACK = () => [box('b', 0, 60, 26, 26, 'metal', 1), dome('d', 0, 18, -26)];

/**
 * След постройки на земле: ширина, глубина и высота — для тени.
 *
 * Записаны только те, у кого след заметно не тот, что у большинства: университет и биржа
 * шире и выше, шахта ядра и свалка — яма, от которой тени не бывает вовсе, ангары и поля
 * стелются по земле. Остальные тридцать берут общий след, и это не лень: тень —
 * приблизительная вещь, а лишняя строка в таблице живёт ровно до первой правки рецепта, с
 * которой она молча разойдётся.
 */
const FOOTPRINTS: Record<string, [number, number, number]> = {
  'astro-university': [100, 34, 70],
  'stock-exchange': [88, 30, 54],
  supercomputer: [42, 26, 64],
  'galactic-cybernet': [64, 26, 60],
  'space-port': [96, 28, 20],
  'fighter-garrison': [112, 22, 14],
  'armor-barracks': [92, 24, 20],
  'marine-barracks': [82, 26, 22],
  'soil-enrichment': [100, 34, 12],
  'subterranean-farms': [84, 28, 14],
  'atmospheric-renewer': [84, 26, 60],
  'deep-core-mine': [0, 0, 0],
  'core-waste-dump': [0, 0, 0],
  'missile-base': [94, 30, 12],
};

const FOOTPRINT: [number, number, number] = [80, 26, 34];

/**
 * Тень, которую постройка БРОСАЕТ: смещена прочь от звезды ({@link CAST}) тем дальше, чем
 * выше постройка, и растворяется к своему концу.
 *
 * Прежде под каждым зданием лежал один и тот же овал в 54 единицы — он не знал ни про свет,
 * ни про размеры, и шахта с ямой в земле отбрасывала ровно столько же, сколько башня
 * суперкомпьютера. Пятно касания у основания осталось отдельной фигурой: в узком углу между
 * стеной и землёй свет гаснет совсем, и это самое тёмное место рисунка.
 */
function shade(code: string): ReactNode {
  const [w, d, h] = FOOTPRINTS[code] ?? FOOTPRINT;
  if (w === 0) {
    return null;
  }
  return (
    <g>
      <ellipse cx={CAST.x * h} cy={CAST.y * h * 0.5 + 1} rx={w * 0.62 + h * 0.3} ry={d * 0.5 + h * 0.08}
               fill="url(#ba-shadow)" />
      <ellipse cx={0} cy={1} rx={w * 0.44} ry={Math.max(4, d * 0.3)} fill="#000" opacity={0.45} />
    </g>
  );
}

/** Здание на поверхности: падающая тень под ним и рисунок по рецепту. */
export function BuildingSprite({ code }: { code: string }) {
  const recipe = RECIPES[code] ?? FALLBACK;
  return (
    <g>
      {shade(code)}
      {recipe()}
    </g>
  );
}

/**
 * САМА КОЛОНИЯ — поселение, которое стоит на планете всегда, — п. 10.
 *
 * <b>Чего не хватало.</b> На поверхности рисовались только ПОСТРОЙКИ из справочника, а
 * колония как таковая не рисовалась ничем: свежая колония без единого здания выглядела
 * пустым полем, и экран читался как «тут ничего нет», хотя тут живут люди. В оригинале
 * посёлок на картине есть с первого хода.
 *
 * <b>Это не здание, и продать его нельзя</b> — поэтому оно и не в таблице рецептов, и не
 * ходит через {@link BuildingSprite}: у него нет ни кода в справочнике, ни цены, ни
 * содержания, ни правого щелчка. Колония существует, пока существует колония.
 *
 * <b>Посёлок растёт вместе с населением</b> ({@code level} 1…4, от жителей): у колонии в
 * одного жителя это купол с антенной, у полной — город с башнями и огнями. Вырастить его
 * можно было и от числа построек, но это врало бы: постройки — то, что игрок возвёл, а
 * посёлок — то, где живут, и зависит он от жителей.
 *
 * Свет тот же, что у всей сцены ({@link SUN}): те же грани, те же блики, та же тень.
 */
export function ColonyCentreSprite({ level }: { level: 1 | 2 | 3 | 4 }) {
  const wide = 54 + level * 12;
  const houses = level + 1;
  return (
    <g>
      {/* Площадка поселения: утоптанная земля, на которой оно стоит. */}
      <ellipse cx={4} cy={0} rx={wide * 0.92} ry={wide * 0.22} fill="#26231e" opacity={0.75} />
      <ellipse cx={4} cy={0} rx={wide * 0.92} ry={wide * 0.22} fill="none"
               stroke="#3c382f" strokeWidth={1.5} />

      {/* Жилые корпуса по сторонам: их тем больше, чем больше жителей. */}
      {Array.from({ length: houses }).map((_, index) => {
        // Место дома выводится из его номера, а не из случая: сцена перерисовывается на
        // каждое наведение мыши (то же правило, что и у застройки).
        const side = index % 2 === 0 ? -1 : 1;
        const step = Math.floor(index / 2) + 1;
        const x = side * (18 + step * 16);
        return box(`house${index}`, x, 26 - step * 2, 14 + (index % 3) * 4, 16, 'metal', 1);
      })}

      {/* Купол — сердце поселения: под ним и живут, пока не построено ничего другого. */}
      {box('base', 0, 46 + level * 4, 16, 26, 'metal', 1)}
      {dome('dome', 0, 20 + level * 2, -16, M.glass, M.green)}
      {/* Мачта связи: колония на связи с империей с первого своего хода. */}
      {spire('mast', 20 + level * 2, 34 + level * 6, 3, -16, M.cyan)}
      {glow('window', -14, -8, 2)}
      {level >= 3 ? cylinder('tank', -(30 + level * 4), 9, 22 + level * 2, M.metal) : null}
      {level >= 4 ? box('tower', 34, 18, 44, 16, 'metal', 4) : null}
    </g>
  );
}

/**
 * Насколько вырос посёлок — п. 10: от ЖИТЕЛЕЙ колонии, а не от числа построек.
 *
 * Считается по целым жителям (`planet.population`), а не по тысячам
 * (`colony.populationK`): вместимость планеты — это полтора-два десятка жителей, и
 * ступени размечены по ней. Подставь сюда тысячи — и любая колония сразу оказалась бы
 * городом.
 *
 * Ступеней четыре, и порог у первой — один житель: колония в одного — это уже колония, и
 * купол на поверхности у неё есть.
 */
export const colonyCentreLevel = (population: number): 1 | 2 | 3 | 4 => {
  if (population >= 14) {
    return 4;
  }
  if (population >= 8) {
    return 3;
  }
  return population >= 4 ? 2 : 1;
};

/**
 * Орбитальная постройка в небе: кольцо со ступицей и спицами, и чем выше ступень лестницы
 * (база → станция → крепость), тем больше колец и огней. Стоит одна — старшая: станция
 * заменяет базу, крепость заменяет обе (п. 8), и в небе висит то, что есть.
 */
export function OrbitalSprite({ level }: { level: 1 | 2 | 3 }) {
  const r = 18 + level * 8;
  return (
    <g>
      <ellipse cx={0} cy={0} rx={r} ry={r * 0.36} fill="none" stroke="#b8b2a4" strokeWidth={3 + level} />
      {level >= 2 ? <ellipse cx={0} cy={0} rx={r * 0.62} ry={r * 0.22} fill="none" stroke="#8f897d" strokeWidth={2} /> : null}
      {[0, 60, 120].map((a) => (
        <line key={a} x1={-Math.cos((a * Math.PI) / 180) * r} y1={-Math.sin((a * Math.PI) / 180) * r * 0.36}
              x2={Math.cos((a * Math.PI) / 180) * r} y2={Math.sin((a * Math.PI) / 180) * r * 0.36}
              stroke="#6b665c" strokeWidth={1.5} />
      ))}
      <ellipse cx={0} cy={0} rx={8 + level * 2} ry={6 + level * 2} fill={M.copper} />
      {level === 3 ? <rect x={-3} y={-30} width={6} height={24} fill="#8f897d" /> : null}
      {Array.from({ length: 2 + level * 2 }).map((_, i) => {
        const a = (i / (2 + level * 2)) * Math.PI * 2;
        return <circle key={i} cx={Math.cos(a) * r} cy={Math.sin(a) * r * 0.36} r={1.6} fill={i % 2 ? M.red : M.light} />;
      })}
    </g>
  );
}
