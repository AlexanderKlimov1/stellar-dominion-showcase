import type { ReactNode } from 'react';
import type { ShipRole } from '../../api/types';

/**
 * Рисунки кораблей, платформ обороны и чудищ — п. 8, п. 11, п. 11.1.
 *
 * В MOO II корабль узнают по силуэту: фрегат — стрелка, линкор — тяжёлый клин с башнями,
 * Doom Star — летающая крепость. Рисунки оригинала — чужое добро, поэтому здесь свои,
 * собранные из ОБЩИХ деталей — металл корпуса, тёмные швы, полоса цвета стороны, огонь
 * двигателей, огни рубки, — как рисунки зданий колонии (`ui/system/buildingArt.tsx`), и
 * по той же причине: детали одни, значит и стиль один на все семнадцать корпусов.
 *
 * <b>Один рисунок на все места показа</b> — поле боя, окно дизайна, экран флота. Разные
 * рисунки одного корабля в трёх окнах читались бы тремя разными кораблями.
 *
 * Рисунок лежит в квадрате −50…50 по обеим осям, НОСОМ ВПРАВО; размер ему задаёт место
 * показа (на поле боя — `battleVisuals.shipSize`: фрегат мельче клетки, Левиафан
 * перерастает её вдвое), разворот к противнику — тоже оно. Класс корабля читается не только
 * размером, но и самим силуэтом: на экране флота клетки одинаковые, и там различает именно
 * он.
 *
 * Цвет стороны — одна полоса и огни, а не заливка всего корпуса: корпус металлический, как
 * в оригинале, а своего от чужого отличает окраска, как опознавательные знаки у судов.
 *
 * Корпус, которого нет в таблице (новый в справочнике), получает рисунок своего размера:
 * пропасть с поля он не должен — в бою его обязаны видеть.
 */

/**
 * Градиенты металла и свечения. Кладутся в `<defs>` того SVG, где стоит рисунок: на поле
 * боя один раз на всё поле, в мелких окнах — каждому рисунку свои (они одинаковы, и
 * совпадение имён между рисунками ничего не портит).
 */
export function ShipDefs() {
  return (
    <>
      <linearGradient id="sa-hull" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0" stopColor="#e2ddd0" />
        <stop offset="0.45" stopColor="#9b958a" />
        <stop offset="1" stopColor="#4c4841" />
      </linearGradient>
      <linearGradient id="sa-hull-dark" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0" stopColor="#8d877c" />
        <stop offset="1" stopColor="#2f2c28" />
      </linearGradient>
      <radialGradient id="sa-engine" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stopColor="#ffffff" />
        <stop offset="0.35" stopColor="#8fe7ff" />
        <stop offset="1" stopColor="#1a8cff" stopOpacity="0" />
      </radialGradient>
      <radialGradient id="sa-core" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stopColor="#fff4d0" />
        <stop offset="0.5" stopColor="#ff9a3c" />
        <stop offset="1" stopColor="#ff5a1f" stopOpacity="0" />
      </radialGradient>
      <radialGradient id="sa-flesh" cx="0.4" cy="0.35" r="0.75">
        <stop offset="0" stopColor="#d8f5c0" stopOpacity="0.9" />
        <stop offset="0.6" stopColor="#6fb35a" stopOpacity="0.75" />
        <stop offset="1" stopColor="#24521c" stopOpacity="0.85" />
      </radialGradient>
      <linearGradient id="sa-crystal" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0" stopColor="#f3e8ff" />
        <stop offset="0.5" stopColor="#b48cff" />
        <stop offset="1" stopColor="#4b2a8a" />
      </linearGradient>
      <linearGradient id="sa-scales" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0" stopColor="#d49a5a" />
        <stop offset="0.5" stopColor="#8c4a2a" />
        <stop offset="1" stopColor="#3d1c10" />
      </linearGradient>
    </>
  );
}

const HULL = 'url(#sa-hull)';
const DARK = 'url(#sa-hull-dark)';
const SEAM = '#2a2723';

