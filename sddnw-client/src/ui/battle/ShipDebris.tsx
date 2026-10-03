import { useEffect, useState } from 'react';
import { CELL, fieldShipSize } from './battleVisuals';

/**
 * Гибель корабля — п. 8: огненный шар, ударная волна и обломки корпуса.
 *
 * Обломки — металл корпуса (градиент `sa-hull` из `ui/ship/shipArt.tsx`, он лежит в
 * `<defs>` поля боя) и пара кусков цвета стороны: корабль нарисован металлом с полосой
 * своей окраски, и разлетаться ему пристало тем же, а не цветными квадратами. Куски
 * получают случайное направление, поворот и скорость, так что два взрыва не выглядят
 * одинаково; жребий бросается один раз, при рождении взрыва, и на перерисовки не влияет.
 * Размер шара и волны — от корпуса: Левиафан гибнет заметно громче фрегата.
 *
 * Анимация целиком на CSS: браузер считает её сам, без кадрового цикла в React. По её
 * концу обломки убираются — держать погибший корабль на поле незачем.
 */

/** Сколько обломков даёт один корабль. */
const PIECES = 11;

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
    const full = fieldShipSize(hullSize).length;
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
      {/*
        Огненный шар и ударная волна: без них взрыв читается как «фигурка распалась».
        Волна уходит шире шара и гаснет медленнее — так и выглядит взрыв в пустоте.
      */}
      <circle
        cx={centreX}
        cy={centreY}
        r={fieldShipSize(hullSize).length * 0.55}
        fill="none"
        stroke="#ffd9a0"
        strokeWidth={2}
        style={{
          animation: `sddnw-shockwave ${LIFETIME_MS}ms ease-out forwards`,
          transformBox: 'fill-box',
          transformOrigin: 'center',
        }}
      />
      <circle
        cx={centreX}
        cy={centreY}
        r={fieldShipSize(hullSize).length * 0.6}
        fill="url(#sa-core)"
        style={{
          animation: `sddnw-fireball ${LIFETIME_MS * 0.7}ms ease-out forwards`,
          transformBox: 'fill-box',
          transformOrigin: 'center',
        }}
      />
      {pieces.map((piece, index) => (
        <polygon
          key={index}
          points={`${centreX + piece.offsetX - piece.size / 2},${centreY + piece.offsetY - piece.size / 3} `
            + `${centreX + piece.offsetX + piece.size / 2},${centreY + piece.offsetY - piece.size / 2} `
            + `${centreX + piece.offsetX + piece.size / 3},${centreY + piece.offsetY + piece.size / 2} `
            + `${centreX + piece.offsetX - piece.size / 3},${centreY + piece.offsetY + piece.size / 3}`}
          // Два куска из одиннадцати — цвета стороны: это полоса окраски корпуса.
          fill={index % 5 === 0 ? color : 'url(#sa-hull)'}
          style={{
            // Точка вращения — сам кусок, иначе он крутился бы вокруг угла холста.
            transformOrigin: `${centreX + piece.offsetX}px ${centreY + piece.offsetY}px`,
            animation: `sddnw-debris ${LIFETIME_MS}ms ${piece.delay}ms ease-out forwards`,
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
