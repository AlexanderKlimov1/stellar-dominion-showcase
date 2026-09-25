import { useMemo, useState } from 'react';

import { gameApi } from '../../api/client';
import type { GalaxyMap, Planet, StarSystem } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { readRevealGalaxy } from '../../state/settings';
import { FleetMiniMap } from '../fleet/FleetMiniMap';
import { useModalEscape } from '../useModalEscape';
import { t, useT, type Key as I18nKey } from '../../i18n';

/** Строка списка: планета вместе со своей системой — без неё не показать ни карту, ни соседей. */
interface PlanetRow {
  system: StarSystem;
  planet: Planet;
}

/**
 * Порядок списка — три ключа сортировки оригинала.
 *
 * Руководство MOO II: «Климат сортирует планеты от самых гостеприимных к самым негодным —
 * от Гайи и земного до ядовитого и радиоактивного. Минералы располагает их от самых
 * production к самым бедным. Размер ставит миры по наибольшему населению, которое каждый
 * может прокормить, от большего к меньшему».
 */
const SORTS: { code: 'climate' | 'minerals' | 'size'; label: I18nKey }[] = [
  { code: 'climate', label: 'planets.sort.climate' },
  { code: 'minerals', label: 'planets.sort.minerals' },
  { code: 'size', label: 'planets.sort.size' },
];

/**
 * Ограничения показа — пять ключей оригинала.
 *
 * «Нормальная тяжесть» оставляет в списке миры обычной тяжести, как ключ Normal Gravity
 * оригинала: тяжесть считается от размера мира (п. 4.1), и непривычная расе роняет
 * производство колонии на четверть или вдвое.
 */
const FILTERS: { code: string; label: I18nKey; hint?: I18nKey }[] = [
  { code: 'noEnemy', label: 'planets.filter.noEnemy' },
  { code: 'gravity', label: 'planets.filter.gravity', hint: 'planets.filter.gravity.hint' },
  { code: 'friendly', label: 'planets.filter.friendly' },
  { code: 'rich', label: 'planets.filter.rich' },
  { code: 'range', label: 'planets.filter.range' },
];

/** Климат от лучшего к худшему — тот же порядок, что и цепочка терраформирования (п. 4.1.2). */
const CLIMATE_ORDER = [
  'GAYA', 'TERRAN', 'OCEAN', 'SWAMP', 'ARID', 'TUNDRA', 'DESERT', 'BARREN', 'RADIATED', 'TOXIC',
];

/** Не хуже пустыни — «Non-Hostile Environment» оригинала. */
const FRIENDLY = new Set(['GAYA', 'TERRAN', 'OCEAN', 'SWAMP', 'ARID', 'TUNDRA', 'DESERT']);

/** Богатые минералами — «Mineral Abundance» оригинала. */
const RICH = new Set(['RICH', 'ULTRA_RICH', 'ABUNDANT']);

/**
 * Экран «Планеты» — п. 4.1 и п. 11.1, окно Planets MOO II во весь экран.
 *
 * Руководство оригинала описывает его так: «кнопка Planets открывает одну из имперских
 * баз данных. Эта следит за каждой пригодной для жизни планетой, какую вы открыли.
 * Большую часть окна занимает сам список планет». И дальше по сегментам: у строки —
 * название, чужое присутствие в системе в скобках, особенности системы, климат с едой на
 * фермера, тяжесть, минералы с выработкой на рабочего и размер с вместимостью; наведение
 * подсвечивает планету на мини-карте в правом верхнем углу; под картой три ключа порядка,
 * под ними ключи ограничений, а в правом нижнем углу две кнопки — послать колониальный
 * корабль и корабль-заставу.
 *
 * Отсюда и разметка: слева во всю высоту список со своей прокруткой, справа узкой
 * колонкой мини-карта, порядок, ограничения и кнопки отправки. Размеры — реконструкция
 * по пропорциям: пиксельных размеров окна справочники не публикуют.
 *
 * <b>Показываются только открытые планеты</b> — п. 15: в списке то, что видел флот, а не
 * вся галактика. Отладочный флаг «Показать галактику» в окне «Инфо» открывает карту
 * целиком, и тогда список наполняется вместе с ней.
 */