/**
 * Огонь двигателя: круг свечения у кормы и яркое сопло. Свечение дышит (`sddnw-engine`), и
 * срок у каждого сопла свой — выведен из его места, а не из случая: рисунок перерисовывается
 * часто, и случайный срок дёргал бы огонь при каждой перерисовке.
 */
function engine(key: string, x: number, y: number, r: number): ReactNode {
  const period = 0.8 + (Math.abs(Math.round(x * 7 + y * 13)) % 7) / 10;
  return (
    <g key={key}>
      <circle cx={x - r * 0.8} cy={y} r={r * 2.2} fill="url(#sa-engine)" opacity={0.85}
              style={{ animation: `sddnw-engine ${period}s ease-in-out infinite alternate` }} />
      <rect x={x - r * 0.4} y={y - r * 0.55} width={r} height={r * 1.1} fill="#39434d" />
      <circle cx={x - r * 0.4} cy={y} r={r * 0.45} fill="#dff8ff" />
    </g>
  );
}

/** Огонёк: окно рубки, навигационный огонь. */
function lamp(key: string, x: number, y: number, r: number, color: string): ReactNode {
  return (
    <g key={key}>
      <circle cx={x} cy={y} r={r * 2.4} fill={color} opacity={0.25} />
      <circle cx={x} cy={y} r={r} fill={color} />
    </g>
  );
}

/** Орудийная башня сверху корпуса: основание и ствол к носу. */
function turret(key: string, x: number, y: number, r: number): ReactNode {
  return (
    <g key={key}>
      <rect x={x} y={y - r * 0.22} width={r * 2.2} height={r * 0.44} fill="#3b3833" />
      <circle cx={x} cy={y} r={r} fill={DARK} stroke={SEAM} strokeWidth={0.6} />
    </g>
  );
}

// --- корабли -----------------------------------------------------------------------------

type Draw = (color: string) => ReactNode[];

