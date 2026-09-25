import type { ShipHull, WeaponModification } from '../../api/types';
import { pickCost, pickDamage, pickSpace, type Pick } from './shipDesignRules';
import { t } from '../../i18n';

/**
 * Оружие и особые модули корабля — п. 8, двумя таблицами окна Ship Design MOO II.
 *
 * Нижнюю половину окна оригинала занимают ровно они: сверху таблица оружия со столбцами
 * «оружие — урон — цена — место — замечания» и счётчиком у каждой строки, снизу таблица
 * особых модулей со столбцами «модуль — описание». Пустая таблица не молчит, а зовёт:
 * «Add New Special System» — здесь то же самое словами.
 *
 * Счётчик у оружия из оригинала: пушек в проекте бывает много, и число меняют кнопками
 * прямо в строке, не открывая список заново. У особого модуля счётчика нет — он ставится
 * один раз.
 *
 * <b>Модификации ствола</b> — столбец «Modifications» оригинала: нажатие по нему
 * открывает окно, где стволу назначают тяжёлое орудие, ближнюю оборону, скорострельность,
 * бронебойность или обволакивание. Считает их сервер, а строка показывает уже изменённые
 * урон, цену и место — они и есть весь смысл модификации.
 *
 * <b>Дуги обстрела здесь по-прежнему нет</b>: в оригинале у строки оружия есть ещё и
 * направление (вперёд, вперёд-расширенное, 360°), но оно имеет смысл лишь там, где у
 * корабля есть нос и корма. Бой этой игры разворота не знает вовсе, и столбец «Arc»
 * показывал бы число, которого никто не считает.
 */
export function ShipSystemTables({
  hull,
  picks,
  openSlot,
  onOpenSlot,
  onAdd,
  onRemove,
  canAdd,
  onModify,
}: {
  hull: ShipHull | null;
  picks: Pick[];
  openSlot: string | null;
  onOpenSlot: (slot: string) => void;
  onAdd: (code: string) => void;
  onRemove: (code: string) => void;
  /**
   * Влезет ли ещё один такой ствол — п. 8: на пределе места «+» гаснет.
   * <p>
   * Считает это родитель ({@code fitsMore}), потому что там же лежит и запрет: правило
   * одно, а кнопка лишь показывает его заранее — иначе нажатие молча ничего не делает.
   */
  canAdd: (pick: Pick) => boolean;
  /** Открыть окно модификаций этого ствола — номер строки в составе проекта. */
  onModify: (index: number) => void;
}) {
  // Номер строки в составе нужен модификациям: одинаковые пушки с разными модификациями
  // стоят разными строками, и код компонента их уже не различает.
  const weapons = picks
    .map((pick, index) => ({ pick, index }))
    .filter((row) => row.pick.component.slot === 'WEAPON');
  const specials = picks.filter((pick) => pick.component.slot === 'SPECIAL');

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      <Table
        active={openSlot === 'WEAPON'}
        add={t('design.weapons.add')}
        onOpen={() => onOpenSlot('WEAPON')}
        head={
          <tr>
            <th className="w-24 px-2 py-1">{t('design.col.count')}</th>
            <th className="px-2 py-1">{t('design.col.weapon')}</th>
            <th className="w-20 px-2 py-1 text-right">{t('design.col.damage')}</th>
            <th className="w-20 px-2 py-1 text-right">{t('design.col.cost')}</th>
            <th className="w-20 px-2 py-1 text-right">{t('design.col.space')}</th>
            <th className="w-1/3 px-2 py-1">{t('design.col.modifications')}</th>
          </tr>
        }
      >
        {weapons.map(({ pick, index }) => (
          <tr key={`${pick.component.code}-${index}`} className="border-t border-space-800">
            <td className="px-2 py-1">
              {/* Счётчик оригинала: минус, число, плюс — прямо в строке. */}
              {/*
                Кнопки счётчика в рамках, а не ссылками: у ссылок в этой игре пунктирное
                подчёркивание, и минус под ним читался как многоточие. В оригинале это
                тоже кнопки-квадратики.
              */}
              <span className="flex items-center gap-2 text-18">
                <Step label="-" onClick={() => onRemove(pick.component.code)} />
                <span className="w-5 text-center text-ink-bright">{pick.count}</span>
                <Step
                  label="+"
                  onClick={() => onAdd(pick.component.code)}
                  disabled={!canAdd(pick)}
                  title={canAdd(pick) ? undefined : t('design.noRoomShort')}
                />
              </span>
            </td>
            <td className="px-2 py-1 text-ink-bright">{pick.component.name}</td>
            <td className="px-2 py-1 text-right text-ink-soft">{damage(pick)}</td>
            <td className="px-2 py-1 text-right text-ink-soft">
              {hull ? pickCost(pick, hull) * pick.count : ''}
            </td>
            <td className="px-2 py-1 text-right text-ink-soft">
              {hull ? pickSpace(pick, hull) * pick.count : ''}
            </td>
            {/*
              Столбец модификаций — как в оригинале: нажатие открывает их выбор, а пока
              их нет, строка так и говорит «без модификаций».
            */}
            <td className="px-2 py-1">
              <button type="button" className="link text-left" onClick={() => onModify(index)}>
                {pick.modifications.length === 0
                  ? t('design.noModifications')
                  : pick.modifications.map((mod: WeaponModification) => mod.name).join(', ')}
              </button>
            </td>
          </tr>
        ))}
      </Table>

      <Table
        active={openSlot === 'SPECIAL'}
        add={t('design.specials.add')}
        onOpen={() => onOpenSlot('SPECIAL')}
        head={
          <tr>
            <th className="px-2 py-1">{t('design.col.module')}</th>
            <th className="w-20 px-2 py-1 text-right">{t('design.col.cost')}</th>
            <th className="w-20 px-2 py-1 text-right">{t('design.col.space')}</th>
            <th className="w-1/2 px-2 py-1">{t('design.col.description')}</th>
          </tr>
        }
      >
        {specials.map((pick) => (
          <tr key={pick.component.code} className="border-t border-space-800">
            <td className="px-2 py-1 text-ink-bright">
              <button type="button" className="link" onClick={() => onRemove(pick.component.code)}>
                {pick.component.name}
              </button>
            </td>
            <td className="px-2 py-1 text-right text-ink-soft">
              {hull ? pickCost(pick, hull) : ''}
            </td>
            <td className="px-2 py-1 text-right text-ink-soft">
              {hull ? pickSpace(pick, hull) : ''}
            </td>
            <td className="px-2 py-1 text-ink-dim">
              {pick.component.effects.map((effect) => effect.label).join(', ')}
            </td>
          </tr>
        ))}
      </Table>
    </div>
  );
}

