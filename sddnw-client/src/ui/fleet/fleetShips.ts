import type { Fleet, FleetGroup, FleetShip, StarSystem } from '../../api/types';

/**
 * Правила экрана флота, повторённые на клиенте — п. 8.
 *
 * Окно Fleet Operations MOO II показывает не проекты, а **корабли поимённо**: сетка
 * клеток, в каждой один корабль, и действия — переброска и списание — берут отобранные
 * клетки. Сервер же держит флот по проектам («три фрегата, один крейсер»): так его
 * считает бой и так он ложится в базу.
 *
 * Поэтому здесь и живёт перевод одного в другое: состав разворачивается в отдельные
 * корабли для сетки, а отобранное сворачивается обратно в приказ «проект — сколько»,
 * который понимают перелёт и списание. Считает всё равно сервер: это только раскладка.
 */

/** Один корабль в сетке: его проект и номер среди одинаковых кораблей этого проекта. */
export interface FleetShipSlot {
  /** Ключ клетки: проект и номер. Им же помечается отбор — по нему клетка и находится. */
  key: string;
  ship: FleetShip;
  /** Номер корабля среди своих однотипных, с единицы: он стоит в подписи клетки. */
  index: number;
}

/**
 * Боевой ли это корабль.
 *
 * В MOO II вспомогательные корабли — колониальный, аванпостовый и транспорт — отличаются
 * от боевых тем, что не несут оружия и в бой не входят вовсе. Признак поэтому не отдельное
 * поле, а само вооружение: корабль без единого ствола вспомогательный. Так же читаются и
 * переключатели Support / Combat нижней полосы оригинала.
 */
export function isCombat(ship: FleetShip): boolean {
  return ship.weapons.length > 0;
}

/**
 * Корабли флота по клеткам сетки: каждый корабль отдельно, в порядке состава.
 *
 * Порядок состава — это порядок проектов, в котором их отдал сервер; внутри проекта
 * корабли неразличимы, поэтому нумеруются подряд. Номер нужен не игроку, а отбору:
 * без него две клетки одного проекта были бы одной и той же клеткой.
 */
export function shipSlots(fleet: FleetGroup | null): FleetShipSlot[] {
  if (!fleet) {
    return [];
  }
  const slots: FleetShipSlot[] = [];
  for (const ship of fleet.composition) {
    for (let index = 0; index < ship.ships; index++) {
      slots.push({ key: `${ship.designId}#${index}`, ship, index: index + 1 });
    }
  }
  return slots;
}

/** Клетки, прошедшие переключатели нижней полосы: боевые, вспомогательные или те и другие. */
export function visibleSlots(
  slots: FleetShipSlot[],
  filters: { combat: boolean; support: boolean },
): FleetShipSlot[] {
  return slots.filter((slot) => (isCombat(slot.ship) ? filters.combat : filters.support));
}

/**
 * Отобранное — приказом серверу: сколько кораблей какого проекта.
 *
 * Клетки одного проекта сворачиваются в одну строку: серверу всё равно, какой именно
 * фрегат из трёх улетел, — корабли одного проекта неразличимы и в базе лежат числом.
 */
export function toOrders(
  slots: FleetShipSlot[],
  selected: ReadonlySet<string>,
): { designId: string; ships: number }[] {
  const counts = new Map<string, number>();
  for (const slot of slots) {
    if (selected.has(slot.key)) {
      counts.set(slot.ship.designId, (counts.get(slot.ship.designId) ?? 0) + 1);
    }
  }
  return [...counts].map(([designId, ships]) => ({ designId, ships }));
}

/**
 * Может ли флот основать колонию там, где стоит, — п. 4.1: во флоте есть колониальный
 * корабль, а в системе — свободная пригодная планета. Правило то же, что у кнопки
 * «заселить» окна высадки (`FleetLandingDialog`), и по нему же окно флота показывает
 * «Колонизовать планету», а прилетевший корабль сам предлагает заселение.
 */
export const canColonizeHere = (group: FleetGroup | null, system: StarSystem | null | undefined): boolean =>
  group !== null
  && !group.targetSystemId
  && system !== null && system !== undefined
  && group.composition.some((ship) => ship.role === 'COLONY' && ship.ships > 0)
  && system.planets.some((planet) =>
    !planet.ownerPlayerId && planet.population <= 0 && planet.colonizable);

/**
 * Куда долетает ЭТОТ флот — п. 8, трек техдолга, пункт 22. Дополнительные баки — модуль
 * корабля, и флот с ними уходит дальше империи; дальностей всего две (с баками и без), и
 * сервер отдаёт по списку достижимого на каждую. Флот, чья дальность больше дальности
 * империи, — с баками. Одно правило на карту (прицел) и окно отправки: посчитанное дважды,
 * оно уже разошлось однажды — окно называло дальность империи и занижало её флоту с баками.
 */
export function fleetReach(empire: Fleet | null | undefined, group: FleetGroup | null | undefined):
  { rangeParsecs: number; reachableSystemIds: string[] } {
  const far = group !== null && group !== undefined && group.rangeParsecs > (empire?.rangeParsecs ?? 0);
  return {
    rangeParsecs: group?.rangeParsecs ?? empire?.rangeParsecs ?? 0,
    reachableSystemIds: (far ? empire?.reachableFarSystemIds : empire?.reachableSystemIds) ?? [],
  };
}
