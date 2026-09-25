import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Battle, BattleShip, DemoBattle } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { climateColor } from '../system/planetVisuals';
import { BattleBar } from './BattleBar';
import { BattleField } from './BattleField';
import { BattleOptions, readBattleOptions, type BattleOptionsState } from './BattleOptions';
import { BattleScanDialog } from './BattleScanDialog';
import type { Shot } from './ShotEffect';
import { currentShip, sideColor } from './battleVisuals';
import { t, useT } from '../../i18n';

/**
 * Сцена тактического боя — п. 8, бой MOO II (`docs/moo2/battle.png`).
 *
 * <b>Разметка — оригинала: поле во весь экран, под ним одна полоса.</b> В полосе пять
 * сегментов (`BattleBar`): карточка корабля, чей ход, его оружие вкладками, кнопки,
 * ящик систем и мини-карта. Прежде вокруг поля стояли четыре ряда служебного текста —
 * заголовок, очередь хода, силы сторон и журнал во всю правую треть; всё это полоса
 * заменила собой, а поле выросло вдвое.
 *
 * <b>Ходят корабли, а не игроки.</b> Очередь построена по инициативе и идёт сквозь обе
 * стороны; чей сейчас ход, видно по карточке и по кольцу на поле. Свой корабль ходит по
 * клику: подсвеченная клетка — перелёт, чужой корабль в пределах залпа — цель. Правый
 * щелчок по любому кораблю открывает его осмотр — `SCAN` оригинала.
 *
 * Корабли ИИ ходят на сервере сразу, как очередь доходит до них, и приходят в ответе
 * событиями. Пока очередь у корабля другого человека, сцена ждёт и переспрашивает
 * сервер: живого события «твой ход» у боя ещё нет, и это единственное место, где клиент
 * опрашивает сервер сам.
 *
 * <b>Журнал боя — наша строка, а не оригинала</b>: в MOO II урон виден числами на поле, а
 * у нас бой считает сервер и присылает события словами. Журнал ушёл в угол поля и
 * гасится в настройках.
 *
 * <b>Демонстрация</b> (п. 8) показывается этой же сценой: в ней обе стороны ведёт сервер,
 * и экран только просит следующий шаг. Управления в ней нет вовсе — смотреть, а не
 * играть, — поэтому кнопки хода в полосе гаснут.
 */
