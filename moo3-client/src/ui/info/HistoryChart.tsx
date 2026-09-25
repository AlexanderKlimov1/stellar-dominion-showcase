import type { EmpireProfile } from '../../api/types';
import type { Metric } from './infoMetrics';
import { t } from '../../i18n';

/** Поле графика в своих единицах: SVG тянется по ширине окна, координаты не зависят от неё. */
const WIDTH = 1000;
const HEIGHT = 380;
const PADDING = { left: 64, right: 16, top: 16, bottom: 28 };

/**
 * График летописи империй — п. 11.1, сердце окна Information MOO II.
 *
 * Линия на империю, своя — толще прочих: в оригинале игрок именно так и смотрит, обгоняет
 * он соседей или отстаёт. Цвет линии — цвет империи на карте, чтобы не заводить второй
 * язык обозначений.
 *
 * Рисуется своим SVG, без библиотеки графиков: зависимости клиента ставит `setup.cmd`, и
 * ради одной ломаной новую заводить незачем. Масштаб общий на все линии — иначе сравнивать
 * было бы нечего, а это единственное, ради чего график и нужен.
 */
export function HistoryChart({
  empires,
  metric,
}: {
  empires: EmpireProfile[];
  metric: Metric;
}) {
  const drawn = empires.filter((empire) => empire.history.length > 0);
  if (drawn.length === 0) {
    return (
      <p className="border border-space-700 px-3 py-6 text-center text-xs text-ink-dim">
        {t('history.empty')}
      </p>
    );
  }

  const turns = drawn.flatMap((empire) => empire.history.map((point) => point.turn));
  const firstTurn = Math.min(...turns);
  const lastTurn = Math.max(...turns);
  const values = drawn.flatMap((empire) => empire.history.map(metric.value));
  // Ноль на оси всегда: линия, висящая в воздухе, обманывает — рост от 100 до 110
  // выглядел бы как рост втрое.
  const top = Math.max(1, ...values);

  // Единственный замер ставится посередине поля, а не в его левый угол: на краю он
  // читается как обрезанный график, а не как «пока одна точка».
  const x = (turn: number): number =>
    lastTurn === firstTurn
      ? (PADDING.left + WIDTH - PADDING.right) / 2
      : PADDING.left +
        ((turn - firstTurn) / (lastTurn - firstTurn)) * (WIDTH - PADDING.left - PADDING.right);
  const y = (value: number): number =>
    HEIGHT - PADDING.bottom - (value / top) * (HEIGHT - PADDING.top - PADDING.bottom);

  // Четыре линейки по высоте: реже — не прочесть значение, чаще — рябит.
  const grid = [0, 0.25, 0.5, 0.75, 1].map((share) => share * top);

  return (
    <div className="border border-space-700 bg-space-950/60 p-2">
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="w-full" role="img"
           aria-label={t('history.aria', { metric: t(metric.label) })}>
        {grid.map((value) => (
          <g key={value}>
            <line
              x1={PADDING.left}
              x2={WIDTH - PADDING.right}
              y1={y(value)}
              y2={y(value)}
              stroke="currentColor"
              className="text-space-700"
              strokeWidth={1}
            />
            <text
              x={PADDING.left - 8}
              y={y(value) + 4}
              textAnchor="end"
              className="fill-ink-dim"
              fontSize={11}
            >
              {metric.format(Math.round(value))}
            </text>
          </g>
        ))}

        {drawn.map((empire) => {
          const line = empire.history
            .map((point, index) => `${index === 0 ? 'M' : 'L'}${x(point.turn)},${y(metric.value(point))}`)
            .join(' ');
          const last = empire.history[empire.history.length - 1];
          return (
            <g key={empire.playerId}>
              <path
                d={line}
                fill="none"
                stroke={empire.color}
                strokeWidth={empire.own ? 3 : 1.5}
                // Чужие линии тусклее своей: своя — то, что игрок ищет глазами первым.
                opacity={empire.own ? 1 : 0.75}
              />
              {/*
                Точка на конце линии. Она же спасает первый ход: из одного замера ломаная
                не рисуется вовсе, и график выглядел бы пустым, хотя данные есть.
              */}
              <circle
                cx={x(last.turn)}
                cy={y(metric.value(last))}
                r={empire.own ? 4 : 3}
                fill={empire.color}
                opacity={empire.own ? 1 : 0.75}
              />
            </g>
          );
        })}

        {lastTurn === firstTurn ? (
          <text
            x={x(firstTurn)}
            y={HEIGHT - 8}
            textAnchor="middle"
            className="fill-ink-dim"
            fontSize={11}
          >
            {t('history.turn', { n: firstTurn })}
          </text>
        ) : (
          <>
            <text x={PADDING.left} y={HEIGHT - 8} className="fill-ink-dim" fontSize={11}>
              {t('history.turn', { n: firstTurn })}
            </text>
            <text
              x={WIDTH - PADDING.right}
              y={HEIGHT - 8}
              textAnchor="end"
              className="fill-ink-dim"
              fontSize={11}
            >
              {t('history.turn', { n: lastTurn })}
            </text>
          </>
        )}
      </svg>

      <ul className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-11">
        {drawn.map((empire) => {
          const last = empire.history[empire.history.length - 1];
          return (
            <li key={empire.playerId} className="flex items-center gap-2">
              <span className="inline-block h-2 w-4" style={{ backgroundColor: empire.color }} />
              <span className={empire.own ? 'text-ink-bright' : 'text-ink-soft'}>
                {empire.raceName ?? empire.name}
                {empire.own ? t('history.you') : ''}
              </span>
              <span className="text-ink-faint">{metric.format(metric.value(last))}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