const SHIPS: Record<number, Draw> = {
  // Фрегат — стрелка с одним двигателем.
  1: (color) => [
    engine('e', -26, 0, 5),
    <polygon key="h" points="-28,-10 -12,-14 30,-2 34,0 30,2 -12,14 -28,10" fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="s" points="-20,-4 18,-2 18,2 -20,4" fill={color} opacity={0.9} />,
    lamp('c', 20, 0, 1.6, '#fff1b8'),
  ],
  // Эсминец — узкий корпус с плавниками по бокам и двумя двигателями.
  2: (color) => [
    engine('e1', -32, -6, 5), engine('e2', -32, 6, 5),
    <polygon key="f1" points="-22,-8 -6,-8 -16,-24 -26,-24" fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="f2" points="-22,8 -6,8 -16,24 -26,24" fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="h" points="-34,-11 -8,-13 34,-4 40,0 34,4 -8,13 -34,11" fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
    <line key="seam" x1={-30} y1={0} x2={30} y2={0} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="s" points="-26,-6 10,-5 10,-2 -26,-3" fill={color} />,
    lamp('c', 26, 0, 1.8, '#fff1b8'),
    lamp('n1', -20, -24, 1.2, color), lamp('n2', -20, 24, 1.2, color),
  ],
  // Крейсер — длинный корпус с бортовыми гондолами и рубкой.
  3: (color) => [
    engine('e1', -40, -16, 5), engine('e2', -40, 16, 5), engine('e3', -38, 0, 6),
    <rect key="p1" x={-40} y={-22} width={46} height={12} rx={5} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <rect key="p2" x={-40} y={10} width={46} height={12} rx={5} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="h" points="-38,-12 0,-14 38,-5 46,0 38,5 0,14 -38,12" fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
    <rect key="bridge" x={-6} y={-7} width={16} height={14} rx={3} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <polygon key="s1" points="-34,-10 20,-8 20,-5 -34,-7" fill={color} />,
    <polygon key="s2" points="-34,10 20,8 20,5 -34,7" fill={color} />,
    lamp('c', 4, 0, 2, '#fff1b8'), lamp('nose', 40, 0, 1.4, color),
  ],
  // Линкор — тяжёлый клин с орудийными башнями и тремя двигателями.
  4: (color) => [
    engine('e1', -42, -18, 6), engine('e2', -42, 0, 7), engine('e3', -42, 18, 6),
    <polygon key="h" points="-44,-26 -10,-28 36,-10 48,0 36,10 -10,28 -44,26" fill={HULL} stroke={SEAM} strokeWidth={0.9} />,
    <polygon key="deck" points="-36,-16 -4,-18 26,-6 26,6 -4,18 -36,16" fill={DARK} stroke={SEAM} strokeWidth={0.7} />,
    turret('t1', 10, -8, 5), turret('t2', 10, 8, 5), turret('t3', -14, 0, 6),
    <polygon key="s1" points="-40,-23 8,-24 8,-21 -40,-20" fill={color} />,
    <polygon key="s2" points="-40,23 8,24 8,21 -40,20" fill={color} />,
    lamp('c', 30, 0, 2, '#fff1b8'), lamp('n1', -30, -24, 1.3, color), lamp('n2', -30, 24, 1.3, color),
  ],
  // Титан — огромный корпус с крыльями-гондолами и четырьмя двигателями.
  5: (color) => [
    engine('e1', -44, -30, 6), engine('e2', -46, -10, 7), engine('e3', -46, 10, 7), engine('e4', -44, 30, 6),
    <polygon key="w1" points="-40,-18 4,-18 -8,-44 -42,-44" fill={DARK} stroke={SEAM} strokeWidth={0.9} />,
    <polygon key="w2" points="-40,18 4,18 -8,44 -42,44" fill={DARK} stroke={SEAM} strokeWidth={0.9} />,
    <polygon key="h" points="-46,-20 -4,-24 38,-10 50,0 38,10 -4,24 -46,20" fill={HULL} stroke={SEAM} strokeWidth={0.9} />,
    <rect key="spine" x={-40} y={-4} width={70} height={8} fill={DARK} />,
    turret('t1', -6, -12, 5), turret('t2', -6, 12, 5), turret('t3', 18, 0, 5.5),
    <polygon key="s1" points="-40,-40 -12,-40 -10,-36 -40,-36" fill={color} />,
    <polygon key="s2" points="-40,40 -12,40 -10,36 -40,36" fill={color} />,
    lamp('c', 34, 0, 2.2, '#fff1b8'),
    ...[-30, -10, 10].map((x, i) => lamp(`w${i}`, x, -20, 1, '#ffd27a')),
    ...[-30, -10, 10].map((x, i) => lamp(`v${i}`, x, 20, 1, '#ffd27a')),
  ],
  // Левиафан — летающая крепость: тяжёлый шестиугольник с огненным ядром.
  6: (color) => [
    engine('e1', -44, -26, 7), engine('e2', -48, 0, 8), engine('e3', -44, 26, 7),
    <polygon key="h" points="-46,-30 -10,-46 30,-40 50,0 30,40 -10,46 -46,30" fill={HULL} stroke={SEAM} strokeWidth={1} />,
    <polygon key="ring" points="-30,-20 -6,-32 22,-26 34,0 22,26 -6,32 -30,20" fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="core" cx={-2} cy={0} r={16} fill="url(#sa-core)" />,
    <circle key="coreEdge" cx={-2} cy={0} r={11} fill="none" stroke="#ffd9a0" strokeWidth={1.2} />,
    turret('t1', 12, -20, 5), turret('t2', 12, 20, 5), turret('t3', 30, 0, 5),
    <polygon key="s1" points="-40,-30 -8,-44 -6,-40 -38,-27" fill={color} />,
    <polygon key="s2" points="-40,30 -8,44 -6,40 -38,27" fill={color} />,
    ...[0, 1, 2, 3, 4, 5].map((i) => {
      const a = (i / 6) * Math.PI * 2;
      return lamp(`r${i}`, -2 + Math.cos(a) * 24, Math.sin(a) * 24, 1.2, i % 2 ? color : '#ffd27a');
    }),
  ],
};

