import type { ReactNode } from 'react';
import type { PlayerResearch } from '../api/types';
import { selectSelf, useGameStore } from '../state/gameStore';
import fleetImage from '../assets/fleet.webp';
import foodImage from '../assets/food.webp';
import scienceImage from '../assets/science.webp';
import turnButtonImage from '../assets/turn-button.png';
import {
  SIDE_PANEL_EDGE_HEIGHT,
  SIDE_PANEL_WIDTH,
  TURN_BUTTON_FONT_SIZE,
  TURN_BUTTON_HEIGHT,
  TURN_BUTTON_IMAGE_HEIGHT,
} from './layout';
import { targetName } from './research/researchRules';
import { t, useT } from '../i18n';

/** Игра начинается с 3000 галактического года, каждый ход — год. */
const START_YEAR = 3000;

/** Проценты расы со знаком: ноль показывается словом, а не как «+0%» — п. 7. */
/**
 * Сколько еды увозит один грузовик за ход — п. 4.1.1.
 *
 * Правило серверное (`PopulationCalculator.FOOD_PER_FREIGHTER`), здесь оно повторено
 * ради подписи «до N еды за ход»: считать подвоз клиент не берётся.
 */
const FOOD_PER_FREIGHTER = 5;

const signed = (percent: number): string =>
  percent === 0 ? t('side.fleet.normal') : `${percent > 0 ? '+' : ''}${percent}%`;

interface SidePanelProps {
  /** Конец хода игрока — п. 11.1: расчёт делает сервер. */
  onEndTurn: () => void;
  /** Сервер ещё считает ход: кнопка ждёт ответа. */
  endingTurn: boolean;
}

/**
 * Правая панель состояния — во всю высоту экрана.
 *
 * Разделена на сегменты по вертикали: галактический год, флот, еда, транспорт, наука
 * и ход. Крайние сегменты — узкие строки, четыре средних делят остаток панели поровну,
 * поэтому панель занимает высоту экрана целиком при любом размере окна.
 *
 * Содержимое средних сегментов появится вместе с соответствующими подсистемами:
 * сервер пока не отдаёт ни флот, ни производство. Сегмент «Наука» уже живой —
 * исследования сервер считает (п. 9).
 */
