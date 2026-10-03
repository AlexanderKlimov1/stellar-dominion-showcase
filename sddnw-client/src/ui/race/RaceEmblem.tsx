/**
 * Эмблема расы — то, что в окне выбора расы MOO II нарисовано портретом.
 *
 * Портретов у нас нет и взять их неоткуда: рисунки оригинала — чужое добро, а тринадцать
 * своих не нарисовать кодом. Поэтому раса опознаётся ЗНАКОМ: кольцо её цвета (тем же
 * цветом империя отмечена на карте) и многоугольник, у которого сторон столько, какой
 * номер у расы в справочнике. Знак получается у каждой свой и узнаётся с одного взгляда —
 * ровно то, ради чего в оригинале стоит портрет.
 *
 * Считается знак от номера, а не от случая: экран перерисовывается на каждое наведение
 * мыши, и `Math.random` менял бы эмблемы под курсором.
 */
export function RaceEmblem({ colour, order }: { colour: string; order: number }) {
  // Три … девять сторон: меньше трёх многоугольника не бывает, больше девяти он уже не
  // отличается от круга.
  const sides = 3 + (order % 7);
  // Поворот — чтобы у рас с одинаковым числом сторон знаки не совпадали.
  const turn = (order * 37) % 360;
  const points = Array.from({ length: sides }, (_, index) => {
    const angle = ((index / sides) * 360 + turn - 90) * (Math.PI / 180);
    return `${50 + 26 * Math.cos(angle)},${50 + 26 * Math.sin(angle)}`;
  }).join(' ');

  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden="true">
      <circle cx={50} cy={50} r={38} fill="none" stroke={colour} strokeWidth={2} opacity={0.5} />
      <polygon points={points} fill={colour} opacity={0.22} stroke={colour} strokeWidth={2} />
      {/* Точка-спутник: она же отличает зеркальные знаки друг от друга. */}
      <circle cx={50 + 38 * Math.cos((turn * Math.PI) / 180)}
              cy={50 + 38 * Math.sin((turn * Math.PI) / 180)}
              r={3.5} fill={colour} />
    </svg>
  );
}

/**
 * Знак «своей расы»: пустая рамка со скобками — место, куда игрок впишет своё.
 *
 * Нарочно не похож на эмблемы готовых рас: это не раса, а дорога в конструктор.
 */
export function CustomRaceEmblem() {
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden="true">
      <path
        d="M30 22 H22 V34 M70 22 H78 V34 M30 78 H22 V66 M70 78 H78 V66"
        fill="none"
        stroke="currentColor"
        strokeWidth={3}
        opacity={0.8}
      />
      <path d="M50 36 V64 M36 50 H64" stroke="currentColor" strokeWidth={4} opacity={0.9} />
    </svg>
  );
}