export function BattleScreen({
  battle: initial,
  demo,
  onClose,
}: {
  battle: Battle;
  /** Демонстрационный бой: идёт сам, без игрока. */
  demo?: DemoBattle;
  onClose: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const setActiveBattle = useGameStore((state) => state.setActiveBattle);
  const map = useGameStore((state) => state.map);

  const [battle, setBattle] = useState<Battle>(initial);
  const [log, setLog] = useState<string[]>([]);
  const [debris, setDebris] = useState<
    { id: string; x: number; y: number; hullSize: number; color: string }[]
  >([]);
  const [shots, setShots] = useState<Shot[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  /** Корабль, которого осматривают, — окно `SCAN`; пусто — окна нет. */
  const [scanned, setScanned] = useState<BattleShip | null>(null);
  const [optionsOpen, setOptionsOpen] = useState(false);
  const [options, setOptions] = useState<BattleOptionsState>(readBattleOptions);

  // Esc гасится, пока открыто окно поверх сцены: окно и сцена рождаются в разных
  // отрисовках, и полагаться на очередь useModalEscape нельзя — одно нажатие закрыло бы
  // оба разом (те же грабли, что на экране исследований).
  useModalEscape(scanned === null && !optionsOpen, onClose);
  useT();

  const ship = currentShip(battle);
  // В демонстрации своих кораблей нет: ходит сервер, а игрок смотрит.
  const mine = !demo && ship !== null && ship.yours && battle.state === 'IN_PROGRESS';

  /**
   * Планета, за которую идёт бой, — п. 11: в оригинале она стоит на самом поле
   * (`docs/moo2/battle-planet.png`). Берётся из карты: бой знает свою систему, а чья там
   * колония и какого она климата — знает карта. У демонстрации карты нет вовсе, и планеты
   * на её поле не бывает.
   */
  const planet = useMemo(() => {
    const system = map?.systems.find((candidate) => candidate.id === battle.starSystemId);
    const colony = system?.planets?.find((candidate) => candidate.ownerPlayerId);
    return colony ? { name: colony.name, color: climateColor(colony.climate) } : null;
  }, [map, battle.starSystemId]);

  /** Погибшие в этой пачке событий взрываются, а их строки уходят в журнал. */
  const applyEvents = useCallback((next: Battle) => {
    setBattle(next);
    // Демонстрация живёт сама по себе: партии за ней нет, и хранилищу о ней знать нечего.
    if (!demo) {
      setActiveBattle(next.state === 'IN_PROGRESS' ? next : null);
    }
    if (next.events.length === 0) {
      return;
    }
    setLog((lines) => [...next.events.map((event) => event.text), ...lines].slice(0, 60));

    /*
      Залпы летят по полю: из события известно, кто стрелял, по кому, чем и попал ли.
      Залпы одной пачки расходятся во времени (задержка по номеру), иначе ход целой
      эскадры ИИ вспыхнул бы разом и понять, кто в кого стрелял, было бы нельзя. Шаг
      задержки меняет настройка «быстрые залпы» — `FAST ANIMATIONS` оригинала.
    */
    const fired = next.events.filter((event) => event.type === 'FIRE');
    if (fired.length > 0) {
      setShots((current) => [
        ...current,
        ...fired.flatMap((event, index) => {
          const from = next.ships.find((candidate) => candidate.id === event.shipId);
          const to = next.ships.find((candidate) => candidate.id === event.targetShipId);
          if (!from || !to) {
            return [];
          }
          return [
            {
              id: `${event.shipId}-${event.targetShipId}-${Date.now()}-${index}`,
              kind: event.weaponKind ?? 'PROJECTILE',
              fromX: from.x,
              fromY: from.y,
              toX: to.x,
              toY: to.y,
              hit: (event.damage ?? 0) > 0,
              color: sideColor(from),
              delayMs: index * (optionsRef.current.fast ? 70 : 160),
            },
          ];
        }),
      ]);
    }

    const killed = next.events.filter((event) => event.type === 'DESTROYED');
    if (killed.length > 0) {
      setDebris((current) => [
        ...current,
        ...killed.map((event) => {
          const dead = next.ships.find((candidate) => candidate.id === event.shipId);
          return {
            // Один корабль гибнет один раз, но ключ должен пережить повтор события.
            id: `${event.shipId}-${Date.now()}-${Math.random()}`,
            x: event.x ?? dead?.x ?? 0,
            y: event.y ?? dead?.y ?? 0,
            hullSize: dead?.hullSize ?? 1,
            color: dead ? sideColor(dead) : '#f87171',
          };
        }),
      ]);
    }
  }, [setActiveBattle, demo]);

  /*
    Настройки читаются внутри applyEvents, а он замкнут на своё окружение: ссылка держит
    их свежими, не пересобирая обработчик на каждую правку галочки.
  */
  const optionsRef = useRef(options);
  optionsRef.current = options;

  /*
    Демонстрация идёт сама: экран просит у сервера шаг за шагом и показывает, что
    случилось. Шаг раз в 700 мс — чтобы ходы читались глазами, а бой не тянулся.
  */
  useEffect(() => {
    if (!demo || battle.state !== 'IN_PROGRESS') {
      return;
    }
    const timer = window.setTimeout(() => {
      gameApi.reference
        .demoBattleStep(battle.id)
        .then(applyEvents)
        .catch((failure: Error) => setError(failure.message));
    }, options.fast ? 350 : 700);
    return () => window.clearTimeout(timer);
  }, [demo, battle, applyEvents, options.fast]);

  const act = useCallback(
    (payload: Parameters<typeof gameApi.battleAction>[3]) => {
      if (!game || !credentials || busy) {
        return;
      }
      setBusy(true);
      setError(null);
      gameApi
        .battleAction(game.id, battle.id, credentials.accessToken, payload)
        .then((next) => {
          applyEvents(next);
          setTarget(null);
        })
        .catch((failure: Error) => setError(failure.message))
        .finally(() => setBusy(false));
    },
    [game, credentials, busy, battle.id, applyEvents],
  );

  // Пока ход у соперника-человека, поле переспрашивается: событий боя сервер пока не
  // рассылает, а стоять с устаревшим полем хуже, чем спросить раз в две секунды.
  const waiting = battle.state === 'IN_PROGRESS' && !mine;
  const waitingRef = useRef(waiting);
  waitingRef.current = waiting;

  useEffect(() => {
    if (!waiting || !game || !credentials) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!waitingRef.current) {
        return;
      }
      gameApi
        .getBattle(game.id, battle.id, credentials.accessToken)
        .then((next) => {
          if (next.currentShipId !== battle.currentShipId || next.state !== battle.state) {
            applyEvents(next);
          }
        })
        .catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [waiting, game, credentials, battle.id, battle.currentShipId, battle.state, applyEvents]);

  const fire = (candidate: BattleShip) => {
    setTarget(candidate.id);
    act({ shipId: ship?.id ?? '', action: 'FIRE', targetShipId: candidate.id });
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-950">
      {/* Поле во весь экран над полосой — как в оригинале: рамки и заголовка у него нет. */}
      <div className="relative min-h-0 flex-1">
        <BattleField
          battle={battle}
          selectedTargetId={target}
          debris={debris}
          shots={shots}
          grid={options.grid}
          showMoves={options.moves}
          planet={planet}
          onMove={(x, y) => act({ shipId: ship?.id ?? '', action: 'MOVE', x, y })}
          onFire={fire}
          onScan={setScanned}
          onDebrisDone={(id) => setDebris((current) => current.filter((piece) => piece.id !== id))}
          onShotDone={(id) => setShots((current) => current.filter((shot) => shot.id !== id))}
        />

        {/*
          Журнал боя в углу поля: что сделал чужой залп, из самого поля не всегда видно —
          бой считает сервер и присылает событие словами. Гасится в настройках.
        */}
        {options.log ? (
          <div className="pointer-events-none absolute left-2 top-2 max-h-[40%] w-[min(20rem,34%)] overflow-hidden bg-space-950/70 p-1 text-11 leading-snug">
            <p className="text-ink-dim">
              {t('battle.title', { system: battle.systemName })} ·{' '}
              {t('battle.round', {
                round: battle.round,
                attacker: battle.attackerName,
                defender: battle.defenderName,
              })}
            </p>
            {demo ? (
              <p className="text-ink-faint">
                {t('battle.advantage', {
                  n: Math.round((demo.rightPower / Math.max(1, demo.leftPower) - 1) * 100),
                  side: demo.rightName,
                })}
              </p>
            ) : null}
            {log.length === 0 ? (
              <p className="text-ink-faint">
                {demo
                  ? t('battle.hint.demo', { n: battle.weaponRange })
                  : t('battle.hint', { n: battle.weaponRange })}
              </p>
            ) : (
              log.slice(0, 5).map((line, index) => (
                <p key={index} className={index === 0 ? 'text-ink-soft' : 'text-ink-faint'}>
                  {line}
                </p>
              ))
            )}
          </div>
        ) : null}

        {error ? (
          <p className="absolute bottom-2 left-2 text-12 text-danger">{error}</p>
        ) : null}

        {/* Бой кончился — исход посередине поля, как окно итога в оригинале. */}
        {battle.state !== 'IN_PROGRESS' ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="panel max-w-[min(32rem,90vw)] p-4 text-center text-14">
              <p className="mb-3 text-accent">{battle.stateLabel}</p>
              <p className="mb-4 text-ink">{battle.outcome}</p>
              <button
                type="button"
                className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
                onClick={onClose}
              >
                {t('common.close')}
              </button>
            </div>
          </div>
        ) : null}
      </div>

      <BattleBar
        battle={battle}
        ship={ship}
        mine={mine}
        busy={busy}
        demo={demo !== undefined}
        onPass={() => act({ shipId: ship?.id ?? '', action: 'PASS' })}
        onRetreat={() => act({ shipId: ship?.id ?? '', action: 'RETREAT' })}
        onSelfDestruct={() => act({ shipId: ship?.id ?? '', action: 'SELF_DESTRUCT' })}
        onScan={() => setScanned(ship)}
        onOptions={() => setOptionsOpen(true)}
        onClose={onClose}
      />

      {scanned ? (
        <BattleScanDialog ship={scanned} onClose={() => setScanned(null)} />
      ) : null}

      {optionsOpen ? (
        <BattleOptions
          options={options}
          onChange={setOptions}
          onClose={() => setOptionsOpen(false)}
        />
      ) : null}
    </div>
  );
}
