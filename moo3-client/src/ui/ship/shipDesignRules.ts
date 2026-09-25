import type { Key } from '../../i18n';
import type {
  ShipComponentOption,
  ShipDesign,
  ShipHull,
  WeaponModification,
} from '../../api/types';

/**
 * Правила проекта корабля на клиенте — п. 8.
 *
 * Повторяют `ShipDesignRules.java`, и это сознательный повтор: окно дизайна считает
 * проект на лету, пока игрок его собирает, а сохранённого проекта ещё нет — спросить
 * сервер не о чем. Правда о числах остаётся на сервере: он пересчитает всё при
 * сохранении и откажет, если проект не влезает.
 *
 * Единственное, чего здесь нет, — расовых процентов: их даёт `Fleet.attackPercent` и
 * `Fleet.defensePercent`, и они передаются параметром.
 */

/**
 * Компонент проекта: что взято, сколько раз и с какими модификациями — п. 8.
 *
 * Модификации бывают только у оружия: тяжёлое орудие, ближняя оборона, скорострельное,
 * бронебойное, обволакивающее. Они меняют урон, меткость, цену и место того ствола, на
 * который поставлены, — и одинаковые пушки с разными модификациями стоят в проекте
 * разными строками, как и в оригинале.
 */
export interface Pick {
  component: ShipComponentOption;
  count: number;
  modifications: WeaponModification[];
}

/** Сумма процентов модификаций одного вида: они складываются, как и на сервере. */
const modPercent = (pick: Pick, field: keyof WeaponModification): number =>
  pick.modifications.reduce((total, mod) => total + (mod[field] as number), 0);

/** Место одного такого ствола: модификация делает его крупнее или мельче. */
export const pickSpace = (pick: Pick, hull: ShipHull): number =>
  Math.max(1, Math.trunc((componentSpace(pick.component, hull)
    * Math.max(0, 100 + modPercent(pick, 'spacePercent'))) / 100));

/** Цена одного такого ствола — по тому же правилу, что и место. */
export const pickCost = (pick: Pick, hull: ShipHull): number =>
  Math.max(1, Math.trunc((componentCost(pick.component, hull)
    * Math.max(0, 100 + modPercent(pick, 'costPercent'))) / 100));

/** Урон одного выстрела этого ствола с поправкой модификаций. */
export const pickDamage = (pick: Pick): number => {
  const base = pick.component.effects.find((effect) => effect.type === 'WEAPON_DAMAGE');
  return Math.max(1, Math.trunc(((base?.amount ?? 0)
    * Math.max(0, 100 + modPercent(pick, 'damagePercent'))) / 100));
};

/**
 * Сколько выстрелов делает этот ствол за залп: автоматический огонь утраивает их число,
 * разделяющаяся боеголовка — учетверяет (п. 8).
 */
export const pickShots = (pick: Pick): number => {
  const base = pick.component.effects.find((effect) => effect.type === 'WEAPON_SHOTS');
  return Math.max(1, Math.trunc(((base?.amount ?? 0)
    * Math.max(0, 100 + modPercent(pick, 'shotsPercent'))) / 100));
};

/** Надбавка модификаций к меткости этого ствола, в процентных пунктах. */
export const pickAttackPercent = (pick: Pick): number => modPercent(pick, 'attackPercent');

/** Характеристики собираемого проекта — те же, что покажет сервер после сохранения. */
export interface Totals {
  space: number;
  spaceUsed: number;
  cost: number;
  structure: number;
  armour: number;
  shield: number;
  speed: number;
  /** Боевая скорость: число оригинала, поле боя делит его на четыре. */
  combatSpeed: number;
  attack: number;
  defense: number;
  /** Уклонение от ракет сверх обычной защиты: постановщик помех. */
  missileEvasion: number;
  troops: number;
  command: number;
}

/**
 * Место компонента на этом корпусе. Броня, щит, двигатель и приборы обслуживают весь
 * корабль, поэтому растут вместе с корпусом; пушка занимает своё место на любом.
 */
export function componentSpace(component: ShipComponentOption, hull: ShipHull): number {
  return component.scalesWithHull ? component.space * hull.systemFactor : component.space;
}

/** Цена компонента на этом корпусе — по тому же правилу, что и место. */
export function componentCost(component: ShipComponentOption, hull: ShipHull): number {
  return component.scalesWithHull ? component.cost * hull.systemFactor : component.cost;
}

/** Сумма одного действия по всему проекту — с учётом количества компонентов. */
function sum(picks: Pick[], type: string): number {
  return picks.reduce((total, pick) => {
    const amount = pick.component.effects
      .filter((effect) => effect.type === type)
      .reduce((value, effect) => value + effect.amount, 0);
    return total + amount * pick.count;
  }, 0);
}

/**
 * Во сколько раз обволакивающий удар сильнее обычного — то же число, что на сервере
 * (`ShipDesignRules.ENVELOPING_SIDES`): в бою он приходится на цель четыре раза.
 */
const ENVELOPING_SIDES = 4;

/** Обволакивает ли этот ствол цель — сам собой (плазма, Копьё новы) или модификацией ENV. */
const pickEnvelops = (pick: Pick): boolean =>
  pick.component.envelops === true || pick.modifications.some((mod) => mod.envelops);

