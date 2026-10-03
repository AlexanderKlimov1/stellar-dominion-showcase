import { useEffect, useRef } from 'react';
import { useGameStore } from '../state/gameStore';
import { hotkeys, matches, type HotkeyAction } from './hotkeys';
import { toggleGameMenu } from './GameMenu';
import { canEndTurn } from './SidePanel';
import { modalOpen } from './useModalEscape';

/**
 * Горячие клавиши карты галактики — п. 11.1.
 *
 * Самые частые действия партии делаются с клавиатуры: <b>T</b> объявляет конец хода,
 * <b>F2</b> открывает науку, <b>F4</b> — родной мир, а каждый пункт нижней полосы — первой
 * буквой своего названия (01.10.2026): <b>C</b> — колонии, <b>P</b> — планеты, <b>F</b> —
 * флот, <b>G</b> — меню «Игра», <b>L</b> — лидеры, <b>R</b> — расы, <b>I</b> — инфо. У
 * родного мира своей кнопки нет вовсе: заходят туда десятки раз за партию, а дорога
 * длинная — найти свою звезду на карте, открыть систему, ткнуть в планету.
 *
 * <b>Раскладку назначает игрок</b> (экран настроек, `ui/preferences/`), и живёт она в его
 * учётной записи — `ui/hotkeys.ts`. Поэтому клавиша здесь не вписана, а СПРАШИВАЕТСЯ в
 * момент нажатия: обработчик ставится один раз, а раскладка меняется когда угодно.
 *
 * <b>Клавиатура принадлежит ВЕРХНЕМУ открытому экрану, а не карте.</b> Пока открыты
 * колонии, бой, итоги хода или любое окно, ни одна из этих клавиш не работает: карта
 * из-под них не слушает. Спрашивается это у очереди модальных экранов ({@link modalOpen}) —
 * той же, что раздаёт Esc, — а не списком состояний: окон в игре больше двух десятков, и
 * перечисленный список отстал бы от первого же нового окна, причём молча. Оттого и экран
 * настроек, на котором клавиши переназначают, ничего этим клавишам не отдаёт: он сам —
 * модальный экран, и нажатая в нём F4 достаётся ему, а не карте.
 *
 * <b>Компонент, а не хук в `App`.</b> Хуки нельзя звать после выходов по условию, а в
 * `App` их десяток (вход, меню, лобби, заставки), так что хук карты пришлось бы звать и на
 * всех этих экранах, с признаком «сейчас не карта». Компонент же просто не существует,
 * пока не открыта партия, — и клавиши вместе с ним.
 */
export function GalaxyHotkeys({ endingTurn, onEndTurn }: {
  /** Ход уже объявлен и считается — п. 11.1: второе объявление ему ни к чему. */
  endingTurn: boolean;
  /** Объявить конец хода — то же действие, что у кнопки правой панели. */
  onEndTurn: () => void;
}) {
  /*
    Действие берётся из ссылки, а не из замыкания: обработчик ставится один раз, на всю
    жизнь карты, и замкнутый в нём `endingTurn` навсегда остался бы тем, каким был при
    первой отрисовке, — клавиша объявляла бы ход поверх идущего пересчёта.
  */
  const turn = useRef({ endingTurn, onEndTurn });
  turn.current = { endingTurn, onEndTurn };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      /*
        Клавиша с модификатором — это ДРУГАЯ команда, и чаще всего не наша: Alt+F4
        закрывает окно, Ctrl+T открывает вкладку браузера. Перехватив их, мы отняли бы у
        игрока привычное — и тем незаметнее, чем реже он ими пользуется в самой игре.
      */
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
        return;
      }
      // Удержание клавиши — не второе нажатие: иначе зажатая клавиша хода слала бы его
      // столько раз, сколько успеет повториться, а «колонии» открывались бы без конца.
      if (event.repeat) {
        return;
      }
      // Открытое окно забирает клавиатуру себе — см. описание компонента.
      if (modalOpen() || typing(event.target)) {
        return;
      }

      const map = hotkeys();
      const pressed = (Object.keys(ACTIONS) as HotkeyAction[])
        .find((action) => matches(event, map[action]));
      if (!pressed) {
        return;
      }
      ACTIONS[pressed](turn.current);
      // Своё дело клавиша сделала, и браузеру её отдавать незачем: F2 и F3 в иных
      // браузерах заняты своим.
      event.preventDefault();
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Рисовать нечего: подсказки о клавишах стоят на самих кнопках и на экране настроек.
  return null;
}

/** Что делает каждое действие. Новое действие заводится здесь и в `ui/hotkeys.ts`. */
const ACTIONS: Record<HotkeyAction, (turn: TurnAction) => void> = {
  turn: endTurn,
  research: () => useGameStore.getState().setOverlay('research'),
  fleet: () => useGameStore.getState().setOverlay('fleet'),
  colonies: () => useGameStore.getState().setOverlay('colonies'),
  planets: () => useGameStore.getState().setOverlay('planets'),
  leaders: () => useGameStore.getState().setOverlay('leaders'),
  races: () => useGameStore.getState().setOverlay('races'),
  info: () => useGameStore.getState().setOverlay('info'),
  // Меню «Игра» не экран, а раскрывающийся список полосы, и своё «открыто» держит само:
  // клавиша его переключает, как и нажатие на пункт.
  game: toggleGameMenu,
  homeworld: openHomeworld,
};

interface TurnAction {
  endingTurn: boolean;
  onEndTurn: () => void;
}

/**
 * Поле ввода забирает буквы себе.
 *
 * На самой карте полей нет, и проверка эта — задел: заведись на ней хоть одно (поиск
 * звезды, переименование флота), и «C» в набираемом слове открывала бы список колоний, а
 * «T» объявляла ход. Ловится такое поздно и с трудом.
 */
function typing(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  const tag = element?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
    || element?.isContentEditable === true;
}

/**
 * Конец хода по клавише — п. 11.1.
 *
 * Можно ли объявлять, решает {@link canEndTurn} — то же правило, которым гаснет кнопка
 * панели. Нельзя — клавиша молчит: почему нельзя, панель говорит на самой кнопке
 * («ждём N»), и второй раз сообщать это тому же игроку незачем.
 */
function endTurn({ endingTurn, onEndTurn }: TurnAction): void {
  const { game, waitingFor } = useGameStore.getState();
  if (canEndTurn(game, waitingFor, endingTurn)) {
    onEndTurn();
  }
}

/**
 * Родной мир — экраном колонии, как двойной клик по планете.
 *
 * Планета ищется по всей карте: родная система открыта игроку с первого хода, а экран
 * колонии сам достаёт её из карты и в открытой системе не нуждается — так же он
 * открывается и со списка колоний империи.
 *
 * <b>Родного мира может не быть.</b> Его отнимают десантом (п. 12), и тогда открывать
 * нечего — клавиша молчит. Подставлять вместо него другую колонию нельзя: «родной мир»
 * перестало бы быть правдой, а о потере игрок узнавал бы из подписи чужого экрана.
 */
function openHomeworld(): void {
  const { map, credentials, setOpenedColony } = useGameStore.getState();
  const home = map?.systems
    .flatMap((system) => system.planets)
    .find((planet) => planet.homeworld && planet.ownerPlayerId === credentials?.playerId);
  if (home) {
    setOpenedColony(home.id);
  }
}
