import type { Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { findTechnology } from '../research/researchRules';
import type { ReactNode } from 'react';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import type { FieldSide } from './OrbitDiagram';

/**
 * Карточка планеты под указателем — п. 4.1, тем же сегментом, что и в окне «Star System»
 * MOO II: прямоугольник в верхнем углу поля, поверх звёзд и орбит.
 *
 * В оригинале в нём ровно четыре строки — название, размер с плодородностью, население
 * и минералы, — а через пустую строку перечислены постройки. Здесь то же самое: карточка
 * только называет планету, подробности колонии живут на её экране (`ColonyScreen`).
 *
 * Отдельной таблицы планет в оригинале нет и здесь не осталось: выбранную планету
 * называет эта карточка, а перебираются планеты кликом по схеме.
 */
export function PlanetInfo({ planet, side }: {
  /** Планета под указателем; пусто — указатель ни на какой планете, и карточки нет. */
  planet: Planet | null;
  /** Половина поля, в которую встаёт карточка, — та, где указателя НЕТ. */
  side: FieldSide;
}) {
  const players = useGameStore((state) => state.players);
  const climates = useGameStore((state) => state.planetClimates);
  const researchTree = useGameStore((state) => state.researchTree);
  useT();

  if (!planet) {
    return null;
  }

  const owner = players.find((player) => player.id === planet.ownerPlayerId) ?? null;
  const climate = climates.find((entry) => entry.code === planet.climate) ?? null;
  const nextClimate = climate?.nextClimateCode
    ? (climates.find((entry) => entry.code === climate.nextClimateCode) ?? null)
    : null;

  return (
    /*
      КАРТОЧКА — ПОДСКАЗКА ПО НАВЕДЕНИЮ (30.09.2026, решение хозяина проекта): она видна,
      пока указатель стоит на планете, и гаснет, когда он ушёл. Прежде карточка лежала в
      углу поля всегда, а планеты уворачивались от неё по орбите (`placePlanets`) — и с
      крупным кеглем места для уворота хватало не всем: карточка закрывала планету.

      Встаёт она в ПРОТИВОПОЛОЖНУЮ половину поля — ту, где указателя нет (`side`), поэтому
      никогда не закрывает планету, на которую смотрят. Прижата к верхнему углу, как окошко
      сведений в оригинале.

      Указатель карточка не ловит НИКОГДА: всплыв под курсором, она перехватила бы его, и
      планета тут же «потеряла» бы наведение — карточка мигала бы. Прокручивать её нечем,
      поэтому высота — по содержимому, но не выше поля; не влезшее срезается.

      ГОРИЗОНТАЛЬНОЙ прокрутки у карточки быть не должно ни при каком тексте, поэтому
      перенос разрешён и посреди слова (`overflow-wrap: anywhere`): длинное неразрывное
      слово — код технологии в строке терраформирования — иначе вылезало за рамку.

      КЕГЛЬ — 16, а не 11 (30.09.2026, решение хозяина проекта): карточку читают, выбирая,
      куда слать колониальный корабль, а одиннадцать пикселей на полупрозрачной подложке
      поверх звёзд читались с трудом.
    */
    <div
      className={
        'pointer-events-none absolute top-3 z-10 max-h-[calc(100%-1.5rem)] w-[42%] overflow-hidden '
        + 'border border-space-700 bg-space-950/90 px-3 py-2 text-16 leading-snug '
        + '[overflow-wrap:anywhere] '
        + (side === 'left' ? 'left-3' : 'right-3')
      }
    >
      <p className={planet.homeworld ? 'text-accent' : 'text-ink-bright'}>
        {planet.name}
        {owner ? (
          <span style={{ color: owner.color }}> ({owner.name})</span>
        ) : null}
      </p>

      <p className={figureLabelClass('value')}>
        {planet.sizeLabel}, {planet.climateLabel}
        {planet.colonizable ? <>{' '}<Figure accent="value">{planet.populationMultiplierPercent}%</Figure></> : null}
      </p>

      {/* Тяжесть — п. 4.1: непривычная расе роняет производство колонии на четверть
          или вдвое, и знать о ней надо до высадки, а не после. */}
      <p className="text-ink-soft">
        {planet.gravityLabel}
        {/* Родной мир всегда обычной тяжести (PlanetGravity) — иначе большой родной мир за
            очко расы обернулся бы половиной производства на старте; пункт 12. */}
        {planet.homeworld ? t('rule.homeworldGravity') : null}
      </p>

      {/* Находка — п. 4.1: она решает, стоит ли планета колонии, поэтому стоит выше
          населения и выделена цветом. Обыкновенной планете строки нет вовсе. Что она даёт,
          сказано сразу под ней: у туземцев и артефактов правила наши (три места колонии,
          технологии первому разведчику), и одного названия мало — пункт 12. */}
      {planet.findLabel ? (
        <p className="text-accent">{t('planet.find')}: {planet.findLabel}</p>
      ) : null}
      {planet.findDescription ? (
        <p className="text-14 leading-snug text-ink-dim">{planet.findDescription}</p>
      ) : null}

      <p className={figureLabelClass('value')}>{population(planet)}</p>

      <p className={figureLabelClass('value')}>
        {tf('planet.perWorker', {
          minerals: planet.mineralsLabel,
          n: <Figure accent="value">{planet.productionPerWorker}</Figure>,
        })}
        {planet.colonizable
          ? tf('planet.perFarmer', { n: <Figure accent="value">{planet.foodPerFarmer}</Figure> })
          : null}
      </p>

      {/* Пустая строка и список построек — как в оригинале под данными планеты. */}
      <p className="mt-2 text-ink-dim">{buildings(planet)}</p>

      {nextClimate && climate?.requiredTechCode ? (
        <p className="text-ink-faint">
          {t('planet.terraforming', {
            climate: nextClimate.label,
            /*
              Технология — НАЗВАНИЕМ из дерева, а не кодом: прежде игрок читал здесь
              «GAIA_TRANSFORMATION». Код, которого в дереве нет, показывается как есть:
              молчать о требовании хуже, чем показать его строкой (так же и в окне
              дизайна кораблей, `ShipDesignService.techName`).
            */
            tech: findTechnology(researchTree, climate.requiredTechCode)?.name
              ?? climate.requiredTechCode,
          })}
        </p>
      ) : null}
    </div>
  );
}

/** Строка населения: у незаселённой планеты вместо чисел причина, почему их нет. */
const population = (planet: Planet): ReactNode => {
  if (!planet.colonizable) {
    return t('planet.uninhabitable');
  }
  if (!planet.colony) {
    return tf('planet.unsettled', { n: <Figure accent="value">{planet.maxPopulation}</Figure> });
  }
  return tf('planet.population', {
    n: <Figure accent="value">{planet.population}</Figure>,
    max: <Figure accent="value">{planet.colony.maxPopulation}</Figure>,
  });
};

/** Что на планете построено. Особые проекты — дома и товары — зданиями не становятся. */
const buildings = (planet: Planet): string => {
  const built = planet.colony?.buildings ?? [];
  if (built.length === 0) {
    return planet.colony ? t('planet.noBuildings') : '—';
  }
  return built.map((building) => building.name).join(' · ');
};
