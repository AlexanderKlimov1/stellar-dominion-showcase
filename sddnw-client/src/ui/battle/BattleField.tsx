import { useState } from 'react';
import type { Battle, BattleShip } from '../../api/types';
import { ShipDebris } from './ShipDebris';
import { ShipDefs, ShipSprite } from '../ship/shipArt';
import { ShotEffect, type Shot } from './ShotEffect';
import { t } from '../../i18n';
import {
  CELL,
  currentShip,
  inRange,
  reachable,
  shipLabel,
  shipRect,
  sideColor,
} from './battleVisuals';

/**
 * Поле тактического боя — п. 8, поле боя MOO II (`docs/moo2/battle.png`).
 *
 * Поле занимает весь экран над нижней полосой: ни рамки, ни заголовка у него нет — всё
 * служебное стоит в полосе. Корабли — рисунками своих корпусов (`ui/ship/shipArt.tsx`),
 * платформы — сооружениями (в оригинале звёздная база и не похожа на корабль), чудища —
 * существами.
 *
 * Управление как в оригинале: ходит корабль, чья очередь, подсвеченная клетка — перелёт,
 * чужой корабль в пределах залпа — цель. <b>Правый щелчок по кораблю — осмотр</b>, тот
 * самый `SCAN`: так к нему быстрее, чем через кнопку.
 *
 * Сетка и подсветка ходов включаются в настройках боя (`BattleOptions`) — как
 * `DISPLAY GRID` и `SHOW LEGAL MOVES` оригинала; по умолчанию сетки нет, как и там.
 */
