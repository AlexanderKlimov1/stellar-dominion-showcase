import { useState } from 'react';
import type { ShipComponentOption, ShipHull } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { componentCost, componentSpace, SLOT_NAMES, type Pick } from './shipDesignRules';
import { useT, type Key } from '../../i18n';
import { RuleNote } from '../RuleNote';
import { Figure } from '../Figure';

/**
 * Выбор системы корабля — п. 8, окном SELECT ... SYSTEM из MOO II.
 *
 * В оригинале состав корабля не набирают из списка сбоку: нажатие на строку корабля
 * открывает <b>окно поверх</b> — «SELECT WEAPON SYSTEM», «SELECT SPECIAL SYSTEM» и так
 * далее, — таблицей со своими столбцами и кнопкой отмены. Здесь так же.
 *
 * Первой строкой всегда стоит пустая («оружия нет», «щита нет»): в оригинале ею снимают
 * поставленное, и другого способа освободить гнездо нет.
 *
 * <b>НЕИЗУЧЕННОЕ НЕ ПОКАЗЫВАЕТСЯ ВОВСЕ</b> — решение хозяина проекта (29.09.2026).
 * Прежде оно стояло в списке погашенным, «как затемнённые строки оригинала», ради того
 * чтобы игрок видел, за чем идти в дерево науки. На деле вышло обратное: в начале партии
 * изучены две-три строки из десяти, и окно выбора превращалось в список того, чего
 * нельзя, — шесть «нужна технология» на два годных модуля. Узнают о будущем оружии в
 * дереве науки, а выбирают в этом окне из того, что есть.
 *
 * Отсюда и пустой список: у щитов и помех в начале партии не изучено НИЧЕГО, и окно
 * говорит об этом словами. Пустое окно без объяснения читается как поломка.
 *
 * У оружия столбцы свои (урон, цена, место, замечания) и над ними полоса видов — луч,
 * снаряд, ракета, — та самая BEAM/MISSILE/BOMB оригинала.
 */

/** Виды оружия полосой, как в окне выбора оружия оригинала. */
const WEAPON_KINDS: Array<{ code: string; label: Key }> = [
  { code: 'ALL', label: 'design.kind.ALL' },
  { code: 'BEAM', label: 'design.kind.BEAM' },
  { code: 'PROJECTILE', label: 'design.kind.PROJECTILE' },
  { code: 'MISSILE', label: 'design.kind.MISSILE' },
];

