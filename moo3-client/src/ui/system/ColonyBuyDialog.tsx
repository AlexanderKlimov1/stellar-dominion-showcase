import type { Colony } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Выкуп стройки — п. 10, окошком подтверждения из MOO II.
 *
 * В оригинале нажатие на BUY открывает окно с вопросом «Do you want to buy X production
 * for Y BC?» — и оба числа там не случайны: по ним видно, дорого ли вышло. Пока сделано
 * меньше половины, кредит стоит вдвое больше единицы производства, и это заметно сразу.
 *
 * Цена приходит с сервера (`colony.buyCost`): правило живёт там, рядом с остальными
 * числами, и повторять его здесь нельзя — разъедется.
 */
export function ColonyBuyDialog({
  colony,
  credits,
  saving,
  error,
  onBuy,
  onClose,
}: {
  colony: Colony;
  /** Казна империи: по ней видно, хватает ли на выкуп. */
  credits: number;
  saving: boolean;
  error: string | null;
  onBuy: () => void;
  onClose: () => void;
}) {
  useModalEscape(true, onClose);
  const { t } = useT();

  const price = colony.buyCost ?? 0;
  const remaining = (colony.projectCost ?? 0) - colony.projectPoints;
  const enough = credits >= price;
  // Вдвое дороже единицы — примета того, что сделано меньше половины: та самая подсказка
  // оригинала «if Y exceeds 2X, the item is less than 50% complete».
  const early = price > remaining * 2;

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex w-full max-w-md flex-col">
        <h3 className="border-b border-space-700 pb-2 text-22 text-ink-bright">
          {t('colony.buy.title')}
        </h3>

        <p className="mt-3 text-20 leading-relaxed text-ink">
          {t('colony.buy.question', { n: remaining, project: colony.projectName ?? '' })}{' '}
          <span className={enough ? 'text-ink-bright' : 'text-danger'}>{price}</span> {t('colony.buy.question.tail')}
        </p>

        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-20">
          <dt className="text-ink-dim">{t('colony.buy.treasury')}</dt>
          <dd className="text-ink-bright">{t('common.credits', { n: credits })}</dd>
          <dt className="text-ink-dim">{t('colony.buy.invested')}</dt>
          <dd className="text-ink-bright">
            {t('colony.buy.progress', { points: colony.projectPoints, cost: colony.projectCost ?? 0 })}
          </dd>
        </dl>

        {early ? (
          <p className="mt-3 text-19 leading-relaxed text-warn/80">
            {t('colony.buy.early')}
          </p>
        ) : null}
        {error ? <p className="mt-3 text-19 text-danger">{error}</p> : null}

        <div className="mt-4 flex items-center justify-end gap-5 border-t border-space-700 pt-2 text-20">
          <button type="button" className="link" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={saving || !enough}
            onClick={onBuy}
          >
            {t('colony.buy')}
          </button>
        </div>
      </div>
    </div>
  );
}
