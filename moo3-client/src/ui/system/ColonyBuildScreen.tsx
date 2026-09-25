import { useEffect, useMemo, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet, ShipDesign } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { ShipDesignScreen } from '../ship/ShipDesignScreen';
import { useModalEscape } from '../useModalEscape';
import { projectTurns } from './colonyRules';
import { t, useT } from '../../i18n';

/**
 * Сцена «Постройки колонии» — п. 10, окном стройки MOO II во весь экран.
 *
 * Разметка снята со скриншота оригинала и повторяет его посегментно:
 *
 * ```
 *  ┌ список слева ┬ рамка с видом ┬ название, цена, срок ┬ корабли ┐
 *  │ товары       ├───────────────┴──────────────────────┤ флот    │
 *  │ дома         │ описание выбранного                  │ шесть   │
 *  │ здания       ├──────────────────────────────────────┤ проектов│
 *  │ база         │ ОЧЕРЕДЬ КОЛОНИИ «имя»                ├─────────┤
 *  │              │ строки очереди со стрелками и ✕      │ кнопки  │
 *  └──────────────┴──────────────────────────────────────┴─────────┘
 * ```
 *
 * Слева — то, что колония строит на себе: товары, дома, здания и колониальная база.
 * Справа — то, что уходит с неё: грузовой флот, шесть корабельных проектов империи,
 * гражданские корабли и шпион. Так делит список и оригинал, и деление это не
 * косметическое: здание остаётся на планете, корабль улетает.
 *
 * Шесть корабельных ячеек показываются <b>всегда</b>, даже когда колония их построить не
 * может: империя видит свои проекты, а причина отказа стоит подсказкой (нет технологии
 * кораблестроения, корпус велик для верфи). Пустая колонка вместо шести строк читалась бы
 * как поломка — в оригинале она не пустует никогда.
 *
 * Очередь правится здесь же, как в правом нижнем сегменте оригинала: строка убирается
 * крестиком, двигается стрелками, а выбранное слева уходит либо на стапель («строить»),
 * либо в очередь.
 */
