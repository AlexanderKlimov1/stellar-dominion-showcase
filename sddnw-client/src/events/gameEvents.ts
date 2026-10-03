import type { FleetGroup, GalaxyMap, Player, StarSystem } from '../api/types';

/**
 * Шина событий между слоем React (меню, HUD, дерево технологий, дипломатия)
 * и движком Phaser 3 (карта, юниты, бой, отрисовка).
 *
 *   React UI  --EventEmitter-->  Phaser 3
 *   Phaser 3  --EventEmitter-->  React UI
 *
 * Ни один из слоёв не держит ссылку на другой: React публикует команды,
 * сцена Phaser на них подписана, и наоборот.
 */

/** Команды React → Phaser. */
export interface UiToGameEvents {
  /** Загрузить карту галактики в сцену. */
  'map:load': { map: GalaxyMap; players: Player[] };
  /** Центрировать камеру на системе. */
  'map:focus-system': { systemId: string };
  /** Показать всю галактику целиком. */
  'map:fit': void;
  /** Показать или скрыть подписи звёзд. */
  'map:toggle-labels': { visible: boolean };
  /** Выделить систему, не двигая камеру: так возвращается выделение после перезагрузки. */
  'map:select-system': { systemId: string };
  /** Снять выделение. */
  'map:clear-selection': void;
  /**
   * Флоты игрока на карте — п. 8: у каждой системы, где стоит флот, появляется значок.
   *
   * Шлётся целиком, а не по одному флоту: флот заводится, растёт и исчезает в конце хода,
   * и разбирать разницу сцене было бы дороже, чем перерисовать десяток значков.
   *
   * Ход партии идёт вместе с флотами: по нему сцена считает, где сейчас летящий флот —
   * сервер шлёт маршрут и сроки, а не координаты (п. 8).
   */
  'map:fleets': { fleets: FleetGroup[]; color: string; turn: number };
  /**
   * Флот выбран, ждём системы назначения — п. 8: порядок MOO II, сперва корабли, потом
   * цель. Пока прицел включён, карта тянет от значка флота линию к звезде под курсором:
   * зелёную сплошную до достижимой, красную пунктирную до той, куда не хватит топлива.
   *
   * `fleetId: null` — прицел снят (нажали Esc, выбрали цель, флот исчез).
   *
   * Список достижимых систем приходит вместе с прицелом, а не спрашивается по одной:
   * дальность меряется от колоний империи и на время выбора не меняется.
   */
  'fleet:targeting': { fleetId: string | null; reachableSystemIds: string[] };
  /**
   * Сдвинуть карту на экране — на `dx`, `dy` пикселей страницы (вправо и вниз — плюс).
   *
   * Так окно «флот в пути» выводит путь флота из-под себя, когда оба левых угла карты на
   * него ложатся: карта оригинала прокручивается, и окно там путь не прячет. Сдвиги
   * складываются; `map:shift-back` возвращает карту на их сумму.
   */
  'map:shift': { dx: number; dy: number };
  /** Вернуть карту, сдвинутую `map:shift`, — окно, ради которого её сдвигали, закрыто. */
  'map:shift-back': void;
}

/** События Phaser → React. */
export interface GameToUiEvents {
  /** Сцена создана и готова принимать карту. */
  'scene:ready': void;
  /** Карта отрисована. */
  'map:rendered': { systems: number; planets: number };
  /** Игрок выбрал звёздную систему одиночным кликом. */
  'system:selected': { system: StarSystem };
  /** Игрок открыл экран звёздной системы двойным кликом по звезде. */
  'system:opened': { system: StarSystem };
  /** Курсор над системой (null — курсор ушёл со звезды). */
  'system:hovered': { system: StarSystem | null };
  /** Текущий масштаб камеры для HUD. */
  'camera:changed': { zoom: number };
  /**
   * Игрок нажал на значок флота — п. 8. Стоящему флоту выбирают систему назначения,
   * летящему открывают окно «флот в пути».
   *
   * `course` — путь летящего флота на экране, от значка до цели, в пикселях страницы: по
   * нему окно встаёт туда, где не закроет ни флот, ни звезду, куда он летит. Позиция
   * камеры есть только у сцены, поэтому и считает её сцена.
   */
  'fleet:selected': { fleetId: string; course?: ScreenCourse };
  /**
   * Система назначения выбрана — п. 8: приказ отдан, осталось отобрать корабли.
   * Приходит вместо обычного выделения звезды, пока включён прицел.
   */
  'fleet:target-picked': { fleetId: string; systemId: string };
}

export type GameEvents = UiToGameEvents & GameToUiEvents;

/** Отрезок пути флота на экране — в пикселях страницы, как у `getBoundingClientRect`. */
export interface ScreenCourse {
  from: { x: number; y: number };
  to: { x: number; y: number };
}

type Handler<T> = (payload: T) => void;

/** Минимальный типизированный EventEmitter — общий для React и Phaser. */
export class TypedEventEmitter<Events> {
  private readonly handlers = new Map<keyof Events, Set<Handler<never>>>();

  on<K extends keyof Events>(event: K, handler: Handler<Events[K]>): () => void {
    const set = this.handlers.get(event) ?? new Set();
    set.add(handler as Handler<never>);
    this.handlers.set(event, set);
    return () => this.off(event, handler);
  }

  once<K extends keyof Events>(event: K, handler: Handler<Events[K]>): () => void {
    const unsubscribe = this.on(event, (payload) => {
      unsubscribe();
      handler(payload);
    });
    return unsubscribe;
  }

  off<K extends keyof Events>(event: K, handler: Handler<Events[K]>): void {
    this.handlers.get(event)?.delete(handler as Handler<never>);
  }

  emit<K extends keyof Events>(event: K, payload: Events[K]): void {
    const set = this.handlers.get(event);
    if (!set) {
      return;
    }
    for (const handler of [...set]) {
      // Изоляция: упавший подписчик не должен лишать события остальных.
      try {
        (handler as Handler<Events[K]>)(payload);
      } catch (error) {
        console.error(`Обработчик события ${String(event)} завершился ошибкой`, error);
      }
    }
  }

  removeAll(): void {
    this.handlers.clear();
  }
}

/** Единственный экземпляр шины на приложение. */
export const gameEvents = new TypedEventEmitter<GameEvents>();
