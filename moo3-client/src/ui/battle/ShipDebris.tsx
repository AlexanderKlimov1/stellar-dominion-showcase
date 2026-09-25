import { useEffect, useState } from 'react';
import { CELL, shipSize } from './battleVisuals';

/**
 * Гибель корабля — п. 8: прямоугольник разлетается кусками и гаснет.
 *
 * Корабль пока прямоугольник, поэтому и разлетается он тем же — обломками того же цвета
 * и размера, что и он сам. Куски получают случайное направление, поворот и скорость, так
 * что два одинаковых взрыва не выглядят одинаково; когда появятся спрайты, куски станут
 * их фрагментами, а движение останется прежним.
 *
 * Анимация целиком на CSS: браузер считает её сам, без кадрового цикла в React. По её
 * концу обломки убираются — держать погибший корабль на поле незачем.
 */

/** Сколько обломков даёт один корабль. */
const PIECES = 9;

/** Сколько живёт взрыв, миллисекунды. */
const LIFETIME_MS = 900;

interface Piece {
  size: number;
  offsetX: number;
  offsetY: number;
  flyX: number;
  flyY: number;
  spin: number;
  delay: number;
}

export function ShipDebris({
  x,
  y,
  hullSize,
  color,
  onDone,
}: {
  /** Клетка, в которой корабль стоял в момент гибели. */
  x: number;
  y: number;
  hullSize: number;
  color: string;
  onDone: () => void;
}) {
  // Куски считаются один раз: пересчёт на каждой отрисовке дёргал бы их с места.
  const [pieces] = useState<Piece[]>(() => {
    const full = shipSize(hullSize);
    return Array.from({ length: PIECES }, () => {
      const angle = Math.random() * Math.PI * 2;
      const reach = full * (0.8 + Math.random() * 1.6);
      return {
        size: Math.max(2, Math.round((full / 3) * (0.4 + Math.random() * 0.8))),
        offsetX: (Math.random() - 0.5) * full * 0.6,
        offsetY: (Math.random() - 0.5) * full * 0.6,
        flyX: Math.cos(angle) * reach,
        flyY: Math.sin(angle) * reach,
        spin: (Math.random() - 0.5) * 720,
        delay: Math.random() * 90,
      };
    });
  });

  useEffect(() => {
    const timer = window.setTimeout(onDone, LIFETIME_MS + 120);
    return () => window.clearTimeout(timer);
  }, [onDone]);

  const centreX = x * CELL + CELL / 2;
  const centreY = y * CELL + CELL / 2;

  return (
    <g>
      {/* Вспышка на месте корабля: без неё взрыв читается как «фигурка распалась». */}
      <circle
        cx={centreX}
        cy={centreY}
        r={shipSize(hullSize) * 0.7}
        fill={color}
        opacity={0.55}
        style={{ animation: `moo3-flash ${LIFETIME_MS / 2}ms ease-out forwards` }}
      />
      {pieces.map((piece, index) => (
        <rect
          key={index}
          x={centreX + piece.offsetX - piece.size / 2}
          y={centreY + piece.offsetY - piece.size / 2}
          width={piece.size}
          height={piece.size}
          fill={color}
          style={{
            // Точка вращения — сам кусок, иначе он крутился бы вокруг угла холста.
            transformOrigin: `${centreX + piece.offsetX}px ${centreY + piece.offsetY}px`,
            animation: `moo3-debris ${LIFETIME_MS}ms ${piece.delay}ms ease-out forwards`,
            // Куда лететь этому куску — в переменных, чтобы кадры анимации были общими.
            ['--fly-x' as string]: `${piece.flyX}px`,
            ['--fly-y' as string]: `${piece.flyY}px`,
            ['--spin' as string]: `${piece.spin}deg`,
          }}
        />
      ))}
    </g>
  );
}
