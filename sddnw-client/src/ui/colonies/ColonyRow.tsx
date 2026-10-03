import type { Planet } from '../../api/types';
import { BuyCoin } from './BuyCoin';
import { Colonist, ResourceIcon, type ResourceKind } from '../system/colonyIcons';
import { turnsLeft } from '../system/colonyRules';
import { t, tf, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass, signed, signTone } from '../accent';
import type { JobName } from './usePopulationDrag';

/**
 * Строка колонии в списке колоний империи — п. 4.1.1 и п. 10.
 *
 * Состав блоков строки взят у окна Colonies MOO II: слева название колонии, дальше
 * жители фигурками, затем еда, производство и наука, и в конце — что колония строит.
 * Порядок тот же, что в оригинале: сперва кто, потом сколько их, потом что они дают,
 * и лишь затем — куда уходит производство. Доход стоит между наукой и стройкой: это
 * единственная величина без своих жителей, и в оригинале её в этом окне нет вовсе, а
 * здесь она нужна — казна империи складывается из колоний, и видеть её надо рядом.
 *
 * Жители нарисованы по занятиям и помечены теми же значками, что и величины справа:
 * мешок у фермеров и у еды, молоток у рабочих и у производства, микроскоп у учёных и у
 * науки. Так строка читается парами «кто — что даёт», а не столбцом отдельных чисел.
 *
 * Фигурка — ручка переноса: жителя перетаскивают в другую строку, и он уезжает туда
 * грузовым флотом. Занятие при этом не переезжает: сервер раскладывает прибывших по
 * работам сам, а перевозится именно житель.
 *
 * <b>В пределах своей колонии грузовой флот не нужен</b>: бросок на другую группу занятий
 * той же строки и щелчок по фигурке просто меняют занятие (как на экране колонии), а
 * перевозку по щелчку вызывает Shift.
 *
 * Справа от названия стройки стоит монетка выкупа (`BuyCoin`, п. 10): решают о выкупе,
 * глядя на всю империю разом — у кого стройка вот-вот кончится и на что хватит казны, — а
 * до этой правки выкуп жил только на экране колонии, и на каждую покупку уходило открыть
 * колонию, купить и вернуться.
 */

/**
 * Ширины столбцов: шапка списка и строки берут их отсюда, иначе разъедутся.
 *
 * Первый столбец — пометка выделения: по ней колонию берут в набор, которому потом
 * закладывают стройку разом (п. 10). Узкий и стоит слева от названия, чтобы пометки
 * выстраивались в столбик и набор читался одним взглядом.
 */
export const COLUMNS =
  'grid grid-cols-[1.5rem_15rem_minmax(16rem,1fr)_5.5rem_5.5rem_5.5rem_5.5rem_15rem]'
  + ' items-center gap-x-3 px-2';

const JOB_ORDER: JobName[] = ['farmers', 'workers', 'scientists'];

const JOB_LABELS: Record<JobName, Key> = {
  farmers: 'colony.job.farmers',
  workers: 'colony.job.workers',
  scientists: 'colony.job.scientists',
};

const JOB_ICONS: Record<JobName, ResourceKind> = {
  farmers: 'grain',
  workers: 'hammer',
  scientists: 'microscope',
};

