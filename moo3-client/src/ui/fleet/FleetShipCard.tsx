import type { FleetGroup, FleetShip } from '../../api/types';
import { t } from '../../i18n';

/**
 * Карточка корабля — левый нижний угол окна Fleet Operations MOO II (п. 8).
 *
 * Разметка снята с оригинала строка в строку: название проекта, щит, два числа боя в две
 * колонки, строка назначения у флота в пути и внизу два столбца — оружие и особые модули.
 * Всё это приходит вместе с составом флота, поэтому карточка живёт и у корабля, чей
 * проект уже вытеснен из ячейки новым: список проектов империи такой не содержит, а
 * корабль по нему летает.
 *
 * Строки экипажа из оригинала («Veteran Crew (247 EP)») здесь нет: опыта команд игра не
 * считает вовсе — это точка расширения при особенности расы «военачальники» (п. 7).
 * Написать в карточку выдуманное звание хуже, чем не писать ничего: игрок решил бы, что
 * опыт на что-то влияет.
 */
export function FleetShipCard({ ship, fleet }: { ship: FleetShip | null; fleet: FleetGroup | null }) {
  if (!ship) {
    return (
      <div className="flex-none border border-space-700 bg-space-950 p-2 text-11 text-ink-faint">
        {t('shipCard.hover')}
      </div>
    );
  }

  return (
    <div className="flex-none border border-space-700 bg-space-950 p-2 text-11 leading-tight">
      <p className="text-13 text-accent">
        {ship.designName}
        {ship.obsolete ? <span className="ml-2 text-10 text-warn">{t('shipCard.obsolete')}</span> : null}
      </p>
      <p className="text-ink-dim">
        {ship.hullName ?? t('shipCard.unknownHull')} · {ship.shield ?? t('shipCard.noShield')}
      </p>

      {/* Два числа боя в две колонки — Beam OCV и Beam DCV оригинала. */}
      <div className="mt-1 grid grid-cols-2 gap-x-4">
        <Value label={t('shipCard.attack')} value={ship.attack} />
        <Value label={t('shipCard.defence')} value={ship.defense} />
      </div>

      {/* Строка назначения стоит только у флота в пути — как и в оригинале. */}
      {fleet?.targetSystemId ? (
        <p className="mt-1 text-ink-bright">
          {t('shipCard.destination', { name: fleet.targetSystemName ?? t('fleetOps.unexploredStar') })}
          {fleet.arrivalTurn == null ? '' : t('shipCard.arrival', { n: fleet.arrivalTurn })}
        </p>
      ) : null}

      <div className="mt-2 grid grid-cols-2 gap-x-4">
        <Column title={t('shipCard.weapons')}>
          {ship.weapons.length === 0 ? (
            <li className="text-ink-faint">{t('common.none')}</li>
          ) : (
            ship.weapons.map((weapon) => (
              <li key={weapon.name} className="text-ink-soft">
                {weapon.count} × {weapon.name}{' '}
                <span className="text-ink-faint">({weapon.damage})</span>
              </li>
            ))
          )}
        </Column>
        <Column title={t('shipCard.specials')}>
          {ship.specials.length === 0 ? (
            <li className="text-ink-faint">{t('common.none')}</li>
          ) : (
            ship.specials.map((special) => (
              <li key={special} className="text-ink-soft">
                {special}
              </li>
            ))
          )}
        </Column>
      </div>
    </div>
  );
}

/** Число боя с подписью: подпись слева, значение справа — как в две колонки оригинала. */
function Value({ label, value }: { label: string; value: number }) {
  return (
    <p className="flex items-baseline justify-between gap-2">
      <span className="text-ink-dim">{label}</span>
      <span className="text-ink-bright">{value}</span>
    </p>
  );
}

/** Столбец списка карточки: заголовок и строки под ним. */
function Column({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-10 uppercase tracking-[0.2em] text-ink-dim">{title}</p>
      <ul className="mt-0.5">{children}</ul>
    </div>
  );
}