// --- гражданские корабли ---------------------------------------------------------------
//
// У гражданского корабля свой рисунок, а не рисунок корпуса: игра строит его на самом
// дешёвом корпусе, и по корпусу колониальный корабль выглядел бы фрегатом. Рисунок выбирает
// РОЛЬ (`FleetShip.role`), и у всех трёх нет ни одной башни — оружия они не носят (п. 4.1,
// п. 12), и глаз должен понимать это с первого взгляда.

const CIVIL: Partial<Record<ShipRole, Draw>> = {
  // Колониальный корабль — купол поселения на пузатом корпусе, по бокам панели солнечных
  // батарей: он везёт не пушки, а город.
  COLONY: (color) => [
    engine('e1', -34, -8, 5), engine('e2', -34, 8, 5),
    <rect key="p1" x={-18} y={-40} width={30} height={14} fill="#26405c" stroke="#8fb4d8" strokeWidth={0.8} />,
    <rect key="p2" x={-18} y={26} width={30} height={14} fill="#26405c" stroke="#8fb4d8" strokeWidth={0.8} />,
    <line key="a1" x1={-3} y1={-26} x2={-3} y2={-16} stroke={SEAM} strokeWidth={2} />,
    <line key="a2" x1={-3} y1={26} x2={-3} y2={16} stroke={SEAM} strokeWidth={2} />,
    <ellipse key="h" cx={0} cy={0} rx={34} ry={17} fill={HULL} stroke={SEAM} strokeWidth={0.9} />,
    <path key="dome" d="M-14,-4 A16,14 0 0 1 18,-4 Z" fill="#9fe3ff" opacity={0.55} stroke="#dff8ff" strokeWidth={0.8} />,
    ...[-8, 0, 8].map((x, i) => <rect key={`b${i}`} x={x - 2} y={-10} width={4} height={6} fill="#ffe7a8" opacity={0.9} />),
    <rect key="s" x={-26} y={4} width={44} height={4} fill={color} />,
    lamp('c', 30, 0, 1.6, '#fff1b8'),
  ],
  // Застава — модуль-куб с решётчатой мачтой маяка: её ставят и оставляют.
  OUTPOST: (color) => [
    engine('e', -30, 0, 5),
    <rect key="h" x={-24} y={-16} width={36} height={32} rx={3} fill={HULL} stroke={SEAM} strokeWidth={0.9} />,
    <line key="x1" x1={-24} y1={-16} x2={12} y2={16} stroke={SEAM} strokeWidth={0.7} />,
    <line key="x2" x1={-24} y1={16} x2={12} y2={-16} stroke={SEAM} strokeWidth={0.7} />,
    <polygon key="mast" points="12,-3 36,-1 36,1 12,3" fill={DARK} />,
    <circle key="dish" cx={38} cy={0} r={6} fill="none" stroke="#c9d3dc" strokeWidth={1.6} />,
    <rect key="s" x={-20} y={-12} width={4} height={24} fill={color} />,
    lamp('beacon', 38, 0, 2, '#ff6b5a'),
  ],
  // Транспорт — длинная сцепка грузовых отсеков с десантом.
  TRANSPORT: (color) => [
    engine('e1', -40, -6, 5), engine('e2', -40, 6, 5),
    <rect key="spine" x={-38} y={-3} width={70} height={6} fill={DARK} />,
    ...[-34, -12, 10].map((x, i) => (
      <rect key={`pod${i}`} x={x} y={-14} width={18} height={28} rx={2} fill={HULL} stroke={SEAM} strokeWidth={0.8} />
    )),
    ...[-34, -12, 10].map((x, i) => <rect key={`st${i}`} x={x + 2} y={-2} width={14} height={4} fill={color} />),
    <polygon key="cab" points="30,-9 42,-4 42,4 30,9" fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
    lamp('c', 36, 0, 1.6, '#fff1b8'),
  ],
};

