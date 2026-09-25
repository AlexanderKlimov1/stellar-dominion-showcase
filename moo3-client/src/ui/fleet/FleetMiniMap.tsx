import type { FleetGroup, GalaxyMap, StarSystem } from '../../api/types';

/**
 * Карта галактики в левом верхнем углу экрана флота — п. 8, п. 15.
 *
 * В окне Fleet Operations MOO II это первое, что видит игрок: звёздное поле, на нём
 * выбранный флот и пунктир его курса. Отсюда флоту и назначают цель — нажатием по
 * звезде, а не выбором названия из списка: у звезды есть место в галактике, и «лететь
 * вон туда, за край скопления» читается с карты, а не из выпадающего списка.
 *
 * Своих координат у флота нет: сервер шлёт систему вылета, цель и сроки — где корабли
 * сейчас, считает клиент по номерам ходов. Тем же способом это делает и большая карта,
 * поэтому значок флота здесь стоит там же, где там.
 *
 * Звёзды рисуются все, как и на большой карте: светило видно из любой точки галактики.
 * А вот **что** в системе — известно только по разведке, поэтому у неразведанной звезды
 * нет ни названия, ни хозяина, и подпись ей не выводится вовсе.
 */
export function FleetMiniMap({
  map,
  fleet,
  turn,
  target,
  reachable,
  onPick,
}: {
  map: GalaxyMap | null;
  /** Флот, чей курс показан; null — флотов у империи нет. */
  fleet: FleetGroup | null;
  turn: number;
  /** Выбранная игроком цель — куда уйдут отобранные корабли. */
  target: string | null;
  /** Куда хватает дальности: недостижимые звёзды помечены и цели не принимают — п. 8. */
  reachable: ReadonlySet<string>;
  onPick: (systemId: string) => void;
}) {
  if (!map) {
    return <div className="flex-1 border border-space-700 bg-space-950" />;
  }

  const systems = map.systems;
  const from = systems.find((system) => system.id === fleet?.starSystemId) ?? null;
  const to = systems.find((system) => system.id === (target ?? fleet?.targetSystemId)) ?? null;
  const here = fleetPoint(from, to, fleet, turn);

  return (
    <svg
      viewBox={`0 0 ${map.widthParsecs} ${map.heightParsecs}`}
      preserveAspectRatio="xMidYMid meet"
      className="min-h-0 w-full flex-1 border border-space-700 bg-space-950"
    >
      {/*
        Курс: от системы вылета к цели по прямой — так флот и летит. Пунктир зелёный,
        когда цель достижима, и красный, когда дальности не хватит: то же правило и те же
        цвета, что на большой карте, — игрок не должен переучиваться, переходя на экран.
      */}
      {from && to ? (
        <line
          x1={from.x}
          y1={from.y}
          x2={to.x}
          y2={to.y}
          stroke={reachable.has(to.id) ? '#4ade80' : '#f87171'}
          strokeWidth={map.widthParsecs / 400}
          strokeDasharray={`${map.widthParsecs / 90} ${map.widthParsecs / 140}`}
        />
      ) : null}

      {systems.map((system) => (
        <Star
          key={system.id}
          system={system}
          map={map}
          home={system.id === fleet?.starSystemId}
          picked={system.id === target}
          reachable={reachable.has(system.id)}
          onPick={onPick}
        />
      ))}

      {/*
        Сам флот — треугольник, тот же значок, что и на большой карте. Он стоит там, где
        корабли сейчас: в системе вылета, если флот стоит, и на своей доле пути, если идёт.
      */}
      {here ? (
        <polygon
          points={trianglePoints(here.x, here.y, map.widthParsecs / 90)}
          className="fill-accent"
        />
      ) : null}
    </svg>
  );
}

/** Звезда: точка своего цвета, кружок отбора и подпись у разведанной. */
function Star({
  system,
  map,
  home,
  picked,
  reachable,
  onPick,
}: {
  system: StarSystem;
  map: GalaxyMap;
  home: boolean;
  picked: boolean;
  reachable: boolean;
  onPick: (systemId: string) => void;
}) {
  const radius = map.widthParsecs / (system.special ? 130 : 200);
  return (
    <g className="cursor-pointer" onClick={() => onPick(system.id)}>
      {/*
        Прозрачный круг под звездой — площадь нажатия: сама точка меньше пары пикселей
        на экране, и попасть по ней мышью нельзя.
      */}
      <circle cx={system.x} cy={system.y} r={map.widthParsecs / 50} fill="transparent" />
      <circle cx={system.x} cy={system.y} r={radius} fill={system.colorHex} />
      {home || picked ? (
        <circle
          cx={system.x}
          cy={system.y}
          r={radius * 2.6}
          fill="none"
          stroke={picked ? (reachable ? '#4ade80' : '#f87171') : '#7dd3fc'}
          strokeWidth={map.widthParsecs / 500}
        />
      ) : null}
      {system.explored && system.name ? (
        <text
          x={system.x}
          y={system.y - radius * 3}
          textAnchor="middle"
          fill={picked || home ? '#e2e8f0' : '#64748b'}
          fontSize={map.widthParsecs / 75}
        >
          {system.name}
        </text>
      ) : null}
    </g>
  );
}

/**
 * Где флот сейчас: в системе вылета, если стоит, и на своей доле пути, если идёт.
 *
 * Доля считается по номерам ходов вылета и прибытия — тем же способом, что и на большой
 * карте: сервер шлёт маршрут и сроки, а не координаты.
 */
function fleetPoint(
  from: StarSystem | null | undefined,
  to: StarSystem | null | undefined,
  fleet: FleetGroup | null,
  turn: number,
): { x: number; y: number } | null {
  if (!from || !fleet) {
    return null;
  }
  if (!fleet.targetSystemId || !to || fleet.departureTurn == null || fleet.arrivalTurn == null) {
    return { x: from.x, y: from.y };
  }
  const total = Math.max(1, fleet.arrivalTurn - fleet.departureTurn);
  const done = Math.min(1, Math.max(0, (turn - fleet.departureTurn) / total));
  return { x: from.x + (to.x - from.x) * done, y: from.y + (to.y - from.y) * done };
}

/** Треугольник значка флота остриём вверх — тот же силуэт, что на большой карте. */
function trianglePoints(x: number, y: number, size: number): string {
  return `${x},${y - size} ${x - size * 0.8},${y + size * 0.7} ${x + size * 0.8},${y + size * 0.7}`;
}
