import { useEffect, useRef, useState } from 'react';
import type { Planet } from '../../api/types';
import type { Colony } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { Colonist, ResourceIcon, type ResourceKind } from './colonyIcons';
import { output, type Jobs } from './colonyRules';
import { TransferDialog } from './TransferDialog';
import { t, useT, type Key } from '../../i18n';

/**
 * Жители колонии — п. 4.1, панелью распределения экрана колонии MOO II.
 *
 * Три строки фигурок: фермеры, рабочие, учёные. Житель переносится на другую работу
 * перетаскиванием — фигурка следует за курсором и встаёт в ту строку, над которой её
 * отпустили. Изменение применяется сразу: подтверждать нечего, а вернуть жителя обратно —
 * то же перетаскивание.
 *
 * Каждая строка кончается тем, что эти жители дают: фермеры — едой, рабочие —
 * производством, учёные — наукой. Отдельной панели выработки нет: величина и те, кто её
 * делает, стоят в одной строке, и перенос жителя видно тут же, без взгляда на соседний
 * сегмент. Население с приростом — в заголовке панели: это тоже про жителей.
 *
 * Считает распределение {@link useColonyJobs} — этажом выше, потому что от него зависит
 * и стройка.
 *
 * Клик по фигурке переводит жителя на следующую работу по кругу. Этого в MOO II нет, но
 * фигурки — обычные кнопки, и без такого хода они были бы недоступны с клавиатуры.
 */

type JobName = keyof Jobs;

const JOB_ORDER: JobName[] = ['farmers', 'workers', 'scientists'];

const JOB_LABELS: Record<JobName, Key> = {
  farmers: 'colony.job.farmers',
  workers: 'colony.job.workers',
  scientists: 'colony.job.scientists',
};

