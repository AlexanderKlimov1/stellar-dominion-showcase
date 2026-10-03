import Phaser from 'phaser';
import { renderToStaticMarkup } from 'react-dom/server';
import { ShipDefs, ShipSprite } from '../../ui/ship/shipArt';

/**
 * Значок сторожа на карте галактики — п. 11.1.
 *
 * <b>Рисунок тот же, что на поле боя</b> (`ui/ship/shipArt.tsx`, тело чудища по коду
 * корпуса): дракон на карте и дракон в бою обязаны быть одним и тем же существом, иначе игрок
 * узнавал бы сторожа по подписи, а не по виду. Canvas Phaser SVG-разметки React не знает,
 * поэтому рисунок один раз собирается строкой (`renderToStaticMarkup`) и грузится текстурой.
 *
 * Текстура грузится асинхронно: `addBase64` отдаёт её не сразу, а событием. Значок поэтому
 * создаётся сразу, а картинку получает, когда она готова (`onReady`), — ждать загрузки ради
 * того, чтобы нарисовать звезду, карте незачем.
 */

/**
 * Сторона значка на экране в пикселях — 17, решение хозяина проекта (30.09.2026): значок
 * стоит рядом со звездой и не должен её перерастать; вдвое крупнее он спорил со звездой
 * за взгляд.
 */
export const MONSTER_ICON_SIZE = 17;

// Ключ несёт номер вида: текстура живёт в движке, пока жива страница, и значок прежнего вида
// под старым ключом остался бы на карте после правки рисунка.
const keyOf = (hull: string) => `monster-v2-${hull}`;

/**
 * Ключ текстуры тела чудища; `onReady` зовётся, когда её можно ставить (сразу — если она
 * уже есть). Загрузка идёт один раз на вид чудища за всю жизнь сцены.
 */
export function ensureMonsterTexture(scene: Phaser.Scene, hull: string, onReady: (key: string) => void): void {
  const key = keyOf(hull);
  if (scene.textures.exists(key)) {
    onReady(key);
    return;
  }
  scene.textures.once(`addtexture-${key}`, () => onReady(key));
  if (pending.has(key)) {
    return;
  }
  pending.add(key);
  // Без подложки и без каймы (решение хозяина проекта): звезду и так обводит красное кольцо
  // сторожа, и второй круг рядом с ним читался бы лишним.
  const svg = renderToStaticMarkup(
    <svg xmlns="http://www.w3.org/2000/svg" width={MONSTER_ICON_SIZE * 2} height={MONSTER_ICON_SIZE * 2}
         viewBox="-50 -50 100 100">
      <defs><ShipDefs /></defs>
      <ShipSprite hullCode={hull} hullSize={1} color="#f87171" cx={0} cy={0} size={100} />
    </svg>,
  );
  const encoded = btoa(unescape(encodeURIComponent(svg)));
  scene.textures.addBase64(key, `data:image/svg+xml;base64,${encoded}`);
}

/** Ключи, чья загрузка уже запрошена: второй запрос той же текстуры Phaser отверг бы. */
const pending = new Set<string>();