/**
 * Таблица состава: шапка столбцов, строки и зовущая строка под ними.
 *
 * <b>Своего заголовка у таблицы нет, и это оригинал</b> (`docs/moo2/ship-design.png`):
 * там над таблицей стоит только шапка столбцов — «Weapon Type · Damage · Arc · Cost ·
 * Space · Modifications», — а название ей заменяет первый столбец. Лишняя плашка
 * «Оружие» съедала строку на каждой из двух таблиц.
 *
 * <b>«Добавить» стоит ПОД строками, а не в шапке</b>: в оригинале это «Add New Weapon»
 * по центру следующей строкой, и видно её всегда — и у пустой таблицы, и у полной.
 * Раньше зов был только у пустой, а у полной прятался ссылкой в углу заголовка.
 */
function Table({
  head,
  children,
  add,
  active,
  onOpen,
}: {
  head: React.ReactNode;
  children: React.ReactNode[];
  /** Подпись зовущей строки: «Добавить оружие», «Добавить особый модуль». */
  add: string;
  active: boolean;
  onOpen: () => void;
}) {
  return (
    <div
      className={`flex min-h-0 flex-1 flex-col border bg-space-950/60 ${
        active ? 'border-amber-400/60' : 'border-space-700'
      }`}
    >
      <div className="min-h-0 flex-1 overflow-y-auto">
        <table className="w-full text-left text-18">
          <thead className="text-16 uppercase tracking-[0.2em] text-ink-faint">{head}</thead>
          <tbody>{children}</tbody>
        </table>
        <button
          type="button"
          onClick={onOpen}
          className="w-full py-2 text-center text-20 text-ink-faint hover:text-accent"
        >
          {add}
        </button>
      </div>
    </div>
  );
}

/** Кнопка счётчика: квадратик со знаком, как у строки оружия в оригинале. */
function Step({
  label,
  onClick,
  disabled,
  title,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={
        'border px-1.5 leading-none '
        + (disabled
          ? 'cursor-not-allowed border-space-800 text-ink-off'
          : 'border-space-700 text-ink hover:border-space-500 hover:text-accent')
      }
    >
      {label}
    </button>
  );
}

/** Урон пушки: столько и столько раз за залп — «3 × 2» вместо «3–12» оригинала. */
function damage(pick: Pick): string {
  const value = pick.component.effects.find((effect) => effect.type === 'WEAPON_DAMAGE');
  const shots = pick.component.effects.find((effect) => effect.type === 'WEAPON_SHOTS');
  if (!value) {
    return '—';
  }
  // Урон показывается с поправкой модификаций: тяжёлое орудие бьёт больнее, ближняя
  // оборона слабее, и именно это число уходит в бой.
  const each = pickDamage(pick);
  return shots && shots.amount > 1 ? `${each} × ${shots.amount}` : String(each);
}
