import { useGameStore } from '../../state/gameStore';
import { eventMark, eventSections } from '../TurnResults';
import { useT } from '../../i18n';

/**
 * Итоги последнего хода — п. 11.1, отчёт Turn Summary консоли Info MOO II.
 *
 * Руководство оригинала: «этот отчёт хранит копию последнего итога хода. Полезная запись,
 * если окно итогов выключено, а случилось что-то странное — или просто нужно вспомнить».
 * Здесь ровно то же: диалог итогов закрывается один раз на ход, и вернуться к нему больше
 * неоткуда.
 *
 * Копия берётся из общего состояния, а не запросом: отчёт туда кладёт сам конец хода, и
 * он же переживает перезагрузку страницы — клиент забирает его с сервера при входе.
 */
export function TurnSummary() {
  const report = useGameStore((state) => state.turnReport);
  const { t } = useT();

  if (!report) {
    return (
      <p className="border border-space-700 px-3 py-6 text-center text-xs text-ink-dim">
        {t('turnSummary.none')}
      </p>
    );
  }

  const sections = eventSections(report);

  return (
    <div className="space-y-3">
      <p className="text-11 text-ink-faint">
        {t('turnSummary.header', { turn: report.turn, n: report.events.length })}
      </p>

      {sections.length === 0 ? (
        <p className="text-xs text-ink-dim">{t('turnSummary.quiet')}</p>
      ) : (
        sections.map((section) => (
          <section key={section.title}>
            <div className="text-10 uppercase tracking-[0.2em] text-ink-dim">
              {t(section.title)}
            </div>
            <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
              {section.events.map((event, index) => (
                <li
                  key={index}
                  className="flex gap-2 py-1 text-11 leading-tight text-ink"
                >
                  <span className="w-3 shrink-0 text-ink-faint">{eventMark(event)}</span>
                  <span>{event.text}</span>
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </div>
  );
}
