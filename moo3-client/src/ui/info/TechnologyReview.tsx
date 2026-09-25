import { useState } from 'react';

import type { AcquiredTechnology, ResearchTree } from '../../api/types';
import { t, type Key } from '../../i18n';

/**
 * Разделы списка — п. 11.1, четыре части окна Tech Review MOO II.
 *
 * Руководство оригинала: «список технологий разделён на четыре части, доступные кнопками
 * вдоль низа: General Achievements, Colony improvements, Weapons и Ship Equipment».
 * Раздел приходит с сервера у каждой технологии: он выводится не из дерева науки, а из
 * того, что технология открывает, — см. `TechnologySections`.
 *
 * Пятая кнопка своя, «всё»: в оригинале списки не пересекаются и общего вида нет, но у
 * нас та же страница служит и справочником всего дерева, а листать его по четырём
 * разделам, когда ищешь одну технологию, — лишняя работа.
 */
const SECTIONS: { code: string; label: Key }[] = [
  { code: 'ALL', label: 'techReview.section.ALL' },
  { code: 'GENERAL', label: 'techReview.section.GENERAL' },
  { code: 'COLONY', label: 'techReview.section.COLONY' },
  { code: 'WEAPON', label: 'techReview.section.WEAPON' },
  { code: 'SHIP', label: 'techReview.section.SHIP' },
];

/**
 * Технологии в окне «Инфо» — п. 11.1: отчёт Tech Review консоли Info MOO II и он же
 * справочник всего дерева на странице Reference.
 *
 * Обе строятся из того, что клиент уже знает: изученное приходит с экраном исследований,
 * дерево — справочником при подключении. Своего запроса странице не нужно.
 */
export function TechnologyReview({
  tree,
  acquired,
  all,
}: {
  tree: ResearchTree | null;
  acquired: AcquiredTechnology[];
  /** Весь справочник или только изученное: страницы отличаются только этим. */
  all: boolean;
}) {
  const [openCategory, setOpenCategory] = useState<string | null>(null);
  const [section, setSection] = useState<string>('ALL');

  if (!tree) {
    return <p className="text-xs text-ink-dim">{t('techReview.notLoaded')}</p>;
  }

  const known = new Set(acquired.map((technology) => technology.optionCode));
  const turnByCode = new Map(
    acquired.map((technology) => [technology.optionCode, technology.acquiredTurn]),
  );

  const categories = tree.categories
    .map((category) => ({
      category,
      levels: category.levels
        .map((level) => ({
          level,
          options: level.options.filter(
            (option) =>
              (all || known.has(option.code))
              && (section === 'ALL' || (option.section ?? 'GENERAL') === section),
          ),
        }))
        .filter((entry) => entry.options.length > 0),
    }))
    .filter((entry) => entry.levels.length > 0);

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto">
        {!all ? (
          <p className="text-11 text-ink-faint">{t('techReview.count', { n: acquired.length })}</p>
        ) : null}

        {categories.length === 0 ? (
          <p className="border border-space-700 px-3 py-6 text-center text-xs text-ink-dim">
            {all
              ? t('techReview.emptyAll')
              : t('techReview.emptyOwn')}
          </p>
        ) : null}

        {categories.map(({ category, levels }) => (
          <section key={category.code} className="border border-space-700">
            {/*
              Раздел дерева разворачивается по нажатию: восемь разделов MOO II с описаниями
              не помещаются на экран разом, а листать их прокруткой — терять то, что искали.
              Так же устроен и Reference оригинала: щёлкаешь по категории — раскрывается
              список её записей.
            */}
            <button
              type="button"
              className="link flex w-full items-baseline justify-between px-3 py-2 text-left"
              onClick={() => setOpenCategory(openCategory === category.code ? null : category.code)}
            >
              <span className="text-ink-bright">{category.name}</span>
              <span className="text-11 text-ink-faint">
                {t('techReview.techs', { n: levels.reduce((count, entry) => count + entry.options.length, 0) })}
              </span>
            </button>

            {openCategory === category.code ? (
              <ul className="space-y-2 border-t border-space-800 px-3 py-2 text-xs">
                {levels.map(({ level, options }) =>
                  options.map((option) => (
                    <li key={option.code}>
                      <div className="flex items-baseline gap-2">
                        <span className={known.has(option.code) ? 'text-accent' : 'text-ink'}>
                          {option.name}
                        </span>
                        <span className="text-11 text-ink-faint">
                          {t('techReview.level', { level: level.order, cost: level.cost })}
                          {option.sectionLabel ? ` · ${option.sectionLabel}` : ''}
                          {known.has(option.code)
                            ? t('techReview.researchedOn', { n: turnByCode.get(option.code) ?? 0 })
                            : ''}
                        </span>
                      </div>
                      <p className="leading-relaxed text-ink-dim">{option.description}</p>
                    </li>
                  )),
                )}
              </ul>
            ) : null}
          </section>
        ))}
      </div>

      {/* Кнопки разделов — вдоль низа списка, как в оригинале. */}
      <div className="flex flex-none flex-wrap gap-2 border-t border-space-700 pt-2">
        {SECTIONS.map((entry) => (
          <button
            key={entry.code}
            type="button"
            onClick={() => setSection(entry.code)}
            className={`border px-3 py-1 text-11 uppercase tracking-wider ${
              section === entry.code
                ? 'border-accent bg-space-800 text-accent'
                : 'border-space-700 bg-space-950 text-ink-soft hover:text-ink-bright'
            }`}
          >
            {entry.label}
          </button>
        ))}
      </div>
    </div>
  );
}
