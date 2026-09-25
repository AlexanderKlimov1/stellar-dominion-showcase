import { gameApi, setAccountToken } from '../api/client';
import { setLocale, type Locale } from '../i18n';
import { TEXT_SCALES, applyTextScale, type TextScale } from '../ui/textScale';
import type { AccountSession, PlayerCredentials } from '../api/types';
// Только тип: на выполнение импорт не влияет, поэтому взаимной ссылки модулей не возникает.
import type { OverlayPanel } from './gameStore';

/**
 * Ссылка на партию в браузерном хранилище.
 *
 * Сама игра живёт на сервере, поэтому клиенту достаточно запомнить её идентификатор
 * и пропуск игрока: по ним партия восстанавливается после перезагрузки страницы.
 *
 * Сохранения партий здесь ни при чём: их состояние лежит в базе сервера,
 * а меню «Игра» → «Загрузить» работает со списком с сервера.
 */
export interface StoredSession {
  gameId: string;
  gameName: string;
  credentials: PlayerCredentials;
  savedAt: string;
}

/** Текущая партия: подхватывается сама при следующем открытии страницы. */
const ACTIVE_KEY = 'moo3.session.active';

/**
 * Открытые экраны и выделение — состояние вида, а не партии, поэтому лежит отдельно
 * и помнит, к какой игре относится: вид чужой партии восстанавливать нельзя.
 */
const VIEW_KEY = 'moo3.session.view';

/**
 * Учётная запись игрока — п. 3.1.
 *
 * Лежит отдельно от партии и переживает её: партию игрок бросает и заводит новую, а
 * входить заново при этом не должен. Пропуск отсюда сразу ставится в `api/client`, и
 * дальше клиенту о нём думать не надо.
 */
const ACCOUNT_KEY = 'moo3.account';

/**
 * Что открыто поверх главного меню — и почему это вообще хранится.
 *
 * Пульт балансировки, редактор цен, заявки на регистрацию, схема Галактики и список
 * сохранений живут поверх меню, а открытость их лежала в состоянии самого меню. Стоило
 * дереву пересобраться — перезагрузка страницы, горячая замена модуля у dev-сервера,
 * возврат машины экранов в `menu`, — и пульт закрывался сам собой, выбрасывая хозяина на
 * первый экран. За прогоном сидят часами, и «экран сбросился сам» — это потеря работы, а
 * не мелкое неудобство: ПРИЛОЖЕНИЕ НЕ МЕНЯЕТ СЦЕНУ БЕЗ ХОЗЯИНА.
 *
 * Хранится строкой рядом с прочим состоянием вида: партии тут нет, эти экраны открываются
 * до всякой игры.
 */
const MENU_KEY = 'moo3.session.menu';

/** Что было на экране, когда игрок ушёл со страницы. */
export interface StoredView {
  gameId: string;
  /** Выделенная звезда — карточка системы справа. */
  selectedSystemId: string | null;
  /** Звёздная система, открытая двойным кликом на отдельном экране. */
  openedSystemId: string | null;
  /** Колония, открытая двойным кликом по планете на экране управления — п. 4.1. */
  openedColonyId: string | null;
  /** Открытая панель п. 11.1: технологии, дипломатия, расы, справка. */
  overlay: OverlayPanel;
  showLabels: boolean;
}

const read = (key: string): StoredSession | null => {
  const raw = window.localStorage.getItem(key);
  if (!raw) {
    return null;
  }
  try {
    const session = JSON.parse(raw) as StoredSession;
    // Хранилище переживает смену версий клиента, поэтому проверяем, что запись живая.
    if (!session?.gameId || !session.credentials?.accessToken) {
      return null;
    }
    return session;
  } catch {
    return null;
  }
};

const write = (key: string, session: StoredSession): void => {
  window.localStorage.setItem(key, JSON.stringify(session));
};

export const readActiveSession = (): StoredSession | null => read(ACTIVE_KEY);

export const writeActiveSession = (session: StoredSession): void => write(ACTIVE_KEY, session);

export const clearActiveSession = (): void => window.localStorage.removeItem(ACTIVE_KEY);

export const readView = (gameId: string): StoredView | null => {
  const raw = window.localStorage.getItem(VIEW_KEY);
  if (!raw) {
    return null;
  }
  try {
    const view = JSON.parse(raw) as StoredView;
    return view?.gameId === gameId ? view : null;
  } catch {
    return null;
  }
};

export const writeView = (view: StoredView): void => {
  window.localStorage.setItem(VIEW_KEY, JSON.stringify(view));
};

export const clearView = (): void => window.localStorage.removeItem(VIEW_KEY);

/** Экран, открытый поверх главного меню; `null` — открыто само меню. */
export type MenuScreen =
  | 'balance' | 'costs' | 'accounts' | 'milkyway' | 'saves' | 'newgame'
  | 'orionometer' | null;

const MENU_SCREENS: MenuScreen[] =
  ['balance', 'costs', 'accounts', 'milkyway', 'saves', 'newgame', 'orionometer'];