export function SidePanel({ onEndTurn, endingTurn }: SidePanelProps) {
  const game = useGameStore((state) => state.game);
  const research = useGameStore((state) => state.research);
  const researchTree = useGameStore((state) => state.researchTree);
  const self = useGameStore(selectSelf);
  const fleet = useGameStore((state) => state.fleet);
  const setOverlay = useGameStore((state) => state.setOverlay);
  // Кого ждёт партия — п. 11.1: список наполняется, когда игрок закончил ход, и пустеет,
  // как только ход посчитан. Приходит и с ответом на свой ход, и подпиской.
  const waitingFor = useGameStore((state) => state.waitingFor);
  // Грузовой флот империи — п. 4.1.1: сегмент «Транспорт» показывает его целиком.
  const freighters = useGameStore((state) => selectSelf(state)?.freighters ?? 0);
  // Занятые рейсом грузовики еду не возят — п. 4.1.1: по одному на жителя в пути.
  const reserved = useGameStore((state) => selectSelf(state)?.freightersReserved ?? 0);
  // Кораблей всего: флот у игрока не один, он стоит по системам — п. 8.
  const fleetShips = useGameStore(
    (state) => state.fleet?.fleets.reduce((sum, group) => sum + group.ships, 0) ?? 0,
  );
  const waiting = waitingFor.length > 0;
  useT();

  if (!game) {
    return null;
  }

  // Партия кончилась — п. 3: ходов в ней больше нет, и кнопка хода отвечала бы отказом.
  // Кто победил, игрок узнаёт из итогов хода: событие «Победа» приходит туда же, куда и
  // всё остальное, что случилось не по его нажатию.
  const finished = game.status !== 'IN_PROGRESS';

  return (
    <aside
      style={{ width: SIDE_PANEL_WIDTH }}
      className="absolute right-0 top-0 z-20 flex h-full flex-col border-l border-space-700 bg-space-900/95 text-xs"
    >
      <EdgeSegment title={t('side.year')} value={String(START_YEAR + game.turn - 1)} centered />
      {/* Казна — п. 10: доход колоний за ход минус содержание зданий. */}
      {self ? <EdgeSegment title={t('side.treasury')} value={t('side.credits', { n: self.credits })} /> : null}

      <Segment title={t('side.fleet')}>
        {/*
          Картинка вписывается в остаток сегмента целиком: min-h-0 разрешает флексу сжать
          её ниже собственной высоты, object-contain уменьшает картинку до размеров
          сегмента, не обрезая корабль и не искажая пропорций. Размер сегмента от картинки
          не зависит ни при каком размере окна.
        */}
        <img
          src={fleetImage}
          alt={t('side.fleet')}
          className="min-h-0 w-full flex-1 object-contain"
        />
        {/*
          Строка под картинкой — п. 8: чего стоит раса в бою и сколько у неё кораблей.
          Постройки флотов ещё нет, поэтому кораблей всегда ноль, а расовая часть уже
          настоящая и считается сервером.
        */}
        {fleet ? (
          <div className="flex-none text-10 leading-tight text-ink-dim">
            <div>
              {t('side.fleet.stats', { attack: signed(fleet.attackPercent), defence: signed(fleet.defensePercent) })}
            </div>
            <div>{fleetShips === 0 ? t('side.fleet.none') : t('side.fleet.ships', { ships: fleetShips, systems: fleet.fleets.length })}</div>
          </div>
        ) : null}
      </Segment>
      <Segment title={t('side.food')}>
        <img src={foodImage} alt={t('side.food')} className="min-h-0 w-full flex-1 object-contain" />
      </Segment>
      {/*
        Транспорт — грузовой флот империи (п. 4.1.1): он возит еду голодающим колониям.
        Пока грузовиков нет, сегмент так и говорит: строятся они в колонии и только после
        своей технологии.
      */}
      <Segment title={t('side.transport')}>
        <div className="text-11 leading-relaxed text-ink-soft">
          {freighters > 0 ? (
            <>
              <div>
                {t('side.freighters')} <span className="text-ink-bright">{freighters}</span>
              </div>
              <div className="text-ink-faint">
                {t('side.freighters.food', { n: Math.max(0, freighters - reserved) * FOOD_PER_FREIGHTER })}
              </div>
              {reserved > 0 ? (
                <div className="text-ink-faint">{t('side.freighters.enRoute', { n: reserved })}</div>
              ) : null}
            </>
          ) : (
            <div className="text-ink-faint">{t('side.freighters.none')}</div>
          )}
        </div>
      </Segment>
      {/* Клик по сегменту открывает экран выбора технологии для исследования — п. 9. */}
      <Segment title={t('side.science')} onOpen={() => setOverlay('research')}>
        <img src={scienceImage} alt={t('side.science')} className="min-h-0 w-full flex-1 object-contain" />
        {/*
          Строка под картинкой — что исследуется и почём. Пока сервер не отдал состояние
          исследований, её нет: пустая строка на месте цифр читается как «ноль очков».
        */}
        {research ? (
          <div className="flex-none text-10 leading-tight text-ink-dim">
            <div className="truncate text-accent">
              {targetName(researchTree, research) ?? t('side.research.none')}
            </div>
            <div>
              {research.levelCost === undefined
                ? t('side.research.perTurn', { n: research.researchPerTurn })
                : t('side.research.progress', { points: research.researchPoints, cost: research.levelCost, perTurn: research.researchPerTurn })}
            </div>
            {/*
              Срок и шанс прорыва — здесь, а не на экране исследований: в MOO II этот
              сегмент показывает «~7 turns / 12 RP», а окно выбора технологии хода
              исследования не показывает вовсе. Прорыв не наступает ровно на базовой
              стоимости: она открывает возможность, гарантирован прорыв на её двойном
              размере — поэтому после оплаты здесь стоит шанс, а не срок.
            */}
            {research.levelCost === undefined ? null : <ResearchProgress research={research} />}
          </div>
        ) : null}
      </Segment>

      {/*
        Ход объявляет каждый игрок сам — п. 11.1, и галактика считается, когда объявили
        все. Поэтому после нажатия кнопка не оживает сразу: пока ждём соседей, на ней
        видно, сколько их осталось, и нажимать её второй раз незачем.
      */}
      <button
        type="button"
        // Кнопка хода вдвое крупнее прочих сегментов панели: её нажимают чаще всего
        // остального в игре, и мелкой она была самой неудобной кнопкой на экране.
        style={{ height: TURN_BUTTON_HEIGHT, fontSize: TURN_BUTTON_FONT_SIZE }}
        className="flex flex-none items-center justify-between border-t border-space-600 bg-space-800 px-3 uppercase tracking-[0.2em] text-ink hover:bg-space-700 hover:text-accent disabled:cursor-not-allowed disabled:text-ink-faint"
        disabled={endingTurn || waiting || finished}
        title={
          finished
            ? t('side.turn.finished')
            : waiting
              ? t('side.turn.waiting', { n: waitingFor.length })
              : t('side.turn.end')
        }
        onClick={onEndTurn}
      >
        {/*
          Слово «Ход» нарисовано на самой картинке, поэтому подписи рядом нет.
          Высота задана в пикселях и меньше высоты кнопки — кнопка не растягивается.
        */}
        <img
          src={turnButtonImage}
          alt={t('side.turn.image')}
          style={{ height: TURN_BUTTON_IMAGE_HEIGHT }}
          className="w-auto"
        />
        <span className={endingTurn || waiting || finished ? 'text-ink-faint' : 'text-accent'}>
          {finished ? t('side.turn.final') : endingTurn ? '…' : waiting ? t('side.turn.waitingShort', { n: waitingFor.length }) : game.turn}
        </span>
      </button>
    </aside>
  );
}