// --- платформы обороны (вид сверху: сооружение, а не корабль) --------------------------

const PLATFORMS: Record<string, Draw> = {
  'star-base': (color) => [
    <circle key="ring" r={36} fill="none" stroke={HULL} strokeWidth={9} />,
    <circle key="ringEdge" r={36} fill="none" stroke={SEAM} strokeWidth={0.8} />,
    ...[0, 90, 180, 270].map((a) => (
      <rect key={`sp${a}`} x={-2.5} y={-34} width={5} height={24} fill={DARK} transform={`rotate(${a})`} />
    )),
    <circle key="hub" r={12} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="hubLight" r={4} fill={color} />,
    ...[45, 135, 225, 315].map((a) => (
      <circle key={`l${a}`} cx={Math.cos((a * Math.PI) / 180) * 36} cy={Math.sin((a * Math.PI) / 180) * 36} r={2} fill="#ffd27a" />
    )),
  ],
  'battle-station': (color) => [
    <circle key="outer" r={44} fill="none" stroke={DARK} strokeWidth={6} />,
    <circle key="ring" r={32} fill="none" stroke={HULL} strokeWidth={10} />,
    ...[0, 60, 120, 180, 240, 300].map((a) => (
      <rect key={`sp${a}`} x={-2.5} y={-44} width={5} height={30} fill={DARK} transform={`rotate(${a})`} />
    )),
    ...[30, 150, 270].map((a) => (
      <g key={`t${a}`} transform={`rotate(${a}) translate(0 -32)`}>{turret('t', 0, 0, 5)}</g>
    )),
    <circle key="hub" r={14} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="hubLight" r={5} fill={color} />,
  ],
  'star-fortress': (color) => [
    <polygon key="shell" points="0,-48 42,-24 42,24 0,48 -42,24 -42,-24" fill={HULL} stroke={SEAM} strokeWidth={1} />,
    <polygon key="inner" points="0,-34 30,-17 30,17 0,34 -30,17 -30,-17" fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="core" r={14} fill="url(#sa-core)" />,
    ...[0, 60, 120, 180, 240, 300].map((a) => (
      <g key={`t${a}`} transform={`rotate(${a}) translate(0 -40)`}>{turret('t', 0, 0, 4.5)}</g>
    )),
    ...[30, 90, 150, 210, 270, 330].map((a) => (
      <circle key={`l${a}`} cx={Math.cos((a * Math.PI) / 180) * 24} cy={Math.sin((a * Math.PI) / 180) * 24} r={1.8} fill={color} />
    )),
  ],
  'planetary-battery': (color) => [
    <circle key="pad" r={34} fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="dome" r={22} fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
    <rect key="b1" x={0} y={-8} width={44} height={5} fill="#3b3833" />,
    <rect key="b2" x={0} y={3} width={44} height={5} fill="#3b3833" />,
    <circle key="light" r={5} fill={color} />,
  ],
  'planetary-shield': (color) => [
    <circle key="glow" r={46} fill={color} opacity={0.12} />,
    ...[0, 60, 120].map((a) => (
      <ellipse key={`m${a}`} rx={44} ry={16} fill="none" stroke={color} strokeWidth={1.4} opacity={0.7} transform={`rotate(${a})`} />
    )),
    <circle key="edge" r={44} fill="none" stroke={color} strokeWidth={2} opacity={0.85} />,
    <circle key="core" r={8} fill={HULL} stroke={SEAM} strokeWidth={0.8} />,
  ],
};

// --- чудища ------------------------------------------------------------------------------

