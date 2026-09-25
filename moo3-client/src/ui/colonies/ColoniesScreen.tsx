import { useState } from 'react';
import { gameApi } from '../../api/client';
import type { ColonyProject, Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { Colonist } from '../system/colonyIcons';
import { TransferDialog } from '../system/TransferDialog';
import { useModalEscape } from '../useModalEscape';
import { ColoniesQueueDialog } from './ColoniesQueueDialog';
import { COLUMNS, ColonyRow } from './ColonyRow';
import { usePopulationDrag } from './usePopulationDrag';
import { t, useT } from '../../i18n';

/**
 * Колонии империи — экран нижнего меню, окном Colonies MOO II (п. 4.1.1, п. 10, п. 11.1).
 *
 * Экран занимает всё окно, как и в оригинале: колоний у империи бывает под десяток, и
 * каждая занимает строку в семь блоков — колония, её жители, еда, производство, наука,
 * доход и стройка. Шапка списка и строки берут ширины столбцов из одного места
 * (`COLUMNS`), иначе они разъезжаются на первой же правке.
 *
 * Два действия, ради которых в оригинале и открывают это окно:
 *
 * * **перевозка жителей** — житель перетаскивается из одной строки в другую, и рейс
 *   уходит грузовым флотом (п. 4.1.1). Грузовик резервируется на каждого жителя, поэтому
 *   свободные грузовики стоят в шапке: без них перевозки не будет, и узнавать об этом из
 *   ошибки сервера поздно. Нажатие на жителя открывает тот же рейс диалогом — так можно
 *   отправить сразу нескольких и так до жителей добираются с клавиатуры;
 * * **переход к колонии** — нажатие на название открывает экран управления планетой.
 *   Он ложится поверх списка: закрыл колонию — вернулся к списку, как в оригинале.
 *
 * Своих правил у экрана нет: он показывает то, что уже пришло в карте (колония приходит
 * с каждой планетой целиком), и просит сервер о перевозке. Считает всё сервер.
 */

/**
 * Ключи порядка списка — как полоса SORT в консоли колоний оригинала
 * (`docs/moo2/colonies.png`: Name, Population, Food, Industry, Science, Producing, BC).
 *
 * Своей полосы мы не заводим: ключом служит сам ЗАГОЛОВОК столбца, по нему и нажимают.
 * Числа идут по убыванию (сверху лучшие), имена — по алфавиту: у порядка по величине
 * смысл «покажи, где густо», а у имени — «найди вот эту колонию».
 */
type ColonyOrder = 'name' | 'colonists' | 'food' | 'production' | 'research' | 'income' | 'build';

type ColonyEntry = { planet: Planet; systemId: string; systemName: string };

function compare(order: ColonyOrder) {
  const byName = (left: ColonyEntry, right: ColonyEntry) =>
    left.systemName.localeCompare(right.systemName) || left.planet.orbit - right.planet.orbit;
  if (order === 'name') {
    return byName;
  }
  if (order === 'build') {
    return (left: ColonyEntry, right: ColonyEntry) =>
      (left.planet.colony?.projectName ?? '').localeCompare(right.planet.colony?.projectName ?? '')
      || byName(left, right);
  }
  const value = (entry: ColonyEntry) => {
    const colony = entry.planet.colony;
    if (!colony) {
      return 0;
    }
    switch (order) {
      case 'colonists':
        return entry.planet.population;
      case 'food':
        return colony.food;
      case 'production':
        return colony.production;
      case 'research':
        return colony.research;
      default:
        return colony.income;
    }
  };
  return (left: ColonyEntry, right: ColonyEntry) =>
    value(right) - value(left) || byName(left, right);
}

export function ColoniesScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const self = useGameStore(selectSelf);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setLobby = useGameStore((state) => state.setLobby);
  const setMap = useGameStore((state) => state.setMap);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const openedColonyId = useGameStore((state) => state.openedColonyId);

  const [transferFrom, setTransferFrom] = useState<Planet | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /*
    Набор колоний — п. 10: пометки живут в самом экране и уходят вместе с ним. Хранить
    их дольше незачем: набирают их ради одного приказа, а список колоний меняется каждый
    ход — сохранённый набор пришлось бы чистить от потерянных колоний.
  */
  const [selected, setSelected] = useState<string[]>([]);
  /** Последняя помеченная строка: от неё Shift набирает кусок списка. */
  const [anchor, setAnchor] = useState<string | null>(null);
  const [queueing, setQueueing] = useState(false);

  /*
    Пока поверх открыт экран колонии или диалог перевозки, Esc список не слушает вовсе.
    Диалог рождается в той же отрисовке, что и экран, и в очереди оказался бы ниже —
    одно нажатие закрыло бы оба разом. На этом уже обжигались на экране исследований.
  */
  useModalEscape(
    openedColonyId === null && transferFrom === null && !queueing,
    onClose,
  );
  const [order, setOrder] = useState<ColonyOrder>('name');
  useT();

  // Колонии берутся из карты: колония приходит с каждой планетой целиком, и своего
  // запроса этому экрану не нужно. Порядок — по системе и орбите, как на карте.
  const colonies = (map?.systems ?? [])
    .flatMap((system) =>
      system.planets
        .filter((planet) => planet.ownerPlayerId === self?.id && planet.colony)
        .map((planet) => ({
          planet,
          systemId: system.id,
          systemName: system.name ?? t('colonies.unnamedSystem'),
        })),
    )
    .sort(compare(order));

  /** Помечены все колонии списка: по этому и переключается пометка в шапке. */
  const allSelected = colonies.length > 0 && selected.length >= colonies.length;

  const freighters = self?.freighters ?? 0;
  const free = Math.max(0, freighters - (self?.freightersReserved ?? 0));

  /**
   * Отправка одного жителя — п. 4.1.1.
   *
   * <b>Внутри своей системы житель переходит сразу и без грузовиков</b> — правило
   * оригинала; между системами он сходит с колонии сейчас, а прибывает в конце хода, и
   * рейс держит по грузовику на жителя.
   *
   * Нехватку грузовиков и последнего жителя проверяем и здесь: сервер их тоже не
   * пропустит, но игрок должен узнать об этом от того места, где тянул фигурку.
   */
  const send = (fromPlanetId: string, toPlanetId: string) => {
    const fromEntry = colonies.find((entry) => entry.planet.id === fromPlanetId);
    const toEntry = colonies.find((entry) => entry.planet.id === toPlanetId);
    const from = fromEntry?.planet;
    const to = toEntry?.planet;
    if (!game || !credentials || !from || !to || busy) {
      return;
    }
    if (from.population <= 1) {
      setError(t('colonies.lastColonist', { name: from.name }));
      return;
    }
    const sameSystem = fromEntry?.systemId === toEntry?.systemId;
    if (!sameSystem && free <= 0) {
      setError(t('colonies.noFreighters'));
      return;
    }

    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .transferPopulation(game.id, from.id, credentials.accessToken, to.id, 1)
      .then(async (updated) => {
        updatePlanet(updated);
        if (sameSystem) {
          // Внутри системы изменились обе колонии сразу, а ответ приходит один: карту
          // берём свежей, иначе колония назначения осталась бы с прежним населением.
          setMap(await gameApi.getMap(game.id, credentials.accessToken));
          setNotice(t('colonies.movedSameSystem', { from: from.name, to: to.name }));
          return;
        }
        // Рейс занял грузовик — это состояние игрока, а не планеты, и клиент его сам
        // не пересчитывает: забираем состав партии свежим.
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setNotice(t('colonies.movedTrip', { from: from.name, to: to.name }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /**
   * Пометить колонию или кусок списка — п. 10.
   *
   * Простое нажатие переключает одну, нажатие с Shift берёт всё от прошлой помеченной
   * строки до этой: колоний у империи бывает под десяток, и отмечать их по одной ради
   * общего приказа — работа впустую.
   */
  const toggle = (planetId: string, range: boolean) => {
    setNotice(null);
    setError(null);
    const order = colonies.map((entry) => entry.planet.id);
    if (range && anchor !== null && anchor !== planetId) {
      const from = order.indexOf(anchor);
      const to = order.indexOf(planetId);
      if (from >= 0 && to >= 0) {
        const piece = order.slice(Math.min(from, to), Math.max(from, to) + 1);
        setSelected((current) => [...new Set([...current, ...piece])]);
        setAnchor(planetId);
        return;
      }
    }
    setAnchor(planetId);
    setSelected((current) =>
      current.includes(planetId)
        ? current.filter((id) => id !== planetId)
        : [...current, planetId],
    );
  };

  // Набор чистится от того, чего в списке уже нет: колонию можно потерять, а пометка
  // пережила бы её и ушла бы в приказ несуществующей планетой.
  const selectedPlanets = colonies
    .map((entry) => entry.planet)
    .filter((planet) => selected.includes(planet.id));

  /**
   * Один проект всем выделенным колониям — п. 10.
   *
   * Проект встаёт первым в очередь каждой из них, сдвигая её вниз: приказ отсюда —
   * про всю империю разом, и ждать за тем, что каждая колония успела набрать, он не
   * может. Отвечает сервер всеми изменёнными колониями, и каждая кладётся в карту:
   * у них поменялась не только очередь, но и сама стройка.
   */
  const enqueueAll = (project: ColonyProject) => {
    if (!game || !credentials || selectedPlanets.length === 0 || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .enqueueProjects(
        game.id,
        selectedPlanets.map((planet) => planet.id),
        credentials.accessToken,
        project.code,
      )
      .then((updated) => {
        updated.forEach(updatePlanet);
        setQueueing(false);
        setNotice(t('colonies.queued', { project: project.name, n: updated.length }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  const { drag, hover, start, register, justDropped } = usePopulationDrag(send);
  const color = self?.color ?? '#7fd4ff';

  const totals = colonies.reduce(
    (sum, { planet }) => ({
      population: sum.population + planet.population,
      food: sum.food + (planet.colony?.foodBalance ?? 0) + (planet.colony?.foodDelivered ?? 0),
      production: sum.production + (planet.colony?.production ?? 0),
      research: sum.research + (planet.colony?.research ?? 0),
      income: sum.income + (planet.colony?.income ?? 0),
    }),
    { population: 0, food: 0, production: 0, research: 0, income: 0 },
  );

  return (
    // Экран во всё окно: в MOO II список колоний — полноэкранный вид, а не окошко.
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 p-4">
      <div className="mb-2 flex flex-none items-baseline justify-between gap-6 border-b border-space-700 pb-2">
        <h2 className="text-28 uppercase tracking-[0.3em] text-ink-bright">{t('colonies.title')}</h2>
        <p className="text-19 text-ink-faint">
          {t('colonies.count')} <span className="text-ink">{colonies.length}</span> {t('colonies.colonists')}{' '}
          <span className="text-ink">{totals.population}</span> {t('colonies.freighters')}{' '}
          <span className={free === 0 ? 'text-danger' : 'text-ink'}>{free}</span> {t('colonies.of')}{' '}
          {freighters}
        </p>
      </div>

      {/*
        Шапка списка стоит внутри прокрутки, а не над ней: строка шире узкого окна,
        и при боковой прокрутке подписи уехали бы от своих столбцов. По вертикали она
        остаётся на месте (sticky) — колоний бывает больше, чем влезает в экран.
      */}
      {/*
        Прокрутка со списком не выделяется мышью (select-none): жителя тащат по строкам,
        и браузер вместо переноса выделял бы текст от одной колонии до другой.
      */}
      <div className="min-h-0 flex-1 select-none overflow-auto">
        <div
          className={`${COLUMNS} sticky top-0 z-10 border-b border-space-700 bg-space-950 pb-1 text-15 uppercase tracking-[0.2em] text-ink-faint`}
        >
          {/*
            Пометка в шапке берёт или отпускает весь список разом — им и начинают, когда
            приказ касается всей империи («выделить все колонии и заложить автолабораторию»).
          */}
          <button
            type="button"
            aria-pressed={allSelected}
            aria-label={allSelected ? t('colonies.deselectAll.aria') : t('colonies.selectAll.aria')}
            title={allSelected ? t('colonies.deselectAll') : t('colonies.selectAll')}
            className={
              'flex h-5 w-5 items-center justify-center border text-14 leading-none '
              + (allSelected
                ? 'border-accent bg-accent/20 text-accent'
                : 'border-space-700 text-transparent hover:border-space-600')
            }
            onClick={() =>
              setSelected(allSelected ? [] : colonies.map((entry) => entry.planet.id))
            }
          >
            ✔
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.colony') })}
            aria-pressed={order === 'name'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'name' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('name')}
          >
            {t('colonies.col.colony')}{order === 'name' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.colonists') })}
            aria-pressed={order === 'colonists'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'colonists' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('colonists')}
          >
            {t('colonies.col.colonists')}{order === 'colonists' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.food') })}
            aria-pressed={order === 'food'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'food' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('food')}
          >
            {t('colonies.col.food')}{order === 'food' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.production') })}
            aria-pressed={order === 'production'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'production' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('production')}
          >
            {t('colonies.col.production')}{order === 'production' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.research') })}
            aria-pressed={order === 'research'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'research' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('research')}
          >
            {t('colonies.col.research')}{order === 'research' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.income') })}
            aria-pressed={order === 'income'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'income' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('income')}
          >
            {t('colonies.col.income')}{order === 'income' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.build') })}
            aria-pressed={order === 'build'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'build' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('build')}
          >
            {t('colonies.col.build')}{order === 'build' ? ' ▾' : ''}
          </button>
        </div>

        {colonies.length === 0 ? (
          <p className="px-2 py-3 text-19 text-ink-faint">
            {t('colonies.none')}
          </p>
        ) : (
          colonies.map(({ planet, systemName }) => (
            <ColonyRow
              key={planet.id}
              planet={planet}
              systemName={systemName}
              color={color}
              hovered={hover === planet.id}
              source={drag?.from === planet.id}
              selected={selected.includes(planet.id)}
              onSelect={(range) => toggle(planet.id, range)}
              rowRef={register(planet.id)}
              onStartDrag={start(planet.id)}
              onOpen={() => setOpenedColony(planet.id)}
              onTransfer={() => {
                // Клик сразу за переносом — тот самый, которым браузер сопровождает
                // отпускание указателя: рейс уже отправлен.
                if (!justDropped()) {
                  setTransferFrom(planet);
                }
              }}
            />
          ))
        )}
      </div>

      {/*
        Полоса приказа появляется только с набором — п. 10: пока никто не помечен,
        показывать её нечему, и место в экране она не занимает.
      */}
      {selectedPlanets.length > 0 ? (
        <div className="mt-2 flex flex-none flex-wrap items-baseline gap-x-5 gap-y-1 border-t border-space-700 pt-2 text-19">
          <span className="text-ink-faint">
            {t('colonies.selected')} <span className="text-ink">{selectedPlanets.length}</span> {t('colonies.of')}{' '}
            {colonies.length}
          </span>
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={busy}
            onClick={() => setQueueing(true)}
          >
            {t('colonies.enqueueAll')}
          </button>
          <button type="button" className="link" onClick={() => setSelected([])}>
            {t('colonies.clearSelection')}
          </button>
        </div>
      ) : null}

      <div className="mt-2 flex-none border-t border-space-700 pt-2 text-17">
        <div className="flex items-baseline justify-between gap-6">
          <p className="text-ink-faint">
            {t('colonies.totals.food')}{' '}
            <span className={totals.food < 0 ? 'text-danger' : 'text-ink'}>
              {totals.food >= 0 ? `+${totals.food}` : totals.food}
            </span>{' '}
            {t('colonies.totals.production')} <span className="text-ink">{totals.production}</span> {t('colonies.totals.research')}{' '}
            <span className="text-ink">{totals.research}</span> {t('colonies.totals.income')}{' '}
            <span className={totals.income < 0 ? 'text-danger' : 'text-ink'}>
              {totals.income >= 0 ? `+${totals.income}` : totals.income}
            </span>{' '}
            {t('colonies.totals.perTurn')}
          </p>

          <button type="button" className="link shrink-0 text-19" onClick={onClose}>
            {t('common.closeEsc')}
          </button>
        </div>

        {/*
          Подсказка, ошибка и вести о рейсе — одна строка под итогами, во всю ширину:
          рядом с итогами её обрезало на узком окне, а именно её и читают после переноса.
        */}
        <p className="mt-1 truncate text-16">
          {error ? (
            <span className="text-danger">{error}</span>
          ) : notice ? (
            <span className="text-good">{notice}</span>
          ) : (
            <span className="text-ink-faint">
              {t('colonies.hint')}
            </span>
          )}
        </p>
      </div>

      {/* Приказ стройки набору колоний — п. 10: список общего им всем. */}
      {queueing ? (
        <ColoniesQueueDialog
          planets={selectedPlanets}
          saving={busy}
          error={error}
          onEnqueue={enqueueAll}
          onClose={() => setQueueing(false)}
        />
      ) : null}

      {/* Рейс на несколько жителей — тот же диалог, что и на экране колонии (п. 4.1.1). */}
      {transferFrom ? (
        <TransferDialog from={transferFrom} onClose={() => setTransferFrom(null)} />
      ) : null}

      {/* Житель, которого несут: летит за курсором и не перехватывает события. */}
      {drag ? (
        <div className="pointer-events-none fixed z-50" style={{ left: drag.x - 6, top: drag.y - 8 }}>
          <Colonist color={color} className="h-[17px] w-[12px]" />
        </div>
      ) : null}
    </div>
  );
}
