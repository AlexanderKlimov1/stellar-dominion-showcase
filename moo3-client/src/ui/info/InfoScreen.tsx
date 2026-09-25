import { useEffect, useState } from 'react';

import { gameApi } from '../../api/client';
import type { EmpireInfo } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { EmpireBudget } from './EmpireBudget';
import { EmpireProfiles } from './EmpireProfiles';
import { GalaxyReference } from './GalaxyReference';
import { HistoryChart } from './HistoryChart';
import { METRICS, metricByCode } from './infoMetrics';
import { TechnologyReview } from './TechnologyReview';
import { TurnSummary } from './TurnSummary';
import { t, useT, type Key } from '../../i18n';

/** Отчёты консоли — те же пять, что и селекторы окна Info MOO II. */
type Report = 'history' | 'tech' | 'races' | 'summary' | 'reference';

/** Игра начинается с 3000 галактического года, каждый ход — год; так же в правой панели. */
const GALACTIC_YEAR_ZERO = 3000;

/**
 * Селекторы левой колонки — п. 11.1.
 *
 * Порядок и состав из руководства оригинала: History Graph, Tech Review, Race Statistics,
 * Turn Summary, Reference. Шестого отчёта в консоли нет и здесь не заводим: справочник по
 * галактике — часть Reference, как и в оригинале, где Reference это «библиотека помощи».
 */
const REPORTS: { code: Report; label: Key; hint: Key }[] = [
  { code: 'history', label: 'info.report.history', hint: 'info.report.history.hint' },
  { code: 'tech', label: 'info.report.tech', hint: 'info.report.tech.hint' },
  { code: 'races', label: 'info.report.races', hint: 'info.report.races.hint' },
  { code: 'summary', label: 'info.report.summary', hint: 'info.report.summary.hint' },
  { code: 'reference', label: 'info.report.reference', hint: 'info.report.reference.hint' },
];

/**
 * Консоль «Инфо» — п. 11.1, окно Information MOO II во весь экран.
 *
 * Разметка снята с руководства оригинала, которое описывает её сегментами: «начнём с тех
 * частей консоли, которые не меняются. Текущая звёздная дата отмечена в верхнем левом
 * углу. В нижнем левом — разбивка бюджета за ход. Между ними — селекторы, управляющие
 * тем, что показано в остальной части консоли». Отсюда и разделение:
 *
 * * **левая колонка** во всю высоту — три несменяемых сегмента: дата сверху, селекторы
 *   посередине, бюджет снизу;
 * * **правая часть** — выбранный отчёт, и под ним полоса кнопок этого отчёта: величины
 *   графика или разделы списка технологий. В оригинале они стоят «вдоль низа», а не
 *   сбоку, — здесь так же;
 * * **правый нижний угол** — «Вернуться», кнопка Return оригинала.
 *
 * <i>Размеры</i> — реконструкция по пропорциям: пиксельных размеров консоли (640×480 в
 * оригинале) справочники не публикуют, поэтому левая колонка занимает около четверти
 * ширины, как на снимках окна, а не задана числом пикселей. Экран при этом
 * полноэкранный: в MOO II консоль Info закрывает карту целиком.
 *
 * Летопись и стороны рас приходят одним запросом при открытии: они меняются каждый ход и
 * от чужих действий, поэтому в общем состоянии их не держим — консоль показывала бы
 * позавчерашний график.
 */
