import { useMemo, useState } from 'react';
import { gameApi } from '../../api/client';
import type { RaceDesign } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Стоимость особенностей рас — п. 7: правка справочника прямо из главного меню.
 *
 * Экран нужен для баланса: цены сторон расы — самое частое, что хочется покрутить,
 * а лежат они в JSON-справочнике сервера. Отсюда правка уходит в тот же файл
 * (`resources/Races/race-traits.json`), поэтому переживает перезапуск сервера и видна
 * всем, кто сядет собирать расу следующим.
 *
 * Разметка повторяет окно race picks (`RacePicksScreen`): те же газетные столбцы, тот
 * же порядок групп из справочника, та же нижняя полоса. Отличие одно — вместо выбора
 * у каждой строки поле цены: экран правит справочник, а не собирает расу.
 *
 * Правится только цена и бюджет очков. Набор особенностей, их действия и описания живут
 * в файле и редактором не трогаются: менять их — дело файла, а не окна с числами.
 *
 * Сохраняется **только изменённое**: сервер пишет ровно те цены, что пришли, и не
 * переписывает файл целиком. Пока правка не сохранена, она живёт в экране — «сброс»
 * возвращает то, что лежит на сервере.
 */
export function RaceCostEditor({ onClose }: { onClose: () => void }) {
  const design = useGameStore((state) => state.raceDesign);
  const setRaceDesign = useGameStore((state) => state.setRaceDesign);

  /** Правки игрока: код особенности → новая цена. Пусто — менять нечего. */
  const [edits, setEdits] = useState<Record<string, number>>({});
  const [budget, setBudget] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useModalEscape(true, onClose);
  useT();

  const savedBudget = design?.picks ?? 0;
  const shownBudget = budget ?? savedBudget;

  /** Что уйдёт на сервер: только те цены, что игрок действительно изменил. */
  const changes = useMemo(() => {
    const byCode = new Map(
      (design?.groups ?? []).flatMap((group) => group.options.map((option) => [option.code, option])),
    );
    return Object.entries(edits)
      .filter(([code, picks]) => byCode.get(code)?.picks !== picks)
      .map(([code, picks]) => ({ code, picks }));
  }, [design, edits]);

  const budgetChanged = budget !== null && budget !== savedBudget;
  const dirty = changes.length > 0 || budgetChanged;

  const reset = () => {
    setEdits({});
    setBudget(null);
    setError(null);
    setSaved(null);
  };

  const save = () => {
    setBusy(true);
    setError(null);
    setSaved(null);
    gameApi.reference
      .saveRaceTraitCosts({
        picks: budgetChanged ? shownBudget : undefined,
        traits: changes,
      })
      .then((fresh: RaceDesign) => {
        // Показываем то, что действительно легло в файл: сервер отдаёт справочник заново.
        setRaceDesign(fresh);
        setEdits({});
        setBudget(null);
        setSaved(
          t('costs.saved', { n: changes.length, unit: plural(changes.length) }) +
            (budgetChanged ? t('costs.saved.budget', { n: shownBudget }) : ''),
        );
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="absolute left-3 uppercase tracking-[0.2em] text-ink-dim">
          {t('costs.reference')}
        </span>
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 uppercase tracking-[0.3em] text-accent">
          {t('costs.title')}
        </span>
      </header>

      <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
        <p className="mb-3 max-w-4xl leading-relaxed text-ink-dim">
          {t('costs.intro')}
        </p>

        {design ? (
          <div className="columns-1 gap-8 md:columns-2 xl:columns-3">
            {design.groups.map((group) => (
              <section key={group.code} className="mb-4 break-inside-avoid">
                <h3 className="mb-1 uppercase tracking-[0.2em] text-accent" title={group.description}>
                  {group.name}
                </h3>
                <ul>
                  {group.options.map((option) => {
                    const value = edits[option.code] ?? option.picks;
                    const changed = value !== option.picks;
                    return (
                      <li key={option.code} className="flex items-center gap-2 py-0.5 leading-tight">
                        <span
                          className={'min-w-0 shrink truncate ' + (changed ? 'text-accent' : 'text-ink-soft')}
                          title={describe(option.description, option.effects)}
                        >
                          {option.name}
                        </span>
                        <span
                          aria-hidden="true"
                          className="mx-1 min-w-[1rem] flex-1 translate-y-[-0.3em] border-b border-dotted border-space-600"
                        />
                        {changed ? (
                          <span className="text-10 text-ink-faint">{t('costs.was', { n: option.picks })}</span>
                        ) : null}
                        <input
                          type="number"
                          min={-20}
                          max={20}
                          value={value}
                          onChange={(event) =>
                            setEdits((current) => ({
                              ...current,
                              [option.code]: toPicks(event.target.value, option.picks),
                            }))
                          }
                          className={
                            'w-14 border bg-space-950 px-1 py-0.5 text-right ' +
                            (changed ? 'border-accent text-accent' : 'border-space-600 text-ink')
                          }
                        />
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))}
          </div>
        ) : (
          <p className="text-ink-faint">{t('costs.notLoaded')}</p>
        )}

        {error ? <p className="mt-3 text-danger">{error}</p> : null}
        {saved ? <p className="mt-3 text-accent">{saved}</p> : null}
      </div>

      <footer
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-between border-t border-space-600 bg-space-800 px-3 uppercase tracking-[0.2em] text-ink"
      >
        <span className="flex items-center gap-4">
          <BarButton onClick={onClose}>{t('common.close')}</BarButton>
          <BarButton onClick={reset} disabled={!dirty || busy}>
            {t('costs.reset')}
          </BarButton>
        </span>

        <span className="absolute left-1/2 flex -translate-x-1/2 items-center gap-2">
          <span className="text-ink-dim">{t('costs.budget')}</span>
          <input
            type="number"
            min={1}
            max={100}
            value={shownBudget}
            onChange={(event) => setBudget(toBudget(event.target.value, savedBudget))}
            className={
              'w-16 border bg-space-950 px-2 py-0.5 text-right ' +
              (budgetChanged ? 'border-accent text-accent' : 'border-space-600 text-ink')
            }
          />
        </span>

        <button
          type="button"
          disabled={!dirty || busy}
          className={
            'border px-4 py-0.5 uppercase tracking-[0.2em] ' +
            (dirty && !busy
              ? 'border-space-600 text-accent hover:bg-space-700'
              : 'pointer-events-none border-space-700 text-ink-faint')
          }
          onClick={save}
        >
          {busy ? t('costs.saving') : `${t('costs.save')}${dirty ? ` (${changes.length + (budgetChanged ? 1 : 0)})` : ''}`}
        </button>
      </footer>
    </div>
  );
}

/** Кнопка нижней полосы — тот же вид, что в окне race picks. */
function BarButton({
  children,
  onClick,
  disabled = false,
}: {
  children: string;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      className={
        'border px-4 py-0.5 uppercase tracking-[0.2em] ' +
        (disabled
          ? 'pointer-events-none border-space-700 text-ink-faint'
          : 'border-space-600 hover:bg-space-700 hover:text-accent')
      }
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/** Подсказка строки: описание особенности и то, что она меняет. */
function describe(
  description: string,
  effects: { type: string; amount: number }[],
): string {
  const parts = effects.map((effect) => `${effect.type} ${effect.amount}`);
  return parts.length === 0 ? description : `${description} (${parts.join(', ')})`;
}

/** Пустое поле — прежняя цена: незаполненное число сервер бы не принял. */
function toPicks(raw: string, fallback: number): number {
  const value = Number.parseInt(raw, 10);
  if (Number.isNaN(value)) {
    return fallback;
  }
  return Math.max(-20, Math.min(20, value));
}

function toBudget(raw: string, fallback: number): number {
  const value = Number.parseInt(raw, 10);
  if (Number.isNaN(value)) {
    return fallback;
  }
  return Math.max(1, Math.min(100, value));
}

function plural(count: number): string {
  const tail = count % 10;
  const hundred = count % 100;
  if (tail === 1 && hundred !== 11) {
    return t('costs.trait.1');
  }
  if (tail >= 2 && tail <= 4 && (hundred < 12 || hundred > 14)) {
    return t('costs.trait.few');
  }
  return t('costs.trait.many');
}