export function ColonyBuildScreen({
  planet,
  colony,
  saving,
  error,
  onChoose,
  onEnqueue,
  onRemove,
  onMove,
  onClose,
}: {
  planet: Planet;
  colony: Colony;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  saving: boolean;
  error: string | null;
  onChoose: (project: ColonyProject) => void;
  onEnqueue: (project: ColonyProject) => void;
  onRemove: (index: number) => void;
  onMove: (index: number, toIndex: number) => void;
  onClose: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);

  const [selectedCode, setSelectedCode] = useState<string | null>(colony.projectCode ?? null);
  /** Шесть ячеек дизайна империи: колонка кораблей показывает их все, а не только строимые. */
  const [designs, setDesigns] = useState<ShipDesign[]>([]);
  /** Сколько у империи ячеек проекта; до ответа справочника — шесть, как в оригинале. */
  const [slots, setSlots] = useState(6);
  /**
   * Ячейка, которую правит дизайн; пусто — сцена закрыта.
   *
   * В оригинале DESIGN не открывает никакого списка: указатель превращается в выбор
   * ЦЕЛИ, и цель берут из тех самых шести проектов, что стоят в правой колонке окна
   * стройки. Поэтому здесь два состояния, а не одно: «ждём выбора» и «правим ячейку».
   */
  const [designSlot, setDesignSlot] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  const designing = designSlot !== null;
  useT();

  // Esc гасится, пока поверх открыт дизайн: обе сцены рождаются в одной отрисовке, и
  // одно нажатие закрыло бы обе — те же грабли, что на экране исследований.
  useModalEscape(!designing, picking ? () => setPicking(false) : onClose);

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    /*
      Справочник спрашивается рядом с проектами ради ОДНОГО числа — сколько у империи
      ячеек. Держать его здесь константой значило бы завести вторую правду о числе
      ячеек: решает сервер (`catalog.maxDesigns`), а шестёрка ниже — лишь запасной ответ
      на случай, когда справочник ещё не пришёл.
    */
    Promise.all([
      gameApi.getShipDesigns(game.id, credentials.accessToken),
      gameApi.getShipCatalog(game.id, credentials.accessToken),
    ])
      .then(([loadedDesigns, loadedCatalog]) => {
        setDesigns(loadedDesigns);
        setSlots(loadedCatalog.maxDesigns);
      })
      .catch(() => setDesigns([]));
  }, [game, credentials, designing]);

  const available = colony.available;
  const byCode = useMemo(
    () => new Map(available.map((project) => [project.code, project])),
    [available],
  );

  // Слева — то, что остаётся на планете; справа — то, что с неё улетает.
  const planetside = available.filter((project) => !isSpace(project.code));
  const freighter = byCode.get('FREIGHTER') ?? null;
  const spy = byCode.get('SPY') ?? null;
  const civil = available.filter((project) =>
    ['COLONY_SHIP', 'OUTPOST_SHIP', 'TRANSPORT'].includes(project.code),
  );

  const selected =
    (selectedCode === null ? null : byCode.get(selectedCode) ?? null) ?? planetside[0] ?? null;
  const current = selected !== null && selected.code === colony.projectCode;
  const queueFull = colony.queue.length >= QUEUE_LIMIT;
  const canQueue = selected !== null && !queueFull && (!current || selected.repeatable);

  return (
    <div
      /*
        Пока ждут выбора цели дизайна, указатель по всему окну — прицел: в оригинале
        DESIGN именно меняет курсор, а не открывает список (п. 8).
      */
      className={
        'absolute inset-0 z-50 flex flex-col gap-2 bg-space-950 p-3 '
        + (picking ? 'cursor-crosshair' : '')
      }
    >
      <div className="grid min-h-0 flex-1 grid-cols-[16rem_minmax(0,1fr)_15rem] gap-2">
        {/* Левый сегмент во всю высоту: всё, что колония строит на себе. */}
        <Panel title={t('buildScreen.planetside')} className="overflow-auto">
          <ProjectList
            projects={planetside}
            selectedCode={selected?.code ?? null}
            currentCode={colony.projectCode}
            onSelect={setSelectedCode}
            onChoose={onChoose}
          />
        </Panel>

        <div className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)] gap-2">
          {/* Три верхних сегмента: вид, числа и описание — как в оригинале. */}
          <div className="grid grid-cols-[10rem_minmax(0,1fr)] gap-2">
            {/*
              Место под изображение постройки: в оригинале здесь её рисунок. Спрайтов в
              игре пока нет, поэтому в рамке название — рамка уже размечена, и с
              появлением картинок останется заменить содержимое.
            */}
            <div className="flex h-[7.5rem] items-center justify-center border border-space-700 bg-space-900/60 p-2 text-center text-19 leading-tight text-ink-soft">
              {selected?.name ?? '—'}
            </div>

            <Panel title={selected?.name ?? t('buildScreen.nothingSelected')}>
              {selected ? (
                <dl className="grid grid-cols-[11rem_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-20">
                  <dt className="text-ink-dim">{t('buildScreen.cost')}</dt>
                  <dd className="text-ink-bright">
                    {selected.cost === undefined ? t('build.endless') : t('buildScreen.units', { n: selected.cost })}
                  </dd>
                  <dt className="text-ink-dim">{t('buildScreen.upkeep')}</dt>
                  <dd className="text-ink-bright">
                    {selected.upkeep > 0 ? t('buildScreen.upkeep.value', { n: selected.upkeep }) : t('common.none')}
                  </dd>
                  <dt className="text-ink-dim">{t('buildScreen.time')}</dt>
                  <dd className="text-ink-bright">{buildTime(colony, selected)}</dd>
                </dl>
              ) : null}
            </Panel>
          </div>

          <Panel title={t('buildScreen.description')} className="min-h-0">
            <p className="text-20 leading-relaxed text-ink-soft">
              {selected?.description ?? t('buildScreen.description.empty')}
            </p>
          </Panel>
        </div>

        {/* Правый сегмент: всё, что с колонии улетает, — как в оригинале. */}
        <Panel title={t('buildScreen.ships')} className="overflow-auto">
          <ProjectList
            projects={freighter ? [freighter] : []}
            selectedCode={selected?.code ?? null}
            currentCode={colony.projectCode}
            onSelect={setSelectedCode}
            onChoose={onChoose}
          />

          {/*
            Шесть ячеек дизайна: строка есть у КАЖДОЙ — и у той, которую колония не
            поднимет, и у пустой. Недоступное помечено и объяснено, пустая так и говорит,
            что свободна: в оригинале эта колонка не пустует никогда, а четыре строки
            вместо шести читаются как пропажа двух корпусов.
          */}
          <ul className="mt-2 border-t border-space-800 pt-1">
            {Array.from({ length: slots }, (_, index) => index + 1).map((cell) => {
              const design = designs.find((one) => one.slot === cell) ?? null;
              if (design === null) {
                return (
                  <li key={`free-${cell}`}>
                    <button
                      type="button"
                      className={
                        'block w-full truncate px-1 py-0.5 text-left text-20 '
                        + (picking
                          ? 'cursor-crosshair bg-space-800/60 text-accent outline outline-1 outline-accent'
                          : 'cursor-not-allowed text-ink-off')
                      }
                      /* Пустую ячейку правят так же, как занятую: с неё начинают новый проект. */
                      disabled={!picking}
                      title={
                        picking ? t('buildScreen.design.pickHint') : t('buildScreen.ship.free.hint')
                      }
                      onClick={() => {
                        if (picking) {
                          setPicking(false);
                          setDesignSlot(cell);
                        }
                      }}
                    >
                      {t('buildScreen.ship.free', { n: cell })}
                    </button>
                  </li>
                );
              }
              const project = byCode.get(`SHIP:${design.id}`) ?? null;
              return (
                <li key={design.id}>
                  <button
                    type="button"
                    className={
                      'block w-full truncate px-1 py-0.5 text-left text-20 '
                      + (picking
                        ? 'cursor-crosshair bg-space-800/60 text-accent outline outline-1 outline-accent'
                        : project === null
                          ? 'cursor-not-allowed text-ink-off'
                          : project.code === selected?.code
                            ? 'link link-active bg-space-800'
                            : 'link')
                    }
                    /*
                      Пока выбирают цель дизайна, строка жива ВСЕГДА — даже у корабля,
                      которого колония не поднимет: проект правят там, где он есть, а
                      строят там, где могут.
                    */
                    disabled={!picking && project === null}
                    title={
                      picking
                        ? t('buildScreen.design.pickHint')
                        : project === null
                          ? t('buildScreen.ship.unavailable')
                          : design.hullName
                    }
                    onClick={() => {
                      if (picking) {
                        setPicking(false);
                        setDesignSlot(design.slot);
                        return;
                      }
                      if (project) {
                        setSelectedCode(project.code);
                      }
                    }}
                    onDoubleClick={() => !picking && project && onChoose(project)}
                  >
                    {design.name}
                  </button>
                </li>
              );
            })}
          </ul>

          {civil.length > 0 ? (
            <div className="mt-2 border-t border-space-800 pt-1">
              <ProjectList
                projects={civil}
                selectedCode={selected?.code ?? null}
                currentCode={colony.projectCode}
                onSelect={setSelectedCode}
                onChoose={onChoose}
              />
            </div>
          ) : null}

          {spy ? (
            <div className="mt-2 border-t border-space-800 pt-1">
              <ProjectList
                projects={[spy]}
                selectedCode={selected?.code ?? null}
                currentCode={colony.projectCode}
                onSelect={setSelectedCode}
                onChoose={onChoose}
              />
            </div>
          ) : null}
        </Panel>
      </div>

      <div className="grid h-[15rem] flex-none grid-cols-[16rem_minmax(0,1fr)_15rem] gap-2">
        {/* Под левым списком — то, что колония строит сейчас: её стапель. */}
        <Panel title={t('buildScreen.current')}>
          <p className="text-22 text-ink-bright">
            {colony.projectName ?? t('build.nothing')}
          </p>
          <p className="mt-1 text-19 text-ink-dim">
            {colony.projectCost === undefined
              ? t('buildScreen.endless')
              : t('build.progress', { points: colony.projectPoints, cost: colony.projectCost })}
          </p>
          <p className="mt-1 text-19 text-ink-faint">
            {t('buildScreen.production', { n: colony.production })}
            {colony.pollution > 0 ? t('buildScreen.cleanup', { n: colony.pollution }) : ''}
          </p>
        </Panel>

        <Panel title={t('buildScreen.queue', { name: planet.name })} className="overflow-auto">
          {colony.queue.length === 0 ? (
            <p className="text-19 text-ink-faint">
              {t('buildScreen.queue.empty')}
            </p>
          ) : (
            <ol className="flex flex-col gap-0.5">
              {colony.queue.map((project, index) => (
                <li key={`${project.code}-${index}`} className="flex items-baseline gap-2">
                  <span className="w-5 shrink-0 text-right text-18 text-ink-faint">
                    {index + 1}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-20 text-ink">
                    {project.name}
                  </span>
                  <span className="shrink-0 text-18 text-ink-faint">
                    {buildTime(colony, project)}
                  </span>
                  <span className="flex shrink-0 items-baseline gap-1 text-18">
                    <QueueButton
                      label="▸"
                      title={t('build.now.title')}
                      disabled={saving}
                      onClick={() => onChoose(project)}
                    />
                    <QueueButton
                      label="▲"
                      title={t('build.up.title')}
                      disabled={saving || index === 0}
                      onClick={() => onMove(index, index - 1)}
                    />
                    <QueueButton
                      label="▼"
                      title={t('build.down.title')}
                      disabled={saving || index === colony.queue.length - 1}
                      onClick={() => onMove(index, index + 1)}
                    />
                    <QueueButton
                      label="✕"
                      title={t('build.remove.title')}
                      disabled={saving}
                      onClick={() => onRemove(index)}
                    />
                  </span>
                </li>
              ))}
            </ol>
          )}
        </Panel>

        {/* Правый нижний сегмент — полоса кнопок оригинала: она и правит очередью. */}
        <Panel title={t('buildScreen.controls')}>
          <div className="flex h-full flex-col gap-1 text-20">
            <button
              type="button"
              className="link text-left disabled:cursor-not-allowed disabled:text-ink-off"
              disabled={saving || selected === null || current}
              onClick={() => selected && onChoose(selected)}
            >
              {t('buildScreen.build')}
            </button>
            <button
              type="button"
              className="link text-left disabled:cursor-not-allowed disabled:text-ink-off"
              disabled={saving || !canQueue}
              onClick={() => selected && onEnqueue(selected)}
            >
              {t('buildScreen.enqueue')}
            </button>
            {/*
              DESIGN оригинала: нажатие не открывает окно, а превращает указатель в
              выбор ЦЕЛИ — правят один из шести проектов правой колонки (п. 8). Второе
              нажатие снимает выбор, как и Esc.
            */}
            <button
              type="button"
              className={'text-left ' + (picking ? 'link link-active' : 'link')}
              onClick={() => setPicking(!picking)}
            >
              {picking ? t('buildScreen.design.picking') : t('buildScreen.design')}
            </button>

            <span className="mt-auto block text-17 leading-tight text-ink-faint">
              {error ? (
                <span className="text-danger">{error}</span>
              ) : picking ? (
                <span className="text-accent">{t('buildScreen.design.pickHint')}</span>
              ) : queueFull ? (
                t('buildScreen.queueFull', { n: QUEUE_LIMIT })
              ) : current ? (
                t('buildScreen.alreadyBuilding') + (selected?.repeatable ? t('buildScreen.canRepeat') : '')
              ) : (
                t('buildScreen.hint')
              )}
            </span>
            <button type="button" className="link text-left" onClick={onClose}>
              {t('buildScreen.close')}
            </button>
          </div>
        </Panel>
      </div>

      {/*
        Дизайн кораблей — та же сцена, что и с экрана флота, но открытая СРАЗУ на
        выбранной ячейке: список проектов ей не нужен, цель уже назвали указателем.
      */}
      {designSlot !== null ? (
        <ShipDesignScreen slot={designSlot} onClose={() => setDesignSlot(null)} />
      ) : null}
    </div>
  );
}

