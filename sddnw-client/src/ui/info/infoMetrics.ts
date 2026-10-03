import type { EmpireHistoryPoint } from '../../api/types';
import { t, type Key } from '../../i18n';

/**
 * Величины графика окна «Инфо» — п. 11.1, кнопки вдоль низа графика окна Info MOO II.
 *
 * Руководство оригинала называет их поимённо: «вы можете выбрать (кнопками величин вдоль
 * низа графика) историю по населению, числу построек, силе флота и развитию технологий,
 * у каждой своя единица измерения». Эти четыре здесь и стоят — в том же порядке.
 *
 * Первой добавлена своя, пятая: сводная мощь империи. В оригинале её нет, но график
 * открывается именно ею — по ней сразу видно, кто кого обгоняет, а частные величины
 * смотрят потом. Считает её сервер, и по ней же империи ИИ решают, нападать ли (п. 15):
 * экран показывает игроку ровно то, по чему судит сосед.
 *
 * Отступление в единицах одно, и оно в силе флота: в MOO II корабль весит по классу
 * корпуса (фрегат 20, эсминец 40, крейсер 80, дредноут 160, титан 320, звезда смерти
 * 640), здесь — своей боевой силой, той же, по которой сходятся флоты (п. 8). Постройки
 * считаются как в оригинале — ценой: «каждое здание добавляет свою стоимость
 * производства».
 *
 * Список один на экран: и кнопки, и сам график берут подписи и способ считать отсюда —
 * иначе они разъехались бы при первой же правке.
 */
export interface Metric {
  code: string;
  /** Ключ названия величины в словаре: переводится при показе. */
  label: Key;
  /** Значение точки: то, что рисует линия. */
  value: (point: EmpireHistoryPoint) => number;
  /** Подпись значения в легенде и на оси. */
  format: (value: number) => string;
}

/** Тысячи жителей в привычном виде: 8500 тыс. — это 8,5 млн. */
const population = (value: number): string =>
  value >= 1000 ? t('metric.millions', { n: (value / 1000).toFixed(1) }) : t('metric.thousands', { n: value });

export const METRICS: Metric[] = [
  {
    code: 'might',
    label: 'metric.might',
    value: (point) => point.might,
    format: (value) => `${value}`,
  },
  {
    code: 'population',
    label: 'metric.population',
    value: (point) => point.populationK,
    format: population,
  },
  {
    code: 'buildings',
    label: 'metric.buildings',
    value: (point) => point.buildings ?? 0,
    format: (value) => t('metric.prod', { n: value }),
  },
  {
    code: 'fleetPower',
    label: 'metric.fleetPower',
    value: (point) => point.fleetPower,
    format: (value) => `${value}`,
  },
  {
    code: 'technologies',
    label: 'metric.technologies',
    value: (point) => point.technologies,
    format: (value) => `${value}`,
  },
];

/** Величина по коду; неизвестный код — первая: экран без линии не остаётся. */
export const metricByCode = (code: string): Metric =>
  METRICS.find((metric) => metric.code === code) ?? METRICS[0];