export function BattleField({
  battle,
  weaponRows,
  selectedTargetId,
  debris,
  shots,
  grid,
  showMoves,
  planet,
  onMove,
  onFire,
  onScan,
  onDebrisDone,
  onShotDone,
}: {
  battle: Battle;
  /** Выбранные строки оружия корабля, чей ход; пусто — всё. По ним прицел меряет дальность. */
  weaponRows: number[] | null;
  selectedTargetId: string | null;
  /** Погибшие корабли, чьи обломки ещё летят. */
  debris: { id: string; x: number; y: number; hullSize: number; color: string }[];
  /** Залпы, которые сейчас летят: по ним и видно, кто в кого стреляет. */
  shots: Shot[];
  /** Настройка `DISPLAY GRID`: клетки поля видны. */
  grid: boolean;
  /** Настройка `SHOW LEGAL MOVES`: куда дойдёт корабль, чей ход. */
  showMoves: boolean;
  /**
   * Колония, за которую идёт бой, — п. 11: планета стоит НА ПОЛЕ у края обороняющегося
   * (`docs/moo2/battle-planet.png`). Пусто — бой в пустоте, и планеты нет.
   */
  planet: { name: string; color: string } | null;
  onMove: (x: number, y: number) => void;
  onFire: (target: BattleShip) => void;
  /** Осмотр корабля — правым щелчком по нему. */
  onScan: (ship: BattleShip) => void;
  onDebrisDone: (id: string) => void;
  onShotDone: (id: string) => void;
}) {
  const ship = currentShip(battle);
  const mine = ship !== null && ship.yours && battle.state === 'IN_PROGRESS';
  const cells = mine && ship && showMoves ? reachable(battle, ship) : new Set<string>();
  /** Чужой корабль под указателем: на нём стоит прицел. */
  const [aimed, setAimed] = useState<string | null>(null);

  const width = battle.width * CELL;
  const height = battle.height * CELL;

  // Середина строя каждой стороны: к чужой середине корабль и повёрнут носом.
  const alive = battle.ships.filter((candidate) => !candidate.destroyed);
  const middle = (yours: boolean) => {
    const side = alive.filter((candidate) => candidate.yours === yours);
    return side.length === 0 ? null : side.reduce((sum, candidate) => sum + candidate.x, 0) / side.length;
  };
  const middles = { yours: middle(true), theirs: middle(false) };
  const facesLeft = (candidate: BattleShip) => {
    const enemy = candidate.yours ? middles.theirs : middles.yours;
    return enemy !== null && enemy < candidate.x;
  };

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-full w-full"
      style={{ maxHeight: '100%', background: '#05070f' }}
    >
      <defs>
        <ShipDefs />
      </defs>
      {grid ? (
        <g stroke="#16203a">
          {Array.from({ length: battle.width + 1 }, (_, index) => (
            <line key={`v${index}`} x1={index * CELL} y1={0} x2={index * CELL} y2={height} />
          ))}
          {Array.from({ length: battle.height + 1 }, (_, index) => (
            <line key={`h${index}`} x1={0} y1={index * CELL} x2={width} y2={index * CELL} />
          ))}
        </g>
      ) : null}

      {/*
        Планета обороняющегося у правого края — п. 11. Она не фон: за ней край поля, и
        именно к ней пробивается нападающий, чтобы высадить десант.
      */}
      {planet ? (
        <g>
          <circle
            cx={width - CELL * 1.6}
            cy={height / 2}
            r={CELL * 2.2}
            fill={planet.color}
            fillOpacity={0.85}
          />
          <circle
            cx={width - CELL * 1.6}
            cy={height / 2}
            r={CELL * 2.2}
            fill="#05070f"
            fillOpacity={0.35}
            style={{ clipPath: 'inset(0 0 0 50%)' }}
          />
          <text
            x={width - CELL * 1.6}
            y={height / 2 + CELL * 3}
            textAnchor="middle"
            fill="#7d8aa3"
            fontSize={CELL * 0.4}
          >
            {planet.name}
          </text>
        </g>
      ) : null}

      {/* Куда дойдёт корабль, чей ход: клетки кликабельны. */}
      {mine
        ? Array.from(cells).map((cell) => {
            const [x, y] = cell.split(':').map(Number);
            return (
              <rect
                key={`m${cell}`}
                x={x * CELL}
                y={y * CELL}
                width={CELL}
                height={CELL}
                fill="#7dd3fc"
                opacity={0.14}
                className="cursor-pointer"
                onClick={() => onMove(x, y)}
              />
            );
          })
        : null}

      {battle.ships
        .filter((candidate) => !candidate.destroyed)
        .map((candidate) => {
          const rect = shipRect(candidate);
          const isCurrent = candidate.id === battle.currentShipId;
          const isTarget = candidate.id === selectedTargetId;
          const enemy = mine && ship !== null && !candidate.yours && !ship.fired;
          const reachableTarget = enemy && inRange(battle, ship, candidate, weaponRows);
          const health = candidate.maxStructure > 0
            ? candidate.structure / candidate.maxStructure
            : 1;

          return (
            <g
              key={candidate.id}
              // Системный курсор прячется над целью: вместо него стоит прицел поля.
              className={enemy ? 'cursor-none' : 'cursor-pointer'}
              onMouseEnter={() => setAimed(candidate.id)}
              onMouseLeave={() => setAimed((current) => (current === candidate.id ? null : current))}
              onClick={() => (reachableTarget ? onFire(candidate) : undefined)}
              onContextMenu={(event) => {
                event.preventDefault();
                onScan(candidate);
              }}
            >
              {/*
                Корабль рисунком своего корпуса (`ui/ship/shipArt.tsx`): у кораблей силуэт
                класса, у платформ сооружение, у чудищ — существо. Нос смотрит на
                середину чужого строя: в оригинале флоты стоят друг к другу лицом.
              */}
              <ShipSprite
                hullCode={candidate.hullCode}
                hullSize={candidate.hullSize}
                color={sideColor(candidate)}
                cx={rect.x + rect.size / 2}
                cy={rect.y + rect.height / 2}
                size={rect.size}
                height={rect.height}
                left={facesLeft(candidate)}
              />
              {isTarget ? (
                <circle
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={rect.size * 0.62}
                  fill="none"
                  stroke="#fbbf24"
                  strokeWidth={2}
                />
              ) : null}

              {/*
                Корабль, чей ход, обведён пунктирным кольцом — как в оригинале, где свой
                корабль помечен именно кольцом, а не рамкой.
              */}
              {isCurrent ? (
                <circle
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={rect.size * 0.75}
                  fill="none"
                  stroke="#fde68a"
                  strokeWidth={2}
                  strokeDasharray="4 4"
                />
              ) : null}

              {/*
                Разбитый двигатель — п. 8: корабль неподвижен и с поля уже не уйдёт.
                Крест по борту виден и тогда, когда карточка показывает другой корабль:
                решая, кого добивать, игрок смотрит на поле, а не в ящик систем.
              */}
              {candidate.engineWrecked ? (
                <path
                  d={`M ${rect.x} ${rect.y} l ${rect.size} ${rect.height}
                      M ${rect.x + rect.size} ${rect.y} l ${-rect.size} ${rect.height}`}
                  stroke="#f87171"
                  strokeWidth={2}
                  fill="none"
                />
              ) : null}

              {/* Полоска прочности над кораблём. */}
              <rect x={rect.x} y={rect.y - 5} width={rect.size} height={2} fill="#1f2937" />
              <rect
                x={rect.x}
                y={rect.y - 5}
                width={Math.max(0, rect.size * health)}
                height={2}
                fill={health > 0.5 ? '#4ade80' : health > 0.25 ? '#facc15' : '#f87171'}
              />

              {/* Кого можно взять целью — красная рамка по клетке, как в оригинале. */}
              {reachableTarget ? (
                <rect
                  x={candidate.x * CELL + 1}
                  y={candidate.y * CELL + 1}
                  width={CELL - 2}
                  height={CELL - 2}
                  fill="none"
                  stroke="#f87171"
                  strokeDasharray="3 3"
                  opacity={0.7}
                />
              ) : null}
              {/*
                Прицел — п. 8, как в оригинале: над чужим кораблём указатель становится
                прицелом, и прицел говорит, ДОСТАНЕТ ли выбранное оружие. Достаёт — зелёное
                кольцо с перекрестьем; нет — красное, перечёркнутое. Ракеты достают всегда.
              */}
              {enemy && aimed === candidate.id ? (
                <Crosshair
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={Math.max(CELL * 0.55, rect.size * 0.7)}
                  ok={reachableTarget}
                />
              ) : null}
              <title>
                {t('battle.shipTitle', { ship: shipLabel(candidate), owner: candidate.ownerName, hull: candidate.hullName ?? '', structure: candidate.structure, max: candidate.maxStructure, armour: candidate.armour, shield: candidate.shield, attack: candidate.attack, initiative: candidate.initiative, speed: candidate.speed })}
              </title>
            </g>
          );
        })}

      {/*
        Ракеты в полёте (backlog-promo, пункт 30): залп, не долетевший в круг пуска, стоит на
        поле, пока не долетит или не кончится топливо. Носом к цели — видно, в кого он идёт, —
        и числом ракет рядом. Появляется с задержкой: сперва его приносит анимация пуска.
      */}
      {(battle.missiles ?? []).map((missile) => {
        const shooter = battle.ships.find((candidate) => candidate.id === missile.shooterShipId);
        const target = battle.ships.find((candidate) => candidate.id === missile.targetShipId);
        const color = shooter ? sideColor(shooter) : '#f87171';
        const cx = missile.x * CELL + CELL / 2;
        const cy = missile.y * CELL + CELL / 2;
        const angle = target
          ? (Math.atan2(target.y - missile.y, target.x - missile.x) * 180) / Math.PI
          : 0;
        return (
          <g key={`missile-${missile.id}`} style={{ animation: 'sddnw-missile-wait 900ms ease-out both' }}>
            <g transform={`translate(${cx} ${cy}) rotate(${angle})`}>
              <circle cx={-12} cy={0} r={5} fill="#ffb347" opacity={0.5}
                style={{ animation: 'sddnw-engine 380ms ease-in-out infinite alternate' }} />
              <polygon points="-8,-5 -4,-2.6 -4,2.6 -8,5" fill="#8d877c" />
              <rect x={-8} y={-2.8} width={17} height={5.6} rx={2.6} fill="#d6d0c2" stroke="#4c4841" strokeWidth={0.6} />
              <polygon points="9,-2.8 15,0 9,2.8" fill={color} />
            </g>
            {missile.count > 1 ? (
              <text x={cx + 10} y={cy - 9} fontSize={CELL * 0.32} fill={color} fontWeight={700}>
                ×{missile.count}
              </text>
            ) : null}
            <title>{t('battle.missileTitle', { count: missile.count, fuel: missile.fuel })}</title>
          </g>
        );
      })}

      {/* Выстрелы: поверх кораблей, чтобы линия огня читалась через весь строй. */}
      {shots.map((shot) => (
        <ShotEffect key={shot.id} shot={shot} onDone={() => onShotDone(shot.id)} />
      ))}

      {/* Обломки погибших: летят и гаснут, потом убираются сами. */}
      {debris.map((piece) => (
        <ShipDebris
          key={piece.id}
          x={piece.x}
          y={piece.y}
          hullSize={piece.hullSize}
          color={piece.color}
          onDone={() => onDebrisDone(piece.id)}
        />
      ))}
    </svg>
  );
}

