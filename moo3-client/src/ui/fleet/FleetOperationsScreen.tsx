import { useEffect, useMemo, useState } from 'react';
import { gameApi } from '../../api/client';
import { readRevealGalaxy } from '../../state/settings';
import { useGameStore } from '../../state/gameStore';
import { ShipDesignScreen } from '../ship/ShipDesignScreen';
import { useModalEscape } from '../useModalEscape';
import { FleetLandingDialog } from './FleetLandingDialog';
import { FleetMiniMap } from './FleetMiniMap';
import { FleetShipCard } from './FleetShipCard';
import { FleetShipGrid } from './FleetShipGrid';
import { shipSlots, toOrders, visibleSlots } from './fleetShips';
import { t, useT } from '../../i18n';

/**
 * Флот империи — экран нижнего меню окном Fleet Operations MOO II (п. 8, п. 11.1).
 *
 * Разметка снята с оригинала и делит экран пополам:
 *
 * * **слева сверху** — карта галактики с курсом выбранного флота. Отсюда же назначают
 *   цель: нажатие по звезде выбирает её, а уводит корабли кнопка «перебросить»;
 * * **слева посередине** — переключатель флотов стрелками, как в оригинале: флотов у
 *   империи бывает много, и листаются они по одному, а не выбираются из списка;
 * * **слева снизу** — карточка корабля: щит, залп, защита, оружие и особые модули;
 * * **справа** — сетка кораблей флота по четыре в ряд, каждый корабль своей клеткой;
 * * **справа снизу** — «все», «перебросить», «списать», а ниже переключатели боевых и
 *   вспомогательных кораблей и выход: полосы кнопок оригинала.
 *
 * **Списание** — то, ради чего в MOO II на этот экран и заходят: другого места, где
 * корабль убирают из строя, в игре нет. Возврата за списанное не полагается ни там, ни
 * здесь.
 *
 * Переброска — тот же приказ, что и с карты, только адресуется отобранным кораблям: флот
 * не обязан лететь целиком (`FleetService.detach`). Флоту в пути приказ отдать нельзя,
 * пока империя не изучит Hyperspace Communications, — кнопка тогда погашена.
 *
 * Отступление от оригинала одно и вынужденное: кнопка «проекты кораблей». В MOO II к
 * проектам приходят из стройки колонии, а здесь окно дизайна больше ниоткуда не открыть.
 * Кнопка «лидеры» стоит на своём месте оригинала, но погашена: лидеров игра пока не знает
 * (`PendingScreen` в главном списке экранов).
 */