const MONSTERS: Record<string, Draw> = {
  // Амёба — студень с ядром и ложноножками.
  'amoeba-body': () => [
    <path key="b" d="M-40,-4 C-44,-30 -10,-44 10,-34 C34,-40 48,-14 40,6 C50,28 20,44 0,36 C-22,46 -46,28 -40,-4 Z" fill="url(#sa-flesh)" stroke="#2e6b24" strokeWidth={1.2} />,
    <circle key="n" cx={4} cy={-2} r={10} fill="#e9ffc9" opacity={0.8} />,
    <circle key="nn" cx={6} cy={-4} r={4} fill="#5c8f3c" />,
    ...[[-18, 18], [22, 20], [-10, -20]].map(([x, y], i) => <circle key={`v${i}`} cx={x} cy={y} r={4} fill="#b8e8a0" opacity={0.6} />),
  ],
  // Кристалл — друза граней.
  'crystal-body': () => [
    <polygon key="c1" points="-10,40 -30,6 -14,-40 4,-6" fill="url(#sa-crystal)" stroke="#e8dcff" strokeWidth={0.8} />,
    <polygon key="c2" points="0,42 -2,-48 18,-10 12,40" fill="url(#sa-crystal)" stroke="#e8dcff" strokeWidth={0.8} />,
    <polygon key="c3" points="10,38 26,-26 44,4 30,36" fill="url(#sa-crystal)" stroke="#e8dcff" strokeWidth={0.8} />,
    <polygon key="c4" points="-24,36 -44,20 -34,-12 -18,20" fill="url(#sa-crystal)" stroke="#e8dcff" strokeWidth={0.8} />,
    <circle key="glint" cx={2} cy={-20} r={3} fill="#ffffff" />,
  ],
  // Угорь — извилистое тело с плавником и светящимися глазами.
  'eel-body': () => [
    <path key="b" d="M-46,10 C-30,-24 -10,24 8,-6 C22,-28 34,-10 46,-4 L44,4 C32,0 24,-14 14,4 C-2,34 -30,-2 -40,18 Z" fill="url(#sa-scales)" stroke="#2a130a" strokeWidth={1} />,
    <path key="fin" d="M-20,-2 C-10,-18 0,-10 4,-14" fill="none" stroke="#e8b070" strokeWidth={1.5} />,
    lamp('eye', 40, -2, 1.8, '#ffef7a'),
  ],
  // Гидра — тело и три шеи с головами.
  'hydra-body': () => [
    <ellipse key="b" cx={-14} cy={4} rx={26} ry={20} fill="url(#sa-scales)" stroke="#2a130a" strokeWidth={1} />,
    ...[-26, 0, 26].map((y, i) => (
      <g key={`n${i}`}>
        <path d={`M-2,${4 + y * 0.3} C12,${y * 0.8} 22,${y} 34,${y}`} fill="none" stroke="#8c4a2a" strokeWidth={7} strokeLinecap="round" />
        <ellipse cx={38} cy={y} rx={8} ry={6} fill="url(#sa-scales)" />
        {lamp('e', 42, y - 2, 1.4, '#ffef7a')}
      </g>
    )),
  ],
  // Дракон — крылатый ящер: тело, два крыла, хвост и голова к носу.
  'dragon-body': () => [
    <path key="tail" d="M-20,4 C-34,10 -40,24 -48,22" fill="none" stroke="#8c4a2a" strokeWidth={6} strokeLinecap="round" />,
    <path key="w1" d="M-12,-6 C-18,-40 10,-48 18,-26 C8,-24 2,-16 -2,-6 Z" fill="#6a2a18" stroke="#2a130a" strokeWidth={1} />,
    <path key="w2" d="M-12,10 C-18,44 10,50 18,30 C8,28 2,20 -2,10 Z" fill="#6a2a18" stroke="#2a130a" strokeWidth={1} />,
    <ellipse key="b" cx={0} cy={2} rx={22} ry={12} fill="url(#sa-scales)" stroke="#2a130a" strokeWidth={1} />,
    <path key="neck" d="M18,0 C28,-6 34,-6 40,-2" fill="none" stroke="#8c4a2a" strokeWidth={7} strokeLinecap="round" />,
    <polygon key="head" points="36,-8 50,-2 36,6" fill="url(#sa-scales)" stroke="#2a130a" strokeWidth={1} />,
    lamp('eye', 42, -3, 1.5, '#ffef7a'),
  ],
  // Страж — бронированная громада с глазом посредине.
  'guardian-body': () => [
    <polygon key="shell" points="-44,-20 -20,-44 20,-44 44,-20 44,20 20,44 -20,44 -44,20" fill={HULL} stroke={SEAM} strokeWidth={1.2} />,
    <polygon key="plate" points="-30,-14 -14,-30 14,-30 30,-14 30,14 14,30 -14,30 -30,14" fill={DARK} stroke={SEAM} strokeWidth={0.8} />,
    <circle key="eye" r={14} fill="#3a0a0a" />,
    <circle key="iris" r={9} fill="url(#sa-core)" />,
    <circle key="pupil" cx={2} r={3} fill="#1a0505" />,
    ...[0, 90, 180, 270].map((a) => (
      <g key={`t${a}`} transform={`rotate(${a}) translate(0 -36)`}>{turret('t', 0, 0, 4)}</g>
    )),
  ],
};

