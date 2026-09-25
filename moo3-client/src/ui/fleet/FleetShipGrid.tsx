import { CELL, shipSize } from '../battle/battleVisuals';
import { isCombat, type FleetShipSlot } from './fleetShips';

/**
 * Сетка кораблей флота — правая половина окна Fleet Operations MOO II (п. 8).
 *
 * В оригинале это поле синих клеток по четыре в ряд, в каждой силуэт корабля; отобранные
 * подсвечены рамкой, и над ними работают кнопки переброски и списания. Клеток всегда
 * ровно столько, сколько кораблей: пустые ряды в оригинале дорисованы до конца поля,
 * и здесь тоже — иначе флот из двух кораблей выглядел бы обрезком экрана, а не флотом.
 *
 * Корабль в клетке — прямоугольник размера своего корпуса, тот же, что на поле боя
 * (`battleVisuals.shipSize`): фрегат мелкий, звезда смерти во всю клетку. Спрайтов у
 * игры нет; когда появятся, они встанут в те же прямоугольники.
 */
export function FleetShipGrid({
  slots,
  selected,
  onToggle,
  onFocus,
  focused,
}: {
  slots: FleetShipSlot[];
  selected: ReadonlySet<string>;
  onToggle: (key: string) => void;
  /** Карточка слева показывает наведённый или последний тронутый корабль. */
  onFocus: (key: string) => void;
  focused: string | null;
}) {
  /*
    Ряды добираются пустыми клетками — как в оригинале, где поле всегда одного размера:
    сперва до пяти рядов по четыре (столько клеток видно в окне MOO II), а у флота крупнее
    — до конца последнего ряда, чтобы поле оставалось прямоугольным.

    Высоту ряды делят между собой (`auto-rows-fr`), а клетка растягивается по ряду, а не
    держит квадрат: поле оригинала занимает свой сегмент целиком, и на нашем широком
    экране квадратные клетки не помещались — двадцать штук уезжали под полосу прокрутки,
    и половина флота пряталась за краем. Корабль внутри клетки по-прежнему рисуется
    размером своего корпуса, так что от растяжения он не меняется.
  */
  const total = Math.max(MIN_CELLS, Math.ceil(slots.length / COLUMNS) * COLUMNS);
  const empty = total - slots.length;

  return (
    <div className="grid min-h-0 flex-1 auto-rows-fr grid-cols-4 gap-1 overflow-hidden border border-space-700 bg-space-950 p-1">
      {slots.map((slot) => (
        <ShipCell
          key={slot.key}
          slot={slot}
          selected={selected.has(slot.key)}
          focused={focused === slot.key}
          onToggle={onToggle}
          onFocus={onFocus}
        />
      ))}
      {Array.from({ length: empty }, (_, index) => (
        <div key={`empty-${index}`} className="border border-space-800/70" />
      ))}
    </div>
  );
}

/** Клеток в ряду и наименьшее число клеток поля — размеры сетки оригинала. */
const COLUMNS = 4;
const MIN_CELLS = 20;

/** Клетка сетки: силуэт корабля, номер среди однотипных и рамка отбора. */
function ShipCell({
  slot,
  selected,
  focused,
  onToggle,
  onFocus,
}: {
  slot: FleetShipSlot;
  selected: boolean;
  focused: boolean;
  onToggle: (key: string) => void;
  onFocus: (key: string) => void;
}) {
  const { ship } = slot;
  /*
    Силуэт занимает долю клетки по размеру корпуса — тот же ряд долей, что на поле боя
    (`battleVisuals`), но в процентах, а не в пикселях: клетка боя всегда 44 пикселя, а
    клетка этой сетки тянется вместе с окном, и абсолютный размер потерялся бы в ней.
    Доли важнее самих пикселей: по ним фрегат и читается мелким рядом с дредноутом.
  */
  const share = (shipSize(ship.hullSize ?? 1) / CELL) * 100;

  return (
    <button
      type="button"
      title={`${ship.designName}${ship.hullName ? ` · ${ship.hullName}` : ''}`}
      onClick={() => {
        onToggle(slot.key);
        onFocus(slot.key);
      }}
      onMouseEnter={() => onFocus(slot.key)}
      onFocus={() => onFocus(slot.key)}
      className={`relative flex h-full w-full items-center justify-center border ${
        selected
          ? 'border-accent bg-space-800'
          : focused
            ? 'border-space-500 bg-space-900'
            : 'border-space-700 bg-space-900/60 hover:border-space-500'
      }`}
    >
      <span
        aria-hidden="true"
        style={{ width: `${share}%`, height: `${share * 0.62}%` }}
        className={
          // Вспомогательный корабль серый, боевой — цвета своей империи: тот же приём,
          // что делят переключатели Support / Combat нижней полосы.
          isCombat(ship) ? 'bg-accent/80' : 'border border-slate-500 bg-slate-600/50'
        }
      />
      {/* Вытесненный проект помечен: корабли по нему летают, а строить его уже нельзя. */}
      {ship.obsolete ? (
        <span className="absolute right-0.5 top-0.5 text-9 leading-none text-warn">
          ×
        </span>
      ) : null}
      <span className="absolute bottom-0.5 left-1 text-9 leading-none text-ink-dim">
        {slot.index}
      </span>
    </button>
  );
}
