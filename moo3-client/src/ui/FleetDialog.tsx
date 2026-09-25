import { useEffect, useState } from 'react';
import { gameApi } from '../api/client';
import type { FleetGroup } from '../api/types';
import { useGameStore } from '../state/gameStore';
import { useModalEscape } from './useModalEscape';
import { useT } from '../i18n';

/**
 * Отбор кораблей для отданного приказа — п. 8: последний шаг отправки флота.
 *
 * Порядок повторяет MOO II: игрок выбирает флот на карте, тянет линию к звезде и, выбрав
 * её, попадает сюда — сказать, кто именно летит. Система назначения здесь уже известна и
 * не меняется: передумал — Esc, и приказ отменён.
 *
 * <b>Лететь может часть флота.</b> Игрок набирает корабли по проектам — весь флот,
 * несколько или один, — и оставшиеся продолжают держать свою систему. Это и есть разница
 * между «флотом» и «кораблями»: флот здесь не единица, а стоянка, из которой корабли
 * уходят и в которую приходят.
 *
 * Считает всё сервер: он же проверяет и состав, и дальность. Клиент повторяет правила
 * только чтобы показать срок в пути и не дать отправить корабли туда, куда не хватит
 * топлива, — узнавать об этом из ошибки после нажатия поздно.
 */