export function ColonyRow({
  planet,
  systemName,
  color,
  hovered,
  refused,
  hoverZone,
  source,
  selected,
  credits,
  busy,
  rowRef,
  zoneRef,
  onStartDrag,
  held,
  onOpen,
  onTransfer,
  onMoveJob,
  onSelect,
  onBuy,
}: {
  planet: Planet;
  systemName: string;
  /** Казна империи: по ней монетка выкупа и гаснет — п. 10. */
  credits: number;
  /** Запрос к серверу ещё идёт: второе нажатие выкупа списало бы казну дважды. */
  busy: boolean;
  /** Цвет расы владельца: тот же, каким жители нарисованы на экране колонии. */
  color: string;
  /** Над этой строкой сейчас держат жителя — она и примет его. */
  hovered: boolean;
  /**
   * Сюда группу не довезти (нет грузовиков, места или колония осталась бы пустой) — строка
   * горит красным, и бросок вернёт жителей на место.
   */
  refused: boolean;
  /** «колония:занятие» группы, над которой держат жителя: бросок сюда сменит занятие. */
  hoverZone: string | null;
  /** Из этой строки жителя несут. */
  source: boolean;
  /** Колония в наборе: приказ стройкой достанется и ей — п. 10. */
  selected: boolean;
  rowRef: (element: HTMLDivElement | null) => void;
  /** Рамка группы занятия — приёмник броска внутри своей колонии. */
  zoneRef: (job: JobName) => (element: HTMLElement | null) => void;
  /** Взять жителя и всех правее него в группе: `count` — сколько их, считая взятого. */
  onStartDrag: (job: JobName, count: number) => (event: React.PointerEvent<HTMLElement>) => void;
  /** Группа, взятая из этой строки: её фигурки бледнеют на месте. */
  held: { job: JobName; count: number } | null;
  onOpen: () => void;
  /** Перевозка жителя в другую колонию (Shift + щелчок): диалог с грузовиками. */
  onTransfer: () => void;
  /** Житель меняет занятие в этой же колонии: от одного к другому, без грузовиков. */
  onMoveJob: (from: JobName, to: JobName) => void;
  /** С Shift — набор до этой строки от последней помеченной. */
  onSelect: (range: boolean) => void;
  /** Выкупить стройку этой колонии — п. 10; подтверждение спрашивает сам экран. */
  onBuy: () => void;
}) {
  const colony = planet.colony;
  if (!colony) {
    return null;
  }

  // Остаток еды считается как на экране колонии: своя выработка минус едоки плюс подвоз
  // грузового флота — п. 4.1.1. Сервер шлёт остаток до подвоза, а голодает колония или
  // нет, решает подвоз.
  const balance = colony.foodBalance + colony.foodDelivered;
  // Последнего жителя увезти в другую колонию нельзя (колония без населения перестала бы
  // существовать) — но занятие он менять вправе, поэтому фигурка живая всегда, а отказ
  // на перевозке даёт сам экран.

  return (
    <div
      ref={rowRef}
      data-colony-row={planet.id}
      className={
        `${COLUMNS} border-b border-space-800 py-1.5 ` +
        (hovered && !source && refused
          ? 'bg-space-800 outline outline-1 -outline-offset-1 outline-danger'
          : hovered && !source
          ? 'bg-space-800'
          : source
            ? 'bg-space-900/60'
            : selected
              ? 'bg-space-900'
              : '')
      }
    >
      {/*
        Пометка выделения — п. 10. Квадратик, а не флажок браузера: экран нарисован
        своими средствами, и родной флажок в нём выглядел бы чужим. Нажатие с Shift
        помечает всё от прошлой помеченной строки до этой — списком колоний правят
        целыми кусками.
      */}
      <button
        type="button"
        aria-pressed={selected}
        aria-label={t('colonyRow.select.aria', { name: planet.name })}
        className={
          'flex h-5 w-5 items-center justify-center border text-14 leading-none '
          + (selected
            ? 'border-accent bg-accent/20 text-accent'
            : 'border-space-700 text-transparent hover:border-space-600')
        }
        onClick={(event) => onSelect(event.shiftKey)}
      >
        ✔
      </button>

      {/* Колония: отсюда открывается экран управления планетой — как в оригинале. */}
      <button type="button" className="link min-w-0 text-left" onClick={onOpen}>
        <span className="block truncate text-19 text-ink-bright">{planet.name}</span>
        <span className="block truncate text-15 text-ink-faint no-underline">
          {systemName} · {planet.sizeLabel} · {planet.climateLabel}
          {planet.homeworld ? t('colonyRow.homeworld') : ''}
        </span>
      </button>

      {/*
        Жители: три группы по занятиям, каждая фигурка — ручка переноса. Группы — РАВНЫЕ
        доли столбца (01.10.2026, решение хозяина проекта), а не по числу жителей: иначе
        рабочие одной колонии стояли под фермерами другой, и столбцы занятий не читались
        сверху вниз. Не влезло в долю — фигурки переносятся строкой ниже внутри своей группы.
      */}
      <div className="flex flex-col gap-0.5">
        <div className="grid grid-cols-3 gap-x-3">
          {JOB_ORDER.map((job) => (
            <div
              key={job}
              ref={zoneRef(job)}
              className={
                'flex min-h-[22px] min-w-0 flex-wrap items-center gap-1 px-1 '
                + (hoverZone === `${planet.id}:${job}` ? 'bg-space-800 ring-1 ring-accent' : '')
              }
            >
              <ResourceIcon
                kind={JOB_ICONS[job]}
                label={JOB_LABELS[job]}
                className="h-[16px] w-[16px] shrink-0 text-ink-faint"
              />
              {colony[job] === 0 ? (
                <span className="text-15 text-ink-off">—</span>
              ) : (
                Array.from({ length: colony[job] }).map((_, index) => (
                  <button
                    key={index}
                    type="button"
                    data-colonist={planet.id}
                    className={
                      'cursor-pointer'
                      + (held?.job === job && index >= colony[job] - held.count ? ' opacity-30' : '')
                    }
                    draggable={false}
                    aria-label={t('colonyRow.colonist.aria', { job: t(JOB_LABELS[job]), name: planet.name, n: index + 1 })}
                    onPointerDown={onStartDrag(job, colony[job] - index)}
                    onDragStart={(event) => event.preventDefault()}
                    onClick={(event) => {
                      if (event.shiftKey) {
                        onTransfer();
                        return;
                      }
                      // Смена занятия по кругу — только с клавиатуры (detail = 0): мышь
                      // берёт группу нажатием, и её щелчок здесь лишний.
                      if (event.detail === 0) {
                        onMoveJob(job, JOB_ORDER[(JOB_ORDER.indexOf(job) + 1) % JOB_ORDER.length]);
                      }
                    }}
                  >
                    <Colonist color={color} className="h-[17px] w-[12px]" />
                  </button>
                ))
              )}
            </div>
          ))}
        </div>
        <span className={figureLabelClass('note')}>
          {tf('colonyRow.population', {
            n: <Figure accent="note">{planet.population}</Figure>,
            max: <Figure accent="note">{colony.maxPopulation}</Figure>,
          })}{' '}
          <Figure accent="note" tone={signTone(colony.growthK)}>
            {signed(colony.growthK)}
          </Figure>{' '}
          {t('colonyRow.thousand')}
        </span>
      </div>

      {/*
        Еда стоит с остатком: сама по себе выработка ничего не говорит — колония живёт
        или голодает по тому, что осталось после едоков и подвоза.
      */}
      <Value value={colony.food}>
        <Figure accent="note" tone={balance < 0 ? 'danger' : 'good'}>
          {signed(balance)}
        </Figure>
        {colony.foodDelivered > 0 ? (
          <span>
            {tf('colonyRow.delivery', { n: <Figure accent="note">{colony.foodDelivered}</Figure> })}
          </span>
        ) : null}
      </Value>

      {/*
        Производство идёт с уборкой (п. 10): в столбце стоит то, что достаётся стройке,
        а под ним — сколько колония тратит на то, чтобы убрать за собой. Иначе
        одинаковые по жителям колонии различались бы выработкой без всякой видимой
        причины — грязь зависит от размера планеты.
      */}
      <Value value={colony.production}>
        {colony.pollution > 0 ? (
          <span className="text-warn">
            {tf('colonyRow.cleanup', { n: <Figure accent="note" tone="warn">{colony.pollution}</Figure> })}
          </span>
        ) : null}
      </Value>

      <Value value={colony.research} />

      {/* Доход — единственная величина без своих жителей: она из налогов и содержания. */}
      <div className="text-right">
        <span className="block">
          <Figure accent="value" tone={signTone(colony.income)}>
            {signed(colony.income)}
          </Figure>
        </span>
        {colony.upkeep > 0 ? (
          <span className="block">
            <Figure accent="note">−{colony.upkeep}</Figure>
          </span>
        ) : null}
      </div>

      {/*
        Стройка: что строится и сколько осталось — тот же срок, что на экране колонии, — а
        справа монетка выкупа (п. 10). Монетка прижата к правому краю столбца, а не стоит
        встык за названием: названия разной длины, и монетки плясали бы по строкам, тогда
        как в столбик они читаются одним взглядом.
      */}
      <div className="flex min-w-0 items-center gap-2">
        <div className="min-w-0 flex-1">
          <span className="block truncate text-18 text-ink">
            {colony.projectName ?? t('build.nothing')}
            {/*
              Очередь названа числом, а не списком: в строке списка колоний ей места нет,
              а знать, что колония занята надолго вперёд, отсюда и нужно — п. 10.
            */}
            {colony.queue.length > 0 ? (
              <> <Figure accent="value">+{colony.queue.length}</Figure></>
            ) : null}
          </span>
          <span className={'block truncate ' + figureLabelClass('note')}>{progress(planet)}</span>
        </div>
        <BuyCoin
          colony={colony}
          credits={credits}
          colonyName={planet.name}
          busy={busy}
          onBuy={onBuy}
        />
      </div>
    </div>
  );
}

