import Phaser from 'phaser';

/**
 * Рисунки карты галактики — звезда и фон дальних звёзд (п. 11.3, `docs/moo2/galaxy-map.png`).
 *
 * В оригинале звезда на карте — не плоский кружок, а светящийся шарик с белым сердцем,
 * цветным ореолом и четырьмя короткими лучами крестом, а поле под звёздами засыпано
 * мелкой белой пылью дальних звёзд. Плоский круг радиусом три пикселя на чёрной заливке
 * читался схемой, а не небом.
 *
 * Рисуется всё холстом один раз и ложится текстурой: звёзд на карте до сотни, пылинок —
 * тысячи, и держать объект на каждую значило бы платить за них каждый кадр. Случайность
 * здесь только считанная (`mulberry32` с постоянным зерном): карта перерисовывается при
 * каждой смене хода и окна, и пыль, перетряхнутая заново, мигала бы.
 */

/**
 * Сторона текстуры звезды в пикселях. Звезда показывается один к одному — её экранный
 * размер не зависит от масштаба (п. 11.3), — поэтому текстура и есть её размер на экране:
 * ядро в середине, лучи до краёв.
 */
export const STAR_TEXTURE_SIZE = 28;

/** Длина луча от центра, пиксели: чуть короче половины текстуры, чтобы конец не срезался. */
const RAY_LENGTH = 13;

/** Радиус ореола: мягкое свечение вокруг ядра того же цвета, что и звезда. */
const HALO_RADIUS = 7;

/** Сторона плитки фона. Плитка повторяется, и на 512 пикселях повтор глазу не заметен. */
const DUST_TILE = 512;

/** Пылинок на плитку: густо, как в оригинале, но каждая еле видна. */
const DUST_COUNT = 2000;

/** Ключ текстуры фона. */
export const DUST_TEXTURE = 'galaxy-dust';

