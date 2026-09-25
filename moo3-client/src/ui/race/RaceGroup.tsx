import type { RaceDesign, RaceTraitGroup } from '../../api/types';
import { combinationOf, isBlocked } from './racePicks';
import { t } from '../../i18n';

/**
 * Группа сторон расы — блок из окна race picks MOO II.
 *
 * Все варианты видны сразу: в оригинале раса собирается по открытому списку, а не по
 * раскрывающимся спискам — так видно, чего раса ещё не взяла, и цена соседнего варианта
 * стоит рядом с выбранным. Прятать варианты значило бы прятать и цену выбора.
 *
 * Строка сделана как в оригинале: прямоугольная кнопка выбора, название, точечная
 * линейка до правого края и цена в очках. Цена — со знаком оригинала: сколько очков
 * сторона стоит, а у слабых сторон число отрицательное, потому что очки они возвращают.
 *
 * Из группы-переключателя берётся не больше одной особенности (п. 7): нажатие на
 * соседний вариант переносит огонёк, нажатие на горящий гасит его — группа снова
 * «обычная». Группа особых способностей MOO II ведёт себя иначе: там каждая сторона
 * сама по себе, и горят они разом.
 *
 * Недоступная сторона гаснет, а не исчезает: в оригинале несовместимая с выбранным
 * строка остаётся на месте вместе с ценой, и видно, чего именно раса лишилась.
 *
 * **Заголовок у группы бывает не всегда** (`headless`): в правом столбце оригинала восемь
 * наших групп сведены под одну надпись «особые способности», и свои заголовки им там
 * только мешают — см. {@link RACE_COLUMNS}. Правила группы при этом остаются её
 * собственными: из группы-переключателя по-прежнему берётся одна сторона.
 */
export function RaceGroup({
  design,
  group,
  traits,
  headless = false,
  onPick,
}: {
  /** Весь конструктор: по нему считаются запреты между сторонами разных групп. */
  design: RaceDesign | null;
  group: RaceTraitGroup;
  traits: string[];
  /** Прятать заголовок группы: столбец уже назван общим. */
  headless?: boolean;
  onPick: (code: string) => void;
}) {
  return (
    <section className={headless ? 'break-inside-avoid' : 'mb-4 break-inside-avoid'}>
      {headless ? null : (
        <h3
          className="mb-1 uppercase tracking-[0.2em] text-accent"
          title={group.description}
        >
          {group.name}
        </h3>
      )}
      <ul>
        {group.options.map((option) => {
          const active = traits.includes(option.code);
          const blocked = isBlocked(design, traits, option);
          // Связка, собравшаяся в наборе: обе её стороны получают кружок ОДНОГО цвета и
          // цену удорожания рядом — иначе связь между строками пришлось бы искать глазами.
          const pair = active ? combinationOf(design, traits, option.code) : null;
          return (
            <li key={option.code}>
              <button
                type="button"
                disabled={blocked}
                title={blocked ? t('race.group.blocked', { description: option.description }) : option.description}
                className={
                  'flex w-full items-center gap-2 py-0.5 text-left leading-tight ' +
                  (blocked
                    ? 'cursor-not-allowed text-space-600'
                    : 'hover:bg-space-800/60 ' +
                      (active ? 'text-ink-bright' : 'text-ink-soft hover:text-ink-bright'))
                }
                onClick={() => onPick(option.code)}
              >
                <Marker active={active} />
                <span className="min-w-0 shrink truncate">{option.name}</span>
                {pair ? <CombinationMark index={pair.index} picks={pair.picks} names={pair.names} /> : null}
                {/* Точечная линейка до цены — она же в оригинале ведёт взгляд к числу. */}
                <span
                  aria-hidden="true"
                  className="mx-1 min-w-[0.5rem] flex-1 translate-y-[-0.3em] border-b border-dotted border-space-600"
                />
                <Price picks={option.picks} />
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/**
 * Цвета связок: у обеих сторон одной связки кружок одного цвета — этим они и связаны на
 * глаз. Цвета взяты из палитры экранов игры и различимы между собой; связок в справочнике
 * считанные, поэтому список короткий, а лишние номера идут по кругу.
 */
const COMBINATION_COLORS = ['#e0a33e', '#5ec2b1', '#c87ad6', '#7aa7e0', '#d6705e'];

/**
 * Кружок связки с ценой удорожания — п. 7.
 *
 * Показывается только когда взяты ОБЕ стороны: пока связка не собрана, платить не за что,
 * и кружок сбивал бы с толку. Цена стоит рядом с кружком, а не в общем итоге, потому что
 * игрок выбирает стороны по одной и должен видеть, за что именно с него берут сверх.
 */
function CombinationMark({
  index,
  picks,
  names,
}: {
  index: number;
  picks: number;
  names: string[];
}) {
  const color = COMBINATION_COLORS[index % COMBINATION_COLORS.length];
  return (
    <span
      className="ml-1 flex flex-none items-center gap-0.5"
      title={t('race.group.combination', { names: names.join(' + '), picks })}
    >
      <span
        aria-hidden="true"
        className="h-2 w-2 flex-none rounded-full"
        style={{ backgroundColor: color }}
      />
      <span className="text-[0.7em] leading-none" style={{ color }}>
        +{picks}
      </span>
    </span>
  );
}

/**
 * Прямоугольная кнопка выбора — она же и отметка, что сторона взята.
 *
 * В оригинале сторона включается такой же кнопкой и горит цветом окна; здесь горит
 * акцентом правой панели карты — палитра у экранов игры общая.
 */
function Marker({ active }: { active: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={
        'h-3 w-4 flex-none border ' +
        (active ? 'border-accent bg-accent' : 'border-space-500 bg-space-900')
      }
    />
  );
}

/**
 * Цена в очках со знаком оригинала: сильная сторона стоит очков, слабая их возвращает
 * и потому стоит отрицательное число. Ноль показывается тоже — в оригинале у диктатуры
 * стоит именно «0», а не пустое место: даром, но выбрано.
 */
function Price({ picks }: { picks: number }) {
  const tone = picks > 0 ? 'text-ink' : picks < 0 ? 'text-good' : 'text-ink-faint';
  return <span className={'w-6 flex-none text-right ' + tone}>{picks}</span>;
}
