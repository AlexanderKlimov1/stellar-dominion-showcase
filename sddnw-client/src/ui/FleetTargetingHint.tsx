import { useGameStore } from '../state/gameStore';
import { useModalEscape } from './useModalEscape';
import { tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass } from './accent';

/**
 * Подсказка при выборе системы назначения — п. 8.
 *
 * Пока флот прицелен, карта ведёт себя иначе: нажатие по звезде задаёт цель полёта, а не
 * открывает систему. Об этом нужно сказать — молчаливо изменившееся поведение карты
 * игрок принял бы за поломку.
 *
 * Здесь же живёт Esc: он снимает прицел. Слушается общей очередью экранов, а не своим
 * обработчиком, — иначе одно нажатие сняло бы заодно и то, что открыто поверх карты.
 */
export function FleetTargetingHint({ fleetName }: { fleetName: string }) {
  const setTargetingFleet = useGameStore((state) => state.setTargetingFleet);
  /*
    Дальность берётся у САМОГО флота, а не у империи — п. 8: баки стоят на кораблях, и
    флот с баками улетает в полтора раза дальше соседнего. Подсказка, называющая общее
    число, врала бы обоим.
  */
  const rangeParsecs = useGameStore((state) =>
    state.fleet?.fleets.find((one) => one.id === state.targetingFleetId)?.rangeParsecs
    ?? state.fleet?.rangeParsecs ?? 0);

  const { t } = useT();
  useModalEscape(true, () => setTargetingFleet(null));

  return (
    <div className="pointer-events-none absolute inset-x-0 top-24 z-30 flex justify-center">
      <div className="panel pointer-events-auto flex items-center gap-4 px-4 py-2 text-11">
        <span className="text-ink">{t('fleetHint.choose', { fleet: fleetName })}</span>
        <span className={figureLabelClass('value')}>
          {tf('fleetHint.range', { n: <Figure accent="value">{rangeParsecs}</Figure> })}
        </span>
        <button type="button" className="link" onClick={() => setTargetingFleet(null)}>
          {t('common.cancelEsc')}
        </button>
      </div>
    </div>
  );
}