/** Число выработки со своей припиской снизу: в столбце помещается и то, и другое. */
function Value({ value, children }: { value: number; children?: React.ReactNode }) {
  return (
    <div className="text-right">
      <span className="block">
        <Figure accent="value">{value}</Figure>
      </span>
      {children ? <span className={'block ' + figureLabelClass('note')}>{children}</span> : null}
    </div>
  );
}

/**
 * Чем кончится стройка: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются. Короче, чем на экране колонии: здесь строка одна
 * из многих.
 */
const progress = (planet: Planet): React.ReactNode => {
  const colony = planet.colony;
  if (!colony?.projectCode) {
    return t('colonyRow.wasted');
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? tf('colonyRow.housing', { n: <Figure accent="note">{colony.housingBonusPercent ?? 0}</Figure> })
      : tf('colonyRow.tradeGoods', { n: <Figure accent="note">{Math.floor(colony.production / 2)}</Figure> });
  }
  const turns = turnsLeft(colony);
  return (
    <>
      {tf('build.progress', {
        points: <Figure accent="note">{colony.projectPoints}</Figure>,
        cost: <Figure accent="note">{colony.projectCost}</Figure>,
      })}
      {turns === null ? t('colonyRow.noProduction') : (
        <> · {tf('build.turns', { n: <Figure accent="note">{turns}</Figure> })}</>
      )}
    </>
  );
};
