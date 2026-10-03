import type { ReactNode } from 'react';
import { useT } from '../i18n';

/** Общая рамка для экранов, открывающихся поверх карты — п. 11.1. */
export function OverlayFrame({
  title,
  onClose,
  children,
  width = 'max-w-5xl',
  fill = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  /**
   * Ширина окна. По умолчанию рамка тянется во всю отведённую ширину, но экрану с
   * разметкой из оригинала (исследования) нужна ширина по содержимому: там сетка сама
   * знает свой размер, и рамка должна её облегать, а не растягивать заголовок.
   */
  width?: string;
  /**
   * Окно во всю высоту экрана, прижатое к правому краю, и непрозрачный фон под ним.
   * Так открывается экран исследований: за ним не должно просвечивать ни карты, ни
   * панелей — он занимает экран целиком, как полноэкранные окна MOO II.
   */
  fill?: boolean;
}) {
  const { t } = useT();
  return (
    <div
      className={
        'absolute inset-0 z-20 flex ' +
        (fill ? 'justify-end bg-space-950' : 'items-center justify-center bg-space-950/80 p-10')
      }
    >
      <div
        className={
          `panel w-full ${width} ` +
          // В режиме fill окно раскладывается колонкой: заголовок сверху, всё остальное
          // — оставшаяся высота. Содержимое от неё и считает свои размеры, поэтому
          // ничего не вылезает за края окна и прокрутки у окна нет.
          (fill ? 'flex h-full flex-col overflow-hidden' : 'max-h-full overflow-auto')
        }
      >
        <div className="mb-4 flex flex-none items-baseline justify-between">
          <h2 className="text-sm uppercase tracking-[0.3em] text-accent">{title}</h2>
          <button type="button" className="link text-xs" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
        {fill ? <div className="min-h-0 flex-1">{children}</div> : children}
      </div>
    </div>
  );
}
