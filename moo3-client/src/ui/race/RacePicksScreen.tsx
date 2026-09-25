import { useMemo, useState } from 'react';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { RaceGroup } from './RaceGroup';
import { PLACED_GROUPS, RACE_COLUMNS } from './raceColumns';
import { returnedPicks, spentPicks, toggleInGroup } from './racePicks';
import { useT } from '../../i18n';

/**
 * Экран сборки своей расы — п. 7, окном race picks MOO II.
 *
 * Разметка снята со снимка оригинала (StrategyWiki, Race design options): окно, в нём
 * название расы плашкой по центру сверху, три столбца сторон и снизу полоса
 * CLEAR — PICKS — ACCEPT. Списки раскрыты всегда, выбранное помечено прямоугольной
 * кнопкой — см. `RaceGroup`.
 *
 * **Столбцы назначены таблицей** ({@link RACE_COLUMNS}), а не текут по порядку
 * справочника: слева лестницы хозяйства, посередине война с разведкой и правительства,
 * справа — ОДИН список особых способностей, в который оригинал сводит всё остальное.
 * Газетные колонки (CSS columns) раскладывали группы в порядке файла, и правительство
 * оказывалось первым в левом столбце, а восемь мелких групп — россыпью заголовков.
 *
 * Палитра — не оригинальная зелёная, а та же, что у правой панели карты: поле
 * `space-900`, разделители `space-700`, заголовки акцентом, нижняя полоса `space-800`,
 * как у кнопки хода. Экраны игры стоят рядом, и разной палитрой они читались бы как
 * два разных приложения.
 *
 * Счётчика SCORE из оригинала здесь нет: он показывает силу расы в процентах, а формулы
 * оценки у игры пока нет — писать в рамку выдуманное число хуже, чем не писать ничего.
 * Точка расширения: оценка встанет рядом с очками.
 */
export function RacePicksScreen({
  raceName,
  traits: initialTraits,
  confirmLabel,
  onAccept,
  onBack,
}: {
  /** Название расы: в оригинале оно стоит в рамке над столбцами. */
  raceName: string;
  traits: string[];
  /** Подпись ACCEPT: экран заканчивает и создание партии, и вход в чужую. */
  confirmLabel: string;
  onAccept: (traits: string[]) => void;
  onBack: () => void;
}) {
  const design = useGameStore((state) => state.raceDesign);
  const [traits, setTraits] = useState<string[]>(initialTraits);
  const { t } = useT();

  useModalEscape(true, onBack);

  const spent = useMemo(() => spentPicks(design, traits), [design, traits]);
  const returned = useMemo(() => returnedPicks(design, traits), [design, traits]);
  const budget = design?.picks ?? 0;
  const antiBudget = design?.antiPicks ?? 0;
  const left = budget - spent;
  // Перебрать можно двумя способами, и они независимы: потратить больше бюджета или
  // вернуть слабостями больше потолка. Вторая раса укладывается в бюджет и всё равно
  // незаконна, поэтому «Ок» гасится по обоим числам, а не по одному остатку.
  const overspent = left < 0;
  const oversold = returned > antiBudget;

  const byCode = new Map((design?.groups ?? []).map((group) => [group.code, group]));
  // Группа, которой не нашлось места в таблице столбцов, уходит в правый последней:
  // новая группа справочника должна быть видна игроку, а не пропасть молча.
  const forgotten = (design?.groups ?? []).filter((group) => !PLACED_GROUPS.has(group.code));

  const pick = (groupCode: string) => (code: string) =>
    setTraits((chosen) => toggleInGroup(design, chosen, groupCode, code));

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(96vw,72rem)] border border-space-600 bg-space-900/95 p-[2.5%] shadow-lg shadow-black/60">
        {/* Название расы плашкой по центру — заголовок окна оригинала. */}
        <h2 className="mx-auto mb-[2.5%] w-[42%] border border-space-600 bg-space-800 py-1.5 text-center text-18 uppercase tracking-[0.3em] text-accent">
          {raceName.trim() || t('race.picks.unnamed')}
        </h2>

        {design ? (
          <div className="flex items-start gap-[4%] text-13">
            {RACE_COLUMNS.map((column, index) => (
              <div key={index} className="min-w-0 flex-1">
                {column.heading ? (
                  <h3 className="mb-1 uppercase tracking-[0.2em] text-accent">
                    {byCode.get('special')?.name ?? ''}
                  </h3>
                ) : null}
                {[...column.groups, ...(index === RACE_COLUMNS.length - 1
                  ? forgotten.map((group) => group.code)
                  : [])]
                  .map((code) => byCode.get(code))
                  .filter(Boolean)
                  .map((group) => (
                    <RaceGroup
                      key={group!.code}
                      design={design}
                      group={group!}
                      traits={traits}
                      headless={Boolean(column.heading)}
                      onPick={pick(group!.code)}
                    />
                  ))}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-ink-faint">{t('race.picks.notLoaded')}</p>
        )}

      {/*
        Нижняя полоса оригинала: CLEAR слева, счётчик очков по центру, ACCEPT справа.
        Возврат к именам — там же слева: в оригинале отсюда некуда возвращаться, раса
        собирается в один заход, а у нас перед этим экраном стоит диалог с именами.
      */}
        <footer className="relative mt-[2.5%] flex items-center justify-between border-t border-space-700 pt-3 text-13 uppercase tracking-[0.2em] text-ink">
          <span className="flex items-center gap-3">
            <BarButton onClick={onBack}>{t('race.picks.back')}</BarButton>
            <BarButton onClick={() => setTraits([])}>{t('race.picks.clear')}</BarButton>
          </span>

        <span className="absolute left-1/2 flex -translate-x-1/2 items-center gap-2">
          <span className="text-ink-dim">{t('race.picks.points')}</span>
          <span
            className={
              'border border-space-600 bg-space-950 px-3 py-0.5 ' +
              (overspent ? 'text-danger' : 'text-accent')
            }
          >
            {left}
          </span>
          <span className="text-ink-faint">{t('race.picks.of', { n: budget })}</span>
          <span className="ml-4 text-ink-dim">{t('race.picks.weaknesses')}</span>
          <span
            className={
              'border border-space-600 bg-space-950 px-3 py-0.5 ' +
              (oversold ? 'text-danger' : 'text-accent')
            }
          >
            {returned}
          </span>
          <span className="text-ink-faint">{t('race.picks.of', { n: antiBudget })}</span>
        </span>

        <button
          type="button"
          className={
            'border px-4 py-0.5 uppercase tracking-[0.2em] ' +
            (overspent || oversold
              ? 'pointer-events-none border-space-700 text-ink-faint'
              : 'border-space-600 text-accent hover:bg-space-700')
          }
          onClick={() => onAccept(traits)}
        >
          {confirmLabel}
        </button>
        </footer>
      </section>
    </div>
  );
}

/** Кнопка нижней полосы: тот же вид, что у кнопок оригинала — рамка и разрядка. */
function BarButton({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className="border border-space-600 px-4 py-0.5 uppercase tracking-[0.2em] hover:bg-space-700 hover:text-accent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}
