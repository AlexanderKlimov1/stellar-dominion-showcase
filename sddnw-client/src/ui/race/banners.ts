import type { Key } from '../../i18n';

/** Знамя империи — так его называет сервер (`BannerColor`), п. 3.2. */
export type BannerCode =
  | 'RED' | 'YELLOW' | 'GREEN' | 'SILVER' | 'BLUE' | 'BROWN' | 'PURPLE' | 'ORANGE';

/**
 * Восемь знамён MOO II — п. 3.2, в порядке оригинала.
 *
 * Цвета повторяют серверное `BannerColor` слово в слово: экран выбора рисует знамя ДО
 * партии, когда спросить у сервера нечего, а игрок обязан увидеть ровно тот цвет, каким
 * его империя потом встанет на карте. Сервер принимает только код, поэтому разъедься
 * оттенки — неправдой станет лишь картинка выбора, а не цвет империи.
 */
export const BANNERS: { code: BannerCode; hex: string; label: Key }[] = [
  { code: 'RED', hex: '#ff5f5f', label: 'banner.RED' },
  { code: 'YELLOW', hex: '#ffd447', label: 'banner.YELLOW' },
  { code: 'GREEN', hex: '#57d977', label: 'banner.GREEN' },
  { code: 'SILVER', hex: '#c9d1dc', label: 'banner.SILVER' },
  { code: 'BLUE', hex: '#4fa3ff', label: 'banner.BLUE' },
  { code: 'BROWN', hex: '#b07a4a', label: 'banner.BROWN' },
  { code: 'PURPLE', hex: '#b56cff', label: 'banner.PURPLE' },
  { code: 'ORANGE', hex: '#ff9a3c', label: 'banner.ORANGE' },
];

/** Занято ли знамя: цвета приходят строкой, регистр букв у них не значит ничего. */
export const bannerTaken = (hex: string, takenColors: string[]): boolean =>
  takenColors.some((color) => color.toLowerCase() === hex.toLowerCase());
