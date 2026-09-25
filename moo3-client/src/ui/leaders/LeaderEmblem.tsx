import type { Leader } from '../../api/types';

/**
 * Знак лидера — то, что в окне офицерского резерва MOO II нарисовано портретом.
 *
 * Портретов у нас нет, и рисовать шесть десятков лиц кодом бессмысленно, поэтому лидер
 * опознаётся МОНОГРАММОЙ: первые буквы имени крупно, а под ними рамка. У имени в игре и
 * так главная роль — по нему лидера и запоминают, — так что буквы работают лучше любого
 * узора.
 *
 * Род службы виден сразу: колониальный лидер отмечен кругом (система, планеты),
 * корабельный — треугольником (тем же значком, каким флот помечен на карте галактики).
 * Значок в углу, а не вместо монограммы: род и имя нужны оба.
 */
export function LeaderEmblem({ leader }: { leader: Leader }) {
  // Монограмма: по первой букве каждого слова имени, не больше двух. «Vantrell Sable» —
  // VS, «Quorrin» — Q.
  const monogram = leader.name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0])
    .join('');

  return (
    <span className="relative flex h-full w-full items-center justify-center">
      <span className="text-[2.1em] leading-none tracking-tight text-ink-bright">{monogram}</span>
      <svg
        viewBox="0 0 12 12"
        className="absolute bottom-0 right-0 h-[1em] w-[1em] text-accent"
        aria-hidden="true"
      >
        {leader.kind === 'COLONY' ? (
          <circle cx={6} cy={6} r={4} fill="none" stroke="currentColor" strokeWidth={1.6} />
        ) : (
          <path d="M6 2 L10 10 L2 10 Z" fill="none" stroke="currentColor" strokeWidth={1.6} />
        )}
      </svg>
    </span>
  );
}
