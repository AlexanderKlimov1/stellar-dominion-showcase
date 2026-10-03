import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { FleetGroup } from '../../api/types';
import { gameEvents, type ScreenCourse } from '../../events/gameEvents';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { CELL, shipSize } from '../battle/battleVisuals';
import { MAP_INSET_ATTRIBUTE, sidePanelWidthPx } from '../layout';
import { findTechnology } from '../research/researchRules';
import { ShipDefs, ShipSprite } from '../ship/shipArt';
import { useModalEscape } from '../useModalEscape';
import { isCombat, shipSlots } from './fleetShips';
import { gameApi } from '../../api/client';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Флот в полёте — п. 8, сцена оригинала (`docs/moo2/fleet-send.png`).
 *
 * В MOO II у флота одно окно и для стоянки, и для полёта: плашка «Human Fleet», ячейки
 * кораблей, кнопки ALL и CLOSE, а между ними — строка состояния. У стоящего флота в ней
 * «Orbiting Sol» (`docs/moo2/fleet-dialog.png`), у летящего — **«2 turns to Draconis»**, и это
 * главное, ради чего окно открывают: куда летит и когда долетит. На карте при этом
 * тянется пунктир курса — он у нас был и раньше (`GalaxyScene.drawCourses`).
 *
 * Разметка снята со снимка: окно в углу поля, карта под ним не темнеет, сверху плашка
 * названия, под ней ячейки по три в ряд, строка состояния и одна широкая кнопка внизу.
 *
 * **Угол оригинал выбирает сам, подальше от флота**: на `fleet-send.png` окно слева
 * вверху, а на `fleet-dialog.png`, где флот стоит у Sol в верхней части карты, — слева
 * внизу. Так и у нас (`placeWindow`): окно встаёт в тот из левых углов, что не лежит на пути
 * флота. Легли оба — **карта сдвигается** (`map:shift`) ровно настолько, чтобы путь вышел
 * из-под окна, а закрытое окно возвращает её на место: карта оригинала прокручивается, и
 * путь там из-под окна выкатывают так же.
 *
 * **ALL — как в оригинале**: отбирает все корабли флота, и следующая звезда, указанная на
 * карте, становится новой целью. Окно при этом остаётся открытым, а отобранные ячейки
 * подсвечены — так и в MOO II курс меняют при открытом окне. Отбирается всегда весь флот:
 * в пути его не разделить (`FleetService.redirect`), поэтому ячейки поштучно не нажимаются.
 * Курс в пути меняется только с Hyperspace Communications; без неё ALL погашена, а под
 * строкой состояния сказано, чего не хватает, — погашенная кнопка без причины читалась бы
 * поломкой.
 *
 * <b>Отличия от оригинала — намеренные:</b>
 * - **Под строкой состояния — откуда и с какой скоростью**: в оригинале это видно по
 *   пунктиру на карте, а окно у нас может закрывать его начало.
 * - **Карту сдвигаем сами**, а не ждём, пока игрок её протянет: наша вписана в экран
 *   целиком, и окно слева закрывало бы звезду, куда флот летит, — ровно то, ради чего его
 *   открывают.
 */