export function FleetOperationsScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const fleet = useGameStore((state) => state.fleet);
  const map = useGameStore((state) => state.map);
  const espionage = useGameStore((state) => state.espionage);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const setEncounters = useGameStore((state) => state.setEncounters);
  const setMap = useGameStore((state) => state.setMap);
  const setOverlay = useGameStore((state) => state.setOverlay);

  const groups = useMemo(() => fleet?.fleets ?? [], [fleet]);

  const [current, setCurrent] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [focused, setFocused] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [filters, setFilters] = useState({ combat: true, support: true });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [designsOpen, setDesignsOpen] = useState(false);
  const [landingOpen, setLandingOpen] = useState(false);

  // Пока поверх открыто окно дизайна, экран Esc не слушает вовсе: окно и экран рождаются
  // в разных отрисовках одного компонента, и порядок в очереди оказался бы обратным —
  // одно нажатие закрыло бы оба разом.
  useModalEscape(!designsOpen && !landingOpen, onClose);
  useT();

  const group = groups[Math.min(current, Math.max(0, groups.length - 1))] ?? null;

  /*
    Отбор и цель принадлежат флоту, а не экрану: пролистав к соседнему флоту, игрок
    видит его корабли, и отбор, снятый с прежнего, к ним не относится. Сброс привязан к
    идентификатору флота, а не к объекту: объект пересоздаётся при каждом обновлении
    флотов с сервера, и по объекту отбор слетал бы после первой же переброски.
  */
  useEffect(() => {
    setSelected(new Set());
    setFocused(null);
    setTarget(null);
  }, [group?.id]);

  const slots = useMemo(() => shipSlots(group), [group]);
  const shown = useMemo(() => visibleSlots(slots, filters), [slots, filters]);
  const focusedShip = slots.find((slot) => slot.key === focused)?.ship
    ?? shown[0]?.ship
    ?? null;

  const reachable = useMemo(
    () => new Set(fleet?.reachableSystemIds ?? []),
    [fleet],
  );

  const inFlight = Boolean(group?.targetSystemId);
  // Высаживаться можно только там, где флот стоит: система берётся с карты, а окно
  // высадки открывается лишь у флота с гражданскими кораблями — п. 4.1, п. 8, п. 12.
  const here = map?.systems.find((system) => system.id === group?.starSystemId) ?? null;
  const canLand =
    !busy
    && !inFlight
    && here !== null
    && (group?.composition.some((ship) => ship.role !== 'WARSHIP' && ship.ships > 0) ?? false);
  const canRedirect = fleet?.canRedirect ?? false;
  // Флот в пути слушается приказа только с Hyperspace Communications — п. 8.
  const canRelocate =
    !busy
    && selected.size > 0
    && target !== null
    && reachable.has(target)
    && target !== group?.starSystemId
    && (!inFlight || canRedirect);

  const toggle = (key: string) =>
    setSelected((current) => {
      const next = new Set(current);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });

  /**
   * Обновляет флоты и встречи: переброска могла свести два флота в одной системе.
   *
   * Отбор при этом снимается, даже когда флот остался тот же. Клетка помечена проектом и
   * номером, а после ухода или списания части кораблей под тем же номером стоит уже другой
   * корабль: сохранённый отбор указывал бы не на то, что игрок отбирал.
   */
  const refresh = async () => {
    if (!game || !credentials) {
      return;
    }
    const [fresh, encounters] = await Promise.all([
      gameApi.getFleet(game.id, credentials.accessToken),
      gameApi.getEncounters(game.id, credentials.accessToken),
    ]);
    setEmpire(espionage, fresh);
    setEncounters(encounters);
    setSelected(new Set());
  };

  const relocate = () => {
    if (!game || !credentials || !group || target === null) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .moveFleet(game.id, group.id, credentials.accessToken, target, toOrders(slots, selected))
      .then(refresh)
      .then(() => setNotice(t('fleetOps.orderGiven')))
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  const scrap = () => {
    if (!game || !credentials || !group) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    const count = selected.size;
    gameApi
      .scrapFleetShips(game.id, group.id, credentials.accessToken, toOrders(slots, selected))
      .then(refresh)
      .then(() => setNotice(t('fleetOps.scrapped', { n: count })))
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    // Экран во всё окно: в MOO II флот — полноэкранный вид, а не окошко.
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 p-4">
      <div className="mb-2 flex flex-none items-center justify-between gap-6 border-b border-space-700 pb-2">
        <h2 className="border border-space-600 bg-space-800 px-6 py-1 text-18 uppercase tracking-[0.3em] text-ink-bright">
          {t('fleetOps.title')}
        </h2>
        <p className="text-14 text-ink-faint">
          {t('fleetOps.fleets')} <span className="text-ink">{groups.length}</span> {t('fleetOps.ships')}{' '}
          <span className="text-ink">
            {groups.reduce((total, item) => total + item.ships, 0)}
          </span>{' '}
          {t('fleetOps.range')} <span className="text-ink">{fleet?.rangeParsecs ?? 0}</span> {t('fleetOps.pc')}
          {/* Командные очки — п. 8: пока флот в запас укладывается, он ничего не стоит,
              а за перебор казна платит каждый ход. Перебор поэтому и подсвечен. */}
          {t('fleetOps.command')}
          <span
            className={
              (fleet?.commandUsed ?? 0) > (fleet?.commandCapacity ?? 0)
                ? 'text-danger'
                : 'text-ink'
            }
            title={t('fleetOps.command.title')}
          >
            {t('fleetOps.command.of', { used: fleet?.commandUsed ?? 0, capacity: fleet?.commandCapacity ?? 0 })}
          </span>
        </p>
      </div>

      <div className="flex min-h-0 flex-1 gap-3">
        {/* Левая половина оригинала: карта, переключатель флотов, карточка корабля. */}
        <div className="flex min-h-0 flex-1 flex-col gap-2">
          <FleetMiniMap
            map={map}
            fleet={group}
            turn={game?.turn ?? 1}
            target={target}
            reachable={reachable}
            onPick={setTarget}
          />

          {/* Стрелки листают флоты по одному — полоса под картой в оригинале. */}
          <div className="flex flex-none items-stretch gap-2">
            <ArrowButton
              disabled={groups.length < 2}
              onClick={() => setCurrent((index) => (index - 1 + groups.length) % groups.length)}
            >
              ◀
            </ArrowButton>
            <span className="flex flex-1 items-center justify-center border border-space-700 bg-space-900 px-3 py-1 text-12 text-ink">
              {group
                ? inFlight
                  ? t('fleetOps.inFlight', { target: group.targetSystemName ?? t('fleetOps.unexploredStar'), turn: group.arrivalTurn ?? 0 })
                  : t('fleetOps.group', { system: group.systemName, ships: group.ships, power: group.power })
                : t('fleetOps.noFleets')}
            </span>
            <ArrowButton
              disabled={groups.length < 2}
              onClick={() => setCurrent((index) => (index + 1) % groups.length)}
            >
              ▶
            </ArrowButton>
          </div>

          <FleetShipCard ship={focusedShip} fleet={group} />
        </div>

        {/*
          Правая половина: сетка кораблей и две полосы кнопок под ней.

          Ширина у неё своя, а не половина экрана: в оригинале окно 4:3 и половины равны,
          а здесь экран широкий, и половина растянула бы клетки сетки в огромные квадраты.
          Сетка остаётся компактным блоком оригинала, а лишняя ширина достаётся карте —
          именно на ней игрок и работает.
        */}
        <div className="flex min-h-0 w-[30rem] flex-none flex-col gap-2">
          {groups.length === 0 ? (
            <div className="flex min-h-0 flex-1 items-center justify-center border border-space-700 bg-space-950 p-4 text-center text-11 text-ink-dim">
              {t('fleetOps.noShips')}
            </div>
          ) : (
            <FleetShipGrid
              slots={shown}
              selected={selected}
              onToggle={toggle}
              onFocus={setFocused}
              focused={focused}
            />
          )}

          <div className="flex flex-none gap-2">
            <BarButton
              disabled={shown.length === 0}
              onClick={() => setSelected(new Set(shown.map((slot) => slot.key)))}
            >
              {t('fleetOps.all')}
            </BarButton>
            <BarButton disabled={!canRelocate} onClick={relocate}>
              {t('fleetOps.relocate')}
            </BarButton>
            <BarButton disabled={busy || selected.size === 0} onClick={scrap}>
              {t('fleetOps.scrap')}
            </BarButton>
            <BarButton disabled={!canLand} onClick={() => setLandingOpen(true)}>
              {t('fleetOps.land')}
            </BarButton>
          </div>

          <div className="flex flex-none gap-2">
            {/*
              Кнопка оригинала, и теперь она работает: лидеры в игре есть (п. 6), а
              корабельного назначают во флот — то есть отсюда к ним и ходят. Экран флота
              при этом закрывается: оба открываются поверх карты, и держать их стопкой
              незачем.
            */}
            <BarButton
              onClick={() => {
                onClose();
                setOverlay('leaders');
              }}
            >
              {t('fleetOps.leaders')}
            </BarButton>
            {/* Support и Combat оригинала: вспомогательные корабли и боевые. */}
            <ToggleButton
              active={filters.support}
              onClick={() => setFilters((now) => ({ ...now, support: !now.support }))}
            >
              {t('fleetOps.support')}
            </ToggleButton>
            <ToggleButton
              active={filters.combat}
              onClick={() => setFilters((now) => ({ ...now, combat: !now.combat }))}
            >
              {t('fleetOps.combat')}
            </ToggleButton>
            <BarButton onClick={onClose}>{t('common.close')}</BarButton>
          </div>

          <p className="flex-none text-11 leading-tight">
            {error ? (
              <span className="text-danger">{error}</span>
            ) : notice ? (
              <span className="text-ink-soft">{notice}</span>
            ) : (
              <span className="text-ink-faint">
                {t('fleetOps.selected', { n: selected.size })}
                {target
                  ? t('fleetOps.target', { name: map?.systems.find((system) => system.id === target)?.name
                      ?? t('fleetOps.unexploredStar') }) + (reachable.has(target) ? '' : t('fleetOps.outOfRange'))
                  : t('fleetOps.pickTarget')}
                {inFlight && !canRedirect
                  ? t('fleetOps.noRedirect')
                  : ''}
              </span>
            )}
          </p>

          <div className="flex flex-none gap-2">
            <BarButton onClick={() => setDesignsOpen(true)}>{t('fleetOps.designs')}</BarButton>
          </div>
        </div>
      </div>

      {designsOpen ? <ShipDesignScreen onClose={() => setDesignsOpen(false)} /> : null}

      {landingOpen && group && here && game && credentials ? (
        <FleetLandingDialog
          fleet={group}
          system={here}
          gameId={game.id}
          accessToken={credentials.accessToken}
          ownerPlayerId={credentials.playerId}
          telepathic={fleet?.telepathic ?? false}
          onClose={() => setLandingOpen(false)}
          onDone={(message) => {
            // Высадка меняет и флот, и карту: колония или застава появляется на планете,
            // а корабль уходит из состава. Обновляем оба — соседний экран иначе остался бы
            // с прошлым ходом.
            setLandingOpen(false);
            setNotice(message);
            void refresh();
            void gameApi
              .getMap(game.id, credentials.accessToken, readRevealGalaxy())
              .then(setMap)
              .catch(() => undefined);
          }}
        />
      ) : null}
    </div>
  );
}

