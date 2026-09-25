import { useEffect, useState } from 'react';

import { gameApi, GameApiError } from '../../api/client';
import type { Leader, Leaders, StarSystem } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { LeaderEmblem } from './LeaderEmblem';

/**
 * Офицерский резерв империи — п. 6, окно лидеров MOO II.
 *
 * <b>Лидер — наёмник.</b> Он приходит сам, ждёт решения тридцать ходов и уходит, если
 * ответа нет; за наём берёт разовую плату, за службу — жалованье каждый ход. Экран и
 * устроен вокруг этого решения: слева те, кто предлагает службу, справа — МЕСТА, по четыре
 * на род, и заняты они раздельно. Пока места рода заняты, новые лидеры этого рода не
 * приходят вовсе (StrategyWiki, Hiring Leaders), поэтому пустые места видны клетками, а не
 * числом в подписи: игрок должен видеть, куда ещё есть кого брать.
 *
 * Оправа та же, что у окна новой игры и выбора расы: плашка названия сверху, поле клеток,
 * полоса кнопок внизу. Снимка этого окна оригинала у меня нет (на StrategyWiki его нет), а
 * выдумывать чужую разметку по памяти хуже, чем держаться семьи своих окон — она и снята с
 * оригинала.
 *
 * <b>Подробности — полосой под клетками</b>, у того лидера, на которого игрок смотрит.
 * Прежде каждый лидер был карточкой со всеми способностями, сроками и жалованьем разом:
 * экран на двенадцать таких карточек читался списком договоров, а не резервом. Способность,
 * которой в этой игре пока нечему влиять, показана с пояснением: в оригинале она у лидера
 * есть, и прятать её значило бы врать о лидере.
 */
