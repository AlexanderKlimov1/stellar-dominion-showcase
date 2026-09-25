import { useEffect, useRef } from 'react';
import { createStarPainter, type StarPainter } from './milkyWayStarfield';
import type { ViewAngles } from './milkyWayModel';

/**
 * Канва со звёздами фона — нижний слой схемы Млечного Пути.
 *
 * Лежит под SVG и занимает то же поле, поэтому звёзды и рукава стоят в одних координатах:
 * у канвы своя мерка (пиксели растра), у SVG своя (`viewBox`), но пропорции у поля одни, а
 * проекция берёт масштаб по меньшей стороне — оба слоя показывают одно и то же.
 *
 * <b>Растр — ровно в пиксель экрана, без поправки на плотность.</b> Звезда занимает
 * пиксель, и на экране с двойной плотностью растр вырос бы вчетверо по площади: очищать и
 * выгружать его пришлось бы на каждом кадре, а это дороже всего остального кадра вместе
 * взятого. Подписи, кольца и рукава остаются векторными в любом масштабе: они в SVG.
 *
 * Художник (буфер пикселей) заводится заново только при смене размера поля: выделять его
 * на каждый кадр значило бы отдавать сборщику мусора по мегабайту в секунду.
 */
export function MilkyWayCanvas({
  view,
  width,
  height,
}: {
  view: ViewAngles;
  width: number;
  height: number;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const painter = useRef<StarPainter | null>(null);
  const painterSize = useRef('');

  useEffect(() => {
    const element = canvas.current;
    // Пока разметка не измерена, поля ещё нет: растр нулевой ширины браузер не заводит,
    // а рисовать в него всё равно нечего — первый кадр придёт следующим замером.
    if (!element || width < 2 || height < 2) {
      return;
    }
    if (element.width !== width || element.height !== height) {
      element.width = width;
      element.height = height;
    }
    const key = `${width}×${height}`;
    if (painter.current === null || painterSize.current !== key) {
      painter.current = createStarPainter(width, height);
      painterSize.current = key;
    }
    const context = element.getContext('2d');
    if (!context) {
      return;
    }
    painter.current.paint(context, view);
  }, [view, width, height]);

  return (
    <canvas
      ref={canvas}
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 h-full w-full"
    />
  );
}
