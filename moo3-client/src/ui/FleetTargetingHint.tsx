import { useGameStore } from '../state/gameStore';
import { useModalEscape } from './useModalEscape';
import { useT } from '../i18n';

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
  const rangeParsecs = useGameStore((state) => state.fleet?.rangeParsecs ?? 0);

  const { t } = useT();
  useModalEscape(true, () => setTargetingFleet(null));

  return (
    <div className="pointer-events-none absolute inset-x-0 top-24 z-30 flex justify-center">
      <div className="panel pointer-events-auto flex items-center gap-4 px-4 py-2 text-11">
        <span className="text-ink">{t('fleetHint.choose', { fleet: fleetName })}</span>
        <span className="text-ink-faint">
          {t('fleetHint.range', { n: rangeParsecs })}
        </span>
        <button type="button" className="link" onClick={() => setTargetingFleet(null)}>
          {t('common.cancelEsc')}
        </button>
      </div>
    </div>
  );
}