export const readMenuScreen = (): MenuScreen => {
  const raw = window.localStorage.getItem(MENU_KEY);
  // Проверяем по списку, а не доверяем строке: в хранилище мог остаться экран прошлой
  // сборки, которого в игре уже нет, и меню открылось бы в пустоту.
  return MENU_SCREENS.includes(raw as MenuScreen) ? (raw as MenuScreen) : null;
};

export const writeMenuScreen = (screen: MenuScreen): void => {
  if (screen === null) {
    window.localStorage.removeItem(MENU_KEY);
    return;
  }
  window.localStorage.setItem(MENU_KEY, screen);
};

/**
 * Прогон, открытый на пульте балансировки, — по той же причине, что и сам экран.
 *
 * Вернуть пульт мало: без выбранного прогона хозяин видит список вместо полосы хода, за
 * которой следил. Идёт прогон часами, смотрят на него весь день, и «экран вернулся, а
 * место потеряно» — это та же потеря работы, только на шаг глубже.
 */
const BALANCE_RUN_KEY = 'moo3.session.balance-run';

export const readBalanceRun = (): string | null =>
  window.localStorage.getItem(BALANCE_RUN_KEY);

export const writeBalanceRun = (runId: string | null): void => {
  if (runId === null) {
    window.localStorage.removeItem(BALANCE_RUN_KEY);
    return;
  }
  window.localStorage.setItem(BALANCE_RUN_KEY, runId);
};

/** Сохранённый вход; `null` — игрок не авторизован, и в игру его пускать нельзя. */
export const readAccount = (): AccountSession | null => {
  const raw = window.localStorage.getItem(ACCOUNT_KEY);
  if (!raw) {
    return null;
  }
  try {
    const session = JSON.parse(raw) as AccountSession;
    if (!session?.token || !session.account?.login) {
      return null;
    }
    // Просроченный пропуск сервер всё равно не примет: не морочим игрока «входом»,
    // после которого первое же действие ответит отказом.
    if (session.expiresAt && new Date(session.expiresAt).getTime() < Date.now()) {
      return null;
    }
    return session;
  } catch {
    return null;
  }
};

const storeAccount = (session: AccountSession): void => {
  window.localStorage.setItem(ACCOUNT_KEY, JSON.stringify(session));
  setAccountToken(session.token);
};

/**
 * Язык записи — п. 3.5: ЗАПИСЬ РЕШАЕТ, а не браузер.
 *
 * Иначе вся затея ни к чему: язык и так лежал в `localStorage`, и сев за другую машину,
 * игрок получал английский заново — там хранилище пустое. Поэтому при входе берётся язык
 * записи, а переключатель тут же записывает выбранный обратно (`rememberLocale`).
 */
const applyAccountLocale = (session: AccountSession): void => {
  const wanted = session.account.locale;
  if (wanted === 'en' || wanted === 'ru') {
    setLocale(wanted);
  }
};

/**
 * Размер текста из записи — п. 11.1, брат-близнец {@link applyAccountLocale}.
 *
 * Тот же довод: настройка, лежащая только в браузере, теряется на второй машине, а размер
 * текста выбирают один раз и надолго. Неизвестную ступень запись игнорирует — масштаб
 * останется прежним, а не съедет в неизвестно что.
 */
const applyAccountTextScale = (session: AccountSession): void => {
  const wanted = session.account.textScale;
  if (wanted && TEXT_SCALES.includes(wanted as TextScale)) {
    applyTextScale(wanted as TextScale);
  }
};

export const writeAccount = (session: AccountSession): void => {
  storeAccount(session);
  applyAccountLocale(session);
  applyAccountTextScale(session);
};

/**
 * Запоминает выбранный язык в учётной записи — п. 3.5.
 *
 * Зовётся переключателем языка. Без входа не делает ничего: записи нет, и помнить язык
 * некому, кроме браузера. Сохранённая копия записи правится ТУТ ЖЕ, не дожидаясь сервера:
 * иначе перезагрузка страницы вернула бы прежний язык из этой копии. Отказ сервера гасится
 * молча — язык уже переключён, и мешать игроку сообщением не за что.
 */
export const rememberLocale = (value: Locale): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  storeAccount({ ...session, account: { ...session.account, locale: value } });
  void gameApi.saveLocale(value).catch(() => undefined);
};

/**
 * Запоминает размер текста в учётной записи — п. 11.1.
 *
 * Зовётся переключателем размера, и устроен так же, как {@link rememberLocale}: без входа
 * не делает ничего, сохранённую копию записи правит тут же (иначе перезагрузка вернула бы
 * прежний размер из неё), а отказ сервера гасит молча — текст уже перерисован.
 */
export const rememberTextScale = (value: TextScale): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  storeAccount({ ...session, account: { ...session.account, textScale: value } });
  void gameApi.saveTextScale(value).catch(() => undefined);
};

export const clearAccount = (): void => {
  window.localStorage.removeItem(ACCOUNT_KEY);
  setAccountToken(null);
};

/**
 * Поднимает сохранённый вход при запуске клиента: пропуск попадает в `api/client` до
 * первого запроса. Возвращает запись вошедшего или `null`.
 */
export const restoreAccount = (): AccountSession | null => {
  const session = readAccount();
  setAccountToken(session?.token ?? null);
  if (session) {
    applyAccountLocale(session);
    applyAccountTextScale(session);
  }
  return session;
};
