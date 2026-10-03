import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Leader } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { LeaderEmblem } from '../leaders/LeaderEmblem';
import { t, useT } from '../../i18n';

/**
 * Губернатор системы на экране колонии — п. 6.
 *
 * В оригинале портрет лидера стоит в правом нижнем углу экрана колонии
 * (`docs/moo2/colony.png`), и это не украшение: колониальный лидер служит В СИСТЕМЕ и
 * помогает всем её колониям разом, поэтому глядеть на него удобнее всего там, где смотрят
 * на саму колонию. У нас этого места не было вовсе — лидеры жили только на своём экране.
 *
 * Портрета взять неоткуда, поэтому здесь тот же знак, что и в резерве: монограмма имени со
 * значком рода службы ({@link LeaderEmblem}).
 *
 * **Пустое место показывается тоже.** Молчание читалось бы как «лидеров в игре нет», а
 * правда другая: место есть, губернатора на него не назначили. Назначают по-прежнему на
 * экране лидеров — второго пути к назначению не заводим, чтобы правило жило в одном месте.
 *
 * Список лидеров спрашивается СВОИМ запросом при открытии колонии: в карте его нет, а
 * держать лидеров в хранилище ради одной строки незачем — открытие колонии и так идёт с
 * запросом состояния.
 */
export function ColonyLeader({ systemId, turn }: { systemId: string; turn: number }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [leader, setLeader] = useState<Leader | null>(null);
  useT();

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    let alive = true;
    gameApi
      .getLeaders(game.id, credentials.accessToken)
      .then((leaders) => {
        if (alive) {
          setLeader(leaders.colony.find((one) => one.systemId === systemId) ?? null);
        }
      })
      // Молча: лидер — украшение экрана колонии, и отказ в его загрузке не повод
      // тревожить игрока посреди управления колонией.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [game, credentials, systemId]);

  const travelling = leader?.arrivesTurn !== undefined && leader.arrivesTurn > turn;

  return (
    <section className="flex flex-none items-center gap-3 border border-space-700 bg-space-950/60 p-2">
      <span
        className={
          'block h-14 w-14 flex-none border bg-space-950 p-1 '
          + (leader ? 'border-space-600' : 'border-dashed border-space-700')
        }
      >
        {leader ? <LeaderEmblem leader={leader} /> : null}
      </span>

      <span className="min-w-0 flex-1 text-13">
        {leader ? (
          <>
            <span className="block truncate text-accent">{leader.name}</span>
            <span className="block truncate text-12 text-ink-dim">{leader.title}</span>
            <span className="block truncate text-11 text-ink-faint">
              {travelling
                ? t('leaders.travelling', { n: leader.arrivesTurn ?? 0 }).replace(/^[\s·]+/, '')
                : t('colony.leader.serves')}
            </span>
          </>
        ) : (
          <>
            <span className="block text-ink-dim">{t('colony.leader.none')}</span>
            <span className="block text-11 text-ink-faint">{t('colony.leader.hint')}</span>
          </>
        )}
      </span>
    </section>
  );
}