/**
 * Узкий сегмент в одну строку: подпись слева, значение справа или, если попросили,
 * по центру всей панели — тогда оно не зависит от длины подписи.
 */
function EdgeSegment({
  title,
  value,
  centered = false,
}: {
  title: string;
  value: string;
  centered?: boolean;
}) {
  return (
    <section
      style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
      className="relative flex flex-none items-center justify-between border-b border-space-700 px-3"
    >
      <span className="uppercase tracking-[0.2em] text-ink-bright">{title}</span>
      <span className={centered ? 'absolute left-1/2 -translate-x-1/2 text-accent' : 'text-accent'}>
        {value}
      </span>
    </section>
  );
}

/**
 * Средний сегмент: все четыре одной высоты — делят остаток панели поровну.
 *
 * Сегмент с {@code onOpen} открывает свой экран по клику и рисуется кнопкой — иначе
 * по нему нельзя было бы попасть с клавиатуры. Разметка у обоих одна, поэтому высота
 * сегмента от того, кликабельный он или нет, не зависит.
 */
function Segment({
  title,
  children,
  onOpen,
}: {
  title: string;
  children?: ReactNode;
  onOpen?: () => void;
}) {
  const layout = 'flex flex-1 basis-0 flex-col overflow-auto border-b border-space-700 p-3';
  const content = (
    <>
      {/* Ярче общего .panel-title: подписи правой панели читаются поверх картинок. */}
      <div className="panel-title mb-2 text-ink-bright">{title}</div>
      {children ?? <p className="text-ink-faint">—</p>}
    </>
  );

  if (!onOpen) {
    return <section className={layout}>{content}</section>;
  }

  return (
    <button
      type="button"
      className={layout + ' text-left hover:bg-space-800/60'}
      onClick={onOpen}
    >
      {content}
    </button>
  );
}

/**
 * Строка о ходе исследования — п. 9: срок до базовой стоимости, а после её оплаты —
 * шанс прорыва на следующем ходу. Так же устроен сегмент микроскопа в MOO II: он
 * показывает «~N turns», пока идёт накопление.
 *
 * После оплаты счётчик обязателен: прорыв случаен, и без него ожидание в несколько ходов
 * выглядело бы поломкой — уровень оплачен, а технологии всё нет.
 */
function ResearchProgress({ research }: { research: PlayerResearch }) {
  const remaining = research.remainingPoints ?? 0;

  if (remaining <= 0) {
    return <div>{t('side.research.breakthrough', { n: research.breakthroughPercent ?? 0 })}</div>;
  }
  if (research.researchPerTurn <= 0) {
    return <div>{t('side.research.stalled')}</div>;
  }
  return <div>{t('side.research.turns', { n: Math.ceil(remaining / research.researchPerTurn) })}</div>;
}