export function ShipSystemDialog({
  slot,
  components,
  hull,
  picks,
  onChoose,
  onClear,
  onClose,
  roomFor,
}: {
  slot: string;
  components: ShipComponentOption[];
  hull: ShipHull | null;
  picks: Pick[];
  onChoose: (component: ShipComponentOption) => void;
  /** Пустая строка списка: снимает то, что стоит в гнезде. */
  onClear: () => void;
  onClose: () => void;
  /**
   * Влезает ли компонент в оставшееся место — п. 8. Считает сцена тем же правилом, что и
   * занятое место: второго правила места в игре нет, а окну нужно лишь погасить строку.
   */
  roomFor: (component: ShipComponentOption) => boolean;
}) {
  const [kind, setKind] = useState('ALL');
  useModalEscape(true, onClose);
  const { t } = useT();

  const weapons = slot === 'WEAPON';
  // Изученное — и только оно: неизученное в этом окне не показывается вовсе.
  const researched = components.filter((component) => component.available);
  const shown = weapons && kind !== 'ALL'
    ? researched.filter((component) => component.weaponKind === kind)
    : researched;
  const taken = new Map(picks.map((pick) => [pick.component.code, pick.count]));

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/95 p-6">
      <div className="panel flex max-h-full w-full max-w-3xl flex-col overflow-hidden">
        <div className="flex shrink-0 items-center justify-center border-b border-space-700 pb-2">
          <h3 className="text-20 uppercase tracking-[0.3em] text-ink-bright">
            {t('design.choose', { slot: SLOT_NAMES[slot] ? t(SLOT_NAMES[slot]).toLowerCase() : t('design.slot.generic') })}
          </h3>
        </div>

        {weapons ? (
          <div className="mt-2 flex shrink-0 justify-center gap-2">
            {WEAPON_KINDS.map((option) => (
              <button
                key={option.code}
                type="button"
                onClick={() => setKind(option.code)}
                className={`border px-3 py-0.5 text-18 ${
                  option.code === kind
                    ? 'border-amber-400 text-warn'
                    : 'border-space-700 text-ink-soft hover:border-space-500'
                }`}
              >
                {/* Таблица держит КЛЮЧ, а текст берётся здесь: иначе полоса видов оружия
                    показывала бы сам ключ — так оно и было. */}
                {t(option.label)}
              </button>
            ))}
          </div>
        ) : null}

        <div className="mt-2 min-h-0 flex-1 overflow-y-auto border border-space-800">
          <table className="w-full text-left text-18">
            <thead className="sticky top-0 bg-space-950 text-16 uppercase tracking-[0.2em] text-ink-dim">
              <tr>
                <th className="px-2 py-1">{weapons ? t('design.col.weapon') : t('design.col.system')}</th>
                {weapons ? <th className="px-2 py-1 text-right">{t('design.col.damage')}</th> : null}
                <th className="px-2 py-1 text-right">{t('design.col.space')}</th>
                <th className="px-2 py-1 text-right">{t('design.col.cost')}</th>
                <th className="px-2 py-1">{weapons ? t('design.col.notes') : t('design.col.description')}</th>
              </tr>
            </thead>
            <tbody>
              {/*
                Пустая строка первой — «No Weapon», «No Shield» оригинала: ею гнездо и
                освобождают.
              */}
              <tr className="border-t border-space-800 hover:bg-space-800/60">
                <td className="px-2 py-1" colSpan={weapons ? 5 : 4}>
                  <button type="button" className="link w-full text-left" onClick={onClear}>
                    {t('design.leaveEmpty')}
                  </button>
                </td>
              </tr>
              {shown.map((component) => {
                const count = taken.get(component.code) ?? 0;
                return (
                  <tr key={component.code} className="border-t border-space-800 hover:bg-space-800/60">
                    <td className="px-2 py-1">
                      <button
                        type="button"
                        disabled={!roomFor(component)}
                        onClick={() => onChoose(component)}
                        className={
                          roomFor(component)
                            ? count > 0
                              ? 'text-warn'
                              : 'text-ink-bright'
                            : 'cursor-not-allowed text-ink-faint'
                        }
                      >
                        {count > 0 ? '▸ ' : ''}
                        {component.name}
                        {count > 1 ? ` × ${count}` : ''}
                      </button>
                    </td>
                    {weapons ? (
                      <td className="px-2 py-1 text-right"><Figure accent="value">{damage(component)}</Figure></td>
                    ) : null}
                    <td className="px-2 py-1 text-right">
                      <Figure accent="value">{hull ? componentSpace(component, hull) : component.space}</Figure>
                    </td>
                    <td className="px-2 py-1 text-right">
                      <Figure accent="value">{hull ? componentCost(component, hull) : component.cost}</Figure>
                    </td>
                    <td className="px-2 py-1 text-ink-dim">
                      {/*
                        Изученное, которое не влезает, остаётся в списке погашенным: место
                        освобождают тут же, сняв лишнее, — и строка должна сказать, чего
                        ждать. Это не то же, что неизученное: то из окна убрано совсем.
                      */}
                      {!roomFor(component) ? (
                        <span className="text-danger">{t('design.noRoomShort')}</span>
                      ) : (
                        component.effects.map((effect) => effect.label).join(', ')
                      )}
                    </td>
                  </tr>
                );
              })}
              {shown.length === 0 ? (
                <tr className="border-t border-space-800">
                  <td className="px-2 py-2 text-ink-dim" colSpan={weapons ? 5 : 4}>
                    {weapons ? t('design.noWeapons') : t('design.nothingResearched')}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        {/* Урон — верхняя граница разброса оригинала (правила проекта, ShipDesignService): ветеран
            ищет здесь «1–4» и без строки решит, что у лазера отняли разброс по ошибке. */}
        {weapons ? <RuleNote className="mt-2 shrink-0 text-14" text={t('rule.weaponDamage')} /> : null}

        <div className="mt-2 flex shrink-0 justify-end border-t border-space-700 pt-2">
          <button type="button" className="link text-18" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Урон пушки строкой: столько же и столько раз за залп — как «3–12» в оригинале. */
function damage(component: ShipComponentOption): string {
  const value = component.effects.find((effect) => effect.type === 'WEAPON_DAMAGE');
  const shots = component.effects.find((effect) => effect.type === 'WEAPON_SHOTS');
  if (!value) {
    return '—';
  }
  return shots && shots.amount > 1 ? `${value.amount} × ${shots.amount}` : String(value.amount);
}
