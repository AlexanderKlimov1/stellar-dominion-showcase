import { DISK_RADIUS_LY, starField, viewBasis, type ViewAngles } from './milkyWayModel';

/**
 * Звёзды фона схемы Млечного Пути — рисунок на канве, пиксель за пикселем.
 *
 * Сто тысяч точек не нарисовать ни узлами разметки, ни путями: путь из ста тысяч кусков
 * браузер разбирает по семь миллисекунд на кадр, а сто тысяч `fillRect` — по восемьдесят.
 * Поэтому звёзды кладутся прямо в растр: буфер пикселей, один `putImageData` на кадр.
 *
 * <b>Кадр считается одним проходом.</b> Поворот, проекция и запись пикселя идут в одном
 * цикле по типизированным массивам: ни промежуточных объектов, ни списков по цветам, ни
 * сортировки. Сортировать звёзды и незачем — все они в один-два пикселя, и порядок между
 * ними не виден; сортируется сотня именованных элементов, которые рисует SVG.
 *
 * <b>Свет складывается.</b> Звёзды, попавшие в один пиксель, не перекрывают друг друга, а
 * складываются с насыщением — как свет и ведёт себя. Отсюда сам собой берётся светящийся
 * центр: в балдже на пиксель приходится десяток звёзд, и он выгорает добела, а на краю
 * диска отдельные точки едва различимы. Проверка «пиксель пуст» стоит дешевле сложения, и
 * на пустом поле её и хватает — звёзд в кадре сто тысяч на миллион пикселей.
 *
 * <b>Цвет со прозрачностью посчитан заранее.</b> Тридцать шесть цветов — четыре тона
 * (голубые молодые, жёлтые, красноватые из балджа, розовые области звездообразования) на
 * три ступени яркости и три ступени глубины — лежат готовыми числами, и в цикле остаётся
 * одна выборка из таблицы.
 */

/**
 * Цвет звезды по её виду: молодая голубовато-белая, жёлтая как Солнце, старая красноватая
 * из балджа и розовая область звездообразования — те самые розовые узлы, по которым на
 * снимках узнаются рукава.
 */
const TONES = [
  [0xcf, 0xdf, 0xff],
  [0xff, 0xe9, 0xc2],
  [0xff, 0xb2, 0x8a],
  [0xff, 0x9e, 0xcb],
];

/**
 * Яркость свечения по ступени глубины: дальняя половина схемы тусклее ближней. Это
 * единственное, чем фон показывает объём, — ни размера, ни подписи у звезды нет.
 *
 * <b>Числа подняты нарочно, и поднимают они не всё подряд.</b> Свет складывается с
 * насыщением, и в рукавах он давно упёрся в белое: там на пиксель приходится десяток
 * звёзд. А между рукавами их меньше одной на пиксель, и прибавка видна целиком. Поэтому
 * от подъёма рукава почти не меняются, а межрукавный диск перестаёт быть чёрным — ровно
 * то, чем галактика на снимке отличается от чертежа спирали.
 */
const DEPTH_ALPHA = [0.31, 0.42, 0.56];

/** Во сколько раз ярче звёзды заметные и яркие светила. */
const SHINE = [1, 1.7, 2.6];

/** Сколько ступеней в каждой мерке — по ним и считается место цвета в таблице. */
const DEPTHS = DEPTH_ALPHA.length;
const SHINES = SHINE.length;

/**
 * Готовые цвета в том виде, в каком их принимает буфер: одно 32-битное число на цвет.
 *
 * Порядок байт в буфере — ABGR: на всех целевых машинах порядок little-endian, и красный
 * лежит в младшем байте.
 */
const COLORS = new Uint32Array(
  TONES.flatMap(([red, green, blue]) =>
    SHINE.flatMap((shine) =>
      DEPTH_ALPHA.map((alpha) => {
        const value = Math.min(255, Math.round(alpha * shine * 255));
        return ((value << 24) | (blue << 16) | (green << 8) | red) >>> 0;
      }),
    ),
  ),
);

