import type { ReactNode } from 'react';
import { figureClass, figureIconClass, type Accent, type Tone } from './accent';

/**
 * Число на сцене — со ступенью выделения (`accent.ts`). Всякое отдельно стоящее число игры
 * рисуется этим компонентом, а не своим `<span className="text-…">`: иначе ступень снова
 * подбиралась бы по месту, и одинаковые по смыслу числа разошлись бы видом.
 *
 * `icon` — значок при числе (монеты, колос): получает размер и цвет той же ступени через
 * функцию, потому что значки игры — компоненты со своим `className`. `title` — подсказка,
 * если число без неё непонятно. Числа ВНУТРИ фразы перевода («8 из 16 жителей») сюда не
 * идут: фраза — одна строка словаря, и рвать её ради числа значит рвать перевод.
 */
export function Figure({
  accent,
  tone,
  icon,
  title,
  className = '',
  children,
}: {
  accent: Accent;
  tone?: Tone;
  icon?: (className: string) => ReactNode;
  title?: string;
  className?: string;
  children: ReactNode;
}) {
  const number = <span className={figureClass(accent, tone)}>{children}</span>;
  if (!icon && !title && !className) {
    return number;
  }
  return (
    <span className={'inline-flex items-center gap-1 ' + className} title={title}>
      {number}
      {icon ? icon(figureIconClass(accent, tone)) : null}
    </span>
  );
}