/**
 * Залп проекта: урон всех стволов за заход, без приборов и расы.
 *
 * Обволакивающий удар входит вчетверо — правилом оригинала и сервера: он приходится на
 * цель четыре раза, по разу на каждую сторону.
 */
function salvo(picks: Pick[]): number {
  return picks
    .filter((pick) => pick.component.slot === 'WEAPON')
    .reduce((total, pick) => total + pickDamage(pick) * pickShots(pick) * pick.count
      * (pickEnvelops(pick) ? ENVELOPING_SIDES : 1), 0);
}

/**
 * Влезет ли ещё один такой компонент — п. 8.
 *
 * Место корабля не бывает отрицательным: в оригинале система, которой не хватает места,
 * просто не ставится. Считается это ТЕМ ЖЕ правилом, что и занятое место, — иначе запрет
 * и показанное число однажды разойдутся.
 *
 * Гнездо на одного (двигатель, броня, щит, компьютер, помехи) сравнивается с учётом того,
 * что прежний компонент из него уйдёт: поменять тяжёлую броню на лёгкую можно всегда.
 */
export function fitsMore(
  hull: ShipHull,
  picks: Pick[],
  component: ShipComponentOption,
  racePercent: { attack: number; defense: number },
): boolean {
  const now = totals(hull, picks, racePercent);
  const replaced = isSingle(component.slot)
    ? picks.filter((pick) => pick.component.slot === component.slot)
    : [];
  const freed = replaced.reduce((total, pick) => total + pickSpace(pick, hull) * pick.count, 0);
  const next = componentSpace(component, hull);
  // Место считается ПОСЛЕ добавления: модуль, меняющий вместимость (боевые отсеки), даёт
  // её сам себе, и без пересчёта он не влезал бы в корабль, который сам же и расширяет.
  const after = totals(hull, [...picks.filter((pick) => !replaced.includes(pick)),
    { component, count: 1, modifications: [] }], racePercent);
  return now.spaceUsed - freed + next <= after.space;
}

/** Характеристики проекта — те же формулы, что на сервере. */
export function totals(
  hull: ShipHull,
  picks: Pick[],
  racePercent: { attack: number; defense: number },
): Totals {
  const space = Math.trunc((hull.space * (100 + sum(picks, 'SPACE_PERCENT'))) / 100);
  // Место и цена берутся у строки состава, а не у компонента: модификация ствола меняет
  // и то и другое — п. 8.
  const spaceUsed = picks.reduce((total, pick) => total + pickSpace(pick, hull) * pick.count, 0);
  const cost = picks.reduce((total, pick) => total + pickCost(pick, hull) * pick.count, hull.cost);

  const armour = Math.trunc(
    (sum(picks, 'ARMOUR') * hull.systemFactor * (100 + sum(picks, 'ARMOUR_PERCENT'))) / 100,
  );

  return {
    space,
    spaceUsed,
    cost,
    structure: Math.trunc((hull.structure * (100 + sum(picks, 'STRUCTURE_PERCENT'))) / 100),
    armour,
    shield: sum(picks, 'SHIELD') * hull.systemFactor,
    speed: sum(picks, 'SPEED'),
    combatSpeed: sum(picks, 'COMBAT_SPEED'),
    attack: Math.trunc(
      (salvo(picks) * (100 + sum(picks, 'ATTACK_PERCENT') + racePercent.attack)) / 100,
    ),
    // Уклонение корпуса: по крупному кораблю попадают чаще — п. 8.
    defense: Math.trunc(
      ((100 - hull.hitChancePercent + sum(picks, 'DEFENSE')) * (100 + racePercent.defense)) / 100,
    ),
    missileEvasion: sum(picks, 'MISSILE_EVASION'),
    troops: sum(picks, 'TROOPS'),
    command: hull.command,
  };
}

/** Названия гнёзд по-русски. */
export const SLOT_NAMES: Record<string, Key> = {
  ENGINE: 'design.slotName.ENGINE',
  ARMOR: 'design.slotName.ARMOR',
  SHIELD: 'design.slotName.SHIELD',
  COMPUTER: 'design.slotName.COMPUTER',
  ECM: 'design.slotName.ECM',
  WEAPON: 'design.slotName.WEAPON',
  SPECIAL: 'design.slotName.SPECIAL',
};

/** В гнездо влезает один компонент: двигатель, броня, щит, компьютер, помехи. */
export function isSingle(slot: string): boolean {
  return slot !== 'WEAPON' && slot !== 'SPECIAL';
}

/** Готовый проект — обратно в состав для правки: ячейку переписывают, а не создают с нуля. */
export function picksOf(
  design: ShipDesign,
  components: ShipComponentOption[],
  modifications: WeaponModification[] = [],
): Pick[] {
  const byCode = new Map(components.map((component) => [component.code, component]));
  const modByCode = new Map(modifications.map((mod) => [mod.code, mod]));
  return design.components
    .map((part) => ({
      component: byCode.get(part.code),
      count: part.count,
      modifications: (part.modifications ?? [])
        .map((code) => modByCode.get(code))
        .filter((mod): mod is WeaponModification => mod !== undefined),
    }))
    .filter((pick): pick is Pick => pick.component !== undefined);
}