export function LeadersScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const fleet = useGameStore((state) => state.fleet);

  const [leaders, setLeaders] = useState<Leaders | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Лидер, на которого смотрит игрок: его и описывает полоса подробностей. */
  const [looking, setLooking] = useState<string | null>(null);
  /** Лидер, которому выбирают место службы; пусто — никого не назначаем. */
  const [assigning, setAssigning] = useState<Leader | null>(null);

  useModalEscape(assigning === null, onClose);
  useT();

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    let alive = true;
    gameApi
      .getLeaders(game.id, credentials.accessToken)
      .then((loaded) => {
        if (alive) {
          setLeaders(loaded);
        }
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('leaders.loadFailed')),
      );
    return () => {
      alive = false;
    };
  }, [game, credentials]);

  if (!game || !credentials) {
    return null;
  }

  const act = (action: Promise<Leaders>) => {
    setBusy(true);
    setError(null);
    action
      .then((updated) => {
        setLeaders(updated);
        setAssigning(null);
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('leaders.failed')),
      )
      .finally(() => setBusy(false));
  };

  /** Свои системы: колониального лидера ставят в систему, где есть свои колонии. */
  const ownSystems: StarSystem[] = (map?.systems ?? []).filter((system) =>
    system.planets.some((planet) => planet.ownerPlayerId === credentials.playerId),
  );

  const everyone = leaders
    ? [...leaders.offers, ...leaders.colony, ...leaders.ship]
    : [];
  const shown = everyone.find((leader) => leader.id === looking) ?? null;
  const offering = shown !== null && shown.state === 'OFFERED';
  const affordable = shown !== null && leaders !== null && shown.hireCost <= leaders.credits;

  return (
    <div className="absolute inset-0 z-40 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(94vw,64rem)] border border-space-600 bg-space-900/95 p-[2.5%] shadow-lg shadow-black/60">
        {/* Плашка названия по центру и казна справа: наём решается ею. */}
        <div className="relative mb-[3%]">
          <h2 className="mx-auto w-[46%] border border-space-600 bg-space-800 py-1.5 text-center text-18 uppercase tracking-[0.3em] text-ink-bright">
            {t('leaders.title')}
          </h2>
          <span className="absolute right-0 top-1/2 -translate-y-1/2 text-12 text-ink-dim">
            {t('leaders.treasury', { n: leaders?.credits ?? 0 })}{' '}
            <span className={(leaders?.salaryPerTurn ?? 0) > 0 ? 'text-warn' : 'text-ink-soft'}>
              {t('leaders.perTurn', { n: leaders?.salaryPerTurn ?? 0 })}
            </span>
          </span>
        </div>

        {error ? <p className="mb-2 text-13 text-danger">{error}</p> : null}
        {leaders === null ? <p className="text-13 text-ink-dim">{t('leaders.loading')}</p> : null}

        {leaders ? (
          <div className="flex items-start gap-[4%]">
            {/* Предлагают службу — слева, как приходящие со стороны. */}
            <div className="w-[30%]">
              <h3 className="mb-2 text-13 uppercase tracking-[0.2em] text-ink-dim">
                {t('leaders.offers')}
                {leaders.offers.length > 0 ? (
                  <span className="ml-2 text-warn">{leaders.offers.length}</span>
                ) : null}
              </h3>
              {leaders.offers.length === 0 ? (
                <p className="text-12 text-ink-faint">{t('leaders.noOffers')}</p>
              ) : (
                <ul className="grid grid-cols-2 gap-2">
                  {leaders.offers.map((leader) => (
                    <li key={leader.id}>
                      <LeaderCell
                        leader={leader}
                        looking={looking === leader.id}
                        onLook={() => setLooking(leader.id)}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* Места службы — справа, по четыре на род, пустые видны рамками. */}
            <div className="w-[66%]">
              <SlotRow
                title={t('leaders.colony')}
                hired={leaders.colony}
                slots={leaders.colonySlots}
                looking={looking}
                onLook={setLooking}
              />
              <SlotRow
                title={t('leaders.ship')}
                hired={leaders.ship}
                slots={leaders.shipSlots}
                looking={looking}
                onLook={setLooking}
              />
            </div>
          </div>
        ) : null}

        {/*
          Полоса подробностей: тот лидер, на которого смотрит игрок. Высота постоянная —
          иначе клетки прыгали бы под курсором при каждом движении мыши.
        */}
        <div className="mt-[3%] h-[9rem] overflow-auto border border-space-700 bg-space-950/60 p-3 text-13">
          {shown === null ? (
            <p className="text-ink-faint">{t('leaders.lookHint')}</p>
          ) : (
            <LeaderDetails leader={shown} turn={game.turn} />
          )}
        </div>

        {/* Полоса кнопок: возврат слева, действия над выбранным — справа. */}
        <div className="mt-[2.5%] flex items-center justify-between text-13 uppercase tracking-[0.2em]">
          <BarButton onClick={onClose}>{t('leaders.back')}</BarButton>

          <span className="flex items-center gap-3">
            {shown !== null && offering ? (
              <>
                <BarButton
                  disabled={busy || !affordable}
                  title={
                    affordable
                      ? t('leaders.hire.title')
                      : t('leaders.notEnough', { n: shown.hireCost - (leaders?.credits ?? 0) })
                  }
                  onClick={() => act(gameApi.hireLeader(game.id, credentials.accessToken, shown.id))}
                >
                  {t('leaders.hire', { n: shown.hireCost })}
                </BarButton>
                <BarButton
                  disabled={busy}
                  onClick={() =>
                    act(gameApi.dismissLeader(game.id, credentials.accessToken, shown.id))
                  }
                >
                  {t('leaders.refuse')}
                </BarButton>
              </>
            ) : null}

            {shown !== null && !offering ? (
              <>
                <BarButton disabled={busy} onClick={() => setAssigning(shown)}>
                  {t('leaders.assign')}
                </BarButton>
                <BarButton
                  disabled={busy}
                  onClick={() =>
                    act(gameApi.dismissLeader(game.id, credentials.accessToken, shown.id))
                  }
                >
                  {t('leaders.dismiss')}
                </BarButton>
              </>
            ) : null}
          </span>
        </div>
      </section>

      {/*
        Выбор места службы: колониальному — своя система, корабельному — свой флот.
        Окошко поверх экрана, как и всё, что спрашивает у игрока одно решение.
      */}
      {assigning ? (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-black/70 p-8">
          <div className="panel max-h-full w-full max-w-md overflow-auto">
            <div className="panel-title mb-2">
              {t('leaders.assign.title', { name: assigning.name })}
            </div>
            <p className="mb-3 text-12 text-ink-faint">{t('leaders.assign.hint')}</p>

            <div className="flex flex-col gap-1">
              {(assigning.kind === 'COLONY' ? ownSystems : []).map((system) => (
                <button
                  key={system.id}
                  type="button"
                  className="link text-left disabled:opacity-40"
                  disabled={busy}
                  onClick={() =>
                    act(gameApi.assignLeader(
                      game.id, credentials.accessToken, assigning.id, system.id))
                  }
                >
                  ▸ {system.name}
                </button>
              ))}
              {(assigning.kind === 'SHIP' ? (fleet?.fleets ?? []) : []).map((group) => (
                <button
                  key={group.id}
                  type="button"
                  className="link text-left disabled:opacity-40"
                  disabled={busy}
                  onClick={() =>
                    act(gameApi.assignLeader(
                      game.id, credentials.accessToken, assigning.id, group.id))
                  }
                >
                  {t('leaders.fleetShips', { system: group.systemName, n: group.ships })}
                </button>
              ))}
              {assigning.kind === 'COLONY' && ownSystems.length === 0 ? (
                <p className="text-ink-faint">{t('leaders.noSystems')}</p>
              ) : null}
              {assigning.kind === 'SHIP' && (fleet?.fleets ?? []).length === 0 ? (
                <p className="text-ink-faint">{t('leaders.noFleets')}</p>
              ) : null}
            </div>

            <div className="mt-4 flex justify-between">
              <button
                type="button"
                className="link disabled:opacity-40"
                disabled={busy}
                onClick={() =>
                  act(gameApi.assignLeader(game.id, credentials.accessToken, assigning.id, null))
                }
              >
                {t('leaders.toReserve')}
              </button>
              <button type="button" className="link" onClick={() => setAssigning(null)}>
                {t('common.close')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * Ряд мест одного рода: занятые клетками, свободные — пустыми рамками.
 *
 * Пустое место показано нарочно: пока места рода заняты, новые лидеры этого рода не
 * приходят, и «сколько ещё влезет» — это то, ради чего на экран и смотрят.
 */
function SlotRow({
  title,
  hired,
  slots,
  looking,
  onLook,
}: {
  title: string;
  hired: Leader[];
  slots: number;
  looking: string | null;
  onLook: (id: string) => void;
}) {
  const empty = Math.max(0, slots - hired.length);
  return (
    <div className="mb-3">
      <h3 className="mb-2 text-13 uppercase tracking-[0.2em] text-ink-dim">
        {title}
        <span className="ml-2 text-ink-faint">
          {hired.length}/{slots}
        </span>
      </h3>
      <ul className="grid grid-cols-4 gap-2">
        {hired.map((leader) => (
          <li key={leader.id}>
            <LeaderCell
              leader={leader}
              looking={looking === leader.id}
              onLook={() => onLook(leader.id)}
            />
          </li>
        ))}
        {Array.from({ length: empty }, (_, index) => (
          <li key={`empty-${index}`}>
            <span className="block aspect-square w-full border border-dashed border-space-700 bg-space-950/40" />
            <span className="mt-1 block text-center text-11 text-ink-faint">
              {t('leaders.slot.free')}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Клетка лидера: знак и плашка имени под ним — как у клеток расы и настроек новой игры. */
function LeaderCell({
  leader,
  looking,
  onLook,
}: {
  leader: Leader;
  looking: boolean;
  onLook: () => void;
}) {
  return (
    <button
      type="button"
      className="block w-full text-left"
      onClick={onLook}
      onMouseEnter={onLook}
      onFocus={onLook}
    >
      <span
        className={
          'block aspect-square w-full border bg-space-950 p-1 '
          + (looking ? 'border-accent' : 'border-space-600')
        }
      >
        <LeaderEmblem leader={leader} />
      </span>
      <span
        className={
          'mt-1 block truncate border border-space-700 bg-space-800 px-1 py-0.5 text-center text-11 '
          + (looking ? 'text-accent' : 'text-ink-soft')
        }
        title={`${leader.name} — ${leader.title}`}
      >
        {leader.name}
      </span>
    </button>
  );
}

/** Подробности выбранного лидера: кто он, где служит, что умеет и чего стоит. */
function LeaderDetails({ leader, turn }: { leader: Leader; turn: number }) {
  const waiting = leader.state === 'OFFERED';
  const travelling = leader.arrivesTurn !== undefined && leader.arrivesTurn > turn;

  return (
    <>
      <div className="flex items-baseline gap-3">
        <span className="text-accent">{leader.name}</span>
        <span className="text-ink-dim">{leader.title}</span>
        <span className="ml-auto flex-none text-ink-dim">
          {t('leaders.experience', { rank: leader.rank, n: leader.experience })}
        </span>
      </div>

      <div className="mt-1 text-12 text-ink-dim">
        {leader.kindLabel}
        {leader.raceName ? ` · ${leader.raceName}` : ''}
        {waiting ? t('leaders.decisionBy', { n: leader.expiresTurn ?? 0 }) : ''}
        {!waiting && leader.systemName ? t('leaders.system', { name: leader.systemName }) : ''}
        {!waiting && leader.fleetId && !leader.systemName ? t('leaders.inFleet') : ''}
        {!waiting && !leader.systemName && !leader.fleetId ? t('leaders.inReserve') : ''}
        {travelling ? t('leaders.travelling', { n: leader.arrivesTurn ?? 0 }) : ''}
        {!waiting ? t('leaders.salary', { n: leader.salary }) : ''}
      </div>

      <ul className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-12">
        {leader.skills.map((skill) => (
          <li key={skill.ability} className="flex items-baseline gap-1">
            <span className="text-ink">{skill.name}</span>
            <span className="text-accent">
              {skill.unit === 'PERCENT'
                ? `${skill.value > 0 ? '+' : ''}${skill.value}%`
                : skill.unit === 'CREDITS'
                  ? t('common.credits', { n: skill.value })
                  : `${skill.value > 0 ? '+' : ''}${skill.value}`}
            </span>
            <span className="text-ink-faint">
              {skill.works === 'ALWAYS' ? t('leaders.skill.always') : t('leaders.skill.post')}
              {skill.note ? ` · ${skill.note}` : ''}
            </span>
          </li>
        ))}
      </ul>

      {leader.techs.length > 0 ? (
        <p className="mt-1 text-12 text-ink-dim">
          {t('leaders.techs', { list: leader.techs.join(', ') })}
        </p>
      ) : null}
    </>
  );
}

/** Кнопка нижней полосы: тот же вид, что у окон новой игры и сборки расы. */
function BarButton({
  children,
  disabled = false,
  title,
  onClick,
}: {
  children: string;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      className={
        'border px-4 py-1.5 uppercase tracking-widest '
        + (disabled
          ? 'cursor-not-allowed border-space-700 text-ink-off'
          : 'border-space-600 bg-space-800 text-ink hover:text-accent')
      }
      onClick={onClick}
    >
      {children}
    </button>
  );
}