export function FleetInFlightWindow({ fleet, course, onClose, onColonize }: {
  fleet: FleetGroup;
  /** Путь флота на экране в миг нажатия; нет — окно стоит слева, как в оригинале. */
  course?: ScreenCourse;
  onClose: () => void;
  /**
   * «Колонизовать планету» — п. 4.1: есть, только когда флот с колониальным кораблём стоит
   * у свободной пригодной планеты. Окно высадки рисует `App`, а не это окно: оно само
   * лежит в углу карты, и полноэкранное окно, нарисованное изнутри, легло бы в его рамку.
   */
  onColonize?: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const self = useGameStore(selectSelf);
  const canRedirect = useGameStore((state) => state.fleet?.canRedirect ?? false);
  const researchTree = useGameStore((state) => state.researchTree);
  const setTargetingFleet = useGameStore((state) => state.setTargetingFleet);
  /*
    Отобран ли флот — не своё состояние окна, а прицел и приказ из хранилища: ALL включает
    прицел, выбранная звезда превращает его в приказ, и пока идёт то или другое, корабли
    отобраны. Отменил прицел по Esc — подсветка гаснет сама.
  */
  const targeting = useGameStore((state) => state.targetingFleetId === fleet.id);
  const selected = useGameStore((state) => targeting || state.fleetOrder?.fleetId === fleet.id);
  const rangeParsecs = useGameStore((state) => state.fleet?.rangeParsecs ?? 0);
  const credentials = useGameStore((state) => state.credentials);
  const espionage = useGameStore((state) => state.espionage);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const [exploreBusy, setExploreBusy] = useState(false);
  const [exploreError, setExploreError] = useState<string | null>(null);
  /*
    «Разведывать самим» — backlog-promo, пункт 24: разведчики, долетев, стояли без дела, и
    новичок не возвращался к ним по пять ходов. Разведывают боевые корабли: из смешанного
    флота (стартовые разведчики стоят вместе с колониальным кораблём) сервер отделит их сам.
  */
  const canExplore = fleet.composition.some((ship) => ship.role === 'WARSHIP');
  const toggleExplore = () => {
    if (!game || !credentials || exploreBusy) {
      return;
    }
    setExploreBusy(true);
    setExploreError(null);
    gameApi
      .exploreFleet(game.id, fleet.id, credentials.accessToken, !fleet.autoExplore)
      .then(async () => {
        setEmpire(espionage, await gameApi.getFleet(game.id, credentials.accessToken));
      })
      .catch((failure: Error) => setExploreError(failure.message))
      .finally(() => setExploreBusy(false));
  };
  // Esc сперва снимает отбор, как снимал бы его общий прицел, и только потом закрывает окно.
  useModalEscape(true, () => (targeting ? setTargetingFleet(null) : onClose()));
  useT();

  const panelRef = useRef<HTMLDivElement>(null);
  const [place, setPlace] = useState<{ left: number; top: number } | null>(null);
  // До отрисовки: окно, мелькнувшее в одном углу и перепрыгнувшее в другой, читалось бы сбоем.
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!panel || !course) {
      setPlace(null);
      return;
    }
    const placement = placeWindow(course, panel);
    setPlace(placement.place);
    if (placement.shift) {
      gameEvents.emit('map:shift', placement.shift);
    }
  }, [course]);
  // Сдвинутая карта возвращается, когда окно закрыто, — сколько бы раз её ни сдвигали.
  useEffect(() => () => gameEvents.emit('map:shift-back', undefined), []);

  const slots = shipSlots(fleet);
  const shown = slots.length > MAX_CELLS ? slots.slice(0, MAX_CELLS - 1) : slots;
  const hidden = slots.length - shown.length;
  // Пустые ячейки дорисовываются до конца ряда, как в оригинале: флот из четырёх кораблей
  // там — шесть ячеек, две пустые, а не обрубок ряда.
  const cells = Math.max(COLUMNS * 2, Math.ceil((shown.length + (hidden > 0 ? 1 : 0)) / COLUMNS) * COLUMNS);

  const flying = Boolean(fleet.targetSystemId);
  const target = fleet.targetSystemName ?? t('fleetOps.unexploredStar');
  const turnsLeft = (fleet.arrivalTurn ?? 0) - (game?.turn ?? 0);
  const redirectTech = findTechnology(researchTree, REDIRECT_TECH)?.name ?? REDIRECT_TECH;

  return (
    <div
      ref={panelRef}
      className="panel absolute left-3 top-3 z-30 flex w-[min(21rem,calc(100%-1.5rem))] flex-col gap-2 p-3 text-11"
      style={place ?? undefined}
    >
      {/*
        Плашка названия — «Human Fleet» оригинала: флот назван расой, своих имён у флотов нет.
        Зелёный там — не «хорошо», а цвет империи игрока (тем же цветом подписана его Sol),
        поэтому и здесь плашка и строка состояния — цветом своей империи.
      */}
      <h2
        className="border border-space-600 bg-space-800 py-1 text-center text-14 tracking-[0.15em]"
        style={{ color: self?.color }}
      >
        {t('fleetFlight.title', { race: self?.raceName ?? self?.name ?? '' })}
      </h2>

      <div className="grid grid-cols-3 gap-1 border border-space-700 bg-space-950 p-1">
        {shown.map((slot) => (
          <div
            key={slot.key}
            title={`${slot.ship.designName}${slot.ship.hullName ? ` · ${slot.ship.hullName}` : ''}`}
            className={`flex aspect-square items-center justify-center border ${
              selected ? 'border-accent bg-space-800' : 'border-space-700 bg-space-900/60'
            }`}
          >
            {/* Силуэт корпуса того же размера, что в сетке экрана флота и на поле боя. */}
            <svg aria-hidden="true" viewBox="-50 -50 100 100"
                 style={{ width: `${(shipSize(slot.ship.hullSize ?? 1) / CELL) * 100}%`,
                          height: `${(shipSize(slot.ship.hullSize ?? 1) / CELL) * 100}%` }}>
              <defs><ShipDefs /></defs>
              <ShipSprite hullCode={slot.ship.hullCode} hullSize={slot.ship.hullSize ?? 1} role={slot.ship.role}
                          color={isCombat(slot.ship) ? '#7dd3fc' : '#94a3b8'} cx={0} cy={0} size={100} />
            </svg>
          </div>
        ))}
        {hidden > 0 ? (
          <div className={'flex aspect-square items-center justify-center border border-space-700 ' + figureLabelClass('note')}>
            {tf('fleetFlight.more', { n: <Figure accent="note">{hidden + 1}</Figure> })}
          </div>
        ) : null}
        {Array.from({ length: cells - shown.length - (hidden > 0 ? 1 : 0) }).map((_, index) => (
          <div key={`empty-${index}`} className="aspect-square border border-space-800/70" />
        ))}
      </div>

      {/*
        Строка состояния — «2 turns to Draconis» оригинала. Прибывающий в этот ход флот
        назван прибывающим: «0 ходов до цели» читалось бы как ошибка счёта.
      */}
      <div className="flex gap-1">
        {/* ALL — слева от строки состояния, как на снимке. Повторное нажатие снимает отбор. */}
        <button
          type="button"
          disabled={flying && !canRedirect}
          aria-pressed={selected}
          title={!flying || canRedirect ? t('fleetFlight.allHint') : t('fleetFlight.noRedirect', { tech: redirectTech })}
          className={`border px-3 uppercase tracking-[0.2em] disabled:cursor-not-allowed disabled:border-space-700 disabled:text-ink-off ${
            selected
              ? 'border-accent bg-space-800 text-accent'
              : 'border-space-600 bg-space-800 text-ink hover:border-accent hover:text-accent'
          }`}
          onClick={() => setTargetingFleet(selected ? null : fleet.id)}
        >
          {t('fleetOps.all')}
        </button>
        <p
          className={'flex-1 border border-space-700 bg-space-900 py-1 text-center ' + figureLabelClass('note')}
          style={{ color: self?.color }}
        >
          {!flying
            ? t('fleetFlight.orbiting', { system: fleet.systemName })
            : turnsLeft > 0
              ? tf('fleetFlight.turnsTo', {
                  n: <Figure accent="note">{turnsLeft}</Figure>,
                  unit: turnUnit(turnsLeft),
                  target,
                })
              : t('fleetFlight.arriving', { target })}
        </p>
      </div>
      {flying ? (
        <p className={'text-center ' + figureLabelClass('note')}>
          {tf('fleetFlight.from', {
            origin: fleet.systemName,
            speed: <Figure accent="note">{fleet.speed}</Figure>,
            ships: <Figure accent="note">{fleet.ships}</Figure>,
          })}
        </p>
      ) : null}
      {/*
        COLONIZE PLANET — п. 4.1: колониальный корабль у свободной пригодной планеты. В
        оригинале заселить предлагают при прилёте, а отказавшийся находит эту кнопку в окне
        флота на следующих ходах.
      */}
      {onColonize ? (
        <button
          type="button"
          className="border border-accent bg-space-800 py-1 uppercase tracking-[0.2em] text-accent hover:bg-space-700"
          onClick={onColonize}
        >
          {t('fleetFlight.colonize')}
        </button>
      ) : null}
      {canExplore ? (
        <button
          type="button"
          aria-pressed={Boolean(fleet.autoExplore)}
          disabled={exploreBusy}
          title={t('fleetFlight.explore.title')}
          className={`border py-1 uppercase tracking-[0.2em] ${
            fleet.autoExplore
              ? 'border-accent bg-space-700 text-accent'
              : 'border-space-600 bg-space-800 text-ink hover:border-accent hover:text-accent'
          }`}
          onClick={toggleExplore}
        >
          {fleet.autoExplore ? t('fleetFlight.explore.on') : t('fleetFlight.explore')}
        </button>
      ) : null}
      {exploreError ? <p className="text-center text-danger">{exploreError}</p> : null}
      {/*
        Подсказка прицела — здесь, а не общей полосой по центру карты: та легла бы поверх
        окна, а в оригинале курс и так меняют при открытом окне флота.
      */}
      {targeting ? (
        <p className={'text-center ' + figureLabelClass('note')}>
          {tf('fleetFlight.pickTarget', { n: <Figure accent="note">{rangeParsecs}</Figure> })}
        </p>
      ) : null}
      {!flying || canRedirect ? null : (
        <p className="text-center text-ink-faint">{t('fleetFlight.noRedirect', { tech: redirectTech })}</p>
      )}

      <button
        type="button"
        className="border border-space-600 bg-space-800 py-1 uppercase tracking-[0.3em] text-ink hover:border-accent hover:text-accent"
        onClick={onClose}
      >
        {t('common.close')}
      </button>
    </div>
  );
}

