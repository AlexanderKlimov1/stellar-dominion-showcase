import { useEffect, useState, type ReactNode } from 'react';
import type { GameSummary, PlayerResearch } from '../api/types';
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
  TURN_BUTTON_LABEL_FONT_SIZE,
} from './layout';
import { hotkeyLabel, useHotkeys } from './hotkeys';
import { askTurnNotices } from './turnNotice';
import { targetName } from './research/researchRules';
import { t, tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass, signed, signTone } from './accent';

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

const racePercent = (percent: number): ReactNode =>
  percent === 0 ? t('side.fleet.normal') : <Figure accent="value" tone={signTone(percent)}>{signed(percent)}%</Figure>;

interface SidePanelProps {
  /** Конец хода игрока — п. 11.1: расчёт делает сервер. */
  onEndTurn: () => void;
  /** Сервер ещё считает ход: кнопка ждёт ответа. */
  endingTurn: boolean;
}

/**
 * Можно ли объявить конец хода — п. 11.1.
 *
 * <b>Правило одно на кнопку панели и на горячую клавишу «T»</b> ({@link GalaxyHotkeys}):
 * посчитанное в двух местах, оно однажды разойдётся, и клавиша слала бы серверу то, чего
 * кнопка нажать не даёт. Причин отказа три, и на кнопке они подписаны по-разному: партия
 * кончилась (п. 3 — ходов в ней больше нет), ход уже объявлен и ждём соседей (ходят все
 * одновременно), ответ сервера ещё не пришёл.
 */
