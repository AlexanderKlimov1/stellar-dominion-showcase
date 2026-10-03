import { useRef, useState, type ReactNode } from 'react';
import { useColonistPick } from './useColonistPick';
import type { Planet } from '../../api/types';
import type { Colony } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { Colonist, HeldColonists, ResourceIcon, type ResourceKind } from './colonyIcons';
import { output, type Jobs } from './colonyRules';
import { TransferDialog } from './TransferDialog';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass, signTone, signed } from '../accent';

/**
 * Жители колонии — п. 4.1, панелью распределения экрана колонии MOO II.
 *
 * Три строки фигурок: фермеры, рабочие, учёные. Жители переносятся ЩЕЛЧКАМИ, как в
 * оригинале ({@link useColonistPick}): щелчок по фигурке берёт её и всех правее, второй
 * щелчок кладёт группу в строку под курсором, мимо строк — возвращает на место. Изменение
 * применяется сразу: подтверждать нечего, а вернуть жителей обратно — те же два щелчка.
 *
 * Каждая строка кончается тем, что эти жители дают: фермеры — едой, рабочие —
 * производством, учёные — наукой. Отдельной панели выработки нет: величина и те, кто её
 * делает, стоят в одной строке, и перенос жителя видно тут же, без взгляда на соседний
 * сегмент. Население с приростом — в заголовке панели: это тоже про жителей.
 *
 * Считает распределение {@link useColonyJobs} — этажом выше, потому что от него зависит
 * и стройка.
 *
 * С клавиатуры (Enter или пробел на фигурке) житель переходит на следующую работу по
 * кругу. Этого в MOO II нет, но фигурки — обычные кнопки, и без такого хода они были бы
 * недоступны с клавиатуры. Щелчок мышью этот ход не делает: у мыши свой путь, через группу.
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
  move: (from: JobName, to: JobName, count?: number) => void;
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
    { value: number; eaten?: ReactNode; balance?: number; delivered?: number; waste?: number } | null
  > = {
    farmers: produced
      ? { value: produced.food, eaten: tf('colony.jobs.eaten', { n: <Figure accent="value">{planet.population}</Figure> }), balance, delivered }
      : null,
    // Уборка за собой (п. 10) стоит в строке рабочих: грязь делают они, и видно сразу,
    // сколько из добытого до стройки не доедет.
    workers: produced ? { value: produced.production, waste: produced.pollution } : null,
    scientists: produced ? { value: produced.research } : null,
  };

  // Перевозка жителей в другую колонию — п. 4.1.1: диалог открывается отсюда, из
  // сегмента жителей, потому что везут именно их.
  const [transferring, setTransferring] = useState(false);

  const rows = useRef<Partial<Record<JobName, HTMLDivElement | null>>>({});

  const color = players.find((player) => player.id === planet.ownerPlayerId)?.color ?? '#7fd4ff';

  /** Строка, над которой сейчас курсор: рамки строк известны, попадание считаем по ним. */
  const rowUnder = (x: number, y: number): JobName | null =>
    JOB_ORDER.find((job) => {
      const rect = rows.current[job]?.getBoundingClientRect();
      return rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }) ?? null;

  const { held, pick } = useColonistPick<JobName, JobName>(
    (x, y) => rowUnder(x, y),
    (target, group) => move(group.from, target, group.count),
  );
  const hover = held ? rowUnder(held.x, held.y) : null;

  return (
    <section className="flex flex-col border border-space-700 bg-space-950/60">
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 border-b border-space-800 px-2 py-1">
        <span className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('colony.jobs.title')}</span>
        {colony ? (
          <span className={figureLabelClass('value')}>
            {own ? (
              <>
                <button
                  type="button"
                  className="link-quiet"
                  onClick={() => setTransferring(true)}
                >
                  {t('colony.jobs.transfer')}
                </button>
                {' · '}
              </>
            ) : null}
            {tf('colony.jobs.of', {
              n: <Figure accent="value">{planet.population}</Figure>,
              max: <Figure accent="value">{colony.maxPopulation}</Figure>,
            })}{' '}
            {t('colony.jobs.growth')}{' '}
            <Figure accent="value" tone={signTone(growth)}>{signed(growth)}</Figure>{' '}
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
                  {tf('colony.jobs.subjects', { n: <Figure accent="value" tone="warn">{colony.alienPopulation}</Figure> })}
                  {colony.assimilationTurns !== undefined
                    ? tf('colony.jobs.nextAssimilation', { n: <Figure accent="value" tone="warn">{colony.assimilationTurns}</Figure> })
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
              (hover === job && held?.from !== job ? 'bg-space-800' : '')
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
                      (own ? 'cursor-pointer' : 'cursor-default') +
                      // Взятые остаются на месте бледными: видно, откуда группа ушла и куда
                      // вернётся, если положить её мимо строк.
                      (held?.from === job && index >= jobs[job] - held.count ? ' opacity-30' : '')
                    }
                    disabled={!own}
                    draggable={false}
                    aria-label={t('colony.jobs.colonist.aria', { job: t(JOB_LABELS[job]), n: index + 1 })}
                    onPointerDown={own ? pick(job, jobs[job] - index) : undefined}
                    onDragStart={(event) => event.preventDefault()}
                    onClick={(event) => {
                      // Только клавиатура: у её «щелчка» нет точки нажатия (detail = 0).
                      if (event.detail !== 0) {
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
                  <span className={'leading-none ' + figureLabelClass('value')}>
                    {tf('colony.jobs.mined', { n: <Figure accent="value">{produced!.mined}</Figure> })}{' '}
                    <Figure accent="value" tone="warn">−{values[job]!.waste}</Figure>
                  </span>
                ) : null}
                {values[job]!.eaten ? (
                  <span className={'leading-none ' + figureLabelClass('value')}>
                    {values[job]!.delivered
                      ? tf('colony.jobs.delivered', { n: <Figure accent="value">{values[job]!.delivered}</Figure> })
                      : ''}
                    {values[job]!.eaten}{' '}
                    {/*
                      Цветом помечен только остаток: сама выработка — просто число, а
                      голод или запас видно с одного взгляда, не читая знака.
                    */}
                    <Figure accent="value" tone={values[job]!.balance! < 0 ? 'danger' : 'good'}>
                      {signed(values[job]!.balance!)}
                    </Figure>
                  </span>
                ) : null}
                <span className="min-w-[3ch] text-right leading-none">
                  <Figure accent="value">{values[job]!.value}</Figure>
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

      {/* Группа на руках: летит за курсором и не перехватывает события. */}
      {held ? <HeldColonists held={held} color={color} /> : null}
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