type Box = { left: number; top: number; right: number; bottom: number };

/** Где встать окну и насколько сдвинуть карту, чтобы путь флота вышел из-под него. */
interface Placement {
  /** Левый верхний угол окна в координатах его родителя. */
  place: { left: number; top: number };
  /** Сдвиг карты в пикселях страницы; нет — путь и так на виду. */
  shift?: { dx: number; dy: number };
}

/**
 * Угол окна и сдвиг карты — по пути флота на экране.
 *
 * Углов два, оба левых, как в оригинале: верхний и нижний. Берётся первый, что отстоит от
 * пути хотя бы на {@link CLEAR_MARGINS} отступов. Не отстоит ни один — карта сдвигается:
 * вправо, прочь от окна, или по вертикали прочь от его угла, и из всех вариантов берётся
 * самый короткий, при котором оба конца пути остаются на карте. Такого нет — просто самый
 * короткий: путь, выехавший концом за край, лучше пути под окном.
 *
 * Карта — это экран без правой панели и без нижней полосы: ширину панели знает раскладка
 * (`sidePanelWidthPx`), а верх полосы меряется по той же метке, по которой сцена
 * сжимает камеру (`MAP_INSET_ATTRIBUTE`). Отступ от краёв — тот же, что у `left-3 top-3`.
 */
