import type { Planet } from '../../api/types';
import { Colonist, ResourceIcon, type ResourceKind } from '../system/colonyIcons';
import { turnsLeft } from '../system/colonyRules';
import { t, type Key } from '../../i18n';

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
 */

/**
 * Ширины столбцов: шапка списка и строки берут их отсюда, иначе разъедутся.
 *
 * Первый столбец — пометка выделения: по ней колонию берут в набор, которому потом
 * закладывают стройку разом (п. 10). Узкий и стоит слева от названия, чтобы пометки
 * выстраивались в столбик и набор читался одним взглядом.
 */
export const COLUMNS =
  'grid grid-cols-[1.5rem_15rem_minmax(10rem,1fr)_5.5rem_5.5rem_5.5rem_5.5rem_14rem]'
  + ' items-center gap-x-3 px-2';

type JobName = 'farmers' | 'workers' | 'scientists';

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
  source,
  selected,
  rowRef,
  onStartDrag,
  onOpen,
  onTransfer,
  onSelect,
}: {
  planet: Planet;
  systemName: string;
  /** Цвет расы владельца: тот же, каким жители нарисованы на экране колонии. */
  color: string;
  /** Над этой строкой сейчас держат жителя — она и примет его. */
  hovered: boolean;
  /** Из этой строки жителя несут. */
  source: boolean;
  /** Колония в наборе: приказ стройкой достанется и ей — п. 10. */
  selected: boolean;
  rowRef: (element: HTMLDivElement | null) => void;
  onStartDrag: (event: React.PointerEvent<HTMLElement>) => void;
  onOpen: () => void;
  onTransfer: () => void;
  /** С Shift — набор до этой строки от последней помеченной. */
  onSelect: (range: boolean) => void;
}) {
  const colony = planet.colony;
  if (!colony) {
    return null;
  }

  // Остаток еды считается как на экране колонии: своя выработка минус едоки плюс подвоз
  // грузового флота — п. 4.1.1. Сервер шлёт остаток до подвоза, а голодает колония или
  // нет, решает подвоз.
  const balance = colony.foodBalance + colony.foodDelivered;
  // Последнего жителя увезти нельзя: колония без населения перестала бы существовать.
  const movable = planet.population > 1;

  return (
    <div
      ref={rowRef}
      data-colony-row={planet.id}
      className={
        `${COLUMNS} border-b border-space-800 py-1.5 ` +
        (hovered && !source
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

      {/* Жители: три группы по занятиям, каждая фигурка — ручка переноса. */}
      <div className="flex flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {JOB_ORDER.map((job) => (
            <div key={job} className="flex items-center gap-1">
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
                    disabled={!movable}
                    className={movable ? 'cursor-grab active:cursor-grabbing' : 'cursor-not-allowed'}
                    aria-label={t('colonyRow.colonist.aria', { job: t(JOB_LABELS[job]), name: planet.name, n: index + 1 })}
                    onPointerDown={movable ? onStartDrag : undefined}
                    onClick={onTransfer}
                  >
                    <Colonist color={color} className="h-[17px] w-[12px]" />
                  </button>
                ))
              )}
            </div>
          ))}
        </div>
        <span className="text-15 text-ink-faint">
          {t('colonyRow.population', { n: planet.population, max: colony.maxPopulation })}{' '}
          <span className={colony.growthK < 0 ? 'text-danger' : 'text-ink-dim'}>
            {colony.growthK >= 0 ? `+${colony.growthK}` : colony.growthK}
          </span>{' '}
          {t('colonyRow.thousand')}
        </span>
      </div>

      {/*
        Еда стоит с остатком: сама по себе выработка ничего не говорит — колония живёт
        или голодает по тому, что осталось после едоков и подвоза.
      */}
      <Value value={colony.food}>
        <span className={balance < 0 ? 'text-danger' : 'text-good'}>
          {balance >= 0 ? `+${balance}` : balance}
        </span>
        {colony.foodDelivered > 0 ? (
          <span className="text-ink-faint">{t('colonyRow.delivery', { n: colony.foodDelivered })}</span>
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
          <span className="text-warn">{t('colonyRow.cleanup', { n: colony.pollution })}</span>
        ) : null}
      </Value>

      <Value value={colony.research} />

      {/* Доход — единственная величина без своих жителей: она из налогов и содержания. */}
      <div className="text-right">
        <span
          className={
            'block text-19 leading-tight '
            + (colony.income < 0 ? 'text-danger' : 'text-ink-bright')
          }
        >
          {colony.income >= 0 ? `+${colony.income}` : colony.income}
        </span>
        {colony.upkeep > 0 ? (
          <span className="block text-15 leading-tight text-ink-faint">
            −{colony.upkeep}
          </span>
        ) : null}
      </div>

      {/* Стройка: что строится и сколько осталось — тот же срок, что на экране колонии. */}
      <div className="min-w-0">
        <span className="block truncate text-18 text-ink">
          {colony.projectName ?? t('build.nothing')}
          {/*
            Очередь названа числом, а не списком: в строке списка колоний ей места нет,
            а знать, что колония занята надолго вперёд, отсюда и нужно — п. 10.
          */}
          {colony.queue.length > 0 ? (
            <span className="text-ink-faint"> +{colony.queue.length}</span>
          ) : null}
        </span>
        <span className="block truncate text-15 text-ink-faint">{progress(planet)}</span>
      </div>
    </div>
  );
}

/** Число выработки со своей припиской снизу: в столбце помещается и то, и другое. */
function Value({ value, children }: { value: number; children?: React.ReactNode }) {
  return (
    <div className="text-right">
      <span className="block text-19 leading-tight text-ink-bright">{value}</span>
      {children ? <span className="block text-15 leading-tight">{children}</span> : null}
    </div>
  );
}

/**
 * Чем кончится стройка: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются. Короче, чем на экране колонии: здесь строка одна
 * из многих.
 */
const progress = (planet: Planet): string => {
  const colony = planet.colony;
  if (!colony?.projectCode) {
    return t('colonyRow.wasted');
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? t('colonyRow.housing', { n: colony.housingBonusPercent ?? 0 })
      : t('colonyRow.tradeGoods', { n: Math.floor(colony.production / 2) });
  }
  const turns = turnsLeft(colony);
  return t('build.progress', { points: colony.projectPoints, cost: colony.projectCost })
    + (turns === null ? t('colonyRow.noProduction') : ` · ${t('build.turns', { n: turns })}`);
};
