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
 * <p>
 * Это ряд ОКОН ФЛОТА, где корабль стоит в клетке сетки; на поле боя корабли меряются
 * {@link fieldShipSize}.
 */
const SHIP_SCALE = [0.55, 0.7, 0.9, 1.2, 1.6, 2.2];

/** Размер прямоугольника корабля в пикселях по размеру его корпуса. */
export function shipSize(hullSize: number): number {
  const scale = SHIP_SCALE[Math.max(1, Math.min(6, hullSize)) - 1];
  return Math.round(CELL * scale);
}

/**
 * Сколько клеток корабль занимает НА ПОЛЕ БОЯ в длину по размеру корпуса (1..6) — решение
 * хозяина проекта (03.10.2026): корабль не должен выглядеть больше клеток, чем занимает.
 * Фрегат — половина клетки, эсминец 70 %, крейсер 80 %, линкор 90 %, титан ровно клетка,
 * Левиафан — 120 % клетки, заходит на соседние.
 */
const FIELD_LENGTH = [0.5, 0.7, 0.8, 0.9, 1, 1.2];

/**
 * Какую долю рамки 100 × 100 (`ui/ship/shipArt.tsx`) занимает металл корпуса в длину —
 * снято с самих рисунков, от кормы двигателей до носа, без свечения сопел. По ней рамка
 * растягивается так, чтобы ВИДИМЫЙ корабль был ровно нужной длины, а не его пустая рамка.
 */
const HULL_EXTENT = [0.62, 0.74, 0.86, 0.92, 0.96, 1.01];

/** Сторона рамки рисунка корабля на поле боя в пикселях. */
export function fieldShipSize(hullSize: number): { length: number; width: number } {
  const index = Math.max(1, Math.min(6, hullSize)) - 1;
  const length = Math.round((CELL * FIELD_LENGTH[index]) / HULL_EXTENT[index]);
  return { length, width: length };
}

/** Левый верхний угол корабля: прямоугольник рисунка стоит по центру своей клетки. */
export function shipRect(ship: BattleShip): { x: number; y: number; size: number; height: number } {
  const { length, width } = fieldShipSize(ship.hullSize);
  return {
    x: ship.x * CELL + (CELL - length) / 2,
    y: ship.y * CELL + (CELL - width) / 2,
    size: length,
    height: width,
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

/**
 * Строки оружия, которые ещё стреляют, — по ним полоса боя и заводит выбор: у строки,
 * где выбиты все гнёзда, включать нечего.
 */
export function liveWeaponRows(ship: BattleShip): number[] {
  return (ship.weapons ?? [])
    .map((weapon, index) => ({ weapon, index }))
    .filter(({ weapon }) => weapon.count - weapon.wrecked > 0)
    .map(({ index }) => index);
}

/**
 * Дальность ВЫБРАННОГО оружия — п. 8: по самому дальнобойному включённому стволу, как и
 * на сервере (`BattleRules.reach`). Ракеты бьют через всё поле. Ничего не выбрано — ноль:
 * стрелять нечем, и прицел это покажет.
 */
export function chosenRange(battle: Battle, ship: BattleShip, rows: number[] | null): number {
  const weapons = ship.weapons ?? [];
  if (rows === null || weapons.length === 0) {
    return ship.weaponRange ?? battle.weaponRange;
  }
  return rows.reduce((best, row) => Math.max(best, weapons[row]?.range ?? battle.weaponRange), 0);
}

/** Достанет ли залп этого корабля до цели выбранным оружием. */
export function inRange(battle: Battle, ship: BattleShip, target: BattleShip, rows: number[] | null = null): boolean {
  // Дальность у каждого ствола своя — п. 8: тяжёлая установка бьёт вдвое дальше,
  // ближняя оборона вдвое ближе, ракета — через всё поле.
  const range = chosenRange(battle, ship, rows);
  return range > 0 && distance(ship.x, ship.y, target.x, target.y) <= range;
}

/** Корабль, чей сейчас ход; пусто — бой кончился. */
export function currentShip(battle: Battle): BattleShip | null {
  return battle.ships.find((ship) => ship.id === battle.currentShipId) ?? null;
}

/** Подпись корабля на поле и в очереди: проект, номер и империя. */
export function shipLabel(ship: BattleShip): string {
  return `${ship.designName} ${ship.ordinal}`;
}