function placeWindow(course: ScreenCourse, panel: HTMLElement): Placement {
  const rect = panel.getBoundingClientRect();
  const parent = (panel.offsetParent ?? document.body).getBoundingClientRect();
  const margin = parseFloat(getComputedStyle(document.documentElement).fontSize) * 0.75;
  const bar = document.querySelector(`[${MAP_INSET_ATTRIBUTE}="bottom"]`);
  const mapRight = window.innerWidth - sidePanelWidthPx();
  const mapBottom = bar ? bar.getBoundingClientRect().top : window.innerHeight;
  const enough = margin * CLEAR_MARGINS;

  // Верхний угол уводит путь вниз, нижний — вверх; вправо уводят оба.
  const corners = [
    { top: margin, away: 1 },
    { top: mapBottom - margin - rect.height, away: -1 },
  ].map(({ top, away }) => ({
    box: { left: margin, top, right: margin + rect.width, bottom: top + rect.height },
    away,
  }));
  const at = (box: Box) => ({ left: box.left - parent.left, top: box.top - parent.top });

  for (const { box } of corners) {
    if (clearance(course, box) >= enough) {
      return { place: at(box) };
    }
  }

  const onMap = (x: number, y: number) => x >= 0 && x <= mapRight && y >= 0 && y <= mapBottom;
  let best: (Placement & { length: number; visible: boolean }) | null = null;
  for (const { box, away } of corners) {
    for (const [ux, uy] of [[1, 0], [0, away]]) {
      for (let length = SHIFT_STEP; length <= Math.max(mapRight, mapBottom); length += SHIFT_STEP) {
        const dx = ux * length;
        const dy = uy * length;
        const moved = {
          from: { x: course.from.x + dx, y: course.from.y + dy },
          to: { x: course.to.x + dx, y: course.to.y + dy },
        };
        if (clearance(moved, box) < enough) {
          continue;
        }
        const visible = onMap(moved.from.x, moved.from.y) && onMap(moved.to.x, moved.to.y);
        if (!best || (visible && !best.visible) || (visible === best.visible && length < best.length)) {
          best = { place: at(box), shift: { dx, dy }, length, visible };
        }
        break;
      }
    }
  }
  return best ? { place: best.place, shift: best.shift } : { place: at(corners[0].box) };
}

