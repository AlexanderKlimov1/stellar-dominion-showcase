import { useEffect, useRef } from 'react';

/**
 * Закрытие модального экрана по Esc — с учётом того, что экраны складываются стопкой.
 *
 * Карта → система → колония → диалог стройки: все они слушают Esc на окне, и без общей
 * очереди одно нажатие закрывало бы всю стопку разом. Хук ведёт порядок открытых экранов
 * и отдаёт нажатие только верхнему.
 */
const stack: symbol[] = [];

export function useModalEscape(active: boolean, onClose: () => void): void {
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

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && stack[stack.length - 1] === token) {
        close.current();
      }
    };

    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      stack.splice(stack.indexOf(token), 1);
    };
  }, [active]);
}