/** Стрелка переключения флотов: узкая кнопка полосы под картой. */
function ArrowButton({
  children,
  disabled,
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
      className="border border-space-700 px-3 text-12 text-ink hover:border-space-500 hover:text-accent disabled:cursor-not-allowed disabled:border-space-800 disabled:text-ink-off"
    >
      {children}
    </button>
  );
}

/** Кнопка нижних полос: рамка и разрядка — тот же вид, что у кнопок оригинала. */
function BarButton({
  children,
  disabled,
  title,
  onClick,
}: {
  children: string;
  disabled?: boolean;
  title?: string;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      onClick={onClick}
      className="flex-1 border border-space-600 px-3 py-1 text-11 uppercase tracking-[0.2em] text-ink hover:bg-space-700 hover:text-accent disabled:cursor-not-allowed disabled:border-space-800 disabled:bg-transparent disabled:text-ink-off"
    >
      {children}
    </button>
  );
}

/** Переключатель нижней полосы: включённый горит, как Support и Combat оригинала. */
function ToggleButton({
  children,
  active,
  onClick,
}: {
  children: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex-1 border px-3 py-1 text-11 uppercase tracking-[0.2em] ${
        active
          ? 'border-accent bg-space-800 text-accent'
          : 'border-space-700 text-ink-faint hover:border-space-500'
      }`}
    >
      {children}
    </button>
  );
}