/**
 * Насколько путь отстоит от прямоугольника окна: наименьшее расстояние по точкам отрезка,
 * ноль — окно на пути лежит. Точек два десятка: отрезок на экране короче тысячи пикселей,
 * и промах шага в полсотни пикселей на выбор угла не влияет.
 */
function clearance(course: ScreenCourse, box: Box): number {
  let nearest = Number.POSITIVE_INFINITY;
  for (let step = 0; step <= COURSE_SAMPLES; step++) {
    const share = step / COURSE_SAMPLES;
    const x = course.from.x + (course.to.x - course.from.x) * share;
    const y = course.from.y + (course.to.y - course.from.y) * share;
    const dx = Math.max(box.left - x, 0, x - box.right);
    const dy = Math.max(box.top - y, 0, y - box.bottom);
    nearest = Math.min(nearest, Math.hypot(dx, dy));
  }
  return nearest;
}

const COURSE_SAMPLES = 20;

/** Шаг, которым подбирается сдвиг карты, в пикселях: глазу мельче не различить. */
const SHIFT_STEP = 8;

/** Сколько отступов от края до пути хватает, чтобы угол счесть свободным: значок и звезда видны целиком. */
const CLEAR_MARGINS = 4;

/** Ячеек в ряду — как в оригинале — и сколько их влезает, прежде чем последняя станет «ещё N». */
const COLUMNS = 3;
const MAX_CELLS = 9;

/**
 * Технология, без которой курс в полёте не сменить, — тот же код, что проверяет сервер
 * (`FlightRules.REDIRECT_TECH`). Название берётся из дерева на языке игрока.
 */
const REDIRECT_TECH = 'hyperspace-communications';

/**
 * Слово «ход» в нужной форме: 1 ход, 2 хода, 5 ходов, 21 ход, 12 ходов.
 * В английском словаре формы «few» и «many» совпадают, и правило ему не мешает.
 */
function turnUnit(n: number): string {
  const tens = n % 100;
  const ones = n % 10;
  if (ones === 1 && tens !== 11) {
    return t('fleetOrder.turn.1');
  }
  if (ones >= 2 && ones <= 4 && (tens < 12 || tens > 14)) {
    return t('fleetOrder.turn.few');
  }
  return t('fleetOrder.turn.many');
}
