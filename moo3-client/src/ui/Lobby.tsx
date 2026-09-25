import { useT } from '../i18n';
import { useGameStore } from '../state/gameStore';

interface LobbyProps {
  onStart: () => void;
  onLeave: () => void;
}

/**
 * Лобби — п. 3.2.
 *
 * Создатель игры может стартовать при любом числе присоединившихся, в том числе
 * в одиночку: свободные слоты доберутся ИИ-игроками до восьми.
 */
export function Lobby({ onStart, onLeave }: LobbyProps) {
  const { t } = useT();
  const game = useGameStore((state) => state.game);
  const players = useGameStore((state) => state.players);
  const credentials = useGameStore((state) => state.credentials);
  const lastError = useGameStore((state) => state.lastError);

  if (!game) {
    return null;
  }

  const isHost = credentials?.host === true;
  const aiSlots = game.totalPlayers - players.length;

  return (
    // Прокрутка, а не обрезка: у страницы overflow: hidden, и на невысоком окне список
    // игроков уходил бы за край. `my-auto` центрирует, пока помещается.
    <div className="flex h-full justify-center overflow-y-auto p-8">
      <div className="my-auto w-full max-w-2xl">
        <header className="mb-4">
          <h1 className="text-lg tracking-[0.25em] text-accent">{game.name}</h1>
          <p className="mt-1 text-xs text-ink-dim">
            {t('lobby.summary', { size: game.galaxySize, w: game.widthParsecs, h: game.heightParsecs, stars: game.starCount })}
          </p>
        </header>

        {lastError ? (
          <div className="mb-4 border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger">
            {lastError}
          </div>
        ) : null}

        <section className="panel mb-4">
          <div className="panel-title">
            {t('lobby.players', { have: players.length, max: game.maxHumanPlayers, total: game.totalPlayers })}
          </div>

          <ol className="space-y-1 text-sm">
            {players.map((player) => (
              <li key={player.id} className="flex items-baseline gap-3">
                <span className="w-6 text-ink-faint">{player.slot}.</span>
                <span
                  className="inline-block h-2 w-2 shrink-0"
                  style={{ backgroundColor: player.color }}
                />
                <span className={player.id === credentials?.playerId ? 'text-accent' : ''}>
                  {player.name}
                </span>
                <span className="text-ink-faint">{player.raceName ?? player.raceCode}</span>
                {player.id === game.hostPlayerId ? (
                  <span className="text-10 uppercase tracking-widest text-ink-faint">{t('lobby.host')}</span>
                ) : null}
              </li>
            ))}

            {Array.from({ length: aiSlots }, (_, index) => (
              <li key={`ai-${index}`} className="flex items-baseline gap-3 text-ink-faint">
                <span className="w-6">{players.length + index + 1}.</span>
                <span className="inline-block h-2 w-2 shrink-0 bg-space-600" />
                <span>{t('lobby.free')}</span>
              </li>
            ))}
          </ol>
        </section>

        <div className="flex gap-6">
          <button type="button" className="link text-accent" disabled={!isHost} onClick={onStart}>
            ▸ {t('lobby.start')}
          </button>
          <button type="button" className="link" onClick={onLeave}>
            {t('lobby.leave')}
          </button>
        </div>

        {isHost ? null : <p className="mt-3 text-11 text-ink-faint">{t('lobby.hostNote')}</p>}
      </div>
    </div>
  );
}
