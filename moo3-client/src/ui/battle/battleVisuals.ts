import type { Battle, BattleShip } from '../../api/types';

/**
 * Как выглядит поле боя — п. 8.
 *
 * Корабли пока прямоугольники: спрайтов у игры нет, а размер корпуса показать нужно —
 * фрегат должен читаться мелким, Leviathan громадным. Когда появятся спрайты, они встанут
 * в те же прямоугольники: размеры и координаты клеток менять не придётся.
 */

/**
 * Сторона клетки поля в пикселях холста.
 *
 * Поле рисуется через `viewBox`, поэтому это не экранный размер, а мера: клетка к клетке
 * и корабль к клетке. На экране всё поле (32 × 20 клеток) ужимается под свой сегмент.
 */
export const CELL = 44;

/**
 * Во сколько клеток укладывается корабль каждого размера корпуса (1..6) — п. 8.
 *
 * Так корабли и выглядят в MOO II: мелкий фрегат занимает заметно меньше клетки, а
 * крупные корабли клетку перерастают — Leviathan виден издалека и заслоняет соседние
 * клетки. Ряд нелинейный: пропорция по месту корпуса (30 против 1200) обратила бы фрегат
 * в точку, поэтому взят ряд, где каждый класс различается на глаз, а разница между
 * концами ряда — вчетверо.
 */
const SHIP_SCALE = [0.55, 0.7, 0.9, 1.2, 1.6, 2.2];

/** Размер прямоугольника корабля в пикселях по размеру его корпуса. */
export function shipSize(hullSize: number): number {
  const scale = SHIP_SCALE[Math.max(1, Math.min(6, hullSize)) - 1];
  return Math.round(CELL * scale);
}

/** Левый верхний угол корабля: прямоугольник стоит по центру своей клетки. */
export function shipRect(ship: BattleShip): { x: number; y: number; size: number } {
  const size = shipSize(ship.hullSize);
  return {
    x: ship.x * CELL + (CELL - size) / 2,
    y: ship.y * CELL + (CELL - size) / 2,
    size,
  };
}

/** Цвет стороны: свои — цветом акцента, чужие — красным, как в правой панели карты. */
export function sideColor(ship: BattleShip): string {
  return ship.yours ? '#7dd3fc' : '#f87171';
}

/** Расстояние по полю: диагональ считается за один шаг — то же правило, что на сервере. */
export function distance(fromX: number, fromY: number, toX: number, toY: number): number {
  return Math.max(Math.abs(fromX - toX), Math.abs(fromY - toY));
}

/** Клетка свободна: на ней нет живого корабля. */
export function free(battle: Battle, x: number, y: number): boolean {
  return !battle.ships.some((ship) => !ship.destroyed && ship.x === x && ship.y === y);
}

/** Куда корабль может дойти в этом ходу — по ним и подсвечиваются клетки. */
export function reachable(battle: Battle, ship: BattleShip): Set<string> {
  const cells = new Set<string>();
  for (let dx = -ship.moveLeft; dx <= ship.moveLeft; dx++) {
    for (let dy = -ship.moveLeft; dy <= ship.moveLeft; dy++) {
      const x = ship.x + dx;
      const y = ship.y + dy;
      if (x < 0 || y < 0 || x >= battle.width || y >= battle.height) {
        continue;
      }
      if (distance(ship.x, ship.y, x, y) > ship.moveLeft || !free(battle, x, y)) {
        continue;
      }
      cells.add(`${x}:${y}`);
    }
  }
  return cells;
}

/** Достанет ли залп этого корабля до цели. */
export function inRange(battle: Battle, ship: BattleShip, target: BattleShip): boolean {
  // Дальность у каждого корабля своя — п. 8: тяжёлая установка бьёт вдвое дальше,
  // ближняя оборона вдвое ближе. Поле боя отдаёт её вместе с кораблём.
  return distance(ship.x, ship.y, target.x, target.y)
    <= (ship.weaponRange ?? battle.weaponRange);
}

/** Корабль, чей сейчас ход; пусто — бой кончился. */
export function currentShip(battle: Battle): BattleShip | null {
  return battle.ships.find((ship) => ship.id === battle.currentShipId) ?? null;
}

/** Подпись корабля на поле и в очереди: проект, номер и империя. */
export function shipLabel(ship: BattleShip): string {
  return `${ship.designName} ${ship.ordinal}`;
}