/** Считанная случайность: одно и то же зерно — одна и та же пыль при каждой перерисовке. */
const mulberry32 = (seed: number) => (): number => {
  let t = (seed += 0x6d2b79f5);
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

const rgba = (color: Phaser.Display.Color, alpha: number): string =>
  `rgba(${color.red},${color.green},${color.blue},${alpha})`;

/** Цвет, сдвинутый к белому (доля > 0) или к чёрному (доля < 0). */
const shade = (color: Phaser.Display.Color, amount: number): Phaser.Display.Color => {
  const target = amount > 0 ? 255 : 0;
  const k = Math.abs(amount);
  const mix = (channel: number) => Math.round(channel + (target - channel) * k);
  return new Phaser.Display.Color(mix(color.red), mix(color.green), mix(color.blue));
};

/**
 * Текстура звезды своего цвета; заводится при первой нужде и дальше берётся готовой.
 *
 * Слоёв три, снизу вверх: ореол, лучи крестом и само ядро. Ядро — шар: свет падает слева
 * сверху, как и на всех рисунках игры (`buildingArt.SUN`), поэтому блик сдвинут туда, а
 * противоположный край темнее цвета звезды — этим круг и становится объёмом. Лучи
 * светлее ядра и гаснут к концам: в оригинале они белёсые, а цвет звезде даёт ореол.
 *
 * @param coreRadius радиус ядра в пикселях
 * @returns ключ текстуры
 */
export const ensureStarTexture = (
  scene: Phaser.Scene,
  colorHex: string,
  coreRadius: number,
): string => {
  const key = `galaxy-star-${colorHex.toLowerCase()}`;
  if (scene.textures.exists(key)) {
    return key;
  }
  const texture = scene.textures.createCanvas(key, STAR_TEXTURE_SIZE, STAR_TEXTURE_SIZE);
  if (!texture) {
    return key;
  }
  const ctx = texture.getContext();
  const color = Phaser.Display.Color.HexStringToColor(colorHex);
  const light = shade(color, 0.6);
  const c = STAR_TEXTURE_SIZE / 2;

  // Ореол: свечение цвета звезды, тающее к краю.
  const halo = ctx.createRadialGradient(c, c, 0, c, c, HALO_RADIUS);
  halo.addColorStop(0, rgba(color, 0.45));
  halo.addColorStop(0.45, rgba(color, 0.2));
  halo.addColorStop(1, rgba(color, 0));
  ctx.fillStyle = halo;
  ctx.fillRect(0, 0, STAR_TEXTURE_SIZE, STAR_TEXTURE_SIZE);

  // Лучи крестом: пиксельная линия, яркая у ядра и гаснущая к концу, плюс мягкая
  // подложка втрое шире, но вдвое короче — без неё луч читался бы нитью, а не светом.
  const ray = (dx: number, dy: number, length: number, width: number, alpha: number) => {
    const gradient = ctx.createLinearGradient(c, c, c + dx * length, c + dy * length);
    gradient.addColorStop(0, rgba(light, alpha));
    gradient.addColorStop(0.4, rgba(light, alpha * 0.45));
    gradient.addColorStop(1, rgba(light, 0));
    ctx.fillStyle = gradient;
    if (dx !== 0) {
      ctx.fillRect(Math.min(c, c + dx * length), c - width / 2, length, width);
    } else {
      ctx.fillRect(c - width / 2, Math.min(c, c + dy * length), width, length);
    }
  };
  for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
    ray(dx, dy, RAY_LENGTH / 2, 3, 0.35);
    ray(dx, dy, RAY_LENGTH, 1, 0.9);
  }

  // Ядро — шар со светом слева сверху.
  const core = ctx.createRadialGradient(
    c - coreRadius * 0.4, c - coreRadius * 0.4, 0,
    c, c, coreRadius,
  );
  core.addColorStop(0, 'rgba(255,255,255,1)');
  core.addColorStop(0.3, rgba(light, 1));
  core.addColorStop(0.75, rgba(color, 1));
  core.addColorStop(1, rgba(shade(color, -0.6), 1));
  ctx.fillStyle = core;
  ctx.beginPath();
  ctx.arc(c, c, coreRadius, 0, Math.PI * 2);
  ctx.fill();

  texture.refresh();
  return key;
};

/**
 * Плитка фона — дальние звёзды: белые точки в один пиксель, едва различимые, и изредка
 * точка в два пикселя поярче. Почти все белые, у немногих лёгкий голубой или жёлтый
 * оттенок — так же, как в поле оригинала. Ярких пылинок нет нарочно: фон не должен
 * спорить со звёздами игры, по которым ходят флоты.
 */
export const ensureDustTexture = (scene: Phaser.Scene): string => {
  if (scene.textures.exists(DUST_TEXTURE)) {
    return DUST_TEXTURE;
  }
  const texture = scene.textures.createCanvas(DUST_TEXTURE, DUST_TILE, DUST_TILE);
  if (!texture) {
    return DUST_TEXTURE;
  }
  const ctx = texture.getContext();
  const random = mulberry32(20260930);
  for (let i = 0; i < DUST_COUNT; i += 1) {
    const x = Math.floor(random() * DUST_TILE);
    const y = Math.floor(random() * DUST_TILE);
    const hue = random();
    const [r, g, b] = hue < 0.08 ? [190, 210, 255] : hue < 0.13 ? [255, 236, 200] : [255, 255, 255];
    const big = random() < 0.04;
    // Яркость смещена к тусклым: квадрат случайного числа кладёт большинство у нижней
    // границы, а у верхней — лишь редкие.
    const alpha = (big ? 0.3 : 0.1) + random() ** 2 * (big ? 0.3 : 0.35);
    ctx.fillStyle = `rgba(${r},${g},${b},${alpha.toFixed(3)})`;
    ctx.fillRect(x, y, big ? 2 : 1, big ? 2 : 1);
  }
  texture.refresh();
  return DUST_TEXTURE;
};