export function canEndTurn(
  game: GameSummary | null,
  waitingFor: string[],
  endingTurn: boolean,
): boolean {
  return !!game && game.status === 'IN_PROGRESS' && waitingFor.length === 0 && !endingTurn;
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
  const foodBalance = useGameStore((state) => (state.map?.systems ?? [])
    .flatMap((system) => system.planets)
    .filter((planet) => planet.colony && planet.ownerPlayerId === state.credentials?.playerId)
    .reduce((sum, planet) => sum + (planet.colony?.foodBalance ?? 0) + (planet.colony?.foodDelivered ?? 0), 0));
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
  const players = useGameStore((state) => state.players);
  const waiting = waitingFor.length > 0;
  // Клавиша хода — из живой раскладки: игрок вправе её переназначить (п. 11.1).
  const keys = useHotkeys();
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
      /*
        КЕГЛЬ СОСТОЯНИЯ — ВДВОЕ ПРОТИВ ПРЕЖНЕГО (30.09.2026, решение хозяина проекта), и
        это тот же счёт, по которому однажды выросли надписи нижнего меню и кнопка хода
        (`ui/layout.ts`): на большом экране двенадцать пикселей в панели, на которую
        смотрят весь ход, читались с трудом. Кегль задан здесь ОДИН на всю панель, а
        сегменты лишь уточняют свои строки — иначе они разъедутся при первой же правке.
      */
      className="absolute right-0 top-0 z-20 flex h-full flex-col border-l border-space-700 bg-space-900/95 text-24"
    >
      <EdgeSegment title={t('side.year')} value={<Figure accent="lead">{START_YEAR + game.turn - 1}</Figure>} />
      {/* Казна — п. 10: доход колоний за ход минус содержание зданий. */}
      {self ? <EdgeSegment title={t('side.treasury')} value={tf('common.credits', { n: <Figure accent="lead" tone={signTone(self.credits)}>{self.credits}</Figure> })} /> : null}

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
          <div className={`flex-none ${figureLabelClass('value')}`}>
            <div>
              {tf('side.fleet.stats', { attack: racePercent(fleet.attackPercent), defence: racePercent(fleet.defensePercent) })}
            </div>
            <div>{fleetShips === 0 ? t('side.fleet.none') : tf('side.fleet.ships', { ships: <Figure accent="value">{fleetShips}</Figure>, systems: <Figure accent="value">{fleet.fleets.length}</Figure> })}</div>
          </div>
        ) : null}
      </Segment>
      {/* Еда числом (backlog-promo, пункт 31): одна картинка не говорила, хватает ли империи
          еды. Баланс считается так же, как итог в списке колоний: остаток плюс подвоз. */}
      <Segment title={t('side.food')}>
        <img src={foodImage} alt={t('side.food')} className="min-h-0 w-full flex-1 object-contain" />
        <div className={figureLabelClass('value')}>
          {tf('side.food.balance', { n: <Figure accent="value" tone={signTone(foodBalance)}>{signed(foodBalance)}</Figure> })}
        </div>
      </Segment>
      {/*
        Транспорт — грузовой флот империи (п. 4.1.1): он возит еду голодающим колониям.
        Пока грузовиков нет, сегмент так и говорит: строятся они в колонии и только после
        своей технологии.
      */}
      <Segment title={t('side.transport')}>
        <div className={figureLabelClass('value')}>
          {freighters > 0 ? (
            <>
              <div>
                {t('side.freighters')} <Figure accent="value">{freighters}</Figure>
              </div>
              <div className={figureLabelClass('note')}>
                {tf('side.freighters.food', { n: <Figure accent="note">{Math.max(0, freighters - reserved) * FOOD_PER_FREIGHTER}</Figure> })}
              </div>
              {reserved > 0 ? (
                <div className={figureLabelClass('note')}>{tf('side.freighters.enRoute', { n: <Figure accent="note">{reserved}</Figure> })}</div>
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
          <div className={`flex-none ${figureLabelClass('value')}`}>
            {/* Без цели ход науки не пропадает — раздел продолжается (keepSection, пункт 12):
                в оригинале экран выбора не отпускает без ответа, и молчание тут пугало бы. */}
            <div
              className="truncate text-accent"
              title={targetName(researchTree, research) ? undefined : t('rule.researchKeepsSection')}
            >
              {targetName(researchTree, research) ?? t('side.research.none')}
            </div>
            <div>
              {research.levelCost === undefined
                ? tf('side.research.perTurn', { n: <Figure accent="value">{research.researchPerTurn}</Figure> })
                : tf('side.research.progress', { points: <Figure accent="value">{research.researchPoints}</Figure>, cost: <Figure accent="value">{research.levelCost}</Figure>, perTurn: <Figure accent="value">{research.researchPerTurn}</Figure> })}
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
        УДЕРЖАНИЕ WARDENHOLD — п. 3. Счёт идёт двадцать ходов, и знать о нём надо ВСЕМ
        и ВСЕГДА, а не только в те ходы, когда о нём напомнил отчёт: двадцать ходов — это
        время ответить, а ответить можно, только видя, что пора. Пока звезду не держит
        никто, строка называет саму цель — гостю это первая подсказка, к чему идти.
      */}
      {game.wardenholdVictory && !finished ? (
        <WardenholdLine
          turnsLeft={game.wardenholdTurnsLeft}
          holderName={players.find((player) => player.id === game.wardenholdHolderPlayerId)?.name}
          mine={self !== null && self !== undefined && self.id === game.wardenholdHolderPlayerId}
        />
      ) : null}
      {/*
        ИТОГ ПО МОГУЩЕСТВУ — п. 3, backlog-promo, пункт 2. Строка появляется за тридцать
        ходов до срока, а не с первого хода: срок в трёхстах ходах ничего не говорит, пока
        до него далеко, а последние ходы — время для рывка, и о них надо знать заранее.
      */}
      {game.mightVictory && !finished && game.mightVictoryTurn !== undefined
        && game.mightVictoryTurn - game.turn <= MIGHT_WARNING_TURNS ? (
        <section className="flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug text-warn">
          {tf('side.might.soon', { n: <Figure accent="note" tone="warn">{Math.max(0, game.mightVictoryTurn - game.turn)}</Figure> })}
        </section>
      ) : null}
      {/*
        Часы хода — backlog-promo, пункт 11: сколько осталось до конца хода и кто ушёл из-за
        стола (их империи ведёт ИИ, партия их не ждёт). Без строки о сроке ход «сам»
        считался бы внезапно, а пропавший сосед читался бы как зависшая партия.
      */}
      {game.turnDeadline && !finished ? <TurnDeadline deadline={game.turnDeadline} /> : null}
      {players.some((player) => player.away) && !finished ? (
        <section className="flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug text-ink-soft">
          {t('side.turn.away', {
            names: players.filter((player) => player.away).map((player) => player.name).join(', '),
          })}
        </section>
      ) : null}
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
        disabled={!canEndTurn(game, waitingFor, endingTurn)}
        /*
          Подсказка называет и горячую клавишу — п. 11.1: иначе о ней негде узнать, а
          клавиша, о которой игрок не знает, для игры всё равно что её нет.
        */
        title={
          finished
            ? t('side.turn.finished')
            : waiting
              ? t('side.turn.waiting', { n: waitingFor.length })
              : t('hotkey.title', { action: t('side.turn.end'), key: hotkeyLabel(keys.turn) })
        }
        onClick={() => {
          // Закончивший первым будет ждать — тут и спросить, можно ли позвать его, когда
          // ход наступит (backlog-promo, пункт 11).
          askTurnNotices(players.filter((player) => player.playerType === 'HUMAN').length);
          onEndTurn();
        }}
      >
        {/*
          СЛОВО НА КНОПКЕ — ТЕКСТ, А НЕ ПИКСЕЛИ КАРТИНКИ.

          Раньше «Ход» было нарисовано в самом PNG, и на английском интерфейсе самая
          нажимаемая кнопка игры оставалась русской: клиент переведён весь, больше тысячи
          ключей, а мимо прошла ровно она — потому что переводить картинку нечем. Это та
          же грабля, что и со словом, сохранённым в базе: язык, запечённый в данные,
          потом не достать ничем.

          Теперь картинка — только рамка (слово с неё стёрто), подпись берётся из словаря
          и растёт вместе с настройкой размера текста. Картинка стала украшением, поэтому
          alt у неё пустой: читалка иначе прочла бы слово дважды.

          Высота картинки меньше высоты кнопки — кнопка не растягивается.
        */}
        <span className="relative flex flex-none items-center justify-center">
          <img
            src={turnButtonImage}
            alt=""
            style={{ height: TURN_BUTTON_IMAGE_HEIGHT }}
            className="w-auto"
          />
          <span
            style={{ fontSize: TURN_BUTTON_LABEL_FONT_SIZE }}
            className="absolute tracking-normal text-ink-bright"
          >
            {t('side.turn.label')}
          </span>
        </span>
        <span className={endingTurn || waiting || finished ? 'text-ink-faint' : 'text-accent'}>
          {finished ? t('side.turn.final') : endingTurn ? '…' : waiting ? t('side.turn.waitingShort', { n: waitingFor.length }) : <Figure accent="lead">{game.turn}</Figure>}
        </span>
      </button>
    </aside>
  );
}

/**
 * Обратный отсчёт до конца хода — backlog-promo, пункт 11. Своим компонентом, чтобы
 * секундный тик перерисовывал одну строку, а не всю панель. Последние полминуты — тревожным
 * цветом: это время дожать решения, а не начинать их.
 */
function TurnDeadline({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const left = Math.max(0, Math.round((Date.parse(deadline) - now) / 1000));
  const time = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  return (
    <section
      className={`flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug ${
        left <= 30 ? 'text-warn' : 'text-ink-soft'
      }`}
    >
      {tf('side.turn.deadline', { time: <Figure accent="note" tone={left <= 30 ? 'warn' : undefined}>{time}</Figure> })}
    </section>
  );
}

/** За сколько ходов до итога по могуществу правая панель начинает о нём напоминать. */
const MIGHT_WARNING_TURNS = 30;

/**
 * Строка удержания Wardenhold — п. 3: цель, свой счёт или чужой.
 *
 * Мельче прочих строк панели: это напоминание, а не число, на которое смотрят весь ход.
 * Чужой счёт подсвечен тревогой — он ведёт к чужой победе.
 */
function WardenholdLine({
  turnsLeft,
  holderName,
  mine,
}: {
  turnsLeft?: number;
  holderName?: string;
  mine: boolean;
}) {
  const figureTone = turnsLeft === undefined || !holderName ? undefined : mine ? 'good' : 'warn';
  const n = (count: number) => <Figure accent="note" tone={figureTone}>{count}</Figure>;
  const text = turnsLeft === undefined || !holderName
    ? tf('side.wardenhold.goal', { n: n(20) })
    : mine
      ? tf('side.wardenhold.yours', { n: n(turnsLeft) })
      : tf('side.wardenhold.theirs', { name: holderName, n: n(turnsLeft) });
  const tone = turnsLeft === undefined || !holderName ? 'text-ink-dim' : mine ? 'text-good' : 'text-warn';
  return (
    <section className={`flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug ${tone}`}>
      {text}
    </section>
  );
}

/**
 * Узкий сегмент в одну строку: подпись слева, значение справа.
 *
 * <b>Подпись мельче значения.</b> Кегль панели вырос вдвое (30.09.2026), и «ГАЛАКТИЧЕСКИЙ
 * ГОД» в разрядку занял бы всю ширину панели, не оставив места самому году. Смотрят же
 * здесь на число, а подпись читают один раз — ей крупный кегль и не нужен.
 *
 * <b>Значения по центру панели больше нет.</b> Год стоял по центру, «чтобы не зависеть от
 * длины подписи», — с вдвое крупной подписью он оказался ПОД ней: середина панели теперь
 * внутри самой подписи. Оба значения выровнены по правому краю, и год стоит там же, где
 * казна: столбик чисел читается одним взглядом.
 */
function EdgeSegment({
  title,
  value,
}: {
  title: string;
  value: ReactNode;
}) {
  return (
    <section
      style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
      className="flex flex-none items-center justify-between gap-2 border-b border-space-700 px-3"
    >
      <span className={`truncate uppercase tracking-[0.2em] ${figureLabelClass('lead')}`}>{title}</span>
      <span className={`shrink-0 ${figureLabelClass('lead')}`}>{value}</span>
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
      {/*
        Ярче и крупнее общего `.panel-title`: подписи правой панели читаются поверх
        картинок, а кегль у них тот же, что и у всей панели (вдвое против прежнего).
      */}
      <div className="panel-title mb-2 text-24 text-ink-bright">{title}</div>
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
  /*
    Наверняка прорыв случается на ДВОЙНОЙ цене (`ResearchRules`), и срок называется вилкой
    «возможен — наверняка» (backlog-promo, пункт 17). Прежде строка обещала «~3 хода до
    прорыва», считая только до базовой цены, а после оплаты показывала «шанс 8 %» — и
    новичок читал это как обман.
  */
  const certainAt = research.certainPoints ?? (research.levelCost === undefined ? undefined : research.levelCost * 2);
  const certain = certainAt !== undefined && research.researchPerTurn > 0
    ? Math.max(1, Math.ceil((certainAt - research.researchPoints) / research.researchPerTurn))
    : null;
  // Прорыв по оплате (гостевая партия, первые уровни): вилки нет — «через N х.» как есть.
  const exact = certain !== null && certainAt === research.levelCost;

  if (remaining <= 0) {
    return (
      <div>
        {tf(certain === null ? 'side.research.breakthrough' : 'side.research.breakthroughCertain', {
          n: <Figure accent="value">{research.breakthroughPercent ?? 0}</Figure>,
          certain: <Figure accent="value">{certain ?? 0}</Figure>,
        })}
      </div>
    );
  }
  if (research.researchPerTurn <= 0) {
    return <div>{t('side.research.stalled')}</div>;
  }
  return (
    <div>
      {tf(certain === null || exact ? 'side.research.turns' : 'side.research.turnsRange', {
        n: <Figure accent="value">{Math.ceil(remaining / research.researchPerTurn)}</Figure>,
        certain: <Figure accent="value">{certain ?? 0}</Figure>,
      })}
    </div>
  );
}