/**
 * Прицел над целью: кольцо, перекрестье с зазором посередине и метки по сторонам. Цвет
 * говорит всё: зелёный — выбранное оружие достаёт, красный — нет, и тогда прицел ещё и
 * перечёркнут, чтобы разницу видел и тот, кто плохо различает цвета.
 */
function Crosshair({ cx, cy, r, ok }: { cx: number; cy: number; r: number; ok: boolean }) {
  const color = ok ? '#4ade80' : '#f87171';
  const gap = r * 0.35;
  return (
    <g pointerEvents="none" stroke={color} strokeWidth={2} fill="none">
      <circle cx={cx} cy={cy} r={r} strokeDasharray={ok ? undefined : '6 4'} />
      <line x1={cx - r - 6} y1={cy} x2={cx - gap} y2={cy} />
      <line x1={cx + gap} y1={cy} x2={cx + r + 6} y2={cy} />
      <line x1={cx} y1={cy - r - 6} x2={cx} y2={cy - gap} />
      <line x1={cx} y1={cy + gap} x2={cx} y2={cy + r + 6} />
      {ok ? (
        <circle cx={cx} cy={cy} r={2.5} fill={color} stroke="none" />
      ) : (
        <path d={`M ${cx - r * 0.7} ${cy - r * 0.7} L ${cx + r * 0.7} ${cy + r * 0.7}`} strokeWidth={2.5} />
      )}
    </g>
  );
}
