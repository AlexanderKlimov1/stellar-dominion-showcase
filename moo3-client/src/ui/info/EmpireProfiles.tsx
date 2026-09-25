import type { EmpireProfile } from '../../api/types';
import { t } from '../../i18n';

/**
 * Свойства рас знакомых империй — п. 11.1, вторая страница окна Information MOO II:
 * «свойства рас всех империй, с которыми игрок в контакте».
 *
 * Показываются те же стороны, что и в конструкторе расы (п. 7), с ценой в очках: по ним
 * видно, чем сосед силён и чем расплатился за это. У правителя ИИ рядом стоит характер
 * (п. 15) — по нему судят, чего ждать в переговорах.
 *
 * Незнакомых империй здесь нет и быть не может: сервер их не отдаёт. Пока никого не
 * встретили, страница показывает одну свою — так и в оригинале, где до контакта соперник
 * безымянен.
 */
export function EmpireProfiles({ empires }: { empires: EmpireProfile[] }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {empires.map((empire) => (
        <section key={empire.playerId} className="border border-space-700 p-3">
          <div className="mb-2 flex items-baseline gap-2">
            <span className="inline-block h-2 w-2" style={{ backgroundColor: empire.color }} />
            <span className="text-ink-bright">{empire.raceName ?? empire.name}</span>
            <span className="text-11 text-ink-faint">
              {empire.own ? t('profiles.own') : empire.name}
            </span>
          </div>

          <div className="mb-2 grid grid-cols-2 gap-x-6 text-11">
            <Fact label={t('profiles.government')} value={empire.government ?? t('profiles.notChosen')} />
            {/* Характер есть только у ИИ: за человека решает человек — п. 15. */}
            <Fact label={t('profiles.ruler')} value={empire.character ?? t('profiles.human')} />
          </div>

          {empire.traits.length === 0 ? (
            <p className="text-11 text-ink-faint">
              {t('profiles.noTraits')}
            </p>
          ) : (
            <ul className="grid grid-cols-2 gap-x-6 gap-y-1 text-11">
              {empire.traits.map((trait) => (
                <li key={trait.code} className="flex items-baseline justify-between gap-2">
                  <span className="truncate text-ink-soft">{trait.name}</span>
                  {/* Цена со знаком, как в конструкторе оригинала: минус — раса вернула очки. */}
                  <span className={trait.picks < 0 ? 'text-accent' : 'text-ink-faint'}>
                    {trait.picks > 0 ? `+${trait.picks}` : trait.picks}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className="text-ink-faint">{label}</span>
      <span className="truncate text-ink">{value}</span>
    </div>
  );
}