export function InfoScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const researchTree = useGameStore((state) => state.researchTree);
  const research = useGameStore((state) => state.research);

  const [report, setReport] = useState<Report>('history');
  const [metricCode, setMetricCode] = useState(METRICS[0].code);
  const [info, setInfo] = useState<EmpireInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Экран модальный, и Esc достаётся верхнему в стопке — п. 11.1, см. useModalEscape.
  useModalEscape(true, onClose);
  useT();

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .getInfo(game.id, credentials.accessToken)
      .then(setInfo)
      .catch((failure: Error) => setError(failure.message));
  }, [game, credentials]);

  const metric = metricByCode(metricCode);
  const chosen = REPORTS.find((entry) => entry.code === report) ?? REPORTS[0];

  return (
    <div className="absolute inset-0 z-40 flex bg-space-950 text-ink">
      {/* Левая колонка оригинала: дата — селекторы — бюджет, снизу доверху одна и та же. */}
      <aside className="flex w-[16rem] flex-none flex-col border-r border-space-700 bg-space-900/60">
        <div className="flex-none border-b border-space-700 px-3 py-2">
          <div className="panel-title">{t('info.stardate')}</div>
          <div className="text-22 leading-tight text-accent">
            {info ? GALACTIC_YEAR_ZERO + info.turn - 1 : GALACTIC_YEAR_ZERO + (game?.turn ?? 1) - 1}
          </div>
          <div className="text-11 text-ink-faint">
            {info ? t('info.turn', { n: info.turn }) : t('info.loading')}
          </div>
        </div>

        <nav className="min-h-0 flex-1 overflow-y-auto p-2">
          {REPORTS.map((entry) => (
            <button
              key={entry.code}
              type="button"
              onClick={() => setReport(entry.code)}
              className={`mb-1 block w-full border px-3 py-2 text-left text-12 uppercase tracking-[0.15em] ${
                report === entry.code
                  ? 'border-accent bg-space-800 text-accent'
                  : 'border-space-700 bg-space-950 text-ink-soft hover:text-ink-bright'
              }`}
            >
              {t(entry.label)}
            </button>
          ))}
          <p className="mt-2 px-1 text-11 leading-relaxed text-ink-faint">{t(chosen.hint)}</p>
        </nav>

        <EmpireBudget />
      </aside>

      {/* Правая часть: заголовок отчёта, сам отчёт, полоса кнопок отчёта и «Вернуться». */}
      <section className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex flex-none items-baseline justify-between border-b border-space-700 px-4 py-2">
          <h2 className="text-20 uppercase tracking-[0.3em] text-ink-bright">{t(chosen.label)}</h2>
          {error ? <span className="text-11 text-danger">{error}</span> : null}
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
          {report === 'history' ? (
            info ? (
              <HistoryChart empires={info.empires} metric={metric} />
            ) : (
              <p className="text-xs text-ink-faint">{t('info.historyLoading')}</p>
            )
          ) : null}

          {report === 'tech' ? (
            <TechnologyReview tree={researchTree} acquired={research?.acquired ?? []} all={false} />
          ) : null}

          {report === 'races' ? (
            info ? (
              <EmpireProfiles empires={info.empires} />
            ) : (
              <p className="text-xs text-ink-faint">{t('info.infoLoading')}</p>
            )
          ) : null}

          {report === 'summary' ? <TurnSummary /> : null}

          {report === 'reference' ? (
            <div className="space-y-6">
              <GalaxyReference />
              <section>
                <div className="panel-title mb-2">{t('info.allTech')}</div>
                <TechnologyReview
                  tree={researchTree}
                  acquired={research?.acquired ?? []}
                  all
                />
              </section>
            </div>
          ) : null}
        </div>

        {/*
          Полоса кнопок отчёта — в оригинале она идёт вдоль низа: у графика это величины,
          у списка технологий — четыре раздела. Своя полоса у каждого отчёта, поэтому и
          рисуется она отчётом; здесь остаются только величины графика и «Вернуться».
        */}
        <footer className="flex flex-none items-center gap-3 border-t border-space-700 px-4 py-2">
          {report === 'history' ? (
            <div className="flex flex-wrap gap-2">
              {METRICS.map((entry) => (
                <button
                  key={entry.code}
                  type="button"
                  onClick={() => setMetricCode(entry.code)}
                  className={`border px-3 py-1 text-11 uppercase tracking-wider ${
                    metric.code === entry.code
                      ? 'border-accent bg-space-800 text-accent'
                      : 'border-space-700 bg-space-950 text-ink-soft hover:text-ink-bright'
                  }`}
                >
                  {t(entry.label)}
                </button>
              ))}
            </div>
          ) : (
            <p className="text-11 leading-tight text-ink-faint">
              {report === 'races'
                ? t('info.note.races')
                : report === 'summary'
                  ? t('info.note.summary')
                  : report === 'tech'
                    ? t('info.note.tech')
                    : t('info.note.reference')}
            </p>
          )}

          <button
            type="button"
            className="ml-auto border border-space-600 bg-space-800 px-4 py-1 text-11 uppercase tracking-wider text-ink hover:text-accent"
            onClick={onClose}
          >
            {t('info.back')}
          </button>
        </footer>
      </section>
    </div>
  );
}
