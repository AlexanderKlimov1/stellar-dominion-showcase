import { useEffect, useMemo, useState } from 'react';
import type { Council, CouncilVoter } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Выборы Высшего совета — п. 3, зал совета MOO II (`docs/moo2/council.png`).
 *
 * В оригинале это не строка отчёта, а маленькая сцена: зал, по стенам портреты рас, а
 * поверх картины три надписи — кандидаты со своим счётом по краям, общее число голосов по
 * центру и внизу тот голос, который объявляют сейчас («The Alkaris abstain (5 vote)»).
 * Голоса идут ПО ОДНОМУ, и в этом весь смысл сцены: игрок видит, кто его поддержал, кто
 * воздержался и чьим голосом решилось дело.
 *
 * Здесь так же. Портретов у игры нет, поэтому империи показаны кругами своего цвета с
 * именем — теми же, какими они подписаны на карте; зал набран рамкой и сводом из линий.
 *
 * <b>Голоса объявляются сами, раз в секунду с небольшим</b>, а кнопка «огласить всё»
 * показывает итог сразу: смотреть выборы на восьмой раз игрок не обязан.
 *
 * Сцена ничего не решает <i>о голосовании</i>: оно посчитано сервером на ходу выборов и
 * записано строками (`council_vote`). Открытая второй раз, она расскажет ровно то же
 * самое.
 * <p>
 * <b>Одно решение здесь всё же есть — подчиниться или нет</b> (п. 3). Проигравший вправе
 * не признать избрания: партия тогда продолжается, а все, кто голосовал за избранного,
 * объявляют отказнику войну. Кнопки стоят под приговором и только у того, кому отказ
 * дозволен, — решает это сервер полем {@code canRefuse}, а не сцена: второго свода правил
 * отказа быть не должно.
 */
