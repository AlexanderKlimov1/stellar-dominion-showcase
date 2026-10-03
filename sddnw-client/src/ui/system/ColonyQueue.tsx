import { useState, type ReactNode } from 'react';
import { BuyCoin } from '../colonies/BuyCoin';
import type { Colony, ColonyProject } from '../../api/types';
import { queueEta, queueStalled, turnsLeft } from './colonyRules';
import { QUEUE_LIMIT } from './buildLimits';
import { t, tf } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Очередь стройки колонии — п. 10, ОДНИМ списком на оба экрана.
 *
 * Показывают её двое: узкая панель стройки на экране колонии (`ColonyBuild`) и окно
 * стройки во весь экран (`ColonyBuildScreen`). Раньше у каждого был свой список, и они
 * разошлись в главном: на экране колонии текущая стройка стояла ПЕРВОЙ СТРОКОЙ той же
 * очереди, а в окне стройки — отдельной панелью, и очередь там начиналась сразу со
 * второго дела. Игрок переучивался при переходе между экранами, хотя показывают они одно
 * и то же. Теперь список один, а экраны задают ему только размер.
 *
 * <b>Строится сейчас — верхняя строка списка</b>, и в оригинале так же: очередь там не
 * что-то рядом со стройкой, а её продолжение. Стрелок и крестика у этой строки нет —
 * стройку меняют кнопкой «сменить», а убрать её из списка нельзя вовсе: пустой стройки
 * у колонии не бывает (`ProductionPhase.advanceQueue` сменяет её товарами).
 *
 * <b>Знак текущей стройки — точка, а не «▸».</b> «▸» занят кнопкой «строить сейчас» в
 * строках очереди, и одним значком нельзя обозначать и состояние, и действие: в прежнем
 * списке та же стрелка была то меткой, то кнопкой — смотря по строке.
 *
 * <b>Повторы схлопываются в одну строку</b> — «Фрегат ×3»: очередь держит семь мест, и
 * три из них, занятые одним и тем же кораблём, съедали половину списка, ничего не сообщая
 * сверх того, что сообщает число. Стрелки двигают ВЕСЬ повтор целиком (это по-прежнему
 * один запрос: переставляется соседняя строка, а не каждая копия), крестик убирает ОДНУ
 * копию, а «+» у последней строки добавляет ещё одну.
 *
 * Перетаскивать повтор нельзя — только одиночные строки: перенос трёх копий разом
 * потребовал бы трёх запросов, посчитанных один от другого, а стрелки делают то же самое
 * одним. Это намеренный предел, а не недосмотр.
 *
 * <b>Строку выбирают, и выбранную правят с клавиатуры</b> — <b>D</b> убирает её из
 * очереди, <b>R</b> переключает повтор очереди ({@code useBuildHotkeys}). Без выбора D не
 * делает ничего: удалять «последнее попавшееся» по нажатию буквы — плохой обмен.
 *
 * <b>ДВА ЩЕЛЧКА ПЕРЕСТАВЛЯЮТ СТРОКУ</b> — решение хозяина проекта (30.09.2026), и так же
 * это сделано в оригинале. Первый щелчок по строке её ВЫБИРАЕТ, второй — по ДРУГОЙ строке
 * — вставляет выбранное на её место, а занимавшее это место опускается на строку вниз.
 * Щелчок по той же строке выбор снимает. Прежде порядок правили только стрелками (шаг за
 * нажатие, и поднять пятое дело первым стоило четырёх) и перетаскиванием, которое требует
 * вести мышь с зажатой кнопкой через весь список.
 *
 * <b>Схлопнутый повтор так не переставляется</b>: в строке «Фрегат ×3» три места очереди,
 * и одним запросом их не перенести — тот же предел, по которому повтор нельзя тащить
 * мышью. Щелчок по другой строке тогда просто выбирает её, а двигают повтор стрелками:
 * они переставляют СОСЕДНЮЮ строку и потому справляются одним запросом.
 *
 * <b>Мышью очередь и пополняют, и чистят.</b> Проект из списка стройки перетаскивают на
 * очередь — он встаёт в неё; строку очереди тащат ПРОЧЬ из списка — она из очереди
 * уходит. Обе дороги остаются и кнопками («в очередь», «✕»): перетаскивание — это
 * быстрее, а не «единственный способ».
 *
 * <b>В широком окне очередь перетаскивают мышью.</b> Стрелки двигают на одну позицию, и
 * поднять пятый проект первым стоило четырёх нажатий с четырьмя ответами сервера. В узкой
 * панели колонии стрелки остаются: там строка в палец шириной и тащить некуда, — а в окне
 * во весь экран места хватает. Стрелки при этом никуда не делись и в широком окне: мышь
 * есть не у всех действий, а привычка — у всех.
 *
 * <b>Срок — ОЖИДАНИЕ, а не длина проекта</b> ({@link queueEta}): остаток текущей стройки
 * плюс всё, что стоит впереди. Прежде каждая строка называла свой срок в отрыве от
 * остальных, и числа не складывались ни во что — а спрашивают у очереди именно «когда».
 */
