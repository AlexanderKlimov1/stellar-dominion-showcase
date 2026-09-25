import type { PlayerResearch, ResearchCategory } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { givesWholeLevel, isAcquired, nextLevelOrder } from './researchRules';
import { useT } from '../../i18n';

/**
 * Весь раздел списком — п. 9, окном «<РАЗДЕЛ> LIST» MOO II.
 *
 * В оригинале оно открывается с плашки раздела и занимает правые 57% окна от заголовка
 * до нижней полосы, а внутри — лестница уровней: название уровня, под ним его технологии
 * со сдвигом вправо. Список только показывает: выбрать можно лишь ближайший уровень, и
 * это делается в самой сетке.
 *
 * Отступы те же, что в оригинале, пересчитанные в `em`: сдвиг технологий 29 px и разрыв
 * перед названием уровня 32 px при шаге строки 21 px.
 */
export function ResearchFieldList({
  category,
  research,
  onClose,
}: {
  category: ResearchCategory;
  research: PlayerResearch;
  onClose: () => void;
}) {
  useModalEscape(true, onClose);

  const { t } = useT();
  const next = nextLevelOrder(research, category.code);

  return (
    <div className="absolute inset-0 flex flex-col border border-space-700 bg-space-950/95">
      <div className="border-b border-space-800 px-[0.6em] py-[0.3em] text-center text-[0.9em] uppercase tracking-[0.2em] text-ink-soft">
        {t('research.wholeSection', { name: category.name })}
      </div>

      <div className="flex-1 overflow-auto px-[0.6em] py-[0.3em]">
        {category.levels.map((level) => (
          <div key={level.order} className="mt-[2.11em] first:mt-0">
            <div className="flex items-baseline justify-between gap-[0.5em]">
              <span
                className={
                  'truncate text-[1.45em] leading-[1.19] ' +
                  (level.order < next
                    ? 'text-ink-faint'
                    : level.order === next
                      ? 'text-accent'
                      : 'text-ink')
                }
              >
                {level.name}
              </span>
              {/*
                «Все сразу» стоит у каждого уровня изобретательной расы, а не только у
                общих: у неё цена уровня покупает весь уровень, и список обязан читаться
                так же, как сетка разделов.
              */}
              <span className="shrink-0 text-[0.95em] text-ink-faint">
                {givesWholeLevel(research, level) ? t('research.allAtOnce.prefix') : ''}
                {t('research.rp', { n: level.cost })}
              </span>
            </div>

            <ul>
              {level.options.map((option) => (
                <li
                  key={option.code}
                  className={
                    'truncate pl-[1.92em] leading-[1.39em] ' +
                    (isAcquired(research, category.code, option.code)
                      ? 'text-ink-faint'
                      : 'text-ink-soft')
                  }
                  title={option.description}
                >
                  {isAcquired(research, category.code, option.code) ? '✓ ' : '• '}
                  {option.name}
                </li>
              ))}
            </ul>
          </div>
        ))}

        <p className="mt-[2.11em] text-[0.9em] leading-relaxed text-ink-faint">
          {category.description}
        </p>
      </div>

      <div className="border-t border-space-800 px-[0.6em] py-[0.3em] text-right">
        <button type="button" className="link text-[0.9em]" onClick={onClose}>
          {t('research.back')}
        </button>
      </div>
    </div>
  );
}
