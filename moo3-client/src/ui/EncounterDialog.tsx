import { useState } from 'react';
import { gameApi } from '../api/client';
import type { Encounter } from '../api/types';
import { useGameStore } from '../state/gameStore';
import { useModalEscape } from './useModalEscape';
import { useT } from '../i18n';

/**
 * Встреча флотов — выбор игрока (п. 8).
 *
 * Флоты двух империй, оказавшиеся в конце хода в одной системе, друг друга видят. Первым
 * выбирает тот, чей флот быстрее: атаковать или разойтись. Разошёлся — выбор переходит
 * второму, и напасть может уже он.
 *
 * «Авто» считает бой одной формулой на сервере и показывает готовый итог. Снятая
 * галочка ведёт в тактическую сцену (п. 8): корабли ходят там по инициативе, и исход
 * решает поле, а не формула.
 *
 * Закрыть диалог, ничего не выбрав, можно: встреча так и будет ждать решения, и её снова
 * предложат — здесь же или в итогах следующего хода. Это не отказ, а отсрочка.
 */
export function EncounterDialog({ encounter }: { encounter: Encounter }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const setEncounters = useGameStore((state) => state.setEncounters);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const setBattle = useGameStore((state) => state.setBattle);
  const setActiveBattle = useGameStore((state) => state.setActiveBattle);
  const espionage = useGameStore((state) => state.espionage);

  const [auto, setAuto] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [postponed, setPostponed] = useState(false);

  useModalEscape(!postponed, () => setPostponed(true));

  if (postponed) {
    return null;
  }

  const decide = (decision: 'ATTACK' | 'IGNORE') => {
    if (!game || !credentials) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .decideEncounter(game.id, encounter.id, credentials.accessToken, decision, auto)
      .then(async (result) => {
        // Бой сразу показывается сценой: это её место, даже пока она заглушка. Сцена
        // живёт в хранилище, а не здесь: решённая встреча уходит из списка, и диалог
        // вместе с ней исчезает — сцену было бы некому держать.
        // Ручной бой открывает поле: сервер вернул встречу со ссылкой на бой.
        if (decision === 'ATTACK' && !auto && result.battleId && game && credentials) {
          const opened = await gameApi.getBattle(game.id, result.battleId, credentials.accessToken);
          setActiveBattle(opened);
        } else if (decision === 'ATTACK') {
          setBattle({
            encounter: result,
            // Состав до боя: в ответе он уже с потерями, а сцене нужно, кто с чем сходился.
            before: { yours: encounter.yourShips, theirs: encounter.opponentShips },
          });
        }

        // Бой меняет и флоты, и оставшиеся встречи — перечитываем и то и другое.
        const [encounters, fleet] = await Promise.all([
          gameApi.getEncounters(game.id, credentials.accessToken),
          gameApi.getFleet(game.id, credentials.accessToken),
        ]);
        setEncounters(encounters);
        setEmpire(espionage, fleet);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  const { t } = useT();
  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex w-full max-w-xl flex-col">
        <h2 className="border-b border-space-700 pb-2 text-sm uppercase tracking-[0.3em] text-ink-bright">
          {t('encounter.title', { system: encounter.systemName })}
        </h2>

        <p className="mt-3 text-11 leading-relaxed text-ink-soft">
          {t('encounter.text', { system: encounter.systemName })}{' '}
          <span className="text-ink-bright">{encounter.opponentName}</span>.{' '}
          {encounter.firstMover
            ? t('encounter.firstMover')
            : t('encounter.secondMover')}
        </p>

        <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-1 text-11">
          <Line label={t('encounter.yourFleet')} value={t('encounter.ships', { n: encounter.yourShips })} />
          <Line label={t('encounter.theirFleet')} value={t('encounter.ships', { n: encounter.opponentShips })} />
          <Line label={t('encounter.yourInitiative')} value={`${encounter.yourInitiative}`} />
          <Line label={t('encounter.theirInitiative')} value={`${encounter.opponentInitiative}`} />
        </dl>

        <label className="mt-3 flex items-center gap-2 text-11 text-ink-soft">
          <input
            type="checkbox"
            checked={auto}
            onChange={(event) => setAuto(event.target.checked)}
          />
          {t('encounter.auto')}
        </label>

        {error ? <p className="mt-2 text-11 text-danger">{error}</p> : null}

        <div className="mt-4 flex items-center justify-between border-t border-space-700 pt-2 text-xs">
          <button type="button" className="link" onClick={() => setPostponed(true)}>
            {t('encounter.postpone')}
          </button>
          <span className="flex gap-5">
            <button type="button" className="link" disabled={busy} onClick={() => decide('IGNORE')}>
              {t('encounter.ignore')}
            </button>
            <button
              type="button"
              className="link text-accent"
              disabled={busy}
              onClick={() => decide('ATTACK')}
            >
              {t('encounter.attack')}
            </button>
          </span>
        </div>
      </div>
    </div>
  );
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-ink-faint">{label}</dt>
      <dd className="text-ink-bright">{value}</dd>
    </>
  );
}
