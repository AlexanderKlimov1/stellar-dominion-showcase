/**
 * Значки ресурсов колонии — общая азбука экрана колонии MOO II.
 *
 * Один и тот же рисунок стоит и у строки жителей, и у величины, которую эти жители
 * дают: мешок зерна у фермеров и у еды, молоток у рабочих и у производства, микроскоп
 * у учёных и у науки. Поэтому значки живут отдельным модулем, а не рисуются в каждой
 * панели заново — иначе фермер и еда разъехались бы при первой же правке.
 *
 * Здесь же живёт и сам житель: фигурка стоит и в полосе жителей одной колонии, и в списке
 * колоний империи, где её перетаскивают из колонии в колонию (п. 4.1.1). Две копии
 * разошлись бы при первой же правке.
 *
 * Монеты значка занятия не имеют: денег колонии никто не «работает», доход считается
 * от налогов и содержания, — поэтому монеты встречаются только в выработке.
 */

import { t, type Key } from '../../i18n';

/** Что показывает значок: еда, производство, наука, деньги. */
export type ResourceKind = 'grain' | 'hammer' | 'microscope' | 'coins';

const LABELS: Record<ResourceKind, Key> = {
  grain: 'colony.resource.grain',
  hammer: 'colony.resource.hammer',
  microscope: 'colony.resource.microscope',
  coins: 'colony.resource.coins',
};

const SHAPES: Record<ResourceKind, JSX.Element> = {
  // Мешок зерна: собранная в складки горловина, пузатое тело и перетяжка поперёк.
  grain: (
    <>
      <path d="M6.2 4.4 5.2 2c1.9-.6 3.7-.6 5.6 0l-1 2.4Z" />
      <path d="M6.2 4.4C4.4 5.7 3.3 7.5 3.3 9.5c0 2.5 1.9 4 4.7 4s4.7-1.5 4.7-4c0-2-1.1-3.8-2.9-5.1Z" />
      <path d="M4.3 7.7c2.4.9 5 .9 7.4 0" />
    </>
  ),
  // Молоток: боёк и рукоять под наклоном — прямой «Т» читался бы столом, а не молотком.
  hammer: (
    <g transform="rotate(-35 8 8)">
      <path d="M4.4 2.6h7.2v3.3H4.4Z" />
      <path d="M8 5.9v7.2" />
      <path d="M6.8 13.1h2.4" />
    </g>
  ),
  // Микроскоп: тубус под наклоном, столик, дуга штатива и основание.
  microscope: (
    <>
      <path d="M6.2 2.6h3.1v5.1H6.2Z" />
      <path d="M7 7.7h1.5v1.8H7Z" />
      <path d="M4.6 9.9h6.2" />
      <path d="M9.3 4.6c2 1.5 2.5 4.3 1.1 6.4" />
      <path d="M6.2 9.9v3.3" />
      <path d="M3.4 13.4h9.2" />
    </>
  ),
  // Монеты: две монеты внахлёст лицом к нам. Стопка кружков в перспективе читалась бы
  // цилиндром базы данных, а не деньгами.
  coins: (
    <>
      <circle cx="10" cy="5.9" r="3.3" />
      <circle cx="6.2" cy="9.8" r="3.7" />
      <path d="M6.2 8.1v3.4" />
    </>
  ),
};

/**
 * Значок ресурса. Подпись обязательна: значок стоит вместо слова, и без неё строка
 * выработки читалась бы с экрана как голое число.
 */
export function ResourceIcon({
  kind,
  label,
  className = 'h-8 w-8 shrink-0 text-ink-dim',
}: {
  kind: ResourceKind;
  label?: string;
  className?: string;
}) {
  return (
    <svg
      viewBox="0 0 16 16"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.1"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={label ?? t(LABELS[kind])}
    >
      {SHAPES[kind]}
    </svg>
  );
}

/**
 * Житель колонии: фигурка в цвете расы, как колонисты на экране колонии MOO II.
 *
 * Размер задаёт тот, кто рисует: в полосе жителей одной колонии фигурка крупнее,
 * чем в списке колоний империи, где их в строке десятки.
 */
export function Colonist({
  color,
  className = 'h-[20px] w-[14px]',
}: {
  color: string;
  className?: string;
}) {
  return (
    <svg viewBox="0 0 10 14" className={className} role="img" aria-hidden="true">
      <circle cx="5" cy="3" r="2.6" fill={color} />
      <path d="M5 6.2c2.2 0 3.4 1.5 3.4 3.6V14H1.6V9.8C1.6 7.7 2.8 6.2 5 6.2Z" fill={color} />
    </svg>
  );
}
