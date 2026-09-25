import { useGameStore, selectSelf } from '../../state/gameStore';
import { t } from '../../i18n';

/**
 * Бюджет империи за ход — п. 10, нижний левый угол консоли Info MOO II.
 *
 * Руководство оригинала описывает этот сегмент так: «в нижнем левом углу — разбивка
 * вашего бюджета за ход; общий доход уравновешивается разными видами содержания, из чего
 * и выходит чистый доход за ход». Здесь то же самое и в том же месте: сегмент не
 * меняется, какой бы отчёт ни был открыт справа.
 *
 * Считается по своим колониям карты: доход и содержание сервер уже прислал в карточке
 * каждой колонии, и спрашивать их отдельным запросом незачем. Валовой доход — это чистый
 * плюс содержание: сервер отдаёт колонию уже за вычетом её построек.
 *
 * <i>Чего ещё нет:</i> в MOO II содержание платят и корабли, и грузовики, и шпионы, —
 * здесь его берут только здания. Как появится содержание флота, оно встанет отдельной
 * строкой сюда же.
 */
export function EmpireBudget() {
  const map = useGameStore((state) => state.map);
  const self = useGameStore(selectSelf);
  const credentials = useGameStore((state) => state.credentials);

  const colonies = (map?.systems ?? [])
    .flatMap((system) => system.planets)
    .filter((planet) => planet.ownerPlayerId === credentials?.playerId && planet.colony);

  const net = colonies.reduce((total, planet) => total + (planet.colony?.income ?? 0), 0);
  const upkeep = colonies.reduce((total, planet) => total + (planet.colony?.upkeep ?? 0), 0);
  /** Во что упирается столбец диаграммы: единица бережёт от деления на ноль на первом ходу. */
  const peak = Math.max(1, net + upkeep, upkeep);

  return (
    <section className="border-t border-space-700 px-3 py-2">
      <div className="panel-title mb-1">{t('budget.title')}</div>

      {/*
        ДИАГРАММА ИЗ ДВУХ СТОЛБЦОВ — как в оригинале (`docs/moo2/info.png`): слева доход,
        справа содержание, и видно с одного взгляда, много ли съедает хозяйство. Числами
        это же стоит под ней: столбцы показывают ОТНОШЕНИЕ, числа — величину, и одно без
        другого читается плохо.

        Высота считается от большего из двух, а не от дохода: в убыточной империи
        содержание выше дохода, и столбец дохода иначе упёрся бы в потолок вместе с ним.
      */}
      <div className="mb-2 flex h-16 items-end gap-3 border-b border-space-800 px-2 pb-1">
        <Bar label={t('budget.income')} value={net + upkeep} peak={peak} tone="bg-accent" />
        <Bar label={t('budget.upkeep')} value={upkeep} peak={peak} tone="bg-warn" />
      </div>

      <dl className="space-y-0.5 text-11">
        <Line label={t('budget.income')} value={net + upkeep} />
        <Line label={t('budget.upkeep')} value={-upkeep} />
        <div className="my-1 border-t border-space-800" />
        <Line label={t('budget.net')} value={net} strong />
        <Line label={t('budget.treasury')} value={self?.credits ?? 0} plain />
      </dl>
    </section>
  );
}

function Line({
  label,
  value,
  strong = false,
  plain = false,
}: {
  label: string;
  value: number;
  /** Итоговая строка: выделяется, как в оригинале, где чистый доход стоит под чертой. */
  strong?: boolean;
  /** Без знака: казна — это остаток, а не приход за ход. */
  plain?: boolean;
}) {
  const colour = plain || value === 0 ? 'text-ink' : value > 0 ? 'text-accent' : 'text-danger';
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-ink-faint">{label}</dt>
      <dd className={`${colour} ${strong ? 'font-semibold' : ''}`}>
        {plain ? '' : value > 0 ? '+' : ''}
        {t('common.credits', { n: value })}
      </dd>
    </div>
  );
}

/**
 * Столбец диаграммы бюджета: доля от большего из двух и подпись под ним.
 *
 * Нулевой столбец всё равно рисуется чертой в пиксель — пустое место под подписью
 * читалось бы как поломка, а ноль это ответ.
 */
function Bar({
  label,
  value,
  peak,
  tone,
}: {
  label: string;
  value: number;
  peak: number;
  tone: string;
}) {
  const height = Math.max(1, Math.round((Math.abs(value) / peak) * 100));
  return (
    <span className="flex h-full flex-1 flex-col items-center justify-end gap-1">
      <span className={`w-5 ${tone}`} style={{ height: `${height}%` }} />
      <span className="text-10 uppercase tracking-[0.1em] text-ink-faint">{label}</span>
    </span>
  );
}