export function FleetDialog({
  fleet,
  targetSystemId,
  onClose,
}: {
  fleet: FleetGroup;
  targetSystemId: string;
  onClose: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const empireFleet = useGameStore((state) => state.fleet);
  const espionage = useGameStore((state) => state.espionage);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const setEncounters = useGameStore((state) => state.setEncounters);

  /** Сколько кораблей каждого проекта отобрано: код проекта → число. */
  const [picked, setPicked] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useModalEscape(true, onClose);

  // Флот приходит с сервера заново после каждого действия, и набор сбрасывается вместе
  // с ним: держать отбор от прежнего состава нельзя — кораблей могло стать меньше.
  // Весь флот отобран сразу: чаще всего уводят именно его, а лишнее снимается кнопкой.
  useEffect(() => {
    setPicked(Object.fromEntries(fleet.composition.map((ship) => [ship.designId, ship.ships])));
    setError(null);
  }, [fleet.id, fleet.ships, fleet.composition]);

  const chosen = fleet.composition.reduce(
    (sum, ship) => sum + Math.min(picked[ship.designId] ?? 0, ship.ships),
    0,
  );

  const target = map?.systems.find((system) => system.id === targetSystemId) ?? null;
  const origin = map?.systems.find((system) => system.id === fleet.starSystemId) ?? null;
  const reachable = (empireFleet?.reachableSystemIds ?? []).includes(targetSystemId);
  const { t } = useT();
  const targetName = target?.explored ? (target.name ?? '') : t('fleetOrder.unexplored');

  // Срок в пути — те же правила, что на сервере (FlightRules): расстояние в парсеках,
  // делённое на скорость самого медленного корабля, и не меньше хода.
  const parsecs =
    target && origin && map
      ? Math.hypot(target.x - origin.x, target.y - origin.y)
      : 0;
  const turns = Math.max(1, Math.ceil(parsecs / Math.max(1, fleet.speed)));

  const pick = (designId: string, ships: number, limit: number) =>
    setPicked((current) => ({ ...current, [designId]: Math.max(0, Math.min(limit, ships)) }));

  const send = () => {
    if (!game || !credentials || chosen === 0) {
      return;
    }
    setBusy(true);
    setError(null);

    const ships = fleet.composition
      .map((ship) => ({ designId: ship.designId, ships: picked[ship.designId] ?? 0 }))
      .filter((ship) => ship.ships > 0);

    gameApi
      .moveFleet(game.id, fleet.id, credentials.accessToken, targetSystemId, ships)
      .then(async () => {
        // Приказ меняет больше одного флота (откуда ушли и кто остался) и может свести
        // флоты противников — поэтому обновляем и состав, и встречи.
        const [fresh, encounters] = await Promise.all([
          gameApi.getFleet(game.id, credentials.accessToken),
          gameApi.getEncounters(game.id, credentials.accessToken),
        ]);
        setEmpire(espionage, fresh);
        setEncounters(encounters);
        onClose();
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="absolute inset-0 z-40 flex items-center justify-center bg-space-950/80 p-6">
      <div className="panel flex max-h-full w-full max-w-lg flex-col overflow-auto">
        <h2 className="border-b border-space-700 pb-2 text-sm uppercase tracking-[0.3em] text-ink-bright">
          {fleet.systemName} → {targetName}
        </h2>

        <p className="mt-2 text-11 text-ink-dim">
          {t('fleetOrder.ships')} <span className="text-ink">{fleet.ships}</span> · {t('fleetOrder.power')}{' '}
          <span className="text-ink">{fleet.power}</span> · {t('fleetOrder.speed')}{' '}
          <span className="text-ink">{fleet.speed}</span> {t('fleetOrder.parsecsPerTurn')}
        </p>

        {/*
          Дорога: сколько парсеков и сколько ходов. Приказ отдан, но корабли ещё дома —
          сроки должны быть видны до отправки, а не после, когда менять курс уже нельзя.
        */}
        <p className="mt-1 text-11">
          {reachable ? (
            <span className="text-good">
              {t('fleetOrder.route', { parsecs: parsecs.toFixed(1), turns, unit: t(turns === 1 ? 'fleetOrder.turn.1' : turns < 5 ? 'fleetOrder.turn.few' : 'fleetOrder.turn.many') })}
            </span>
          ) : (
            <span className="text-danger">
              {t('fleetOrder.outOfRange', { n: empireFleet?.rangeParsecs ?? 0 })}
            </span>
          )}
        </p>

        {fleet.composition.length === 0 ? (
          <p className="mt-3 text-11 text-ink-dim">{t('fleetOrder.unknownComposition')}</p>
        ) : (
          <table className="mt-3 w-full text-11">
            <thead>
              <tr className="text-left text-ink-faint">
                <th className="font-normal">{t('fleetOrder.col.design')}</th>
                <th className="w-12 text-right font-normal">{t('fleetOrder.col.have')}</th>
                <th className="w-12 text-right font-normal">{t('fleetOrder.col.attack')}</th>
                <th className="w-12 text-right font-normal">{t('fleetOrder.col.defence')}</th>
                <th className="w-24 text-right font-normal">{t('fleetOrder.col.send')}</th>
              </tr>
            </thead>
            <tbody>
              {fleet.composition.map((ship) => (
                <tr key={ship.designId} className="border-t border-space-800">
                  <td className="py-1 text-ink">
                    {ship.designName}
                    {/*
                      Устаревший проект — тот, чью ячейку игрок занял новым. Корабли по нему
                      продолжают летать, и во флоте их видно именно так: иначе непонятно,
                      почему проекта нет в окне дизайна, а корабли есть.
                    */}
                    {ship.obsolete ? (
                      <span className="text-ink-faint">{t('fleetOrder.obsolete')}</span>
                    ) : null}
                  </td>
                  <td className="py-1 text-right text-ink">{ship.ships}</td>
                  <td className="py-1 text-right text-ink-dim">{ship.attack}</td>
                  <td className="py-1 text-right text-ink-dim">{ship.defense}</td>
                  <td className="py-1 text-right">
                    <input
                      type="number"
                      min={0}
                      max={ship.ships}
                      value={picked[ship.designId] ?? 0}
                      disabled={busy}
                      aria-label={t('fleetOrder.count.aria', { name: ship.designName })}
                      className="w-16 border border-space-700 bg-space-900 px-1 py-0.5 text-right text-ink"
                      onChange={(event) =>
                        pick(ship.designId, Number(event.target.value) || 0, ship.ships)
                      }
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/*
          «Все» и «никого» — это то, чего хочется чаще всего: увести флот целиком или
          передумать. Набирать поштучно приходится только для части кораблей.
        */}
        <div className="mt-2 flex items-baseline gap-4 text-11">
          <button
            type="button"
            className="link"
            onClick={() =>
              setPicked(
                Object.fromEntries(fleet.composition.map((ship) => [ship.designId, ship.ships])),
              )
            }
          >
            {t('fleetOrder.all')}
          </button>
          <button type="button" className="link" onClick={() => setPicked({})}>
            {t('fleetOrder.none')}
          </button>
          <span className="text-ink-faint">
            {t('fleetOrder.picked')} <span className="text-ink">{chosen}</span> {t('fleetOrder.of', { n: fleet.ships })}
            {chosen === fleet.ships && chosen > 0 ? t('fleetOrder.wholeFleet') : ''}
          </span>
        </div>

        {error ? <p className="mt-2 text-11 text-danger">{error}</p> : null}

        <div className="mt-4 flex items-center justify-between border-t border-space-700 pt-2 text-xs">
          <button type="button" className="link" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
          <button
            type="button"
            className="link text-accent disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={busy || chosen === 0 || !reachable}
            onClick={send}
          >
            ▸ {busy ? t('fleetOrder.sending') : t('fleetOrder.send', { n: chosen })}
          </button>
        </div>
      </div>
    </div>
  );
}
