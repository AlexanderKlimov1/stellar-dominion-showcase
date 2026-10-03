import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet, ShipDesign, BuildTemplate } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { ShipDesignScreen } from '../ship/ShipDesignScreen';
import { useModalEscape } from '../useModalEscape';
import { ColonyQueue, queueShift, queueStep } from './ColonyQueue';
import { projectTurns } from './colonyRules';
import { QUEUE_LIMIT } from './buildLimits';
import { useBuildHotkeys } from './useBuildHotkeys';
import { BuildTemplateScreen } from '../build/BuildTemplateScreen';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

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
 *
 * <b>ЩЕЛЧОК ПО СПИСКУ СТРОЙКИ КЛАДЁТ ПРОЕКТ В ГОЛОВУ ОЧЕРЕДИ</b> — решение хозяина
 * проекта (30.09.2026): так очередь набирают в оригинале, одной рукой и без прицеливания
 * в кнопку на другом конце окна. Тот же щелчок показывает проект в средних сегментах —
 * цена, содержание, срок и описание, — так что выбор и действие здесь одно движение.
 * Кнопка «в конец очереди» осталась и делает ровно то, что говорит: дописывает в хвост.
 *
 * <b>Двойного щелчка на списке больше нет.</b> Он ставил проект на стапель, а теперь
 * первым же своим щелчком успевал бы положить копию в очередь: одно движение мыши делало
 * бы два разных дела. «Строить сейчас» осталось кнопкой «строить» внизу и значком «▸» в
 * строке очереди.
 *
 * <b>Дома и товары в очередь не встают</b> (правило сервера: они не кончаются, а очередь
 * забирают только за достроенным), поэтому щелчок по ним значит «строить сейчас» — другого
 * смысла у него там нет.
 */