/**
 * Какой корпус какой рисунок носит: у платформ и чудищ свой, у гражданских кораблей — по
 * роли, у боевых — по размеру корпуса.
 */
function drawing(hullCode: string | undefined, hullSize: number, role?: ShipRole): { draw: Draw; facing: boolean } {
  const civil = role ? CIVIL[role] : undefined;
  if (civil) {
    return { draw: civil, facing: true };
  }
  if (hullCode && PLATFORMS[hullCode]) {
    return { draw: PLATFORMS[hullCode], facing: false };
  }
  if (hullCode && MONSTERS[hullCode]) {
    return { draw: MONSTERS[hullCode], facing: true };
  }
  return { draw: SHIPS[Math.max(1, Math.min(6, hullSize))], facing: true };
}

/**
 * Корабль (платформа, чудище) рисунком, вписанным в квадрат со стороной `size` с центром в
 * (`cx`, `cy`). `left` разворачивает нос влево — к противнику, стоящему левее. Платформы
 * не разворачиваются: сооружение смотрит во все стороны сразу.
 */
export function ShipSprite({ hullCode, hullSize, role, color, cx, cy, size, height, left = false }: {
  hullCode?: string;
  hullSize: number;
  /** Роль корабля: у гражданских она, а не корпус, выбирает рисунок. */
  role?: ShipRole;
  color: string;
  cx: number;
  cy: number;
  size: number;
  /**
   * Высота рамки поперёк хода, если она не равна длине: Левиафан на поле боя три клетки
   * длиной и две шириной (пункт 30). Пусто — рамка квадратная.
   */
  height?: number;
  left?: boolean;
}) {
  const { draw, facing } = drawing(hullCode, hullSize, role);
  const scale = size / 100;
  const scaleY = (height ?? size) / 100;
  const flip = facing && left ? -1 : 1;
  return <g transform={`translate(${cx} ${cy}) scale(${scale * flip} ${scaleY})`}>{draw(color)}</g>;
}

/**
 * Тот же рисунок отдельной картинкой — для окна дизайна и экрана флота, где корабль стоит
 * в HTML, а не на поле SVG. Градиенты кладутся в свой `<defs>`.
 */
export function ShipPicture({ hullCode, hullSize, role, color, size, label }: {
  hullCode?: string;
  hullSize: number;
  role?: ShipRole;
  color: string;
  size: number;
  label?: string;
}) {
  return (
    <svg width={size} height={size} viewBox="-50 -50 100 100" role="img" aria-label={label}>
      <defs><ShipDefs /></defs>
      <ShipSprite hullCode={hullCode} hullSize={hullSize} role={role} color={color} cx={0} cy={0} size={100} />
    </svg>
  );
}