/**
 * Сколько проектов держит очередь стройки — п. 10.
 *
 * Повторяет предел сервера (`ColonyProject.QUEUE_LIMIT`): окно гасит кнопку заранее,
 * а не показывает отказ после нажатия. Решает всё равно сервер.
 */
const QUEUE_LIMIT = 7;

/** Улетающее с планеты: корабли, грузовики и шпион — им место в правой колонке. */
const isSpace = (code: string): boolean =>
  code.startsWith('SHIP') || code === 'FREIGHTER' || code === 'SPY'
  || code === 'COLONY_SHIP' || code === 'OUTPOST_SHIP' || code === 'TRANSPORT';

/** Срок постройки строкой: у бесконечного его нет, без производства — считать нечем. */
const buildTime = (colony: Colony, project: ColonyProject): string => {
  if (project.cost === undefined) {
    return t('build.endless');
  }
  const turns = projectTurns(colony, project.cost);
  return turns === null ? '—' : t('build.turns', { n: turns });
};

/** Сегмент сцены: рамка с подписью — их в оригинале семь, и все одинаковые. */
function Panel({
  title,
  className,
  children,
}: {
  title: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={`flex min-h-0 flex-col border border-space-700 bg-space-950/60 ${className ?? ''}`}>
      <div className="shrink-0 truncate border-b border-space-800 px-2 py-1 text-18 uppercase tracking-[0.2em] text-ink-dim">
        {title}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-2">{children}</div>
    </section>
  );
}

/** Список проектов колонки: строка — название, строящееся помечено. */
function ProjectList({
  projects,
  selectedCode,
  currentCode,
  onSelect,
  onChoose,
}: {
  projects: ColonyProject[];
  selectedCode: string | null;
  currentCode?: string;
  onSelect: (code: string) => void;
  onChoose: (project: ColonyProject) => void;
}) {
  return (
    <ul className="flex flex-col">
      {projects.map((project) => (
        <li key={project.code}>
          <button
            type="button"
            className={
              'link block w-full truncate px-1 py-0.5 text-left text-20 '
              + (project.code === selectedCode ? 'link-active bg-space-800' : '')
            }
            onClick={() => onSelect(project.code)}
            onDoubleClick={() => onChoose(project)}
          >
            {project.code === currentCode ? '▸ ' : ''}
            {project.name}
          </button>
        </li>
      ))}
    </ul>
  );
}

/** Кнопка строки очереди: стрелки, крестик и «строить сейчас». */
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
      className="link disabled:cursor-not-allowed disabled:text-ink-off"
      disabled={disabled}
      title={title}
      onClick={onClick}
    >
      {label}
    </button>
  );
}