export function ColonyBuildScreen({
  planet,
  colony,
  saving,
  error,
  onChoose,
  onEnqueue,
  onBuildMode,
  onTemplate,
  onRemove,
  onMove,
  onBuy,
  onClose,
}: {
  planet: Planet;
  colony: Colony;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  saving: boolean;
  error: string | null;
  /** На стапель; `keepCurrent` — прежняя стройка сдвигается первой в очередь, а не пропадает. */
  onChoose: (project: ColonyProject, keepCurrent?: boolean) => void;
  /** В очередь: `top` — в голову, иначе в конец — п. 10. */
  onEnqueue: (project: ColonyProject, top?: boolean) => void;
  /** Повтор очереди и автострой — п. 10; шлётся только то, что переключили. */
  onBuildMode: (mode: { repeatBuild?: boolean; autoBuild?: boolean }) => void;
  /** Заложить колонии шаблон стройки — п. 10. */
  onTemplate: (templateId: string) => void;
  onRemove: (index: number) => void;
  onMove: (index: number, toIndex: number) => void;
  /** Выкупить текущую стройку — п. 10: монетка у верхней строки очереди. */
  onBuy: () => void;
  onClose: () => void;
}) {
  const credits = useGameStore((state) => selectSelf(state)?.credits ?? 0);
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
  /** Выбранная строка очереди: её убирает клавиша D. */
  const [selectedQueue, setSelectedQueue] = useState<number | null>(null);
  /**
   * Шаблоны стройки — п. 10. Спрашиваются при открытии окна: они принадлежат записи, а не
   * партии, и могли измениться с прошлого раза — в другой партии или на другой машине.
   */
  const [templates, setTemplates] = useState<BuildTemplate[]>([]);
  const [templatesOpen, setTemplatesOpen] = useState(false);

  useEffect(() => {
    gameApi.listBuildTemplates().then(setTemplates).catch(() => setTemplates([]));
  }, [templatesOpen]);
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

  // R — повтор очереди, D — убрать выбранную строку. Окно стройки полноэкранное и
  // открыто поверх всего, поэтому клавиатура принадлежит ему целиком; пока ждут выбора
  // цели дизайна, буквы не наши — там своя игра с указателем.
  useBuildHotkeys({
    active: !picking,
    onRepeat: () => onBuildMode({ repeatBuild: !colony.repeatBuild }),
    onDelete: () => {
      if (selectedQueue !== null && selectedQueue < colony.queue.length) {
        onRemove(selectedQueue);
        setSelectedQueue(null);
      }
    },
    onStep: (delta) => setSelectedQueue(queueStep(colony.queue, selectedQueue, delta)),
    onShift: (delta) => {
      const shift = saving ? null : queueShift(colony.queue, selectedQueue, delta);
      if (shift) {
        onMove(shift.from, shift.to);
        setSelectedQueue(shift.selected);
      }
    },
  });
  const current = selected !== null && selected.code === colony.projectCode;
  const queueFull = colony.queue.length >= QUEUE_LIMIT;
  /**
   * Щелчок по строке списка стройки — п. 10.
   *
   * Проект встаёт В КОНЕЦ очереди и тут же показывается в средних сегментах: выбор и
   * действие — одно движение. Отказ (очередь полна, базе некуда селиться) приходит с
   * сервера словами и ложится в строку ошибки: молчаливое «ничего не произошло» игрок
   * прочтёт как поломку.
   *
   * В конец, а не в голову (01.10.2026, решение хозяина проекта): очередь набирают по
   * порядку, и поставленное следом обязано встать следом. В голову проект вклинивался между
   * уже набранными — две колониальные базы, затем шпион, и шпион оказывался между базами.
   * Поднять проект выше — щелчок по строке очереди и второй по месту, куда его поставить.
   */
  const pick = (project: ColonyProject) => {
    setSelectedCode(project.code);
    if (saving) {
      return;
    }
    // Дома и товары встают в очередь как любая стройка; если колония сама на бесконечной,
    // сервер ставит выбранное на её место (`ColonyService.enqueue`).
    // Уже на стапеле и строится однажды: в очереди ему делать нечего — она отменила бы
    // его на следующем же ходу (`ColonyService.enqueue` на это и отвечает отказом).
    if (project.code === colony.projectCode && !project.repeatable) {
      return;
    }
    onEnqueue(project, false);
  };

  // Колония занята бесконечной стройкой: сервер поставит выбранное НА ЕЁ МЕСТО, а не в
  // очередь (`ColonyService.enqueue`). Кнопка «в конец очереди» тут лжёт названием,
  // поэтому гаснет, а объяснение уходит в подсказку — делает то же самое кнопка «строить».
  const endlessNow = colony.projectCode !== null && colony.projectCost === undefined;
  const canQueue = selected !== null && !queueFull && !endlessNow
    && (!current || selected.repeatable);

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
            onPick={pick}
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
                <dl className={'grid grid-cols-[11rem_minmax(0,1fr)] items-baseline gap-x-3 gap-y-0.5 '
                  + figureLabelClass('value')}>
                  <dt>{t('buildScreen.cost')}</dt>
                  <dd>
                    {selected.cost === undefined ? t('build.endless') : tf('buildScreen.units', { n: <Figure accent="value">{selected.cost}</Figure> })}
                  </dd>
                  <dt>{t('buildScreen.upkeep')}</dt>
                  <dd>
                    {selected.upkeep > 0 ? tf('buildScreen.upkeep.value', { n: <Figure accent="value">{selected.upkeep}</Figure> }) : t('common.none')}
                  </dd>
                  <dt>{t('buildScreen.time')}</dt>
                  <dd>{buildTime(colony, selected)}</dd>
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
            onPick={pick}
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
                            ? 'link-quiet link-active bg-space-800'
                            : 'link-quiet')
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
                        pick(project);
                      }
                    }}
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
                onPick={pick}
              />
            </div>
          ) : null}

          {spy ? (
            <div className="mt-2 border-t border-space-800 pt-1">
              <ProjectList
                projects={[spy]}
                selectedCode={selected?.code ?? null}
                currentCode={colony.projectCode}
                onPick={pick}
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
          <p className={'mt-1 ' + figureLabelClass('value')}>
            {colony.projectCost === undefined
              ? t('buildScreen.endless')
              : tf('build.progress', {
                  points: <Figure accent="value">{colony.projectPoints}</Figure>,
                  cost: <Figure accent="value">{colony.projectCost}</Figure>,
                })}
          </p>
          <p className={'mt-1 ' + figureLabelClass('value')}>
            {tf('buildScreen.production', { n: <Figure accent="value">{colony.production}</Figure> })}
            {colony.pollution > 0
              ? tf('buildScreen.cleanup', { n: <Figure accent="value" tone="warn">{colony.pollution}</Figure> })
              : ''}
          </p>
        </Panel>

        <Panel title={t('buildScreen.queue', { name: planet.name })} className="overflow-auto">
          <ColonyQueue
            colony={colony}
            own
            busy={saving}
            wide
            onRemove={onRemove}
            onMove={onMove}
            onPromote={onChoose}
            onEnqueue={onEnqueue}
            selected={selectedQueue}
            onSelect={setSelectedQueue}
            credits={credits}
            onBuy={onBuy}
            onDropProject={(code) => {
              const project = byCode.get(code);
              if (project) {
                onEnqueue(project);
              }
            }}
          />
        </Panel>

        {/* Правый нижний сегмент — полоса кнопок оригинала: она и правит очередью. */}
        <Panel title={t('buildScreen.controls')}>
          <div className="flex h-full flex-col gap-1 text-20">
            <button
              type="button"
              className="link-quiet text-left disabled:cursor-not-allowed disabled:text-ink-off"
              disabled={saving || selected === null || current}
              onClick={() => selected && onChoose(selected)}
            >
              {t('buildScreen.build')}
            </button>
            {/*
              «В конец очереди» — то, чего щелчок по списку не делает: он кладёт в ГОЛОВУ.
              Обе дороги нужны: голова — это «строить сразу за нынешним», хвост — «когда
              дойдут руки».
            */}
            <button
              type="button"
              className="link-quiet text-left disabled:cursor-not-allowed disabled:text-ink-off"
              disabled={saving || !canQueue}
              title={t('buildScreen.enqueue.title')}
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
              className={'text-left ' + (picking ? 'link-quiet link-active' : 'link-quiet')}
              onClick={() => setPicking(!picking)}
            >
              {picking ? t('buildScreen.design.picking') : t('buildScreen.design')}
            </button>

            {/*
              REPEAT BUILD и AUTO BUILD оригинала — две кнопки, которых у нас не было вовсе
              (`docs/moo2/README.md`). Первая пускает очередь по кругу, вторая отдаёт выбор
              колонии. Обе — переключатели, и взведённая помечена как выбранный пункт.
            */}
            <button
              type="button"
              className={'text-left ' + (colony.repeatBuild ? 'link-quiet link-active' : 'link-quiet')}
              disabled={saving}
              title={t('buildScreen.repeat.title')}
              onClick={() => onBuildMode({ repeatBuild: !colony.repeatBuild })}
            >
              {t('buildScreen.repeat')}
            </button>
            <button
              type="button"
              className={'text-left ' + (colony.autoBuild ? 'link-quiet link-active' : 'link-quiet')}
              disabled={saving}
              title={t('buildScreen.auto.title')}
              onClick={() => onBuildMode({ autoBuild: !colony.autoBuild })}
            >
              {t('buildScreen.auto')}
            </button>

            {/*
              Шаблон стройки — п. 10: набор закладывается одним движением. Шаблоны свои,
              общие для всех партий, поэтому список короткий и лежит прямо в кнопках: лишний
              диалог ради выбора из трёх строк — плохой обмен.
            */}
            {templates.length > 0 ? (
              <div className="mt-1 flex flex-col">
                <span className="text-17 uppercase tracking-[0.2em] text-ink-faint">
                  {t('template.apply')}
                </span>
                {templates.map((template) => (
                  <button
                    key={template.id}
                    type="button"
                    className="link-quiet truncate text-left"
                    disabled={saving || queueFull}
                    title={t('template.apply.title')}
                    onClick={() => onTemplate(template.id)}
                  >
                    {t('template.tier.short', { n: template.tier })} {template.name}
                  </button>
                ))}
              </div>
            ) : null}
            <button type="button" className="link-quiet text-left text-ink-dim"
                    onClick={() => setTemplatesOpen(true)}>
              {t('menu.templates')}
            </button>

            <span className="mt-auto block text-17 leading-tight text-ink-faint">
              {error ? (
                <span className="text-danger">{error}</span>
              ) : picking ? (
                <span className="text-accent">{t('buildScreen.design.pickHint')}</span>
              ) : queueFull ? (
                t('buildScreen.queueFull', { n: QUEUE_LIMIT })
              ) : endlessNow ? (
                t('buildScreen.enqueue.replaces')
              ) : current ? (
                t('buildScreen.alreadyBuilding') + (selected?.repeatable ? t('buildScreen.canRepeat') : '')
              ) : (
                t('buildScreen.hint')
              )}
            </span>
            <button type="button" className="link-quiet text-left" onClick={onClose}>
              {t('buildScreen.close')}
            </button>
          </div>
        </Panel>
      </div>

      {/*
        Дизайн кораблей — та же сцена, что и с экрана флота, но открытая СРАЗУ на
        выбранной ячейке: список проектов ей не нужен, цель уже назвали указателем.
      */}
      {templatesOpen ? <BuildTemplateScreen onClose={() => setTemplatesOpen(false)} /> : null}

      {designSlot !== null ? (
        <ShipDesignScreen slot={designSlot} onClose={() => setDesignSlot(null)} />
      ) : null}
    </div>
  );
}


