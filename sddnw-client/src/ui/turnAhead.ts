import type { Fleet, FleetGroup, GalaxyMap, PlayerResearch, ResearchTree } from '../api/types';
import type { Key } from '../i18n';
import { turnsLeft } from './system/colonyRules';
import { targetName } from './research/researchRules';

/**
 * Что вот-вот случится — backlog-promo, пункт 4.
 *
 * Жанр держится на «ещё одном ходе», то есть на незакрытых делах: итоги хода рассказывали,
 * что СЛУЧИЛОСЬ, и молчали о том, что вот-вот случится. Если все дела закончились в один
 * ход, у игрока появляется естественная точка, чтобы встать из-за стола, — а строка «на
 * следующем ходу достроится завод, прилетит флот, прорыв на 40 %» эту точку убирает.
 *
 * Считается ЦЕЛИКОМ НА КЛИЕНТЕ и из того, что у него уже есть: сроки стройки
 * ({@link turnsLeft} — тот же счёт, что у экрана колонии), ход прилёта флотов и ход
 * исследования с шансом прорыва. Второго счёта на сервере заводить незачем: правила тут
 * не новые, это сводка уже показанных чисел.
 *
 * Сроки — «через сколько концов хода»: единица значит «в конце этого хода», то есть в
 * следующих итогах. Флот садится в конце хода прилёта (`arrivalTurn` не позже текущего).
 */

export type AheadKind = 'build' | 'fleet' | 'research';

export interface AheadItem {
  kind: AheadKind;
  /** Через сколько концов хода: 1 — в следующих итогах. */
  turns: number;
  key: Key;
  params: Record<string, string | number>;
}

export function turnAhead(
  map: GalaxyMap | null,
  fleet: Fleet | null,
  research: PlayerResearch | null,
  researchTree: ResearchTree | null,
  playerId: string | undefined,
  turn: number,
): AheadItem[] {
  const items: AheadItem[] = [];

  for (const system of map?.systems ?? []) {
    for (const planet of system.planets) {
      if (!playerId || planet.ownerPlayerId !== playerId || !planet.colony) {
        continue;
      }
      const left = turnsLeft(planet.colony);
      if (left === null) {
        continue;
      }
      items.push({
        kind: 'build',
        // Ноль — вложено уже всё: достроится в конце этого же хода.
        turns: Math.max(1, left),
        key: 'turnAhead.build',
        params: { planet: planet.name, project: planet.colony.projectName ?? '' },
      });
    }
  }

  for (const group of fleet?.fleets ?? []) {
    if (!group.targetSystemId || group.arrivalTurn === undefined) {
      continue;
    }
    items.push({
      kind: 'fleet',
      turns: Math.max(1, group.arrivalTurn - turn + 1),
      key: group.targetSystemName ? 'turnAhead.fleet' : 'turnAhead.fleetUnknown',
      params: { n: group.ships, system: group.targetSystemName ?? '' },
    });
  }

  const tech = research && researchTree ? targetName(researchTree, research) : undefined;
  if (research && tech) {
    const remaining = research.remainingPoints ?? 0;
    if (remaining <= 0) {
      items.push({
        kind: 'research',
        turns: 1,
        key: 'turnAhead.breakthrough',
        params: { tech, n: research.breakthroughPercent ?? 0 },
      });
    } else if (research.researchPerTurn > 0) {
      items.push({
        kind: 'research',
        turns: Math.ceil(remaining / research.researchPerTurn),
        key: 'turnAhead.research',
        params: { tech },
      });
    }
  }

  return items;
}

/**
 * Флоты без приказа — backlog-promo, пункт 24: стоят вне своих систем, не разведывают сами
 * и состоят из одних боевых кораблей (колониальный корабль и транспорт ждут своего дела, а
 * флот у своей колонии — это гарнизон). Это разведчики, которые долетели и стоят: новичок
 * не возвращался к ним по пять ходов, и партия затихала.
 */
export function idleFleets(map: GalaxyMap | null, fleet: Fleet | null, playerId: string | undefined): FleetGroup[] {
  if (!map || !fleet || !playerId) {
    return [];
  }
  const own = new Set(map.systems
    .filter((system) => system.planets.some((planet) => planet.ownerPlayerId === playerId))
    .map((system) => system.id));
  return fleet.fleets.filter((group) => !group.targetSystemId && !group.autoExplore
    && !own.has(group.starSystemId)
    && group.composition.length > 0
    && group.composition.every((ship) => ship.role === 'WARSHIP'));
}

/**
 * Что показать: всё, что случится на следующем ходу, а если ничего — ближайшее, с
 * числом ходов до него. Пусто — не идёт ничего вовсе, и это тоже стоит сказать.
 */
export function aheadToShow(items: AheadItem[]): { turns: number; items: AheadItem[] } | null {
  if (items.length === 0) {
    return null;
  }
  const soonest = Math.min(...items.map((item) => item.turns));
  return { turns: soonest, items: items.filter((item) => item.turns === soonest) };
}