export function PlanetsScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const players = useGameStore((state) => state.players);
  const fleet = useGameStore((state) => state.fleet);
  const setMap = useGameStore((state) => state.setMap);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const espionage = useGameStore((state) => state.espionage);

  const [sort, setSort] = useState<'climate' | 'minerals' | 'size'>('climate');
  const [filters, setFilters] = useState<Record<string, boolean>>({});
  const [chosen, setChosen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useModalEscape(true, onClose);

  const reachable = useMemo(
    () => new Set(fleet?.reachableSystemIds ?? []),
    [fleet],
  );

  /** Цвет владельца: колонии и заставы в оригинале показаны цветом своей империи. */
  const colourOf = (ownerId?: string) =>
    players.find((player) => player.id === ownerId)?.color ?? '#94a3b8';

  const rows = useMemo(() => collect(map), [map]);

  const shown = useMemo(() => {
    const own = credentials?.playerId;
    return rows
      .filter(({ system, planet }) => {
        if (filters.noEnemy && enemyPresence(system, planet, own)) {
          return false;
        }
        if (filters.gravity && planet.gravity !== 'NORMAL') {
          return false;
        }
        if (filters.friendly && !FRIENDLY.has(planet.climate)) {
          return false;
        }
        if (filters.rich && !RICH.has(planet.minerals)) {
          return false;
        }
        if (filters.range && !reachable.has(system.id)) {
          return false;
        }
        return true;
      })
      .sort((first, second) => compare(sort, first, second));
  }, [rows, filters, sort, reachable, credentials]);

  const chosenRow = shown.find(({ planet }) => planet.id === chosen) ?? null;

  /** Корабли империи, которыми есть чем занять список: колониальные и заставы. */
  const civil = (role: 'COLONY' | 'OUTPOST') =>
    (fleet?.fleets ?? []).filter(
      (group) =>
        !group.targetSystemId
        && group.composition.some((ship) => ship.role === role && ship.ships > 0),
    );

  /**
   * Послать корабль к выбранной планете — две кнопки правого нижнего угла оригинала.
   *
   * Руководство: «если у вас нет хотя бы одного корабля этого вида, не произойдёт ничего;
   * если есть — щелчком по строке планеты в пределах дальности вы отправляете корабль к
   * этому миру». Здесь так же: стоящий в той же системе высаживается сразу, остальные
   * получают приказ лететь — и высадятся, когда придут.
   */
  useT();
  const send = (role: 'COLONY' | 'OUTPOST') => {
    if (!game || !credentials || !chosenRow) {
      return;
    }
    const ready = civil(role);
    if (ready.length === 0) {
      setError(role === 'COLONY' ? t('planets.noColonyShips') : t('planets.noOutpostShips'));
      return;
    }
    const here = ready.find((group) => group.starSystemId === chosenRow.system.id);
    const group = here ?? ready[0];
    if (!here && !reachable.has(chosenRow.system.id)) {
      setError(t('planets.outOfRange'));
      return;
    }

    setBusy(true);
    setError(null);
    setNotice(null);

    const done = (message: string) => {
      setNotice(message);
      return Promise.all([
        gameApi.getFleet(game.id, credentials.accessToken),
        gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
      ]).then(([fresh, galaxy]) => {
        setEmpire(espionage, fresh);
        setMap(galaxy);
      });
    };

    const action = here
      ? role === 'COLONY'
        ? gameApi
            .colonizeWithFleet(game.id, group.id, credentials.accessToken, chosenRow.planet.id)
            .then(() => done(t('planets.colonized', { name: chosenRow.planet.name })))
        : gameApi
            .outpostWithFleet(game.id, group.id, credentials.accessToken, chosenRow.planet.id)
            .then(() => done(t('planets.outposted', { name: chosenRow.planet.name })))
      : gameApi
          .moveFleet(game.id, group.id, credentials.accessToken, chosenRow.system.id)
          .then(() =>
            done(t('planets.shipSent', { name: chosenRow.system.name ?? t('colonies.unnamedSystem') })),
          );

    action
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 text-ink">
      <header className="flex flex-none items-baseline justify-between border-b border-space-700 px-4 py-2">
        <h2 className="text-20 uppercase tracking-[0.3em] text-ink-bright">{t('planets.title')}</h2>
        <span className="text-11 text-ink-faint">
          {t('planets.shown', { shown: shown.length, total: rows.length })}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* Список планет — большая часть окна, со своей прокруткой. */}
        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          {rows.length === 0 ? (
            <p className="max-w-xl text-xs leading-relaxed text-ink-dim">
              {t('planets.none')}
            </p>
          ) : (
            <table className="w-full text-11">
              <thead>
                <tr className="text-left text-ink-faint">
                  <th className="pb-1 font-normal">{t('planets.col.planet')}</th>
                  <th className="pb-1 font-normal">{t('planets.col.climate')}</th>
                  <th className="pb-1 font-normal">{t('planets.col.gravity')}</th>
                  <th className="pb-1 font-normal">{t('planets.col.minerals')}</th>
                  <th className="pb-1 font-normal">{t('planets.col.size')}</th>
                  <th className="pb-1 font-normal">{t('planets.col.capacity')}</th>
                </tr>
              </thead>
              <tbody>
                {shown.map(({ system, planet }) => {
                  const enemy = enemyPresence(system, planet, credentials?.playerId);
                  return (
                    <tr
                      key={planet.id}
                      onPointerEnter={() => setChosen(planet.id)}
                      onPointerDown={() => setChosen(planet.id)}
                      className={`cursor-pointer border-t border-space-800 ${
                        planet.id === chosen ? 'bg-space-900' : ''
                      }`}
                    >
                      <td className="py-1">
                        <span style={{ color: colourOf(planet.ownerPlayerId) }}>
                          {planet.name}
                        </span>
                        {enemy ? <span className="text-ink-dim"> ({enemy})</span> : null}
                        {system.special ? (
                          <span className="ml-2 text-warn">{t('planets.special')}</span>
                        ) : null}
                        {/* Находка — п. 4.1: ради неё в этот список и заглядывают, когда
                            выбирают, куда послать колониальный корабль. */}
                        {planet.findLabel ? (
                          <span className="ml-2 text-accent">{planet.findLabel}</span>
                        ) : null}
                      </td>
                      <td className="py-1 text-ink-soft">
                        {planet.climateLabel}
                        <span className="text-ink-faint">{t('planets.food', { n: planet.foodPerFarmer })}</span>
                      </td>
                      <td className="py-1 text-ink-soft">
                        {planet.gravityLabel}
                        {/*
                          Цена тяжести стоит рядом с самой тяжестью — как в оригинале, где
                          под «Low G» написано «-25% prod» (docs/moo2/planets.png). Список
                          читают, выбирая цель колониального корабля, и последствие должно
                          быть видно там же, где значение.
                        */}
                        {planet.gravityPenaltyPercent !== 0 ? (
                          <span className="text-warn">
                            {t('planets.gravityCost', { n: planet.gravityPenaltyPercent })}
                          </span>
                        ) : null}
                      </td>
                      <td className="py-1 text-ink-soft">
                        {planet.mineralsLabel}
                        <span className="text-ink-faint">{t('planets.prod', { n: planet.productionPerWorker })}</span>
                      </td>
                      <td className="py-1 text-ink-soft">{planet.sizeLabel}</td>
                      <td className="py-1 text-ink-soft">
                        {planet.maxPopulation}
                        {planet.population > 0 ? (
                          <span className="text-ink-faint">{t('planets.colonists', { n: planet.population })}</span>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* Правая колонка оригинала: мини-карта, порядок, ограничения, отправка. */}
        <aside className="flex w-[19rem] flex-none flex-col border-l border-space-700 bg-space-900/60">
          <div className="flex h-[12rem] flex-none flex-col border-b border-space-700 p-2">
            <FleetMiniMap
              map={map}
              fleet={null}
              turn={game?.turn ?? 1}
              target={chosenRow?.system.id ?? null}
              reachable={reachable}
              onPick={() => undefined}
            />
          </div>

          <section className="flex-none border-b border-space-700 px-3 py-2">
            <div className="panel-title mb-1">{t('planets.order')}</div>
            <div className="flex flex-wrap gap-2">
              {SORTS.map((entry) => (
                <Key key={entry.code} active={sort === entry.code} onClick={() => setSort(entry.code)}>
                  {t(entry.label)}
                </Key>
              ))}
            </div>
          </section>

          <section className="min-h-0 flex-1 overflow-y-auto border-b border-space-700 px-3 py-2">
            <div className="panel-title mb-1">{t('planets.filters')}</div>
            <div className="flex flex-wrap gap-2">
              {FILTERS.map((entry) => (
                <Key
                  key={entry.code}
                  active={Boolean(filters[entry.code])}
                  title={entry.hint ? t(entry.hint) : undefined}
                  onClick={() =>
                    setFilters((current) => ({ ...current, [entry.code]: !current[entry.code] }))
                  }
                >
                  {t(entry.label)}
                </Key>
              ))}
            </div>
          </section>

          <section className="flex-none space-y-1 px-3 py-2">
            <Action
              disabled={busy || !chosenRow || civil('COLONY').length === 0}
              onClick={() => send('COLONY')}
            >
              {t('planets.colonyShip')}
            </Action>
            <Action
              disabled={busy || !chosenRow || civil('OUTPOST').length === 0}
              onClick={() => send('OUTPOST')}
            >
              {t('planets.outpostShip')}
            </Action>
            <Action onClick={onClose}>{t('planets.back')}</Action>
          </section>

          <p className="flex-none px-3 pb-2 text-11 leading-tight">
            {error ? (
              <span className="text-danger">{error}</span>
            ) : notice ? (
              <span className="text-accent">{notice}</span>
            ) : (
              <span className="text-ink-faint">
                {chosenRow
                  ? t('planets.chosen', { planet: chosenRow.planet.name, system: chosenRow.system.name ?? t('colonies.unnamedSystem') })
                  : t('planets.hoverHint')}
              </span>
            )}
          </p>
        </aside>
      </div>
    </div>
  );
}

/** Пригодные для жизни планеты открытых систем — то, что и есть база данных оригинала. */
function collect(map: GalaxyMap | null): PlanetRow[] {
  return (map?.systems ?? [])
    .filter((system) => system.explored)
    .flatMap((system) =>
      system.planets
        .filter((planet) => planet.colonizable)
        .map((planet) => ({ system, planet })),
    );
}

/**
 * Чужое присутствие в системе — то, что в оригинале стоит в скобках у названия.
 *
 * Считается по тому, что игрок знает: чужие колонии открытой системы и отметка сканеров
 * о присутствии в неразведанной (п. 15). Чужих флотов игра игроку не показывает.
 */
function enemyPresence(system: StarSystem, planet: Planet, own?: string): string | null {
  const strangers = system.planets.filter(
    (other) => other.ownerPlayerId && other.ownerPlayerId !== own,
  ).length;
  if (strangers > 0) {
    return planet.ownerPlayerId && planet.ownerPlayerId !== own
      ? t('planets.foreignColony')
      : t('planets.foreignColonies', { n: strangers });
  }
  return system.occupied ? t('planets.scannerPresence') : null;
}

/** Порядок списка: климат, минералы или размер — три ключа оригинала. */
function compare(sort: string, first: PlanetRow, second: PlanetRow): number {
  if (sort === 'minerals') {
    return second.planet.productionPerWorker - first.planet.productionPerWorker;
  }
  if (sort === 'size') {
    return second.planet.maxPopulation - first.planet.maxPopulation;
  }
  return (
    CLIMATE_ORDER.indexOf(first.planet.climate) - CLIMATE_ORDER.indexOf(second.planet.climate)
  );
}

/** Ключ порядка или ограничения: включённый светится, как в оригинале. */
function Key({
  children,
  active,
  disabled = false,
  title,
  onClick,
}: {
  children: string;
  active: boolean;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      onClick={onClick}
      className={`border px-2 py-0.5 text-11 uppercase tracking-wider disabled:opacity-40 ${
        active
          ? 'border-accent bg-space-800 text-accent'
          : 'border-space-700 bg-space-950 text-ink-soft hover:text-ink-bright'
      }`}
    >
      {children}
    </button>
  );
}

function Action({
  children,
  disabled = false,
  onClick,
}: {
  children: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="block w-full border border-space-700 bg-space-950 px-3 py-1 text-11 uppercase tracking-wider text-ink-soft hover:text-accent disabled:opacity-40"
    >
      {children}
    </button>
  );
}
