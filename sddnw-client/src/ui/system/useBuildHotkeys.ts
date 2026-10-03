import { useEffect } from 'react';

/**
 * Горячие клавиши очереди стройки — п. 10: **R** повторяет, **D** убирает, **↑ ↓** выбирают
 * строку, **Shift + ↑ ↓** её переносят.
 *
 * Правила те же, что у клавиш карты (`ui/hotkeys.ts`), и каждое тут не для красоты:
 *
 * * **клавиша узнаётся по МЕСТУ и по букве разом** (`event.code` и `event.key`): игра на
 *   двух языках, и на русской раскладке `key` у этой клавиши — «к» и «в», а не «r» и «d»;
 *   но `code` называет место по раскладке US, и на Dvorak буква лежит не там, — поэтому
 *   сверяются оба;
 * * **клавиша с модификатором не наша**: Ctrl+D — закладка браузера, Ctrl+R —
 *   перезагрузка страницы, и отнимать их у браузера нельзя;
 * * **поле ввода забирает буквы себе**: в названии шаблона стройки есть и «r», и «d»;
 * * **удержание не считается вторым нажатием** (`event.repeat`): зажатая D вычистила бы
 *   очередь целиком за секунду.
 *
 * Своей раскладки у этих двух клавиш нет нарочно: раскладка карты живёт в учётной записи
 * и настраивается, а здесь клавиши действуют только внутри открытого экрана стройки, где
 * других претендентов на буквы нет вовсе.
 */
export function useBuildHotkeys({
  active,
  onRepeat,
  onDelete,
  onStep,
  onShift,
}: {
  /** Экран, которому принадлежит клавиатура сейчас: очередь правят в одном месте. */
  active: boolean;
  onRepeat: () => void;
  onDelete: () => void;
  /** ↑ / ↓ — выбрать соседнюю строку очереди (−1 вверх, +1 вниз). */
  onStep: (delta: number) => void;
  /** Shift + ↑ / ↓ — передвинуть выбранную строку. */
  onShift: (delta: number) => void;
}) {
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) {
        return;
      }
      // Стрелки — выбор и перенос строки (трек техдолга, пункт 21): прежде выбрать строку без
      // мыши было нельзя, и R с D оставались половиной клавиатуры. Удержание здесь законно —
      // так ходят по списку; переносу оно тоже не страшно: у края очереди он кончается сам.
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        event.preventDefault();
        const delta = event.key === 'ArrowUp' ? -1 : 1;
        if (event.shiftKey) {
          onShift(delta);
        } else {
          onStep(delta);
        }
        return;
      }
      if (event.repeat) {
        return;
      }
      const key = event.key.toLowerCase();
      if (event.code === 'KeyR' || key === 'r' || key === 'к') {
        event.preventDefault();
        onRepeat();
        return;
      }
      if (event.code === 'KeyD' || key === 'd' || key === 'в') {
        event.preventDefault();
        onDelete();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [active, onRepeat, onDelete, onStep, onShift]);
}
