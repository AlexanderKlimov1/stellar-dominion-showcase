import type { BattleShip } from '../../api/types';
import { sideColor, shipLabel } from './battleVisuals';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Осмотр корабля в бою — п. 8, окно `SCAN` оригинала (`docs/moo2/battle-scan.png`).
 *
 * В MOO II это отдельное окно поверх поля: плашка с именем корабля, слева его картинка,
 * посередине боевые надбавки, справа портрет хозяина, ниже системы двумя столбцами, под
 * ними таблица оружия и особые модули. Здесь то же самое, только вместо картинки и
 * портрета — знак корабля и имя империи: спрайтов у игры нет.
 *
 * <b>Чужой корабль осматривается так же, как свой</b>, и это правило оригинала: в бою
 * видно, чем вооружён соперник. Поэтому состав приходит с сервера у КАЖДОГО корабля поля,
 * а не только у своих.
 */
export function BattleScanDialog({
  ship,
  onClose,
}: {
  ship: BattleShip;
  onClose: () => void;
}) {
  useT();
  useModalEscape(true, onClose);
  const color = sideColor(ship);

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-space-950/70 p-4">
      <div className="panel flex w-[min(48rem,94vw)] flex-col gap-2 p-2">
        <header className="border border-space-600 bg-space-800 py-1 text-center text-16 text-accent">
          {shipLabel(ship)}
        </header>

        <div className="flex gap-2">
          {/* Знак корабля — место картинки оригинала. */}
          <div className="flex w-1/4 flex-none items-center justify-center border border-space-700 bg-space-950 p-2">
            <svg viewBox="0 0 60 40" className="h-16 w-full">
              {ship.platform ? (
                <g stroke={color} fill="none" strokeWidth="1.6">
                  <circle cx="30" cy="20" r="12" />
                  <circle cx="30" cy="20" r="4" fill={color} fillOpacity="0.4" />
                  <path d="M 30 8 L 30 2 M 30 32 L 30 38 M 18 20 L 12 20 M 42 20 L 48 20" />
                </g>
              ) : (
                <path
                  d="M 14 11 L 46 20 L 14 29 L 20 20 Z"
                  stroke={color}
                  strokeWidth="1.6"
                  fill={color}
                  fillOpacity="0.35"
                />
              )}
            </svg>
          </div>

          {/* Боевые надбавки — «Combat Bonuses» оригинала. */}
          <dl className="flex-1 border border-space-700 bg-space-950 p-2 text-13">
            <div className="mb-1 text-center text-14 text-accent">{t('battle.scan.bonuses')}</div>
            <Row label={t('battle.scan.attack')} value={ship.attack} />
            <Row label={t('battle.scan.defense')} value={ship.defense} />
            <Row label={t('battle.scan.evasion')} value={`${ship.missileEvasion} %`} />
            <Row label={t('battle.scan.initiative')} value={ship.initiative} />
          </dl>

          {/* Хозяин: в оригинале здесь портрет правителя. */}
          <div className="flex w-1/4 flex-none flex-col items-center justify-center border border-space-700 bg-space-950 p-2 text-center text-13">
            <span style={{ color }}>{ship.ownerName}</span>
            <span className="text-ink-dim">{ship.hullName}</span>
          </div>
        </div>

        {/* Системы двумя столбцами — как в оригинале: движение и броня слева, приборы справа. */}
        <div className="grid grid-cols-2 gap-2">
          <System
            name={ship.engineName ?? t('battle.systems.drive')}
            wrecked={ship.engineWrecked}
            lines={[
              t('battle.scan.combatSpeed', { n: ship.speed }),
              t('battle.scan.moveLeft', { n: ship.moveLeft }),
            ]}
          />
          <System
            name={ship.armourName ?? t('battle.systems.armour')}
            lines={[
              t('battle.scan.structureOf', { n: ship.structure, max: ship.maxStructure }),
              t('battle.scan.armourOf', { n: ship.armour, max: ship.maxArmour }),
            ]}
          />
          <System
            name={ship.computerName ?? t('battle.systems.noComputer')}
            wrecked={ship.computerWrecked}
            lines={[t('battle.scan.beamAttack', { n: ship.attack })]}
          />
          <System
            name={ship.shieldName ?? t('battle.systems.noShield')}
            wrecked={ship.shieldWrecked}
            lines={[t('battle.scan.blocked', { n: ship.shield })]}
          />
        </div>

        {/* Таблица оружия: счёт, название, урон, модификации — строками, как в оригинале. */}
        <div className="border border-space-700 bg-space-950 p-2 text-13">
          <div className="mb-1 flex justify-between text-14 text-accent">
            <span>{t('battle.scan.weapons')}</span>
            <span className="text-ink-dim">{t('battle.scan.mods')}</span>
          </div>
          {ship.weapons.length === 0 ? (
            <p className="text-ink-faint">{t('battle.bar.none')}</p>
          ) : (
            ship.weapons.map((weapon, index) => (
              <p key={`${weapon.name}-${index}`} className="flex justify-between gap-2">
                <span className="truncate text-ink">
                  {Math.max(0, weapon.count - weapon.wrecked)} · {weapon.name} ·{' '}
                  {t('battle.bar.damage', { n: weapon.damage })}
                  {/* Выбитые гнёзда — п. 8: осмотр и нужен затем, чтобы видеть, чем
                      противник уже не ответит. */}
                  {weapon.wrecked > 0 ? (
                    <span className="text-danger"> · {t('battle.bar.wrecked', { n: weapon.wrecked })}</span>
                  ) : null}
                </span>
                <span className="flex-none text-ink-dim">
                  {weapon.modifications.length === 0
                    ? t('battle.scan.noMods')
                    : weapon.modifications.join(', ')}
                </span>
              </p>
            ))
          )}
        </div>

        <div className="border border-space-700 bg-space-950 p-2 text-13">
          <div className="mb-1 text-14 text-accent">{t('battle.scan.specials')}</div>
          <p className="text-ink">
            {ship.specials.length === 0 ? t('battle.bar.none') : ship.specials.join(' · ')}
          </p>
        </div>

        <footer className="flex justify-end">
          <button
            type="button"
            className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
            onClick={onClose}
          >
            {t('common.close')}
          </button>
        </footer>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-ink-dim">{label}</dt>
      <dd className="text-ink-bright">{value}</dd>
    </div>
  );
}

/** Система корабля: название строкой, под ним её действие точками — как в окне дизайна. */
/**
 * Система корабля в окне осмотра; разбитая названа разбитой — п. 8.
 *
 * Осмотр затем и нужен, чтобы видеть, чем противник уже не ответит: у подбитого корабля
 * половина строк может оказаться мёртвой, и читаться это должно с первого взгляда.
 */
function System({
  name,
  lines,
  wrecked,
}: {
  name: string;
  lines: string[];
  wrecked?: boolean;
}) {
  return (
    <div className="border border-space-700 bg-space-950 p-2 text-13">
      <div className={'truncate ' + (wrecked ? 'text-danger line-through' : 'text-warn')}>
        {name}
        {wrecked ? (
          <span className="no-underline"> · {t('battle.systems.wrecked')}</span>
        ) : null}
      </div>
      {lines.map((line) => (
        <div key={line} className="pl-3 text-ink-soft">
          · {line}
        </div>
      ))}
    </div>
  );
}
