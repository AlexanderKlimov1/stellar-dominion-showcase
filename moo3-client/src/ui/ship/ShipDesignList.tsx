import type { ShipDesign, ShipHull } from '../../api/types';
import { ShipHullPicture } from './ShipHullPicture';
import { t } from '../../i18n';

/**
 * Выбор проекта, который будем переделывать, — п. 8, первый шаг дизайна в MOO II.
 *
 * В оригинале окно дизайна открывается не сразу: сперва империя показывает свои классы
 * кораблей, и только выбрав класс, игрок попадает в само окно. Здесь так же — шаг
 * отдельный, — но возвращаться к нему можно одной ссылкой из нижней полосы: в оригинале
 * ради смены класса приходилось выходить из окна и терять начатое, и повторять эту
 * оплошность незачем.
 *
 * Ячеек шесть, как в оригинале. Занятая показывает корпус, цену и залп — по ним и
 * выбирают, какой проект не жалко переписать; свободная так и говорит, что свободна.
 */
export function ShipDesignList({
  slots,
  designs,
  hulls,
  onOpen,
  onClose,
}: {
  slots: number[];
  designs: ShipDesign[];
  hulls: ShipHull[];
  onOpen: (slot: number) => void;
  onClose: () => void;
}) {
  return (
    <div className="flex h-full w-full flex-col bg-space-950">
      {/* Плашка названия по центру сверху — та же, что у окна дизайна. */}
      <div className="flex shrink-0 items-center justify-center border-b border-space-700 py-2">
        <h2 className="border border-space-700 bg-space-900/60 px-8 py-1 text-22 uppercase tracking-[0.4em] text-ink-bright">
          {t('design.list.title')}
        </h2>
      </div>

      <div className="flex min-h-0 flex-1 items-center justify-center overflow-y-auto p-6">
        <ul className="my-auto grid w-full max-w-5xl gap-3 md:grid-cols-2">
          {slots.map((slot) => {
            const design = designs.find((candidate) => candidate.slot === slot) ?? null;
            const hull = hulls.find((candidate) => candidate.code === design?.hullCode) ?? null;
            return (
              <li key={slot}>
                <button
                  type="button"
                  onClick={() => onOpen(slot)}
                  className="flex w-full items-center gap-4 border border-space-700 bg-space-950/60 px-4 py-3 text-left hover:border-space-500"
                >
                  <span className="w-28 shrink-0">
                    <ShipHullPicture hull={hull} compact />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-22 text-ink-bright">
                      {design ? design.name : t('design.list.slot', { n: slot })}
                    </span>
                    <span className="block truncate text-18 text-ink-dim">
                      {design
                        ? t('design.list.summary', { hull: design.hullName, cost: design.cost, attack: design.attack })
                        : t('design.list.free')}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      {/* Одна кнопка снизу — как у прочих полноэкранных окон игры. */}
      <div className="flex shrink-0 justify-center border-t border-space-700 py-2">
        <button type="button" className="link text-22" onClick={onClose}>
          {t('design.list.back')}
        </button>
      </div>
    </div>
  );
}
