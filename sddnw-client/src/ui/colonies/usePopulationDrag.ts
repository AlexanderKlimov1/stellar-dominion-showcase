import { useRef } from 'react';
import { useColonistPick } from '../system/useColonistPick';

export type JobName = 'farmers' | 'workers' | 'scientists';

/** Куда пришли жители: колония-получатель и, если попали в группу занятия, — это занятие. */
export interface Drop {
  fromPlanetId: string;
  toPlanetId: string;
  fromJob: JobName;
  /** Пусто — группа брошена на строку в целом, занятие прибывшим назначает сервер. */
  toJob: JobName | null;
  /** Сколько жителей в группе: взятый и все правее него. */
  count: number;
}

/**
 * Перенос жителей между колониями и занятиями ЩЕЛЧКАМИ — п. 4.1, п. 4.1.1.
 *
 * Правило то же, что на экране колонии ({@link useColonistPick}): щелчок по жителю берёт его
 * и всех правее в той же группе занятия, второй щелчок кладёт группу — в группу занятия
 * своей колонии (смена занятия, грузовики не нужны), в строку другой колонии (перевозка) —
 * а мимо строк возвращает её на место.
 *
 * Строка-получатель ищется по рамкам строк и групп, а не по событию над элементом:
 * группа летит под курсором и перехватывала бы наведение сама на себя. Группы занятий
 * проверяются раньше строк.
 */
export function usePopulationDrag(onDrop: (drop: Drop) => void) {
  /** Рамки строк колоний: по ним и считается, над кем сейчас группа. */
  const rows = useRef<Record<string, HTMLElement | null>>({});
  /** Рамки групп занятий: ключ — «колония:занятие». */
  const zones = useRef<Record<string, HTMLElement | null>>({});

  const register = (planetId: string) => (element: HTMLElement | null) => {
    rows.current[planetId] = element;
  };

  const registerZone = (planetId: string, job: JobName) => (element: HTMLElement | null) => {
    zones.current[`${planetId}:${job}`] = element;
  };

  const inside = (element: HTMLElement | null | undefined, x: number, y: number) => {
    const rect = element?.getBoundingClientRect();
    return !!rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
  };

  const zoneUnder = (x: number, y: number): { planetId: string; job: JobName } | null => {
    const found = Object.entries(zones.current).find(([, element]) => inside(element, x, y))?.[0];
    if (!found) {
      return null;
    }
    const at = found.lastIndexOf(':');
    return { planetId: found.slice(0, at), job: found.slice(at + 1) as JobName };
  };

  const targetAt = (x: number, y: number): { planetId: string; job: JobName | null } | null => {
    const zone = zoneUnder(x, y);
    if (zone) {
      return zone;
    }
    const row = Object.entries(rows.current).find(([, element]) => inside(element, x, y))?.[0];
    return row ? { planetId: row, job: null } : null;
  };

  const { held, pick } = useColonistPick<{ planetId: string; job: JobName }, { planetId: string; job: JobName | null }>(
    targetAt,
    (target, group) => {
      const { planetId, job } = group.from;
      // Своя колония принимает группу только другим занятием; та же группа или строка в
      // целом — это возврат на место.
      if (target.planetId === planetId && (target.job === null || target.job === job)) {
        return;
      }
      onDrop({ fromPlanetId: planetId, toPlanetId: target.planetId, fromJob: job, toJob: target.job, count: group.count });
    },
  );

  /** Взять жителя и всех правее: `count` — сколько их, считая взятого. */
  const start = (planetId: string, job: JobName, count: number) => (event: React.PointerEvent<HTMLElement>) => {
    // Shift + щелчок — диалог перевозки, а не группа на руках.
    if (event.shiftKey) {
      return;
    }
    pick({ planetId, job }, count)(event);
  };

  // Подсветка — от того, где сейчас курсор с группой: своя группа занятия подсвечивается
  // сама, чужая колония — строкой целиком.
  const under = held ? targetAt(held.x, held.y) : null;
  const ownZone = under !== null && under.job !== null && under.planetId === held?.from.planetId;
  const hoverZone = ownZone ? `${under!.planetId}:${under!.job}` : null;
  const hover = ownZone ? null : (under?.planetId ?? null);

  return { held, hover, hoverZone, start, register, registerZone };
}
