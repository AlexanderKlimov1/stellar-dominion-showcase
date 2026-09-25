import { useState } from 'react';
import { gameApi } from '../../api/client';
import type { Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Перевозка жителей в другую свою колонию — п. 4.1.1.
 *
 * **Внутри своей системы грузовики не нужны, и жители переходят сразу** — правило
 * оригинала. Поэтому диалог смотрит на выбранную колонию: пока она в своей системе,
 * предел ставит только население, а грузовики не упоминаются вовсе.
 *
 * **Между системами** грузовой флот резервируется из расчёта **один грузовик на единицу
 * населения**: пока рейс в пути, эти грузовики не возят еду. Поэтому диалог показывает,
 * сколько грузовиков свободно и сколько займёт отправка, — иначе игрок узнавал бы о
 * нехватке только из ошибки сервера. Жители сходят с колонии-отправителя сразу, а на
 * новую планету — когда ход посчитается.
 *
 * Колонию нельзя оставить без населения — последнего жителя увезти не дадут.
 */
export function TransferDialog({ from, onClose }: { from: Planet; onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const self = useGameStore(selectSelf);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setLobby = useGameStore((state) => state.setLobby);
  const setMap = useGameStore((state) => state.setMap);

  const [target, setTarget] = useState('');
  const [count, setCount] = useState(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useModalEscape(true, onClose);
  const { t } = useT();

  const free = Math.max(0, (self?.freighters ?? 0) - (self?.freightersReserved ?? 0));
  const maxByPopulation = Math.max(0, from.population - 1);

  // Свои колонии, кроме этой: везти жителей можно только к себе.
  const colonies = (map?.systems ?? [])
    .flatMap((system) => system.planets.map((planet) => ({ system, planet })))
    .filter(({ planet }) => planet.ownerPlayerId === self?.id && planet.id !== from.id);

  // Система отправителя — по ней и видно, нужен ли грузовой флот этой отправке.
  const fromSystemId = (map?.systems ?? []).find((system) =>
    system.planets.some((planet) => planet.id === from.id),
  )?.id;
  const chosen = colonies.find((entry) => entry.planet.id === target) ?? null;
  const sameSystem = chosen !== null && chosen.system.id === fromSystemId;
  const limit = sameSystem ? maxByPopulation : Math.min(maxByPopulation, free);

  const send = () => {
    if (!game || !credentials || !target || count <= 0) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .transferPopulation(game.id, from.id, credentials.accessToken, target, count)
      .then(async (planet) => {
        updatePlanet(planet);
        if (sameSystem) {
          // Внутри системы изменились обе колонии сразу, а ответ приходит один: карту
          // берём свежей, иначе колония назначения осталась бы с прежним населением.
          setMap(await gameApi.getMap(game.id, credentials.accessToken));
        } else {
          // Рейс занял грузовики — это видно в правой панели, а состав игроков клиент
          // сам не пересчитывает: забираем его свежим у сервера.
          const details = await gameApi.getGame(game.id);
          setLobby(details.game, details.players);
        }
        onClose();
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex w-full max-w-lg flex-col">
        <h2 className="border-b border-space-700 pb-2 text-sm uppercase tracking-[0.3em] text-ink-bright">
          {t('transfer.title', { name: from.name })}
        </h2>

        <p className="mt-3 text-11 leading-relaxed text-ink-soft">
          {sameSystem ? (
            <>
              {t('transfer.sameSystem')}
            </>
          ) : (
            <>
              {t('transfer.between.1')} <span className="text-ink-bright">{free}</span>{' '}
              {t('transfer.between.2', { n: self?.freighters ?? 0 })}
            </>
          )}
        </p>

        {colonies.length === 0 ? (
          <p className="mt-3 text-11 text-ink-dim">
            {t('transfer.nowhere')}
          </p>
        ) : (
          <div className="mt-3 flex flex-col gap-2 text-11">
            <label className="flex items-center gap-2">
              <span className="w-28 text-ink-faint">{t('transfer.to')}</span>
              <select
                className="flex-1 border border-space-700 bg-space-900 px-1 py-0.5 text-ink"
                value={target}
                onChange={(event) => setTarget(event.target.value)}
              >
                <option value="">{t('transfer.choose')}</option>
                {colonies.map(({ system, planet }) => (
                  <option key={planet.id} value={planet.id}>
                    {planet.name} · {system.name ?? t('transfer.unknownSystem')}
                    {system.id === fromSystemId ? t('transfer.ownSystem') : ''} · {t('transfer.colonists')}{' '}
                    {planet.population}
                  </option>
                ))}
              </select>
            </label>

            <label className="flex items-center gap-2">
              <span className="w-28 text-ink-faint">{t('transfer.colonists')}</span>
              <input
                type="number"
                min={1}
                max={Math.max(1, limit)}
                value={count}
                disabled={limit <= 0}
                className="w-20 border border-space-700 bg-space-900 px-1 py-0.5 text-ink"
                onChange={(event) =>
                  setCount(Math.max(1, Math.min(limit, Number(event.target.value) || 1)))
                }
              />
              <span className="text-ink-faint">
                {sameSystem ? t('transfer.noFreightersNeeded') : t('transfer.takesFreighters', { n: count })}{t('transfer.upTo')}{' '}
                {limit}
                {limit === maxByPopulation ? '' : t('transfer.noMoreFreighters')}
              </span>
            </label>
          </div>
        )}

        {error ? <p className="mt-2 text-11 text-danger">{error}</p> : null}

        <div className="mt-4 flex items-center justify-between border-t border-space-700 pt-2 text-xs">
          <button type="button" className="link" onClick={onClose}>
            {t('common.closeEsc')}
          </button>
          <button
            type="button"
            className="link text-accent disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={busy || !target || limit <= 0}
            onClick={send}
          >
            {t('transfer.send')}
          </button>
        </div>
      </div>
    </div>
  );
}
