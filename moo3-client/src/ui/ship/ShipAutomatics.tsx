import type { Pick, Totals } from './shipDesignRules';
import { SLOT_NAMES } from './shipDesignRules';
import { t } from '../../i18n';

/**
 * Автоматика корабля — п. 8, двумя ящиками окна Ship Design MOO II
 * (`docs/moo2/ship-design.png`).
 *
 * В левом ящике двигатель и броня, в правом щит, компьютер и помехи, а под каждой
 * системой — её действие строчками с точками. <b>Числа в этих строчках — КОРАБЛЯ, а не
 * компонента:</b> в оригинале под «Zortrium Armor» стоит «600 structure points, 600 armor
 * points» — то, что броня даёт этому корпусу, а не её табличные четыре очка. Так и здесь:
 * броня показывает прочность и броню собранного корабля, щит — сколько урона он гасит,
 * компьютер — прицел. Иначе игрок видел бы «броня 4» у линкора, у которого её 32.
 *
 * Пустое гнездо так и говорит «щита нет» — по нему видно, чего кораблю не хватает, и это
 * тоже оригинал: там на месте пустого щита стоит «No Shield» с теми же двумя строчками.
 *
 * Внизу правого ящика — два числа оригинала: «Защита от лучей» (Beam Defense) и
 * «Уклонение от ракет» (Missile Evasion). Залпа здесь нет: в оригинале урон стоит в
 * таблице оружия, где его и ищут.
 */
export function ShipAutomatics({
  picks,
  stats,
  openSlot,
  onOpenSlot,
}: {
  picks: Pick[];
  stats: Totals | null;
  /** Гнездо, чьё окно открыто: подсвечивается, как выбранная строка оригинала. */
  openSlot: string | null;
  onOpenSlot: (slot: string) => void;
}) {
  const pickOf = (slot: string) => picks.find((pick) => pick.component.slot === slot) ?? null;

  /** Что система даёт ЭТОМУ кораблю: строки под её названием. */
  const linesOf = (slot: string): string[] => {
    if (!stats) {
      return [];
    }
    switch (slot) {
      case 'ENGINE':
        return [
          t('design.lines.speed', { n: stats.speed }),
          t('design.lines.combatSpeed', { n: stats.combatSpeed }),
        ];
      case 'ARMOR':
        return [
          t('design.lines.structure', { n: stats.structure }),
          t('design.lines.armour', { n: stats.armour }),
        ];
      case 'SHIELD':
        return pickOf('SHIELD')
          ? [t('design.lines.shield', { n: stats.shield })]
          : [t('design.lines.noShield')];
      case 'COMPUTER':
        return pickOf('COMPUTER')
          ? [t('design.lines.attack', { n: stats.defense })]
          : [t('design.lines.noComputer')];
      case 'ECM':
        return pickOf('ECM')
          ? [t('design.lines.evasion', { n: stats.missileEvasion })]
          : [];
      default:
        return [];
    }
  };

  return (
    <div className="grid min-h-0 grid-cols-2 gap-2">
      <Box>
        <System slot="ENGINE" pick={pickOf('ENGINE')} lines={linesOf('ENGINE')}
                openSlot={openSlot} onOpen={onOpenSlot} />
        <System slot="ARMOR" pick={pickOf('ARMOR')} lines={linesOf('ARMOR')}
                openSlot={openSlot} onOpen={onOpenSlot} />
      </Box>
      <Box>
        <System slot="SHIELD" pick={pickOf('SHIELD')} lines={linesOf('SHIELD')}
                openSlot={openSlot} onOpen={onOpenSlot} />
        <System slot="COMPUTER" pick={pickOf('COMPUTER')} lines={linesOf('COMPUTER')}
                openSlot={openSlot} onOpen={onOpenSlot} />
        <System slot="ECM" pick={pickOf('ECM')} lines={linesOf('ECM')}
                openSlot={openSlot} onOpen={onOpenSlot} />
        {stats ? (
          <dl className="mt-auto space-y-0.5 pt-1 text-18">
            <Readout label={t('design.beamDefence')} value={stats.defense} />
            <Readout label={t('design.missileEvasion')} value={stats.missileEvasion} />
          </dl>
        ) : null}
      </Box>
    </div>
  );
}

function Box({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-0 flex-col gap-2 overflow-y-auto border border-space-700 bg-space-950/60 p-2">
      {children}
    </div>
  );
}

/**
 * Система корабля: название и под ним её действие точками. Нажатие открывает окно выбора —
 * в оригинале так же, и другого способа сменить двигатель нет.
 */
function System({
  slot,
  pick,
  lines,
  openSlot,
  onOpen,
}: {
  slot: string;
  pick: Pick | null;
  lines: string[];
  openSlot: string | null;
  onOpen: (slot: string) => void;
}) {
  const active = slot === openSlot;
  return (
    <button
      type="button"
      onClick={() => onOpen(slot)}
      className={`block w-full text-left ${active ? 'bg-space-800/60' : ''}`}
    >
      <span className={`block text-20 ${pick ? 'text-warn' : 'text-ink-dim'}`}>
        {pick ? pick.component.name : t('design.slotEmpty', { slot: t(SLOT_NAMES[slot]) })}
      </span>
      <span className="block pl-3 text-18 leading-tight text-ink-soft">
        {lines.map((line) => (
          <span key={line} className="block">
            · {line}
          </span>
        ))}
      </span>
    </button>
  );
}

/** Готовое число справа в ящике — как Beam Defense в оригинале. */
function Readout({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-dim">{label}</dt>
      <dd className="text-ink-bright">{value}</dd>
    </div>
  );
}
