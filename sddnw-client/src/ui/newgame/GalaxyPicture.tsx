/**
 * Картинка размера галактики — то, что в окне новой игры MOO II нарисовано спиралью.
 *
 * Рисуется по ВЫБРАННОМУ размеру: сколько в галактике звёзд, столько точек и на картинке
 * (`stars`), и вместе с числом растёт сам диск. Поэтому переключение видно глазом, а не
 * только по подписи под картинкой, — в оригинале ровно так же: у Small спираль мелкая и
 * редкая, у Huge крупная и густая.
 *
 * Разброс точек — от номера через синус, а не `Math.random`: картинка перерисовывается на
 * каждом переключении, и случайность пересобирала бы галактику при каждом нажатии. Тот же
 * приём, что в схеме Млечного Пути.
 */

/** Сколько точек показывать: столько же, сколько звёзд в такой галактике. */
export function GalaxyPicture({ stars, fill }: { stars: number; fill: number }) {
  // Витки спирали от середины к краю. Две ветви, как у спиральной галактики.
  const points = Array.from({ length: stars }, (_, index) => {
    const part = (index + 0.5) / stars;
    // Корень — чтобы точки ложились равномерно по площади, а не сгущались в середине.
    const radius = Math.sqrt(part) * 47 * fill;
    const arm = index % 2 === 0 ? 0 : Math.PI;
    // Закрутка: полтора оборота от центра к краю — столько же у рукавов настоящих галактик.
    const angle = part * Math.PI * 3 + arm + Math.sin(index * 12.9898) * 0.55;
    return {
      x: 50 + radius * Math.cos(angle),
      // Диск виден под углом, поэтому по вертикали он ниже: тот же наклон, что у
      // картинки оригинала.
      y: 50 + radius * Math.sin(angle) * 0.62,
      size: index % 7 === 0 ? 1.7 : 1.1,
    };
  });

  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden="true">
      <defs>
        <radialGradient id="mw-thumb-core">
          <stop offset="0%" stopColor="#ffe9c0" stopOpacity="0.85" />
          <stop offset="55%" stopColor="#ffb367" stopOpacity="0.18" />
          <stop offset="100%" stopColor="#ff9d5c" stopOpacity="0" />
        </radialGradient>
      </defs>
      <ellipse cx={50} cy={50} rx={30 * fill} ry={18 * fill} fill="url(#mw-thumb-core)" />
      {points.map((point, index) => (
        <circle
          key={index}
          cx={point.x}
          cy={point.y}
          r={point.size}
          fill="#dbe7ff"
          opacity={0.35 + 0.65 * ((index * 7) % 10) / 10}
        />
      ))}
    </svg>
  );
}