export function ColonyQueue({
  colony,
  own,
  busy,
  wide,
  onRemove,
  onMove,
  onPromote,
  onEnqueue,
  selected,
  onSelect,
  onDropProject,
  credits,
  onBuy,
}: {
  colony: Colony;
  own: boolean;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  busy: boolean;
  /** Окно стройки во весь экран: там крупнее кегль и шире строка. */
  wide?: boolean;
  onRemove: (index: number) => void;
  onMove: (index: number, toIndex: number) => void;
  /**
   * Взяться за проект очереди сейчас: он уходит из очереди на стапель. `keepCurrent` —
   * прежняя стройка не пропадает, а встаёт первой в очередь: так кладут строку, брошенную
   * на текущую стройку.
   */
  onPromote: (project: ColonyProject, keepCurrent?: boolean) => void;
  /** Добавить ещё одну копию повторяемого — кнопка «+»; без неё её просто не рисуют. */
  onEnqueue?: (project: ColonyProject) => void;
  /** Выбранная строка очереди: её правят с клавиатуры. */
  selected?: number | null;
  onSelect?: (index: number | null) => void;
  /** Проект, притащенный из списка стройки, — встаёт в очередь. */
  onDropProject?: (code: string) => void;
  /** Казна империи — по ней монетка выкупа знает, хватает ли денег (п. 10). */
  credits?: number;
  /**
   * Выкупить текущую стройку — п. 10: монетка у верхней строки, та же, что в списке
   * колоний (`BuyCoin`). Подтверждение спрашивает тот, кто её показывает; без обработчика
   * монетки нет.
   */
  onBuy?: () => void;
}) {
  const queue = colony.queue;
  const left = turnsLeft(colony);
  /** Что тащат и куда целятся: номера строк, обе — только на время перетаскивания. */
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);

  const drop = (to: number) => {
    if (dragging !== null && dragging !== to) {
      onMove(dragging, to);
    }
    setDragging(null);
    setOver(null);
  };

  /**
   * Перетащили что-то НА очередь.
   *
   * Из списка стройки приходит код проекта — он встаёт в очередь; изнутри очереди приходит
   * номер строки, и это перестановка, которую разбирает сама строка. Поэтому здесь
   * разбирается только первый случай: очередь как целое принимает пополнение.
   */
  const dropHere = (event: React.DragEvent) => {
    const code = event.dataTransfer.getData('application/x-sddnw-project');
    if (code && onDropProject) {
      event.preventDefault();
      onDropProject(code);
    }
    setDragging(null);
    setOver(null);
  };
  const stalled = queueStalled(colony) && queue.length > 0;
  const runs = collapse(queue);
  /*
    Выбранное можно вставить в другое место только если это ОДИНОЧНАЯ строка: у
    схлопнутого повтора мест в очереди несколько, и перенести их разом одним запросом
    нечем — тот же предел, что и у перетаскивания.
  */
  const movable = selected !== null && selected !== undefined
    && runs.some((run) => run.first === selected && run.count === 1);
  // Узкой панели колонии длинная подпись не по росту: там «через 14», а в окне во весь
  // экран — «через 14 ходов». Значит подпись одно и то же, и объясняет её одна подсказка.
  const etaKey = wide ? 'build.eta' : 'build.eta.short';

  return (
    <div
      className={wide ? '' : 'border-t border-space-800 pt-1'}
      onDragOver={(event) => {
        // Разрешаем бросок, только когда тащат проект: иначе очередь принимала бы что
        // угодно, включая текст со страницы.
        if (event.dataTransfer.types.includes('application/x-sddnw-project')) {
          event.preventDefault();
        }
      }}
      onDrop={dropHere}
    >
      {/*
        Счётчик мест в заголовке. Прежде о пределе очереди игрок узнавал, только упёршись
        в него: подсказка про семь проектов появлялась в подвале окна ПОСЛЕ отказа.
      */}
      <div className={`flex items-baseline justify-between text-18 uppercase tracking-[0.2em] text-ink-faint`}>
        <span>{t('build.queue')}</span>
        <span className={figureLabelClass('value')} title={t('build.queue.count.title', { max: QUEUE_LIMIT })}>
          {tf('build.queue.count', {
            n: <Figure accent="value">{queue.length}</Figure>,
            max: <Figure accent="value">{QUEUE_LIMIT}</Figure>,
          })}
        </span>
      </div>

      <ol className="mt-1 flex flex-col gap-0.5">
        <li
          /*
            Текущая стройка тоже принимает брошенную строку очереди (01.10.2026): бросок сюда —
            «на первое место». Строка встаёт на стапель, а прежняя стройка сдвигается первой
            в очередь. Прежде бросок сюда не принимал никто, браузер считал его броском МИМО
            очереди, и строка не вставала первой, а исчезала — «бросок мимо» значит «убрать».
          */
          onDragOver={(event) => {
            if (dragging !== null) {
              event.preventDefault();
              setOver(-1);
            }
          }}
          onDrop={(event) => {
            if (dragging === null) {
              return;
            }
            event.preventDefault();
            const project = queue[dragging];
            setDragging(null);
            setOver(null);
            if (project) {
              onPromote(project, true);
            }
          }}
          className={'flex items-baseline gap-2 '
            + (over === -1 && dragging !== null ? 'border-b border-accent' : '')}
        >
          <span className="w-4 shrink-0 text-right text-18 text-accent">●</span>
          <span className={`min-w-0 flex-1 break-words text-20 leading-tight text-ink-bright`}>
            {colony.projectName ?? t('build.nothing')}
          </span>
          {/*
            Вложенное — числом и с объяснением: накопленное производство остаётся КОЛОНИИ,
            а не проекту (`ColonyService.setProject` его не трогает). Пока об этом молчали,
            смены стройки боялись — казалось, что вложенное пропадёт.
          */}
          {colony.projectCost === undefined ? null : (
            <span className={'shrink-0 ' + figureLabelClass('value')}
                  title={t('build.invested.title')}>
              {tf('build.invested', {
                points: <Figure accent="value">{colony.projectPoints}</Figure>,
                cost: <Figure accent="value">{colony.projectCost}</Figure>,
              })}
              {' ·'}
            </span>
          )}
          <span className={'shrink-0 ' + figureLabelClass('value')}>
            {left === null
              ? colony.projectCost === undefined ? t('build.endless') : '—'
              : tf('build.turns', { n: <Figure accent="value">{left}</Figure> })}
          </span>
          {/*
            Выкуп текущей стройки — та же монетка, что в строке списка колоний: четыре её
            состояния (можно, не хватает казны, уже оплачено, выкупать нечего) читаются
            одинаково на обоих экранах.
          */}
          {own && onBuy ? (
            <span className="self-center">
              <BuyCoin colony={colony} credits={credits ?? 0} colonyName={colony.projectName ?? ''}
                       busy={busy} onBuy={onBuy} />
            </span>
          ) : null}
        </li>

        {runs.map((run) => (
          <li
            key={`${run.project.code}-${run.first}`}
            draggable={own && !busy && run.count === 1}
            onDragStart={(event) => {
              setDragging(run.first);
              // Номер строки — чтобы её можно было вынести из очереди, бросив мимо.
              event.dataTransfer.setData('application/x-sddnw-queue', String(run.first));
              event.dataTransfer.effectAllowed = 'move';
            }}
            onDragEnd={(event) => {
              // Бросок МИМО очереди — это «убрать»: `dropEffect` там остаётся `none`,
              // потому что принять его было некому. Так очередь чистят мышью, не целясь
              // в крестик размером с букву.
              if (event.dataTransfer.dropEffect === 'none' && dragging === run.first) {
                onRemove(run.first);
              }
              setDragging(null);
              setOver(null);
            }}
            onDragOver={(event) => { event.preventDefault(); setOver(run.first); }}
            onDrop={(event) => { event.preventDefault(); drop(run.first); }}
            /*
              Щелчок: выбрать, снять выбор или ВСТАВИТЬ выбранное сюда — п. 10. Порядок
              разбора именно такой, потому что «вставить» возможно лишь тогда, когда
              выбрано что-то другое и это что-то — одиночная строка.
            */
            onClick={() => {
              if (!own || busy) {
                return;
              }
              if (selected === null || selected === undefined) {
                onSelect?.(run.first);
                return;
              }
              if (selected === run.first) {
                onSelect?.(null);
                return;
              }
              if (!movable) {
                // Выбран схлопнутый повтор: переставить его одним запросом нечем, и
                // щелчок делает то единственное, что имеет смысл, — выбирает эту строку.
                onSelect?.(run.first);
                return;
              }
              onMove(selected, run.first);
              onSelect?.(null);
            }}
            title={t(selected === null || selected === undefined || selected === run.first
              ? 'build.queue.pick.title'
              : movable ? 'build.queue.insert.title' : 'build.queue.pick.title')}
            className={
              'flex items-baseline gap-2 '
              + (own && !busy && run.count === 1 ? 'cursor-grab ' : '')
              + (selected === run.first ? 'bg-space-800 ' : '')
              + (dragging === run.first ? 'opacity-40 ' : '')
              // Куда упадёт: строку под указателем подчёркиваем, а не подсвечиваем целиком —
              // очередь это порядок, и место вставки важнее самой строки.
              + (over === run.first && dragging !== null && dragging !== run.first
                ? 'border-b border-accent ' : '')
            }
          >
            <span className="w-4 shrink-0 text-right text-18 text-ink-faint">{run.first + 1}</span>
            {/* Название строки — кеглем ступени её чисел: «Фрегат ×3» и срок рядом читаются одной строкой. */}
            <span className={'min-w-0 flex-1 break-words ' + figureLabelClass('value')}>
              {run.project.name}
              {run.count > 1 ? <>{' '}<Figure accent="value">×{run.count}</Figure></> : null}
            </span>
            {/* Срок повтора — когда достроится ПОСЛЕДНЯЯ копия: очередь обещает конец дела. */}
            <span className={'shrink-0 ' + figureLabelClass('value')} title={t('build.eta.title')}>
              {eta(colony, queue, run.last, etaKey)}
            </span>
            {own ? (
              <span className="flex shrink-0 items-baseline gap-1 text-18">
                {/*
                  «+» стоит только у последней строки: новая копия уходит в КОНЕЦ очереди
                  (`enqueue` иначе не умеет), и у строки посередине она встала бы не рядом
                  с повтором, а в хвосте — кнопка обещала бы не то, что делает.
                */}
                {onEnqueue && run.project.repeatable && run.last === queue.length - 1 ? (
                  <QueueButton label="+" title={t('build.more.title')}
                               disabled={busy || queue.length >= QUEUE_LIMIT}
                               onClick={() => onEnqueue(run.project)} />
                ) : null}
                <QueueButton label="▸" title={t('build.now.title')} disabled={busy}
                             onClick={() => onPromote(run.project)} />
                {/* Весь повтор двигается одним запросом: переставляется СОСЕДНЯЯ строка. */}
                <QueueButton label="▲" title={t('build.up.title')}
                             disabled={busy || run.first === 0}
                             onClick={() => onMove(run.first - 1, run.last)} />
                <QueueButton label="▼" title={t('build.down.title')}
                             disabled={busy || run.last === queue.length - 1}
                             onClick={() => onMove(run.last + 1, run.first)} />
                <QueueButton label="✕" title={run.count > 1 ? t('build.removeOne.title') : t('build.remove.title')}
                             disabled={busy}
                             onClick={() => onRemove(run.last)} />
              </span>
            ) : null}
          </li>
        ))}
      </ol>

      {/*
        Очередь за бесконечной стройкой не двинется никогда — правило сервера, о котором
        экран молчал. Молчащая очередь, которая «не работает», читается как поломка игры.
      */}
      {stalled ? (
        <p className={`mt-1 text-18 leading-tight text-warn`}>
          {t('build.queue.stalled', { name: colony.projectName ?? '' })}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Клавиатура очереди — трек техдолга, пункт 21: ↑ и ↓ ходят по строкам (схлопнутый повтор —
 * одна строка, выбор стоит на его первом месте, как и у щелчка). Без выбора ↓ встаёт на
 * первую строку, ↑ — на последнюю. Пустая очередь выбора не даёт.
 */
export const queueStep = (queue: ColonyProject[], selected: number | null, delta: number): number | null => {
  const runs = collapse(queue);
  if (runs.length === 0) {
    return null;
  }
  const at = runs.findIndex((run) => run.first === selected);
  const next = at < 0 ? (delta > 0 ? 0 : runs.length - 1) : Math.min(runs.length - 1, Math.max(0, at + delta));
  return runs[next].first;
};

/**
 * Shift + ↑ / ↓ — та же перестановка, что у стрелок строки: переставляется СОСЕДНЯЯ строка,
 * поэтому весь повтор едет одним запросом. Отдаёт пару «откуда, куда» для `onMove` и место,
 * где выбор окажется после переноса; у края очереди — ничего.
 */
export const queueShift = (queue: ColonyProject[], selected: number | null, delta: number):
  { from: number; to: number; selected: number } | null => {
  const run = collapse(queue).find((candidate) => candidate.first === selected);
  if (!run) {
    return null;
  }
  if (delta < 0) {
    return run.first === 0 ? null : { from: run.first - 1, to: run.last, selected: run.first - 1 };
  }
  return run.last === queue.length - 1 ? null : { from: run.last + 1, to: run.first, selected: run.first + 1 };
};

/**
 * Подряд идущие одинаковые проекты — одной строкой.
 *
 * Схлопываются только СОСЕДНИЕ: «Фрегат, Завод, Фрегат» — это три дела в своём порядке, и
 * сводить их в «Фрегат ×2» значило бы соврать о порядке стройки.
 */
const collapse = (queue: ColonyProject[]) => {
  const runs: { project: ColonyProject; first: number; last: number; count: number }[] = [];
  queue.forEach((project, index) => {
    const previous = runs[runs.length - 1];
    if (previous && previous.project.code === project.code) {
      previous.last = index;
      previous.count += 1;
      return;
    }
    runs.push({ project, first: index, last: index, count: 1 });
  });
  return runs;
};

/** Ожидание строки очереди словами; прочерк — считать нечем (нет производства). */
const eta = (
  colony: Colony,
  queue: ColonyProject[],
  index: number,
  key: 'build.eta' | 'build.eta.short',
): ReactNode => {
  if (queue[index].cost === undefined) {
    return t('build.endless');
  }
  const turns = queueEta(colony, queue, index);
  return turns === null ? '—' : tf(key, { n: <Figure accent="value">{turns}</Figure> });
};

/** Кнопка строки очереди: значок и подсказка, гаснет на время ответа сервера. */
function QueueButton({
  label,
  title,
  disabled,
  onClick,
}: {
  label: string;
  title: string;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className="link-quiet disabled:cursor-not-allowed disabled:text-ink-off"
      disabled={disabled}
      title={title}
      onClick={onClick}
    >
      {label}
    </button>
  );
}
