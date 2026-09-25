import type { Battle, BattleShip } from '../../api/types';
import { ShipDebris } from './ShipDebris';
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
 * служебное стоит в полосе. Корабли пока прямоугольники, платформы — сооружениями
 * (в оригинале звёздная база и не похожа на корабль), спрайтов у игры нет, и это их
 * место: координаты и размеры менять не придётся.
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

  const width = battle.width * CELL;
  const height = battle.height * CELL;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-full w-full"
      style={{ maxHeight: '100%', background: '#05070f' }}
    >
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
          const reachableTarget =
            mine && ship !== null && !candidate.yours && inRange(battle, ship, candidate);
          const health = candidate.maxStructure > 0
            ? candidate.structure / candidate.maxStructure
            : 1;

          return (
            <g
              key={candidate.id}
              className={reachableTarget ? 'cursor-crosshair' : 'cursor-pointer'}
              onClick={() => (reachableTarget ? onFire(candidate) : undefined)}
              onContextMenu={(event) => {
                event.preventDefault();
                onScan(candidate);
              }}
            >
              {candidate.monster ? (
                /*
                  Чудище — существо, а не корабль: тело каплей и когти лучами по краю
                  (п. 11.1). Квадратом его рисовать нельзя: на поле оно стоит одно против
                  целого флота, и отличать его от чужого крейсера игрок должен с первого
                  взгляда, а не по подписи.
                */
                <g
                  stroke={sideColor(candidate)}
                  strokeWidth={2}
                  fill={sideColor(candidate)}
                  fillOpacity={0.25}
                >
                  <ellipse
                    cx={rect.x + rect.size / 2}
                    cy={rect.y + rect.size / 2}
                    rx={rect.size / 2}
                    ry={rect.size / 2.6}
                  />
                  <path
                    d={`M ${rect.x + rect.size / 2} ${rect.y + rect.size / 2}
                        l ${-rect.size / 2} ${-rect.size / 2.2}
                        M ${rect.x + rect.size / 2} ${rect.y + rect.size / 2}
                        l ${rect.size / 2} ${-rect.size / 2.2}
                        M ${rect.x + rect.size / 2} ${rect.y + rect.size / 2}
                        l ${-rect.size / 2.4} ${rect.size / 2}
                        M ${rect.x + rect.size / 2} ${rect.y + rect.size / 2}
                        l ${rect.size / 2.4} ${rect.size / 2}`}
                    fill="none"
                  />
                  <circle
                    cx={rect.x + rect.size / 2}
                    cy={rect.y + rect.size / 2}
                    r={rect.size / 7}
                    fillOpacity={0.9}
                  />
                </g>
              ) : candidate.platform ? (
                /* Платформа обороны — сооружение, а не корабль: кольцо с лучами. */
                <g
                  stroke={sideColor(candidate)}
                  strokeWidth={2}
                  fill={sideColor(candidate)}
                  fillOpacity={0.2}
                >
                  <circle
                    cx={rect.x + rect.size / 2}
                    cy={rect.y + rect.size / 2}
                    r={rect.size / 2}
                  />
                  <circle
                    cx={rect.x + rect.size / 2}
                    cy={rect.y + rect.size / 2}
                    r={rect.size / 6}
                    fillOpacity={0.7}
                  />
                  <path
                    d={`M ${rect.x + rect.size / 2} ${rect.y} v ${-rect.size / 4}
                        M ${rect.x + rect.size / 2} ${rect.y + rect.size} v ${rect.size / 4}
                        M ${rect.x} ${rect.y + rect.size / 2} h ${-rect.size / 4}
                        M ${rect.x + rect.size} ${rect.y + rect.size / 2} h ${rect.size / 4}`}
                  />
                </g>
              ) : (
                <rect
                  x={rect.x}
                  y={rect.y}
                  width={rect.size}
                  height={rect.size}
                  fill={sideColor(candidate)}
                  fillOpacity={0.75}
                  stroke={isTarget ? '#fbbf24' : '#0b1220'}
                  strokeWidth={isTarget ? 2 : 1}
                />
              )}

              {/*
                Корабль, чей ход, обведён пунктирным кольцом — как в оригинале, где свой
                корабль помечен именно кольцом, а не рамкой.
              */}
              {isCurrent ? (
                <circle
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.size / 2}
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
                  d={`M ${rect.x} ${rect.y} l ${rect.size} ${rect.size}
                      M ${rect.x + rect.size} ${rect.y} l ${-rect.size} ${rect.size}`}
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
              <title>
                {t('battle.shipTitle', { ship: shipLabel(candidate), owner: candidate.ownerName, hull: candidate.hullName ?? '', structure: candidate.structure, max: candidate.maxStructure, armour: candidate.armour, shield: candidate.shield, attack: candidate.attack, initiative: candidate.initiative, speed: candidate.speed })}
              </title>
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
