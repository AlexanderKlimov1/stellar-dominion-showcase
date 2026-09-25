import type { Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { t, useT } from '../../i18n';

/**
 * Карточка выбранной планеты — п. 4.1, тем же сегментом, что и в окне «Star System»
 * MOO II: прямоугольник в левом верхнем углу поля, поверх звёзд и орбит.
 *
 * В оригинале в нём ровно четыре строки — название, размер с плодородностью, население
 * и минералы, — а через пустую строку перечислены постройки. Здесь то же самое: карточка
 * только называет планету, подробности колонии живут на её экране (`ColonyScreen`).
 *
 * Отдельной таблицы планет в оригинале нет и здесь не осталось: выбранную планету
 * называет эта карточка, а перебираются планеты кликом по схеме.
 */
export function PlanetInfo({ planet }: { planet: Planet | null }) {
  const players = useGameStore((state) => state.players);
  const climates = useGameStore((state) => state.planetClimates);
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
    <div className="pointer-events-none absolute left-3 top-3 w-[32%] border border-space-700 bg-space-950/85 px-2 py-1.5 text-11 leading-snug">
      <p className={planet.homeworld ? 'text-accent' : 'text-ink-bright'}>
        {planet.name}
        {owner ? (
          <span style={{ color: owner.color }}> ({owner.name})</span>
        ) : null}
      </p>

      <p className="text-ink-soft">
        {planet.sizeLabel}, {planet.climateLabel}
        {planet.colonizable ? ` ${planet.populationMultiplierPercent}%` : null}
      </p>

      {/* Тяжесть — п. 4.1: непривычная расе роняет производство колонии на четверть
          или вдвое, и знать о ней надо до высадки, а не после. */}
      <p className="text-ink-soft">{planet.gravityLabel}</p>

      {/* Находка — п. 4.1: она решает, стоит ли планета колонии, поэтому стоит выше
          населения и выделена цветом. Обыкновенной планете строки нет вовсе. */}
      {planet.findLabel ? (
        <p className="text-accent">{t('planet.find')}: {planet.findLabel}</p>
      ) : null}

      <p className="text-ink-soft">{population(planet)}</p>

      <p className="text-ink-soft">
        {t('planet.perWorker', { minerals: planet.mineralsLabel, n: planet.productionPerWorker })}
        {planet.colonizable ? t('planet.perFarmer', { n: planet.foodPerFarmer }) : null}
      </p>

      {/* Пустая строка и список построек — как в оригинале под данными планеты. */}
      <p className="mt-2 text-ink-dim">{buildings(planet)}</p>

      {nextClimate && climate?.requiredTechCode ? (
        <p className="text-ink-faint">
          {t('planet.terraforming', { climate: nextClimate.label, tech: climate.requiredTechCode })}
        </p>
      ) : null}
    </div>
  );
}

/** Строка населения: у незаселённой планеты вместо чисел причина, почему их нет. */
const population = (planet: Planet): string => {
  if (!planet.colonizable) {
    return t('planet.uninhabitable');
  }
  if (!planet.colony) {
    return t('planet.unsettled', { n: planet.maxPopulation });
  }
  return t('planet.population', { n: planet.population, max: planet.colony.maxPopulation });
};

/** Что на планете построено. Особые проекты — дома и товары — зданиями не становятся. */
const buildings = (planet: Planet): string => {
  const built = planet.colony?.buildings ?? [];
  if (built.length === 0) {
    return planet.colony ? t('planet.noBuildings') : '—';
  }
  return built.map((building) => building.name).join(' · ');
};
