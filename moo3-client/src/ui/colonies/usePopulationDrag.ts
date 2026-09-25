import { useEffect, useRef, useState } from 'react';

/**
 * Перенос жителя из колонии в колонию перетаскиванием — п. 4.1.1.
 *
 * В окне Colonies MOO II население возят именно так: житель берётся из одной строки и
 * кладётся в другую, а рейс уходит грузовым флотом. Хук ведёт сам перенос — за кем
 * следить, что подсвечивать и куда житель попал; отправкой занимается экран.
 *
 * Пока жителя несут, за указателем следит **окно**, а не сама фигурка: рука уходит с
 * фигурки в первый же момент переноса, и отпустить его можно где угодно. На этом уже
 * обжигались в полосе жителей одной колонии — см. `ColonyJobs`.
 *
 * Строка-получатель ищется по рамкам строк, а не по событию над элементом: фигурка
 * летит под курсором и перехватывала бы наведение сама на себя.
 */
export function usePopulationDrag(onDrop: (fromPlanetId: string, toPlanetId: string) => void) {
  /** Рамки строк колоний: по ним и считается, над кем сейчас житель. */
  const rows = useRef<Record<string, HTMLElement | null>>({});
  const [drag, setDrag] = useState<{ from: string; x: number; y: number } | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  /** Двигали ли фигурку в этом переносе: без движения это был клик, а не перевозка. */
  const moved = useRef(false);
  /**
   * Момент, когда перенос состоялся. Браузер сопровождает отпускание указателя кликом
   * по той же кнопке, и его нужно пропустить — иначе следом откроется диалог перевозки.
   * Отметка времени, а не флаг: залипнуть она не может, даже если клик не придёт.
   */
  const droppedAt = useRef(0);
  // Обработчик берётся из ссылки: он замыкает состав колоний, который меняется от
  // каждого ответа сервера, а подписку на окно из-за этого пересоздавать незачем.
  const drop = useRef(onDrop);
  drop.current = onDrop;

  const register = (planetId: string) => (element: HTMLElement | null) => {
    rows.current[planetId] = element;
  };

  const rowUnder = (x: number, y: number): string | null =>
    Object.entries(rows.current).find(([, element]) => {
      const rect = element?.getBoundingClientRect();
      return rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    })?.[0] ?? null;

  const start = (planetId: string) => (event: React.PointerEvent<HTMLElement>) => {
    /*
      Указатель захватывается фигуркой, а нажатие отменяется: иначе браузер берётся за
      жест сам — начинает своё перетаскивание рисунка или выделение соседнего текста, —
      обрывает череду указателя событием `pointercancel`, и ни движения, ни отпускания
      мы уже не увидим. Та же защита стоит у фигурок на экране колонии.
    */
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    moved.current = false;
    setDrag({ from: planetId, x: event.clientX, y: event.clientY });
  };

  /** Только что отпустили жителя: клик после переноса — тот самый, лишний. */
  const justDropped = (): boolean => Date.now() - droppedAt.current < 300;

  const from = drag?.from ?? null;
  useEffect(() => {
    if (!from) {
      return;
    }

    const onMove = (event: PointerEvent) => {
      moved.current = true;
      // Положение меняется своим обновлением: подписка от него не зависит и живёт
      // ровно один перенос.
      setDrag((current) => (current ? { ...current, x: event.clientX, y: event.clientY } : current));
      setHover(rowUnder(event.clientX, event.clientY));
    };

    const onUp = (event: PointerEvent) => {
      const target = rowUnder(event.clientX, event.clientY);
      if (moved.current && target && target !== from) {
        droppedAt.current = Date.now();
        drop.current(from, target);
      }
      moved.current = false;
      setDrag(null);
      setHover(null);
    };

    // Оборванный жест (окно поверх, смена вкладки, системный жест) `pointerup` не
    // присылает вовсе: без этого фигурка осталась бы висеть на курсоре.
    const onCancel = () => {
      moved.current = false;
      setDrag(null);
      setHover(null);
    };

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onCancel);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onCancel);
    };
  }, [from]);

  return { drag, hover, start, register, justDropped };
}
