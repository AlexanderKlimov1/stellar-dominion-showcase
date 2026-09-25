import { useState } from 'react';

import { gameApi } from '../../api/client';
import type { FleetGroup, Planet, StarSystem } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Высадка с флота — п. 4.1, п. 8 и п. 12.
 *
 * Гражданские корабли MOO II делают своё дело не в полёте, а на месте: колониальный
 * корабль основывает колонию, корабль-застава ставит заставу, транспорт высаживает
 * десант. Приказ отдаётся планете, а не кораблю, — поэтому окно и показывает планеты
 * системы, где стоит флот, а не корабли.
 *
 * Что можно с планетой, решает состав флота и сама планета: заселить — свободную и
 * пригодную для жизни, застава — любую ничейную, десант — чужую колонию. Кнопки, для
 * которых во флоте нет корабля, не показываются вовсе: пустая кнопка врала бы о том,
 * что империя может.
 */
export function FleetLandingDialog({
  fleet,
  system,
  gameId,
  accessToken,
  ownerPlayerId,
  telepathic,
  onDone,
  onClose,
}: {
  fleet: FleetGroup;
  system: StarSystem;
  gameId: string;
  accessToken: string;
  ownerPlayerId: string;
  /** Раса телепатов — п. 7: ей доступно подчинение вместо десанта. */
  telepathic: boolean;
  onDone: (message: string) => void;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useModalEscape(true, onClose);
  const { t } = useT();

  const carries = (role: FleetGroup['composition'][number]['role']) =>
    fleet.composition.some((ship) => ship.role === role && ship.ships > 0);

  const troops = fleet.composition
    .filter((ship) => ship.role === 'TRANSPORT')
    .reduce((total, ship) => total + ship.cargo, 0);

  // Подчинение телепатов держится на корпусе, а не на грузе: нужен крейсер и больше.
  const CRUISER = 3;
  const bigShip = fleet.composition.some((ship) => ship.ships > 0 && (ship.hullSize ?? 1) >= CRUISER);

  const act = (what: 'colonize' | 'outpost' | 'invade' | 'mind-control', planet: Planet) => {
    setBusy(true);
    setError(null);
    const call =
      what === 'colonize'
        ? gameApi.colonizeWithFleet(gameId, fleet.id, accessToken, planet.id).then(() => t('landing.colonized', { name: planet.name }))
        : what === 'outpost'
          ? gameApi.outpostWithFleet(gameId, fleet.id, accessToken, planet.id).then(() => t('landing.outposted', { name: planet.name }))
          : what === 'mind-control'
            ? gameApi
                .mindControlWithFleet(gameId, fleet.id, accessToken, planet.id)
                .then(() => t('landing.mindControlled', { name: planet.name }))
            : gameApi
                .invadeWithFleet(gameId, fleet.id, accessToken, planet.id)
                .then((outcome) =>
                  outcome.captured
                    ? t('landing.captured', { name: planet.name, n: outcome.survivors })
                    : t('landing.repelled', { name: planet.name }));

    call
      .then(onDone)
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/70 p-6">
      <div className="w-[36rem] border border-space-700 bg-space-950 p-4">
        <div className="panel-title mb-2">{t('landing.title', { system: system.name ?? t('fleetOps.unexploredStar') })}</div>

        <div className="space-y-1">
          {system.planets.map((planet) => {
            const free = !planet.ownerPlayerId && planet.population <= 0;
            const mineEmpty = planet.ownerPlayerId === ownerPlayerId && planet.population <= 0;
            const enemy = Boolean(planet.ownerPlayerId)
              && planet.ownerPlayerId !== ownerPlayerId
              && planet.population > 0;

            return (
              <div
                key={planet.id}
                className="flex items-center gap-3 border border-space-800 bg-space-900 px-3 py-1 text-12"
              >
                <span className="w-40 truncate text-ink">{planet.name}</span>
                <span className="w-44 truncate text-ink-faint">
                  {planet.sizeLabel} · {planet.climateLabel}
                </span>
                <span className="w-24 text-ink-faint">
                  {planet.population > 0 ? t('landing.colonists', { n: planet.population }) : free ? t('landing.free') : t('landing.taken')}
                </span>
                <span className="ml-auto flex gap-2">
                  {carries('COLONY') && (free || mineEmpty) && planet.colonizable ? (
                    <Action disabled={busy} onClick={() => act('colonize', planet)}>
                      {t('landing.settle')}
                    </Action>
                  ) : null}
                  {carries('OUTPOST') && free ? (
                    <Action disabled={busy} onClick={() => act('outpost', planet)}>
                      {t('landing.outpost')}
                    </Action>
                  ) : null}
                  {carries('TRANSPORT') && enemy ? (
                    <Action disabled={busy} onClick={() => act('invade', planet)}>
                      {t('landing.invade', { n: troops })}
                    </Action>
                  ) : null}
                  {telepathic && bigShip && enemy ? (
                    <Action disabled={busy} onClick={() => act('mind-control', planet)}>
                      {t('landing.mindControl')}
                    </Action>
                  ) : null}
                </span>
              </div>
            );
          })}
        </div>

        <p className="mt-2 text-11 leading-tight">
          {error ? (
            <span className="text-danger">{error}</span>
          ) : (
            <span className="text-ink-faint">
              {t('landing.note')}
            </span>
          )}
        </p>

        <div className="mt-3 flex justify-end">
          <button type="button" className="link text-12" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

function Action({
  disabled,
  onClick,
  children,
}: {
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="border border-space-600 bg-space-800 px-2 py-0.5 text-11 uppercase tracking-wider text-ink disabled:opacity-40"
    >
      {children}
    </button>
  );
}
