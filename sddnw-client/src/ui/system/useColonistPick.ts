import { useEffect, useRef, useState } from 'react';

/** Группа жителей «на руках»: откуда взята, сколько в ней и где сейчас курсор. */
export interface HeldGroup<S> {
  from: S;
  count: number;
  x: number;
  y: number;
}

/**
 * Перенос жителей ЩЕЛЧКАМИ, как в оригинале — п. 4.1, решение хозяина проекта (01.10.2026).
 *
 * Правило MOO II: щелчок по жителю берёт его и ВСЕХ, КТО ПРАВЕЕ, в той же полосе; группа
 * летит за курсором, и второй щелчок кладёт её в полосу, над которой он сделан. Щелчок мимо
 * полос (или правой кнопкой, или Esc) возвращает группу на место — ничего не меняется.
 * Прежде здесь было перетаскивание с зажатой кнопкой по одному жителю: переставить пятерых
 * стоило пяти жестов, а оригинал делает это двумя щелчками.
 *
 * Второй щелчок ловится на ОКНЕ в фазе захвата и дальше не идёт: под курсором может
 * оказаться кнопка (постройка, название колонии), и щелчок, который клал жителей, нажал бы
 * заодно и её. По той же причине гасится и `click`, который браузер шлёт следом. Esc,
 * пока группа на руках, достаётся ей, а не очереди модальных экранов: иначе отмена
 * переноса закрывала бы весь экран колонии.
 *
 * `targetAt` называет полосу под точкой (или пусто), `onDrop` кладёт группу; решать, что
 * бросок в ту же полосу — это возврат, — дело `onDrop`.
 */
export function useColonistPick<S, T>(
  targetAt: (x: number, y: number) => T | null,
  onDrop: (target: T, group: HeldGroup<S>) => void,
) {
  const [held, setHeld] = useState<HeldGroup<S> | null>(null);
  // Обработчики окна берут свежие значения из ссылок: подписка живёт всё время, пока
  // группа на руках, а состав колонии за это время может смениться ответом сервера.
  const heldRef = useRef(held);
  heldRef.current = held;
  const targetRef = useRef(targetAt);
  targetRef.current = targetAt;
  const dropRef = useRef(onDrop);
  dropRef.current = onDrop;
  /** До этого момента щелчки глотаются: тот, что браузер шлёт за положившим нажатием. */
  const swallowUntil = useRef(0);

  /** Взять жителя и всех правее: `count` — сколько их, считая взятого. */
  const pick = (from: S, count: number) => (event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0 || count <= 0 || heldRef.current) {
      return;
    }
    // Своё перетаскивание рисунка и выделение текста браузеру не нужны: жест наш.
    event.preventDefault();
    event.stopPropagation();
    setHeld({ from, count, x: event.clientX, y: event.clientY });
  };

  const holding = held !== null;
  useEffect(() => {
    if (!holding) {
      return;
    }
    const onMove = (event: PointerEvent) =>
      setHeld((current) => (current ? { ...current, x: event.clientX, y: event.clientY } : current));

    const onDown = (event: PointerEvent) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      swallowUntil.current = Date.now() + 500;
      const group = heldRef.current;
      setHeld(null);
      if (!group || event.button !== 0) {
        return;
      }
      const target = targetRef.current(event.clientX, event.clientY);
      if (target !== null) {
        dropRef.current(target, { ...group, x: event.clientX, y: event.clientY });
      }
    };

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        setHeld(null);
      }
    };

    // Оборванная череда указателя (окно поверх, смена вкладки) — группа возвращается.
    const onCancel = () => setHeld(null);

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerdown', onDown, true);
    window.addEventListener('keydown', onKey, true);
    window.addEventListener('blur', onCancel);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerdown', onDown, true);
      window.removeEventListener('keydown', onKey, true);
      window.removeEventListener('blur', onCancel);
    };
  }, [holding]);

  // Щелчок за положившим нажатием приходит, когда группы на руках уже нет, — поэтому
  // глотатель живёт всегда, а решает по отметке времени (она залипнуть не может).
  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      if (Date.now() < swallowUntil.current) {
        swallowUntil.current = 0;
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    };
    const onMenu = (event: MouseEvent) => {
      if (Date.now() < swallowUntil.current) {
        event.preventDefault();
      }
    };
    window.addEventListener('click', onClick, true);
    window.addEventListener('contextmenu', onMenu, true);
    return () => {
      window.removeEventListener('click', onClick, true);
      window.removeEventListener('contextmenu', onMenu, true);
    };
  }, []);

  return { held, pick };
}
