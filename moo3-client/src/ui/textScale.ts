/**
 * Размер текста интерфейса — п. 11.1.
 *
 * В игре всё меряется в `rem` от корневого кегля, поэтому одна величина
 * (`--moo3-text-scale`) правит разом и буквы, и отступы, и высоту панелей. До неё размеры
 * были вбиты в пикселях в 311 местах, и у игрока не оставалось НИ ОДНОГО способа сделать
 * текст крупнее: масштаб браузера тянет и карту галактики, а панели остаются прежними.
 *
 * Выбор живёт там же, где язык: в `localStorage` для первого кадра и в учётной записи,
 * чтобы ехать за игроком с машины на машину.
 */

/** Ступени масштаба. Сотня — прежний размер, и она же значение по умолчанию. */
export const TEXT_SCALES = [100, 115, 130, 150] as const;

export type TextScale = (typeof TEXT_SCALES)[number];

export const DEFAULT_TEXT_SCALE: TextScale = 100;

const STORAGE_KEY = 'moo3.textScale';

const known = (value: unknown): value is TextScale =>
  TEXT_SCALES.includes(Number(value) as TextScale);

const read = (): TextScale => {
  const stored = window.localStorage.getItem(STORAGE_KEY);
  return known(stored) ? (Number(stored) as TextScale) : DEFAULT_TEXT_SCALE;
};

let current: TextScale = typeof window === 'undefined' ? DEFAULT_TEXT_SCALE : read();

const listeners = new Set<() => void>();

/**
 * Поставить масштаб: правится корневой кегль, и за ним следует вся разметка.
 *
 * Карте галактики этого мало — она нарисована канвасом и о `rem` не знает, поэтому ей
 * посылается то же событие, что и при смене размера окна: полоса правой панели стала
 * шире, и окно карты обязано пересчитаться, иначе карта уедет под панель.
 */
export const applyTextScale = (value: TextScale): void => {
  current = known(value) ? value : DEFAULT_TEXT_SCALE;
  document.documentElement.style.setProperty('--moo3-text-scale', String(current / 100));
  try {
    window.localStorage.setItem(STORAGE_KEY, String(current));
  } catch {
    // Приватное окно: масштаб останется на эту сессию, и это лучше отказа.
  }
  listeners.forEach((listener) => listener());
  window.dispatchEvent(new Event('resize'));
};

/** Нынешний масштаб числом: 100 — сотня процентов. */
export const textScale = (): TextScale => current;

/** Во сколько раз крупнее прежнего — для того, что считается в пикселях (канвас, Phaser). */
export const textScaleFactor = (): number => current / 100;

/** Подписка на смену масштаба: нужна тем, кто считает размеры сам, а не берёт их из CSS. */
export const onTextScale = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** Применить сохранённый масштаб при запуске — до первой отрисовки. */
export const restoreTextScale = (): void => applyTextScale(read());
