import aridEarthAfrica from '../../assets/planets/arid-earth-africa.webp';
import barrenMercury from '../../assets/planets/barren-mercury.webp';
import desertMars from '../../assets/planets/desert-mars.webp';
import gaiaEarthClear from '../../assets/planets/gaia-earth-clear.webp';
import gasGiantJupiter from '../../assets/planets/gas-giant-jupiter.webp';
import gasGiantNeptune from '../../assets/planets/gas-giant-neptune.webp';
import gasGiantUranus from '../../assets/planets/gas-giant-uranus.webp';
import oceanEarthPacific from '../../assets/planets/ocean-earth-pacific.webp';
import radiatedIo from '../../assets/planets/radiated-io.webp';
import swampTitan from '../../assets/planets/swamp-titan.webp';
import terranEarthAmericas from '../../assets/planets/terran-earth-americas.webp';
import toxicVenus from '../../assets/planets/toxic-venus.webp';
import tundraEuropa from '../../assets/planets/tundra-europa.webp';
import surfaceArid from '../../assets/surfaces/arid-great-sand-dunes.webp';
import surfaceBarren from '../../assets/surfaces/barren-moon-hadley.webp';
import surfaceDesert from '../../assets/surfaces/desert-mars-rocknest.webp';
import surfaceGaia from '../../assets/surfaces/gaia-alpine-meadow.webp';
import surfaceOcean from '../../assets/surfaces/ocean-cape-hatteras.webp';
import surfaceRadiated from '../../assets/surfaces/radiated-lava-plain.webp';
import surfaceSwamp from '../../assets/surfaces/swamp-bald-cypress.webp';
import surfaceTerran from '../../assets/surfaces/terran-comanche-prairie.webp';
import surfaceToxic from '../../assets/surfaces/toxic-travertine-terraces.webp';
import surfaceTundra from '../../assets/surfaces/tundra-soda-butte-valley.webp';

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
 * Снимок настоящего тела Солнечной системы для каждого климата — п. 4.1.2.
 *
 * <b>Сознательное отступление от MOO II.</b> В оригинале планеты на схеме нарисованы
 * пиксельной графикой; здесь — фотографии, по просьбе хозяина проекта: плоский цветной
 * кружок читался как заглушка. Подобраны тела, чья поверхность ближе всего к климату игры:
 * ядовитая Венера, облучённая Ио (она внутри радиационных поясов Юпитера), безжизненный
 * Меркурий, пустынный Марс, ледяная Европа под тундру, Титан с метановыми болотами. У
 * обитаемых климатов аналога, кроме Земли, нет, поэтому Arid, Ocean, Terran и Gaia — разные
 * её полушария: так их хотя бы видно друг от друга. Источники, авторы и лицензии (все —
 * общественное достояние NASA) — в `assets/planets/CREDITS.md`.
 *
 * <b>Цвет климата при этом остался</b> ({@link climateColor}): им рисуют пояс астероидов,
 * поле боя и поверхность колонии, а схема берёт его запасным, если снимка у климата нет.
 */
const CLIMATE_IMAGES: Record<string, string> = {
  TOXIC: toxicVenus,
  RADIATED: radiatedIo,
  BARREN: barrenMercury,
  DESERT: desertMars,
  TUNDRA: tundraEuropa,
  ARID: aridEarthAfrica,
  SWAMP: swampTitan,
  OCEAN: oceanEarthPacific,
  TERRAN: terranEarthAmericas,
  GAIA: gaiaEarthClear,
};

/**
 * Газовых гигантов в галактике много, и три одинаковых Юпитера в одной системе читались бы
 * копированием. Поэтому снимков у них три, а какой достаётся планете — решает её
 * идентификатор: выбор не меняется между перерисовками и одинаков у всех игроков.
 */
const GAS_GIANT_IMAGES = [gasGiantJupiter, gasGiantNeptune, gasGiantUranus];

/**
 * Снимок планеты; `null` — снимка нет (пояс астероидов, незнакомый климат), и рисовать
 * её приходится цветом.
 */
export const planetImage = (climate: string, planetId: string): string | null => {
  if (climate === 'GAS_GIANT') {
    let hash = 0;
    for (let index = 0; index < planetId.length; index += 1) {
      hash = (hash * 31 + planetId.charCodeAt(index)) % 9973;
    }
    return GAS_GIANT_IMAGES[hash % GAS_GIANT_IMAGES.length];
  }
  return CLIMATE_IMAGES[climate] ?? null;
};

/**
 * Полоса земли под горизонтом на экране колонии — п. 10.
 *
 * Та же дорога, что у {@link planetImage}: настоящий снимок в общественном достоянии,
 * лицензия проверена по метаданным Commons ДО скачивания, источники и способ обработки —
 * в `assets/surfaces/CREDITS.md`. Снятой поверхности у человечества всего три (Луна, Марс,
 * астероиды), поэтому чужими мирами заняты только Barren и Desert, а прочим климатам
 * достались самые непохожие на обжитую Землю места самой Земли.
 *
 * <b>Снимок обрезан по горизонту:</b> дневное небо отброшено целиком — оно спорило бы со
 * звёздным небом сцены, — и полоса ложится ровно под горизонт, а небо над ней остаётся
 * нарисованным. Свет на всех снимках слева сверху: два сняты при солнце справа и потому
 * развёрнуты зеркально — иначе тени снимка шли бы навстречу теням зданий.
 *
 * `null` — снимка нет, и земля рисуется цветом климата, как рисовалась всегда. Такого
 * климата в игре, впрочем, не бывает: колонию основывают только на пригодной планете, а
 * пригодны ровно те десять, у которых снимок есть.
 */
const SURFACE_IMAGES: Record<string, string> = {
  TOXIC: surfaceToxic,
  RADIATED: surfaceRadiated,
  BARREN: surfaceBarren,
  DESERT: surfaceDesert,
  TUNDRA: surfaceTundra,
  ARID: surfaceArid,
  SWAMP: surfaceSwamp,
  OCEAN: surfaceOcean,
  TERRAN: surfaceTerran,
  GAIA: surfaceGaia,
};

export const surfaceImage = (climate: string): string | null => SURFACE_IMAGES[climate] ?? null;

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