/** Улетающее с планеты: корабли, грузовики и шпион — им место в правой колонке. */
const isSpace = (code: string): boolean =>
  code.startsWith('SHIP') || code === 'FREIGHTER' || code === 'SPY'
  || code === 'COLONY_SHIP' || code === 'OUTPOST_SHIP' || code === 'TRANSPORT';

/** Срок постройки строкой: у бесконечного его нет, без производства — считать нечем. */
/**
 * Сколько ходов займёт САМ проект — поле «Build Time» оригинала.
 *
 * Это не то же, что срок в очереди: там стоит ОЖИДАНИЕ (остаток текущей стройки плюс всё
 * впереди, {@link ColonyQueue}), а здесь — длина выбранного в отрыве от очереди, то есть
 * ответ на вопрос «во что мне это обойдётся», а не «когда это будет». В оригинале рядом
 * стоят оба числа: `Build Time` и `Turn(s) Left`.
 */
const buildTime = (colony: Colony, project: ColonyProject): ReactNode => {
  if (project.cost === undefined) {
    return t('build.endless');
  }
  const turns = projectTurns(colony, project.cost);
  return turns === null ? '—' : tf('build.turns', { n: <Figure accent="value">{turns}</Figure> });
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

/**
 * Список проектов колонки: строка — название, строящееся помечено.
 *
 * Щелчок по строке кладёт проект в голову очереди и показывает его в средних сегментах —
 * одно движение на оба дела (п. 10). Двойного щелчка у строки нет: он успевал бы сработать
 * первым щелчком и сделал бы заодно второе, другое дело.
 */
function ProjectList({
  projects,
  selectedCode,
  currentCode,
  onPick,
}: {
  projects: ColonyProject[];
  selectedCode: string | null;
  currentCode?: string;
  onPick: (project: ColonyProject) => void;
}) {
  return (
    <ul className="flex flex-col">
      {projects.map((project) => (
        <li key={project.code}>
          <button
            type="button"
            /*
              Проект тащат отсюда в очередь: это та же «в очередь», только мышью и без
              прицеливания в кнопку на другом конце окна.
            */
            draggable
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-sddnw-project', project.code);
              event.dataTransfer.effectAllowed = 'copy';
            }}
            className={
              'link-quiet block w-full truncate px-1 py-0.5 text-left text-20 '
              + (project.code === selectedCode ? 'link-active bg-space-800' : '')
            }
            title={t('buildScreen.pick.title')}
            onClick={() => onPick(project)}
          >
            {project.code === currentCode ? '● ' : ''}
            {project.name}
          </button>
        </li>
      ))}
    </ul>
  );
}