/**
 * Художник поля звёзд: держит буфер пикселей под размер поля.
 *
 * Буфер живёт между кадрами: выделять мегабайты на каждый кадр вращения — это и есть та
 * работа, ради ухода от которой всё затевалось. Поле прямоугольное: оно занимает всю
 * ширину между краем экрана и панелью управления, а Галактика стоит в его середине.
 */
export interface StarPainter {
  paint(context: CanvasRenderingContext2D, view: ViewAngles): void;
}

export function createStarPainter(width: number, height: number): StarPainter {
  const buffer = new Uint32Array(width * height);
  const image = new ImageData(new Uint8ClampedArray(buffer.buffer), width, height);
  const field = starField();

  /** Складывает свет двух точек в одном пикселе — по каналам, с насыщением. */
  const add = (under: number, over: number): number => {
    const red = Math.min(255, (under & 0xff) + (over & 0xff));
    const green = Math.min(255, ((under >>> 8) & 0xff) + ((over >>> 8) & 0xff));
    const blue = Math.min(255, ((under >>> 16) & 0xff) + ((over >>> 16) & 0xff));
    const alpha = Math.min(255, (under >>> 24) + (over >>> 24));
    return ((alpha << 24) | (blue << 16) | (green << 8) | red) >>> 0;
  };

  return {
    paint(context: CanvasRenderingContext2D, view: ViewAngles): void {
      const { cosYaw, sinYaw, cosPitch, sinPitch, cosRoll, sinRoll, scale, halfX, halfY } =
        viewBasis(view, width, height);
      const { x: xs, y: ys, z: zs, tone, bright, count } = field;

      buffer.fill(0);

      for (let index = 0; index < count; index += 1) {
        const px = xs[index];
        const py = ys[index];
        const pz = zs[index];

        const spunX = px * cosYaw - py * sinYaw;
        const spun = px * sinYaw + py * cosYaw;
        const tilted = spun * cosPitch - pz * sinPitch;
        const depth = spun * sinPitch + pz * cosPitch;
        // Перспектива: ближний край крупнее дальнего — та же формула, что у toScreenWith.
        const perspective = 3 / (3 - depth / DISK_RADIUS_LY);
        // Крен — поворот готовой проекции; те же два умножения, что и в toScreenWith,
        // иначе поле звёзд и именованные элементы разъехались бы при первом же крене.
        const x = spunX * cosRoll - tilted * sinRoll;
        const y = spunX * sinRoll + tilted * cosRoll;

        const screenX = (halfX + x * scale * perspective) | 0;
        const screenY = (halfY + y * scale * perspective) | 0;
        // За краем поля точку не пишем: буфер прямоугольный, и выход за строку прыгнул бы
        // на противоположный край картинки.
        if (screenX < 0 || screenY < 0 || screenX >= width - 1 || screenY >= height - 1) {
          continue;
        }

        const level = perspective < 0.95 ? 0 : perspective < 1.1 ? 1 : 2;
        const shine = bright[index];
        const color = COLORS[(tone[index] * SHINES + shine) * DEPTHS + level];

        const at = screenY * width + screenX;
        buffer[at] = buffer[at] === 0 ? color : add(buffer[at], color);
        // Яркое светило занимает четыре пикселя: на снимках яркая звезда всегда крупнее
        // соседних, и без этого поле выглядит ровной крупой.
        if (shine === 2) {
          buffer[at + 1] = buffer[at + 1] === 0 ? color : add(buffer[at + 1], color);
          const below = at + width;
          buffer[below] = buffer[below] === 0 ? color : add(buffer[below], color);
          buffer[below + 1] = buffer[below + 1] === 0 ? color : add(buffer[below + 1], color);
        }
      }

      context.putImageData(image, 0, 0);
    },
  };
}
