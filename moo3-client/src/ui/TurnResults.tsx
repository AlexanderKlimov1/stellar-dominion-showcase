import { useGameStore } from '../state/gameStore';
import { useModalEscape } from './useModalEscape';
import type { TurnEvent, TurnReport } from '../api/types';
import { useT, type Key } from '../i18n';

/**
 * Результаты хода — диалог в начале каждого хода (п. 11.1).
 *
 * Ход считается один раз на всех, когда его закончили все игроки партии. Значит, почти
 * всё случившееся произошло не по нажатию этого игрока: сосед объявил войну, кто-то
 * познакомился с его расой, шпион вынес технологию, флоты встретились в чужой системе.
 * Без этого списка числа менялись бы молча, и понять причину было бы неоткуда — в MOO II
 * ту же роль играют всплывающие сообщения конца хода.
 *
 * Диалог модальный и закрывается один раз на ход: закрыл — до следующего хода не всплывает.
 * Список тот же, что лежит в отчёте на сервере, поэтому переживает перезагрузку страницы.
 */

/**
 * Разделы списка: события одного вида идут вместе, а не вперемешку по времени.
 * Заголовок — ключ словаря: раздел переводится при показе, на языке игрока.
 */
const SECTIONS: { codes: string[]; title: Key }[] = [
  { codes: ['DIPLOMACY'], title: 'turnResults.section.diplomacy' },
  { codes: ['ENCOUNTER', 'BATTLE', 'EXPLORATION', 'FLEET_ARRIVED'], title: 'turnResults.section.fleets' },
  { codes: ['TRANSFER'], title: 'turnResults.section.transfers' },
  { codes: ['ESPIONAGE'], title: 'turnResults.section.espionage' },
  { codes: ['INVASION'], title: 'turnResults.section.invasions' },
  { codes: ['GALACTIC'], title: 'turnResults.section.galaxy' },
  { codes: ['RESEARCH'], title: 'turnResults.section.research' },
  { codes: ['BUILDING', 'COLONY_BASE', 'SHIP', 'SPY', 'FREIGHTER'], title: 'turnResults.section.build' },
  { codes: ['POPULATION', 'LOSS', 'INCOME'], title: 'turnResults.section.empire' },
];

/**
 * Вид события, которое случается каждый ход и само по себе окна не стоит.
 *
 * Пополнение казны идёт каждый ход и у всех: если открывать итоги ради него, диалог
 * всплывал бы перед игроком всегда и обесценился бы — на него перестают смотреть, а
 * вместе с ним и на всё остальное. Поэтому доход в списке остаётся, но окна не открывает.
 *
 * Убыточная колония (`LOSS`) — уже не рутина, а повод вмешаться, и своим кодом она
 * открывает окно наравне с остальными событиями.
 */
const ROUTINE = 'INCOME';

/**
 * Стоит ли показывать итоги хода: есть ли в них хоть что-то, кроме рутины.
 *
 * Живёт здесь, рядом с разделами и значками: решение «показывать ли окно» и то, что
 * в этом окне нарисовано, должны меняться вместе.
 */
export const worthShowing = (report: TurnReport): boolean =>
  report.events.some((event) => event.code !== ROUTINE);

/**
 * Система, которую флот разведал на этом ходу, — п. 15; `undefined`, если разведки не было.
 *
 * Разведка это событие места: флот пришёл туда, где ещё не бывали, и сервер записал в
 * событие саму систему. Открыть её игроку нужно сразу — ради этого корабль и посылали, а
 * искать по карте, какая из звёзд перестала быть безымянной, ему негде.
 *
 * Берётся первая: за ход может разведаться несколько систем (флотов у империи много), и
 * открыть все разом всё равно нельзя. Остальные останутся в итогах хода строками.
 */
export const exploredSystemId = (report: TurnReport | null): string | undefined =>
  report?.events.find((event) => event.code === EXPLORATION && event.systemId)?.systemId;

/** Код события разведки в отчёте хода — тот же, что у сервера. */
const EXPLORATION = 'EXPLORATION';

/** Значок вида события — тот же словарь, что у отчёта сервера. */
const MARKS: Record<string, string> = {
  POPULATION: '▲',
  BUILDING: '■',
  COLONY_BASE: '☼',
  SHIP: '➤',
  SPY: '◆',
  INCOME: '¤',
  LOSS: '▼',
  RESEARCH: '✶',
  ESPIONAGE: '✖',
  DIPLOMACY: '✦',
  INVASION: '⚔',
  ENCOUNTER: '➤',
  BATTLE: '⚔',
  EXPLORATION: '✧',
  FLEET_ARRIVED: '➤',
  FREIGHTER: '▣',
  TRANSFER: '▣',
  GALACTIC: '✷',
};

/**
 * События хода по разделам — тот же список, что и в диалоге итогов.
 *
 * Отдан наружу ради страницы «Итоги хода» окна «Инфо» (п. 11.1): в MOO II консоль Info
 * «хранит копию последнего итога хода», и копия обязана выглядеть так же, как сам итог, —
 * иначе одно и то же событие читалось бы в двух местах по-разному.
 */
export function eventSections(report: TurnReport): { title: Key; events: TurnEvent[] }[] {
  // Событие, вид которого в разделах не назван, всё равно должно быть видно: список
  // событий растёт, а пропасть из итогов не должно ничего.
  const known = new Set(SECTIONS.flatMap((section) => section.codes));
  return SECTIONS.map((section) => ({
    title: section.title,
    events: report.events.filter((event) => section.codes.includes(event.code)),
  }))
    .concat({
      title: 'turnResults.section.other',
      events: report.events.filter((event) => !known.has(event.code)),
    })
    .filter((section) => section.events.length > 0);
}

/** Значок вида события — им же помечены строки в окне «Инфо». */
export function eventMark(event: TurnEvent): string {
  return MARKS[event.code] ?? '·';
}

export function TurnResults({ report, onClose }: { report: TurnReport; onClose: () => void }) {
  const players = useGameStore((state) => state.players);
  const { t } = useT();
  useModalEscape(true, onClose);

  const sections = eventSections(report);

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex max-h-full w-full max-w-2xl flex-col overflow-auto">
        <div className="flex items-baseline justify-between border-b border-space-700 pb-2">
          <h2 className="text-sm uppercase tracking-[0.3em] text-ink-bright">
            {t('turnResults.title', { n: report.turn })}
          </h2>
          <span className="text-11 text-ink-faint">
            {t('turnResults.events', { n: report.events.length })}
            {players.length > 0 ? t('turnResults.empires', { n: players.length }) : ''}
          </span>
        </div>

        <div className="mt-3 flex flex-col gap-3">
          {sections.map((section) => (
            <section key={section.title}>
              <div className="text-10 uppercase tracking-[0.2em] text-ink-dim">
                {t(section.title)}
              </div>
              <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
                {section.events.map((event, index) => (
                  <li key={index} className="flex gap-2 py-1 text-11 leading-tight text-ink">
                    <span className="w-3 shrink-0 text-ink-faint">{mark(event)}</span>
                    <span>{event.text}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>

        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-xs">
          <button type="button" className="link" onClick={onClose}>
            {t('common.closeEsc')}
          </button>
        </div>
      </div>
    </div>
  );
}

function mark(event: TurnEvent): string {
  return eventMark(event);
}
