import type { Totals } from './shipDesignRules';
import type { ReactNode } from 'react';
import { t, tf } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Нижняя полоса окна дизайна — п. 8.
 *
 * В оригинале внизу ровно две плашки — «Cost» и «Space Available» — и три кнопки:
 * CLEAR, CANCEL, BUILD. Здесь так же.
 *
 * <b>Прочности, брони и скорости тут больше нет.</b> Они стояли строкой рядом с плашками,
 * пока ящики автоматики показывали числа СПРАВОЧНИКА («броня 4»), а не корабля. Теперь
 * ящики показывают итог по кораблю — как в оригинале, где под «Zortrium Armor» стоит
 * «600 structure points» — и строка внизу повторяла бы одно и то же дважды.
 * <b>Командные очки</b> (п. 14) остались: их в оригинале нет вовсе, а у нас корабль их
 * тратит, и место этому числу только здесь — в ящиках ему не под какую систему встать.
 *
 * Свободное место краснеет, когда его не хватает: это единственное, из-за чего сервер
 * откажется принять собранный проект.
 */
export function ShipDesignStats({ stats }: { stats: Totals }) {
  const left = stats.space - stats.spaceUsed;

  return (
    <div className="flex flex-wrap items-baseline gap-x-5 gap-y-1">
      {/* Ступень стоит на ЧИСЛЕ, единица и «из» остаются подписью плашки. */}
      <Plaque
        label={t('design.stats.cost')}
        value={tf('design.stats.prod', { n: <Figure accent="lead">{stats.cost}</Figure> })}
      />
      <Plaque
        label={t('design.stats.spaceLeft')}
        value={tf('design.stats.of', {
          left: <Figure accent="lead" tone={left < 0 ? 'danger' : undefined}>{left}</Figure>,
          space: <Figure accent="lead">{stats.space}</Figure>,
        })}
      />
      <span className="flex flex-wrap gap-x-4 gap-y-1">
        <Value label={t('design.stats.command')} value={stats.command} />
        {stats.troops > 0 ? <Value label={t('design.stats.troops')} value={stats.troops} /> : null}
      </span>
    </div>
  );
}

/** Плашка оригинала: подпись и число в рамке. */
function Plaque({
  label,
  value,
}: {
  label: string;
  value: ReactNode;
}) {
  return (
    <span className="flex items-baseline gap-2 border border-space-700 bg-space-900/60 px-3 py-0.5">
      <span className={'uppercase tracking-[0.2em] ' + figureLabelClass('lead')}>{label}</span>
      <span className={figureLabelClass('lead')}>{value}</span>
    </span>
  );
}

function Value({ label, value }: { label: string; value: number }) {
  return (
    <span className="flex gap-1">
      <span className={figureLabelClass('value')}>{label}</span>
      <Figure accent="value">{value}</Figure>
    </span>
  );
}
