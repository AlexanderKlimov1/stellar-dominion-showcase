/**
 * Как планета выглядит на схеме системы — п. 4.1.
 *
 * Цвет отражает плодородность, радиус — размер. Значения общие для схемы орбит,
 * таблицы планет и карточки, поэтому лежат отдельно от всех троих.
 */

/** Цвет планеты по коду климата — п. 4.1.2. */
const CLIMATE_COLORS: Record<string, string> = {
  TOXIC: '#7fae4e',
  RADIATED: '#b6a02f',
  BARREN: '#8b8378',
  DESERT: '#d9a066',
  TUNDRA: '#9fb8c8',
  ARID: '#c2854f',
  SWAMP: '#6f8f5a',
  OCEAN: '#3f7fbf',
  TERRAN: '#46a05a',
  GAIA: '#5fd47e',
  ASTEROID_BELT: '#6d6d6d',
  GAS_GIANT: '#c9a06a',
};

/**
 * Радиус планеты на схеме системы — п. 4.1.1, в единицах её viewBox (840 × 480).
 *
 * Пропорции сняты со скриншота оригинала: планета занимает там около 1/13 ширины поля
 * (34 px при поле 455 px) — почти столько же, сколько промежуток между соседними
 * орбитами. Планеты в MOO II крупные, а не точки на нитке.
 *
 * Разброс между классами размера узкий: в оригинале крупная планета лишь ненамного
 * больше мелкой, размер игрок читает в карточке, а не меряет глазом. Поэтому все пять
 * значений держатся вокруг среднего.
 */
const SIZE_RADII: Record<string, number> = {
  TINY: 20,
  SMALL: 24,
  MEDIUM: 28,
  LARGE: 32,
  HUGE: 36,
};

export const climateColor = (code: string): string => CLIMATE_COLORS[code] ?? '#7d8aa3';
export const planetRadius = (code: string): number => SIZE_RADII[code] ?? 28;

/**
 * Пояс астероидов на схеме системы — россыпью камней по всей орбите, а не кружком
 * в одной её точке: в MOO II пояс занимает орбиту целиком, планеты на ней нет.
 *
 * Камни расставлены по кругу с постоянным шагом и с отклонением от линии орбиты,
 * посчитанным от их номера: россыпь должна выглядеть неровной, но не меняться при
 * каждой перерисовке.
 */
export function AsteroidRing({
  cx,
  cy,
  rx,
  ry,
  color,
  selected,
}: {
  cx: number;
  cy: number;
  rx: number;
  ry: number;
  color: string;
  selected: boolean;
}) {
  const count = 26;
  return (
    <g opacity={selected ? 1 : 0.75}>
      {Array.from({ length: count }).map((_, index) => {
        const angle = ((index + 0.5) / count) * 2 * Math.PI;
        // Отклонение от линии орбиты и размер камня — от номера: та же россыпь при
        // каждой отрисовке, но без видимой правильности.
        const drift = 1 + 0.055 * Math.sin(index * 2.4);
        const radius = 1.6 + 1.4 * ((index * 7) % 5) / 4;
        return (
          <circle
            key={index}
            cx={cx + rx * drift * Math.cos(angle)}
            cy={cy + ry * drift * Math.sin(angle)}
            r={radius}
            fill={color}
          />
        );
      })}
    </g>
  );
}

/** Пояс астероидов значком — россыпью точек, а не диском: это не планета. */
export function AsteroidBelt({ cx, cy, color }: { cx: number; cy: number; color: string }) {
  const rocks = [
    [-11, -5, 2.5],
    [-4, 4, 2],
    [3, -6, 3],
    [9, 2, 2.5],
    [-1, -1, 1.8],
    [13, -4, 1.8],
  ];
  return (
    <g>
      {rocks.map(([dx, dy, r], index) => (
        <circle key={index} cx={cx + dx} cy={cy + dy} r={r} fill={color} />
      ))}
    </g>
  );
}
