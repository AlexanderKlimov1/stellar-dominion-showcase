import { useLayoutEffect, useRef, useState } from 'react';
import type { ColonyProject } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Продажа постройки — п. 10, всплывающим окошком у указателя (30.09.2026, решение хозяина
 * проекта).
 *
 * В оригинале построенное продают правым щелчком по нему: рядом с постройкой всплывает
 * небольшое окно с названием здания, его описанием и двумя кнопками — продать и отменить.
 * Прежде здесь стоял диалог во весь экран с затемнением; правый щелчок — быстрое действие, и
 * закрывать им всю сцену незачем. Теперь это ПОДСКАЗКА-МЕНЮ: встаёт у точки щелчка, а
 * закрывается щелчком мимо, Esc или кнопкой.
 *
 * Слой под окошком прозрачный и нужен только чтобы поймать щелчок мимо (и погасить меню
 * браузера на правом щелчке рядом): затемнения нет, сцена под окошком остаётся видной.
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
  at,
  soldThisTurn,
  saving,
  error,
  onSell,
  onClose,
}: {
  building: ColonyProject;
  /** Точка правого щелчка в координатах окна: окошко встаёт рядом с ней. */
  at: { x: number; y: number };
  /** Колония уже продавала в этом ходу: второй раз за ход сервер откажет. */
  soldThisTurn: boolean;
  saving: boolean;
  error: string | null;
  onSell: () => void;
  onClose: () => void;
}) {
  useModalEscape(true, onClose);
  const { t } = useT();
  const box = useRef<HTMLDivElement>(null);
  const [place, setPlace] = useState({ left: at.x + 8, top: at.y + 8 });

  // Окошко не должно вылезать за окно: его размер известен только после отрисовки
  // (кегль и масштаб текста меняет игрок), поэтому прижимаем к краям уже по измерению.
  useLayoutEffect(() => {
    const rect = box.current?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    const margin = 8;
    setPlace({
      left: Math.max(margin, Math.min(at.x + margin, window.innerWidth - rect.width - margin)),
      top: Math.max(margin, Math.min(at.y + margin, window.innerHeight - rect.height - margin)),
    });
  }, [at.x, at.y, error, soldThisTurn]);

  return (
    <div
      className="fixed inset-0 z-50"
      onMouseDown={onClose}
      onContextMenu={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div
        ref={box}
        className="panel absolute flex w-[26rem] max-w-[calc(100vw-1rem)] flex-col shadow-lg shadow-black/60"
        style={{ left: place.left, top: place.top }}
        onMouseDown={(event) => event.stopPropagation()}
        onContextMenu={(event) => event.stopPropagation()}
      >
        <h3 className="border-b border-space-700 pb-2 text-22 text-ink-bright">
          {building.name}
        </h3>

        <p className="mt-2 text-20 leading-relaxed text-ink-soft">{building.description}</p>

        <dl className="mt-3 grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-1">
          <dt className={figureLabelClass('lead')}>{t('colony.sell.price')}</dt>
          <dd className={figureLabelClass('lead')}>
            {tf('colony.sell.toTreasury', { n: <Figure accent="lead">{building.sellValue ?? 0}</Figure> })}
          </dd>
          {/*
            Возврат труда в стройку — отступление от MOO II (решение хозяина проекта):
            разобранное здание отдаёт столько же единиц производства, сколько кредитов.
            Сказано здесь потому, что решают о продаже ИМЕННО тут: половина труда всё
            равно теряется, и знать об этом надо ДО нажатия, а не по числу в панели стройки.
          */}
          <dt className={figureLabelClass('value')}>{t('colony.sell.toBuild')}</dt>
          <dd className={figureLabelClass('value')}>
            {tf('colony.sell.toBuild.value', { n: <Figure accent="value">{building.sellValue ?? 0}</Figure> })}
          </dd>
          <dt className={figureLabelClass('value')}>{t('colony.sell.upkeep')}</dt>
          <dd className={figureLabelClass('value')}>
            {building.upkeep > 0
              ? tf('colony.sell.upkeepStops', { n: <Figure accent="value">{building.upkeep}</Figure> })
              : t('common.none')}
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
          <button type="button" className="link-quiet" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
          <button
            type="button"
            className="link-quiet disabled:cursor-not-allowed disabled:text-ink-off"
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