export function ColonyJobs({
  planet,
  colony,
  jobs,
  move,
  error,
  own,
  growth,
}: {
  planet: Planet;
  colony?: Colony;
  jobs: Jobs;
  move: (from: JobName, to: JobName) => void;
  error: string | null;
  own: boolean;
  growth: number;
}) {
  const players = useGameStore((state) => state.players);
  useT();

  // Величины считаются от черновика распределения: фигурки переставляются не дожидаясь
  // сервера, и число в строке должно меняться вместе с ними.
  const produced = colony ? output(colony, jobs) : null;
  // Остаток еды после прокорма: он же и решает, растёт колония или голодает. Подвоз
  // грузового флота империи (п. 4.1.1) считает сервер — он знает все колонии, а не одну.
  const delivered = colony?.foodDelivered ?? 0;
  const balance = produced ? produced.food - planet.population + delivered : 0;
  const values: Record<
    JobName,
    { value: number; eaten?: string; balance?: number; delivered?: number; waste?: number } | null
  > = {
    farmers: produced
      ? { value: produced.food, eaten: t('colony.jobs.eaten', { n: planet.population }), balance, delivered }
      : null,
    // Уборка за собой (п. 10) стоит в строке рабочих: грязь делают они, и видно сразу,
    // сколько из добытого до стройки не доедет.
    workers: produced ? { value: produced.production, waste: produced.pollution } : null,
    scientists: produced ? { value: produced.research } : null,
  };

  // Перевозка жителей в другую колонию — п. 4.1.1: диалог открывается отсюда, из
  // сегмента жителей, потому что везут именно их.
  const [transferring, setTransferring] = useState(false);

  const [drag, setDrag] = useState<{ from: JobName; x: number; y: number } | null>(null);
  const [hover, setHover] = useState<JobName | null>(null);

  const rows = useRef<Partial<Record<JobName, HTMLDivElement | null>>>({});
  /** Двигали ли фигурку в текущем переносе: без движения это был клик. */
  const dragged = useRef(false);
  /**
   * Момент, когда перенос состоялся. Браузер сопровождает отпускание указателя кликом
   * по той же кнопке, и этот клик нужно пропустить — иначе житель сменит работу дважды.
   * Отметка времени, а не флаг: залипнуть она не может, даже если клик не придёт.
   */
  const draggedAt = useRef(0);

  const color = players.find((player) => player.id === planet.ownerPlayerId)?.color ?? '#7fd4ff';

  /** Строка, над которой сейчас курсор: рамки строк известны, попадание считаем по ним. */
  const rowUnder = (x: number, y: number): JobName | null =>
    JOB_ORDER.find((job) => {
      const rect = rows.current[job]?.getBoundingClientRect();
      return rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }) ?? null;

  const startDrag = (job: JobName) => (event: React.PointerEvent<HTMLButtonElement>) => {
    if (!own) {
      return;
    }
    /*
      Указатель захватывается фигуркой, а само нажатие отменяется. Без этого браузер
      берётся за жест сам: на кнопке с рисунком он начинает своё перетаскивание, а на
      соседних числах — выделение текста. И то и другое обрывает череду указателя
      событием `pointercancel`, после которого ни `pointermove`, ни `pointerup` уже не
      приходят: житель повисал на курсоре, и перенос не доходил до строки.
    */
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    dragged.current = false;
    setDrag({ from: job, x: event.clientX, y: event.clientY });
  };

  /**
   * Пока жителя несут, за курсором следит всё окно, а не сама фигурка: рука уходит
   * с кнопки в первый же момент переноса, и отпустить жителя можно где угодно.
   */
  useEffect(() => {
    if (!drag) {
      return;
    }

    const onMove = (event: PointerEvent) => {
      dragged.current = true;
      setDrag((current) => (current ? { ...current, x: event.clientX, y: event.clientY } : current));
      setHover(rowUnder(event.clientX, event.clientY));
    };

    const onUp = (event: PointerEvent) => {
      const target = rowUnder(event.clientX, event.clientY);
      if (dragged.current && target && target !== drag.from) {
        draggedAt.current = Date.now();
        move(drag.from, target);
      }
      dragged.current = false;
      setDrag(null);
      setHover(null);
    };

    // Череду указателя браузер может оборвать и сам — окном поверх, сменой вкладки,
    // системным жестом. Тогда `pointerup` не придёт вовсе, и без этого обработчика
    // фигурка осталась бы висеть на курсоре, а следующее нажатие считалось бы переносом.
    const onCancel = () => {
      dragged.current = false;
      setDrag(null);
      setHover(null);
    };

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onCancel);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onCancel);
    };
  });

  return (
    <section className="flex flex-col border border-space-700 bg-space-950/60">
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 border-b border-space-800 px-2 py-1">
        <span className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('colony.jobs.title')}</span>
        {colony ? (
          <span className="text-20 text-ink-faint">
            {own ? (
              <>
                <button
                  type="button"
                  className="link text-20"
                  onClick={() => setTransferring(true)}
                >
                  {t('colony.jobs.transfer')}
                </button>
                {' · '}
              </>
            ) : null}
            <span className="text-ink-bright">
              {t('colony.jobs.of', { n: planet.population, max: colony.maxPopulation })}
            </span>{' '}
            {t('colony.jobs.growth')}{' '}
            <span className={growth < 0 ? 'text-danger' : 'text-ink-bright'}>
              {growth >= 0 ? `+${growth}` : growth}
            </span>{' '}
            {t('colony.jobs.perTurn')}
            {/* Подданные — п. 12: взятые с боем жители работают по правилам своей прежней
                расы, пока не станут своими. */}
            {colony.alienPopulation > 0 ? (
              <>
                {' · '}
                <span
                  className="text-warn"
                  title={t('colony.jobs.subjects.title')}
                >
                  {t('colony.jobs.subjects', { n: colony.alienPopulation })}
                  {colony.assimilationTurns !== undefined
                    ? t('colony.jobs.nextAssimilation', { n: colony.assimilationTurns })
                    : ''}
                </span>
              </>
            ) : null}
          </span>
        ) : null}
      </div>

      <div className="flex-1">
        {JOB_ORDER.map((job) => (
          <div
            key={job}
            ref={(element) => {
              rows.current[job] = element;
            }}
            className={
              'flex touch-none select-none items-center gap-2 border-b border-space-800'
              + ' px-2 py-1.5 last:border-b-0 ' +
              (hover === job && drag?.from !== job ? 'bg-space-800' : '')
            }
          >
            <JobIcon job={job} />
            <ul className="flex min-h-[22px] flex-1 flex-wrap content-center gap-[4px]">
              {Array.from({ length: jobs[job] }).map((_, index) => (
                <li key={index}>
                  <button
                    type="button"
                    className={
                      'block ' +
                      (own ? 'cursor-grab active:cursor-grabbing' : 'cursor-default') +
                      (drag?.from === job && index === jobs[job] - 1 ? ' opacity-30' : '')
                    }
                    disabled={!own}
                    draggable={false}
                    aria-label={t('colony.jobs.colonist.aria', { job: t(JOB_LABELS[job]), n: index + 1 })}
                    onPointerDown={startDrag(job)}
                    onDragStart={(event) => event.preventDefault()}
                    onClick={() => {
                      // Клик сразу за переносом — тот самый, которым браузер сопровождает
                      // отпускание указателя: житель уже сменил работу.
                      if (Date.now() - draggedAt.current < 300) {
                        return;
                      }
                      move(job, JOB_ORDER[(JOB_ORDER.indexOf(job) + 1) % JOB_ORDER.length]);
                    }}
                  >
                    <Colonist color={color} />
                  </button>
                </li>
              ))}
            </ul>

            {/*
              Итог строки: что эти жители дают за ход. Межстрочный интервал сжат
              (leading-none): иначе высоту полосы задавало бы это число, а не фигурки,
              и уменьшать значки было бы бессмысленно.
            */}
            {values[job] ? (
              <span className="flex shrink-0 items-baseline gap-2">
                {values[job]!.waste ? (
                  /*
                    Грязь показана тем же складом, что и еда: сперва причина, потом
                    число со знаком. Добытое рядом с ним не пишем — оно и есть сумма
                    этих двух, а строка и так тесная.
                  */
                  <span className="text-20 leading-none text-ink-faint">
                    {t('colony.jobs.mined', { n: produced!.mined })}{' '}
                    <span className="text-warn">−{values[job]!.waste}</span>
                  </span>
                ) : null}
                {values[job]!.eaten ? (
                  <span className="text-20 leading-none text-ink-faint">
                    {values[job]!.delivered ? t('colony.jobs.delivered', { n: values[job]!.delivered }) : ''}
                    {values[job]!.eaten}{' '}
                    {/*
                      Цветом помечен только остаток: сама выработка — просто число, а
                      голод или запас видно с одного взгляда, не читая знака.
                    */}
                    <span className={values[job]!.balance! < 0 ? 'text-danger' : 'text-good'}>
                      {values[job]!.balance! >= 0 ? `+${values[job]!.balance}` : values[job]!.balance}
                    </span>
                  </span>
                ) : null}
                <span className="min-w-[3ch] text-right text-22 leading-none text-ink-bright">
                  {values[job]!.value}
                </span>
              </span>
            ) : null}
          </div>
        ))}
      </div>

      <p className="border-t border-space-800 px-2 py-1 text-20 leading-tight text-ink-faint">
        {own ? t('colony.jobs.hint.own') : t('colony.jobs.hint.foreign')}
      </p>
      {error ? <p className="px-2 pb-1 text-20 text-danger">{error}</p> : null}

      {/* Перевозка жителей — п. 4.1.1: грузовики резервируются по одному на жителя. */}
      {transferring ? (
        <TransferDialog from={planet} onClose={() => setTransferring(false)} />
      ) : null}

      {/* Фигурка, которую несут: летит за курсором и не перехватывает события. */}
      {drag ? (
        <div
          className="pointer-events-none fixed z-50"
          style={{ left: drag.x - 7, top: drag.y - 10 }}
        >
          <Colonist color={color} />
        </div>
      ) : null}
    </section>
  );
}

/**
 * Значок занятия слева от строки: мешок зерна, молоток и микроскоп — ровно те же
 * рисунки, что стоят у еды, производства и науки в панели выработки. Житель и то, что
 * он даёт, помечены одинаково, и строку жителей с величиной выработки видно парой.
 */
function JobIcon({ job }: { job: JobName }) {
  const kinds: Record<JobName, ResourceKind> = {
    farmers: 'grain',
    workers: 'hammer',
    scientists: 'microscope',
  };

  // На 30% меньше прежнего: полоса жителей стала ниже, а разобрать значок это не мешает.
  return <ResourceIcon kind={kinds[job]} label={JOB_LABELS[job]}
                       className="h-[22px] w-[22px] shrink-0 text-ink-dim" />;
}
