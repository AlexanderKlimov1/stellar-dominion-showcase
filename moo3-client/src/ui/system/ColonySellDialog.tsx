import type { ColonyProject } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Продажа постройки — п. 10, окошком из экрана колонии MOO II.
 *
 * В оригинале построенное продают правым щелчком по нему: поверх экрана всплывает
 * небольшое окно с названием здания, его описанием и двумя кнопками — продать и отменить.
 * Здесь так же; названия кнопок русские, как и вся остальная игра.
 *
 * Цена продажи приходит с сервера (`project.sellValue`), а не считается здесь: правило
 * MOO II — половина цены здания, — и жить ему на сервере, рядом с остальными числами.
 *
 * Продавать можно одну постройку за ход на колонию (правило оригинала), поэтому кнопка
 * гаснет, когда предел уже выбран, и окно говорит об этом словами: погашенная кнопка без
 * объяснения читается как поломка.
 */
export function ColonySellDialog({
  building,
  soldThisTurn,
  saving,
  error,
  onSell,
  onClose,
}: {
  building: ColonyProject;
  /** Колония уже продавала в этом ходу: второй раз за ход сервер откажет. */
  soldThisTurn: boolean;
  saving: boolean;
  error: string | null;
  onSell: () => void;
  onClose: () => void;
}) {
  useModalEscape(true, onClose);
  const { t } = useT();

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex w-full max-w-md flex-col">
        <h3 className="border-b border-space-700 pb-2 text-22 text-ink-bright">
          {building.name}
        </h3>

        <p className="mt-2 text-20 leading-relaxed text-ink-soft">{building.description}</p>

        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-20">
          <dt className="text-ink-dim">{t('colony.sell.price')}</dt>
          <dd className="text-ink-bright">{t('colony.sell.toTreasury', { n: building.sellValue ?? 0 })}</dd>
          <dt className="text-ink-dim">{t('colony.sell.upkeep')}</dt>
          <dd className="text-ink-bright">
            {building.upkeep > 0 ? t('colony.sell.upkeepStops', { n: building.upkeep }) : t('common.none')}
          </dd>
        </dl>

        {soldThisTurn ? (
          <p className="mt-3 text-19 leading-relaxed text-warn/80">
            {t('colony.sell.soldThisTurn')}
          </p>
        ) : null}
        {error ? <p className="mt-3 text-19 text-danger">{error}</p> : null}

        {/* Кнопки внизу справа, отмена перед продажей — порядок оригинала. */}
        <div className="mt-4 flex items-center justify-end gap-5 border-t border-space-700 pt-2 text-20">
          <button type="button" className="link" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={saving || soldThisTurn}
            onClick={onSell}
          >
            {t('colony.sell')}
          </button>
        </div>
      </div>
    </div>
  );
}