export function CouncilScreen({ council, onClose }: { council: Council; onClose: () => void }) {
  /** Сколько голосов уже объявлено: по нему и растут счётчики кандидатов. */
  const [shown, setShown] = useState(0);
  /*
    Выборы держатся в состоянии сцены, а не читаются из свойства: отказ меняет их (партия
    ожила, второго отказа не будет), и ответ сервера ложится сюда же. Голоса при этом
    остаются прежними — отказ не отменяет голосования.
  */
  const [session, setSession] = useState(council);
  const [refusing, setRefusing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const game = useGameStore((store) => store.game);
  const credentials = useGameStore((store) => store.credentials);
  const setLobby = useGameStore((store) => store.setLobby);
  useT();
  useModalEscape(true, onClose);

  const total = council.voters.length;
  const done = shown >= total;

  useEffect(() => {
    if (done) {
      return;
    }
    const timer = window.setTimeout(() => setShown((count) => count + 1), 1100);
    return () => window.clearTimeout(timer);
  }, [shown, done]);

  /** Счёт кандидатов по уже объявленным голосам — он и растёт на глазах. */
  const tally = useMemo(() => {
    const counted = new Map<string, number>();
    council.voters.slice(0, shown).forEach((voter) => {
      if (voter.choicePlayerId) {
        counted.set(voter.choicePlayerId, (counted.get(voter.choicePlayerId) ?? 0) + voter.weight);
      }
    });
    return counted;
  }, [council.voters, shown]);

  /*
    Отказ оживляет партию: у неё меняется состояние (победы больше нет), а у отказника
    появляется война со всеми, кто голосовал за избранного. Поэтому партия перечитывается
    целиком — в правой панели иначе остался бы конец игры.
  */
  const refuse = () => {
    if (!game || !credentials || refusing) {
      return;
    }
    setRefusing(true);
    gameApi
      .refuseCouncil(game.id, credentials.accessToken)
      .then((updated) => {
        setSession(updated);
        return gameApi.getGame(game.id);
      })
      .then((details) => setLobby(details.game, details.players))
      .catch((cause: unknown) =>
        setFailure(cause instanceof GameApiError ? cause.message : t('council.refuseFailed')),
      )
      .finally(() => setRefusing(false));
  };

  const current = shown > 0 ? council.voters[Math.min(shown, total) - 1] : null;
  const nameOf = (playerId?: string) =>
    council.voters.find((voter) => voter.playerId === playerId)?.name ?? '';

  return (
    <div className="fixed inset-0 z-[55] flex flex-col bg-space-950">
      {/* Свод зала: несколько дуг вместо картины — её у игры нет. */}
      <Hall />

      <header className="relative flex flex-none items-start justify-between px-6 py-4 text-14">
        {council.candidates.map((candidate, index) => (
          <span
            key={candidate.playerId}
            className={'w-1/4 ' + (index === 0 ? 'text-left' : 'text-right')}
            style={{ color: candidate.color ?? undefined }}
          >
            <span className="block text-16">{candidate.name}</span>
            <span className="text-ink-bright">
              {t('council.votesOf', { n: tally.get(candidate.playerId) ?? 0 })}
            </span>
          </span>
        ))}
        {/* Между кандидатами — общий счёт галактики, как в оригинале. */}
        <span className="absolute left-1/2 top-4 -translate-x-1/2 text-center">
          <span className="block text-16 text-accent">
            {t('council.total', { n: council.totalVotes })}
          </span>
          <span className="text-12 text-ink-dim">
            {t('council.required', { n: council.requiredVotes, turn: council.turn })}
          </span>
        </span>
      </header>

      {/* Зал: империи кругами по дуге, уже отдавшие голос — в полный цвет. */}
      <div className="relative flex min-h-0 flex-1 flex-wrap items-center justify-center gap-6 px-8">
        {council.voters.map((voter, index) => (
          <Seat
            key={voter.playerId}
            voter={voter}
            voted={index < shown}
            speaking={index === shown - 1}
            choiceName={nameOf(voter.choicePlayerId)}
          />
        ))}
      </div>

      {/* Объявляемый голос — внизу по центру, крупно: главная строка сцены. */}
      <footer className="relative flex flex-none flex-col items-center gap-3 px-6 pb-6 text-center">
        <p className="min-h-[3rem] text-18 text-warn">
          {current
            ? current.choicePlayerId
              ? t('council.votesFor', {
                  name: current.name,
                  candidate: nameOf(current.choicePlayerId),
                  n: current.weight,
                })
              : t('council.abstains', { name: current.name, n: current.weight })
            : t('council.opening')}
        </p>

        {done ? (
          <p className="text-16 text-accent">
            {council.electedName
              ? t('council.elected', { name: council.electedName })
              : t('council.undecided')}
          </p>
        ) : null}

        {/*
          Отказ — п. 3: предлагается только проигравшему и только один раз за партию.
          Отказавшись, игрок читает здесь же, чем это кончилось: война объявлена, а партия
          продолжается с того хода, на котором собрался совет.
        */}
        {done && session.refused ? (
          <p className="max-w-[48rem] text-14 text-danger">{t('council.refusedNotice')}</p>
        ) : null}
        {failure ? <p className="text-13 text-danger">{failure}</p> : null}

        <span className="flex gap-3">
          {done && session.canRefuse ? (
            <>
              <button
                type="button"
                className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
                onClick={onClose}
              >
                {t('council.submit')}
              </button>
              <button
                type="button"
                className="border border-danger bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-danger hover:text-ink-bright"
                disabled={refusing}
                onClick={refuse}
              >
                {t('council.refuse')}
              </button>
            </>
          ) : null}
          {!done ? (
            <button
              type="button"
              className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
              onClick={() => setShown(total)}
            >
              {t('council.revealAll')}
            </button>
          ) : null}
          {/*
            «Закрыть» пропадает, пока предложен отказ: закрыть и подчиниться — одно и то
            же действие, а две одинаковые кнопки под разными именами читаются как выбор
            там, где выбора нет.
          */}
          {done && session.canRefuse ? null : (
            <button
              type="button"
              className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
              onClick={onClose}
            >
              {t('common.close')}
            </button>
          )}
        </span>
      </footer>
    </div>
  );
}

/**
 * Место в зале: круг цвета империи с её именем и весом голоса.
 *
 * Пока голос не объявлен, место пригашено — в оригинале портреты тоже стоят тёмными, а
 * говорящего выделяет надпись внизу. Отдавший голос подписывается тем, за кого он подан.
 */
function Seat({
  voter,
  voted,
  speaking,
  choiceName,
}: {
  voter: CouncilVoter;
  voted: boolean;
  speaking: boolean;
  choiceName: string;
}) {
  const colour = voter.color ?? '#7d8aa3';
  return (
    <span className={'flex w-28 flex-col items-center gap-1 ' + (voted ? '' : 'opacity-40')}>
      <span
        className="flex h-16 w-16 items-center justify-center rounded-full border-2"
        style={{
          borderColor: colour,
          background: `${colour}22`,
          boxShadow: speaking ? `0 0 0 3px ${colour}66` : undefined,
        }}
      >
        <span className="text-16" style={{ color: colour }}>
          {voter.weight}
        </span>
      </span>
      <span className="truncate text-12" style={{ color: colour }}>
        {voter.name}
        {voter.yours ? t('council.you') : ''}
      </span>
      <span className="h-4 truncate text-11 text-ink-dim">
        {voted ? (voter.choicePlayerId ? choiceName : t('council.abstained')) : ''}
      </span>
      {voter.candidate ? (
        <span className="text-10 uppercase tracking-[0.2em] text-accent">
          {t('council.candidate')}
        </span>
      ) : null}
    </span>
  );
}

/** Свод зала: дуги и колонны линиями — место картины, которой у игры нет. */
function Hall() {
  return (
    <svg
      viewBox="0 0 100 60"
      preserveAspectRatio="none"
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="council-floor" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#0b1220" stopOpacity="0.1" />
          <stop offset="100%" stopColor="#1c2440" stopOpacity="0.55" />
        </linearGradient>
      </defs>
      <rect x="0" y="0" width="100" height="60" fill="url(#council-floor)" />
      {[10, 18, 26].map((depth) => (
        <path
          key={depth}
          d={`M -5 ${depth + 22} Q 50 ${depth - 8} 105 ${depth + 22}`}
          fill="none"
          stroke="#1c2440"
          strokeWidth="0.6"
        />
      ))}
      {[12, 28, 50, 72, 88].map((x) => (
        <line key={x} x1={x} y1="6" x2={x} y2="54" stroke="#16203a" strokeWidth="0.5" />
      ))}
    </svg>
  );
}
