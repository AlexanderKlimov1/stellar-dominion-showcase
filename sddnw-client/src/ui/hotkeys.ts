import { useSyncExternalStore } from 'react';
import type { Key } from '../i18n';

/**
 * Горячие клавиши карты галактики — п. 11.1: раскладка и всё, что о ней известно.
 *
 * Стоит ОТДЕЛЬНО от обработчика ({@link GalaxyHotkeys}), потому что раскладку спрашивают
 * ещё трое: правая панель и нижняя полоса — ради подсказок на кнопках, и экран настроек —
 * ради переназначения. Лежи она в самом обработчике, панель ссылалась бы на него, а он на
 * панель за правилом конца хода, — вышло бы кольцо модулей.
 *
 * <b>Раскладка живёт в учётной записи</b> (`account.hotkeys`), а в `localStorage` —
 * быстрый ответ на первый кадр. Довод тот же, что у языка (п. 3.5) и размера текста:
 * настройка, лежащая только в браузере, теряется на второй машине, а переназначенные
 * клавиши помнят пальцами. Сервер эти пары не толкует вовсе — он их хранит и отдаёт.
 */

/** Что клавиша делает. Новое действие заводится здесь, в `HOTKEY_ACTIONS` и в обработчике. */
export type HotkeyAction =
  | 'turn' | 'research' | 'fleet' | 'colonies' | 'homeworld'
  | 'planets' | 'game' | 'leaders' | 'races' | 'info';

/** Раскладка: какая клавиша какому действию досталась. */
export type Hotkeys = Record<HotkeyAction, string>;

/**
 * Раскладка по умолчанию — `KeyboardEvent.code`, то есть ФИЗИЧЕСКОЕ место клавиши.
 *
 * <b>Разделы нижней полосы — первой буквой своего названия</b> (01.10.2026, решение хозяина
 * проекта): C — Colonies, P — Planets, F — Fleet, G — Game, L — Leaders, R — Races, I — Info.
 * Буква названия запоминается сама, а ряд F2…F4 был набором, который надо было учить. Флот
 * поэтому переехал с F3 на F. Ход остался на T, наука — на F2, родной мир — на F4: у них
 * своих пунктов полосы нет, и буква названия им ничего не подсказала бы.
 */
export const DEFAULT_HOTKEYS: Hotkeys = {
  turn: 'KeyT',
  research: 'F2',
  fleet: 'KeyF',
  colonies: 'KeyC',
  homeworld: 'F4',
  planets: 'KeyP',
  game: 'KeyG',
  leaders: 'KeyL',
  races: 'KeyR',
  info: 'KeyI',
};

/**
 * Порядок и подписи действий — им же задан порядок строк на экране настроек.
 *
 * Подпись берётся ТЕМ ЖЕ ключом, что и у кнопки, которую клавиша дублирует: у родного
 * мира кнопки нет вовсе, поэтому ключ у него свой.
 */
export const HOTKEY_ACTIONS: { action: HotkeyAction; label: Key }[] = [
  { action: 'turn', label: 'side.turn.end' },
  { action: 'research', label: 'side.science' },
  { action: 'colonies', label: 'hud.colonies' },
  { action: 'planets', label: 'hud.planets' },
  { action: 'fleet', label: 'hud.fleet' },
  { action: 'game', label: 'gameMenu.game' },
  { action: 'leaders', label: 'hud.leaders' },
  { action: 'races', label: 'hud.races' },
  { action: 'info', label: 'hud.info' },
  { action: 'homeworld', label: 'hotkey.homeworld' },
];

/*
 * Ключ с номером вида: в браузере раскладка лежит ЦЕЛИКОМ (каждое действие со своей
 * клавишей), а не правками игрока, и прежний ключ навсегда держал бы флот на F3 — новая
 * раскладка по умолчанию до такого игрока не дошла бы. Переназначенное игроком при этом не
 * теряется: оно живёт в учётной записи и приходит при входе.
 */
const STORAGE_KEY = 'sddnw.hotkeys.v2';

/**
 * Клавиши, которые игрок вправе назначить.
 *
 * Буквы, цифры и функциональный ряд, кроме трёх, которые принадлежат не игре, а браузеру:
 * **F5** перезагружает страницу, **F11** разворачивает окно, **F12** открывает отладчик —
 * и отнять их у браузера нельзя (`preventDefault` их не берёт), так что назначенная туда
 * клавиша просто не работала бы. Esc в списке нет намеренно: он закрывает верхний экран, и
 * это правило игры, а не настройка.
 */
