import type { WeaponModification } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import type { Pick } from './shipDesignRules';
import { t, useT } from '../../i18n';

/**
 * Модификации ствола — п. 8, окном «Modifications» из MOO II.
 *
 * В оригинале нажатие на столбец модификаций открывает список: тяжёлое орудие, ближняя
 * оборона, скорострельное, бронебойное, обволакивающее. Взятое помечено, и цена с местом
 * ствола тут же меняются — за усиление платят и тем и другим.
 *
 * Числа приходят с сервера (`ShipCatalog.modifications`): они и есть правило, а окно лишь
 * показывает, во что модификация обойдётся.
 *
 * <b>Список свой у каждого ствола</b> — п. 8: у ракеты набор другой целиком (ARM, FST,
 * OVR, ECCM, MIRV, EMG), тяжёлый луч ближней обороной не становится, а звёздный конвертер
 * не переделывают вовсе. Какие модификации носит именно это оружие, говорит сам
 * справочник (`component.modifications`), и окно показывает только их: предлагать то,
 * что сервер всё равно не примет, хуже, чем не предлагать вовсе.
 */
export function ShipModificationDialog({
  pick,
  modifications,
  onToggle,
  onClose,
}: {
  /** Ствол, которому назначают модификации. */
  pick: Pick;
  modifications: WeaponModification[];
  onToggle: (modification: WeaponModification) => void;
  onClose: () => void;
}) {
  useModalEscape(true, onClose);
  useT();

  const taken = new Set(pick.modifications.map((mod) => mod.code));
  // Носимое этим стволом: список приходит с сервера вместе с самим оружием.
  const offered = modifications.filter(
    (modification) => pick.component.modifications.includes(modification.code),
  );

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/95 p-8">
      <div className="panel flex max-h-full w-full max-w-3xl flex-col overflow-auto">
        <div className="mb-3 flex items-baseline justify-between gap-6 border-b border-space-700 pb-2">
          <h3 className="text-22 uppercase tracking-[0.3em] text-ink-bright">
            {t('design.mods.title')}
          </h3>
          <span className="text-19 text-ink-dim">{pick.component.name}</span>
        </div>

        {offered.length === 0 ? (
          <p className="text-19 text-ink-dim">{t('design.mods.none')}</p>
        ) : null}

        <ul className="flex flex-col gap-1">
          {offered.map((modification) => {
            const on = taken.has(modification.code);
            // Неизученную модификацию показываем погашенной, а не прячем: игроку полезно
            // знать, что она есть и чего для неё не хватает.
            const locked = !on && !modification.available;
            return (
              <li key={modification.code}>
                <button
                  type="button"
                  disabled={locked}
                  className={
                    'block w-full border px-2 py-1 text-left '
                    + (on
                      ? 'border-accent bg-accent/10 text-ink-bright'
                      : locked
                        ? 'border-space-800 text-ink-faint'
                        : 'border-space-700 text-ink-soft hover:border-space-600')
                  }
                  onClick={() => onToggle(modification)}
                >
                  <span className="flex flex-wrap items-baseline justify-between gap-x-4">
                    <span className="text-20">
                      {on ? '▸ ' : ''}
                      {modification.name}
                      {locked ? t('design.mods.locked') : ''}
                    </span>
                    <span className="text-18 text-ink-dim">
                      {changes(modification)}
                    </span>
                  </span>
                  <span className="mt-0.5 block text-18 leading-tight text-ink-faint">
                    {modification.description}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>

        <div className="mt-4 flex items-center justify-end gap-5 border-t border-space-700 pt-3 text-20">
          <button type="button" className="link" onClick={onClose}>
            {t('design.mods.done')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Во что модификация обходится и что даёт — строкой, как в подсказке оригинала. */
const changes = (modification: WeaponModification): string => {
  const parts: string[] = [];
  if (modification.damagePercent !== 0) {
    parts.push(t('design.mod.damage', { n: signed(modification.damagePercent) }));
  }
  if (modification.attackPercent !== 0) {
    parts.push(t('design.mod.accuracy', { n: signed(modification.attackPercent) }));
  }
  if (modification.shotsPercent !== 0) {
    parts.push(t('design.mod.shots', { n: signed(modification.shotsPercent) }));
  }
  if (modification.rangePercent !== 0) {
    parts.push(t('design.mod.range', { n: signed(modification.rangePercent) }));
  }
  if (modification.piercesArmour) {
    parts.push(t('design.mod.piercesArmour'));
  }
  if (modification.piercesShield) {
    parts.push(t('design.mod.piercesShield'));
  }
  if (modification.envelops) {
    parts.push(t('design.mod.envelops'));
  }
  if (modification.noRangePenalty) {
    parts.push(t('design.mod.noRangePenalty'));
  }
  if (modification.halvesEvasion) {
    parts.push(t('design.mod.halvesEvasion'));
  }
  if (modification.missileArmourPercent !== 0) {
    parts.push(t('design.mod.missileArmour', { n: signed(modification.missileArmourPercent) }));
  }
  if (modification.missileSpeed !== 0) {
    parts.push(t('design.mod.missileSpeed', { n: signed(modification.missileSpeed) }));
  }
  if (modification.hitsEngine) {
    parts.push(t('design.mod.hitsEngine'));
  }
  parts.push(t('design.mod.cost', { n: signed(modification.costPercent) }));
  parts.push(t('design.mod.space', { n: signed(modification.spacePercent) }));
  return parts.join(' · ');
};

const signed = (value: number): string => (value > 0 ? `+${value}` : String(value));
