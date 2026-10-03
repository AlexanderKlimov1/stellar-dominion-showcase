import { useEffect, useRef, useSyncExternalStore } from 'react';

/**
 * Закрытие модального экрана по Esc — с учётом того, что экраны складываются стопкой.
 *
 * Карта → система → колония → диалог стройки: все они слушают Esc на окне, и без общей
 * очереди одно нажатие закрывало бы всю стопку разом. Хук ведёт порядок открытых экранов
 * и отдаёт нажатие только верхнему.
 */
const stack: symbol[] = [];

/**
 * Подписчики на смену очереди — backlog-promo, пункт 27: карточке обучения нужно ЗНАТЬ, что
 * открыто окно, чтобы свернуться и не лечь поверх него. Очередь сама по себе не реактивна
 * (см. {@link modalOpen}), поэтому о каждом входе и выходе экрана она сообщает сама.
 */
const listeners = new Set<() => void>();
const changed = () => listeners.forEach((listener) => listener());

/** Открыт ли модальный экран — с перерисовкой при каждой смене (для отрисовки, а не нажатий). */
export function useModalOpen(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => stack.length > 0,
  );
}

/**
 * Открыт ли хоть один модальный экран.
 *
 * Спрашивают это горячие клавиши карты ({@link GalaxyHotkeys}): клавиатура принадлежит
 * верхнему открытому окну, и «T» под открытыми колониями ход объявлять не должна. Очередь
 * эта — единственная правда о том, что открыто: список состояний пришлось бы держать
 * вручную, и он отстал бы от первого же нового окна молча, а сюда каждый модальный экран
 * записывается сам, ради Esc.
 *
 * Спрашивать её можно только В МОМЕНТ НАЖАТИЯ: очередь не реактивна, и отрисовку по ней
 * строить нечем.
 */
export function modalOpen(): boolean {
  return stack.length > 0;
}

/**
 * `closeOnEnter` — для окон, где кроме закрытия делать нечего (итоги хода, осмотр, сцена
 * боя): там Enter закрывает так же, как Esc. Окнам с выбором и полями ввода его не дают —
 * у них Enter значит своё.
 *
 * В поле ввода Enter не перехватывается. На КНОПКЕ — перехватывается, и это не оплошность:
 * фокус остаётся на кнопке, которой окно открыли, и без перехвата браузер превратил бы
 * Enter в щелчок по ней — окно закрылось бы и тут же открылось снова. `preventDefault`
 * этот щелчок гасит.
 */
export function useModalEscape(
  active: boolean,
  onClose: () => void,
  closeOnEnter = false,
): void {
  // Обработчик берётся из ссылки, чтобы эффект не пересоздавался на каждую отрисовку:
  // иначе экран уходил бы в конец очереди и перехватывал Esc у того, кто выше.
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    if (!active) {
      return;
    }

    const token = Symbol('modal');
    stack.push(token);
    changed();

    const onKey = (event: KeyboardEvent) => {
      if (stack[stack.length - 1] !== token) {
        return;
      }
      if (event.key === 'Escape') {
        close.current();
        return;
      }
      if (closeOnEnter && event.key === 'Enter' && !event.repeat
          && !event.ctrlKey && !event.altKey && !event.metaKey && !event.shiftKey
          && !(event.target instanceof HTMLElement
               && event.target.closest('input, textarea, select'))) {
        event.preventDefault();
        close.current();
      }
    };

    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      stack.splice(stack.indexOf(token), 1);
      changed();
    };
  }, [active, closeOnEnter]);
}