const ASSIGNABLE = /^(Key[A-Z]|Digit[0-9]|F[1-4]|F[6-9]|F10)$/;

export const assignable = (code: string): boolean => ASSIGNABLE.test(code);

/**
 * Что написать на клавише: `KeyT` → «T», `Digit1` → «1», `F4` → «F4».
 *
 * Надпись выводится из кода, а не хранится второй строкой: разъехавшись, она обещала бы
 * игроку клавишу, которой нет. Незнакомый код показывается как есть — пусть игрок видит
 * то, что лежит у него в записи, а не пустоту.
 */
export const hotkeyLabel = (code: string): string => {
  if (code.startsWith('Key')) {
    return code.slice(3);
  }
  if (code.startsWith('Digit')) {
    return code.slice(5);
  }
  return code;
};

/** Годится ли раскладка целиком: все действия на месте и все клавиши назначаемые. */
const valid = (value: unknown): value is Hotkeys => {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const map = value as Record<string, unknown>;
  return HOTKEY_ACTIONS.every(({ action }) => typeof map[action] === 'string'
    && assignable(map[action] as string));
};

/**
 * Раскладка из хранилища или из записи: чего не хватает — берётся по умолчанию.
 *
 * Недостающее действие важнее, чем кажется: раскладка игрока сохранена вчера, а сегодня в
 * игре появилось новое действие — и без этого добора клавиша у него осталась бы пустой
 * навсегда, без единого признака поломки.
 */
export const mergeHotkeys = (stored: Partial<Record<string, string>> | null | undefined): Hotkeys => {
  const merged = { ...DEFAULT_HOTKEYS };
  HOTKEY_ACTIONS.forEach(({ action }) => {
    const code = stored?.[action];
    if (typeof code === 'string' && assignable(code)) {
      merged[action] = code;
    }
  });
  return merged;
};

const read = (): Hotkeys => {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return valid(parsed) ? parsed : mergeHotkeys(parsed as Record<string, string> | null);
  } catch {
    // Приватное окно или мусор в хранилище: раскладка по умолчанию лучше отказа.
    return { ...DEFAULT_HOTKEYS };
  }
};

let current: Hotkeys = typeof window === 'undefined' ? { ...DEFAULT_HOTKEYS } : read();

const listeners = new Set<() => void>();

/**
 * Поставить раскладку.
 *
 * Слушателям она рассылается сама: подсказки на кнопках («Колонии (C)») обязаны
 * перерисоваться в тот же миг, иначе игрок увидит на кнопке одну клавишу, а работать будет
 * другая — и поверит кнопке.
 */
export const applyHotkeys = (value: Partial<Record<string, string>>): void => {
  current = mergeHotkeys(value);
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
  } catch {
    // Хранилище закрыто — раскладка поживёт до перезагрузки, и это лучше отказа.
  }
  listeners.forEach((listener) => listener());
};

/** Нынешняя раскладка. Спрашивается В МОМЕНТ НАЖАТИЯ: обработчик ставится один раз. */
export const hotkeys = (): Hotkeys => current;

/** Подписка на смену раскладки — для подсказок на кнопках. */
export const onHotkeys = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/**
 * Хук: перерисовывает компонент, когда игрок переназначил клавишу.
 *
 * То же правило, что у языка (`useT`): компонент, который пишет клавишу на кнопке, обязан
 * подписаться — иначе на кнопке останется прежняя буква, а работать будет новая.
 */
export const useHotkeys = (): Hotkeys =>
  useSyncExternalStore(onHotkeys, hotkeys, () => DEFAULT_HOTKEYS);

/** Применить сохранённую раскладку при запуске — до первой отрисовки. */
export const restoreHotkeys = (): void => applyHotkeys(read());

/**
 * Нажата ли эта клавиша.
 *
 * Сверяется и ФИЗИЧЕСКОЕ место (`code`), и буква (`key`): игра переведена на два языка, и
 * на русской раскладке `key` у клавиши T — это «е», а сверка по одной букве отняла бы
 * горячие клавиши у половины игроков. Но `code` называет место по раскладке US, и на
 * Dvorak буква T лежит не там, — поэтому годится и то, и другое.
 */
export const matches = (event: KeyboardEvent, code: string): boolean =>
  event.code === code || event.key.toLowerCase() === hotkeyLabel(code).toLowerCase();

/** Кому уже досталась эта клавиша, кроме названного действия; пусто — никому. */
export const takenBy = (map: Hotkeys, code: string, except: HotkeyAction): HotkeyAction | null =>
  (HOTKEY_ACTIONS.find(({ action }) => action !== except && map[action] === code)?.action) ?? null;
