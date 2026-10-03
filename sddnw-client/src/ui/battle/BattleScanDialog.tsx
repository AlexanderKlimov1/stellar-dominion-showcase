import type { BattleShip } from '../../api/types';
import { sideColor, shipLabel } from './battleVisuals';
import { ShipPicture } from '../ship/shipArt';
import { useModalEscape } from '../useModalEscape';
import type { ReactNode } from 'react';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

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
  useModalEscape(true, onClose, true);
  const color = sideColor(ship);

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-space-950/70 p-4">
      <div className="panel flex w-[min(48rem,94vw)] flex-col gap-2 p-2">
        <header className="border border-space-600 bg-space-800 py-1 text-center text-16 text-accent">
          {shipLabel(ship)}
        </header>

        <div className="flex gap-2">
          {/* Рисунок корпуса — место картинки оригинала; тот же, что на поле боя. */}
          <div className="flex w-1/4 flex-none items-center justify-center border border-space-700 bg-space-950 p-2">
            <ShipPicture hullCode={ship.hullCode} hullSize={ship.hullSize} color={color}
                         size={72} label={ship.hullName} />
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
              tf('battle.scan.combatSpeed', { n: <Figure accent="value">{ship.speed}</Figure> }),
              tf('battle.scan.moveLeft', { n: <Figure accent="value">{ship.moveLeft}</Figure> }),
            ]}
          />
          <System
            name={ship.armourName ?? t('battle.systems.armour')}
            lines={[
              tf('battle.scan.structureOf', { n: <Figure accent="value">{ship.structure}</Figure>, max: <Figure accent="value">{ship.maxStructure}</Figure> }),
              tf('battle.scan.armourOf', { n: <Figure accent="value">{ship.armour}</Figure>, max: <Figure accent="value">{ship.maxArmour}</Figure> }),
            ]}
          />
          <System
            name={ship.computerName ?? t('battle.systems.noComputer')}
            wrecked={ship.computerWrecked}
            lines={[tf('battle.scan.beamAttack', { n: <Figure accent="value">{ship.attack}</Figure> })]}
          />
          <System
            name={ship.shieldName ?? t('battle.systems.noShield')}
            wrecked={ship.shieldWrecked}
            lines={[tf('battle.scan.blocked', { n: <Figure accent="value">{ship.shield}</Figure> })]}
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
                <span className={'truncate ' + figureLabelClass('value')}>
                  <Figure accent="value">{Math.max(0, weapon.count - weapon.wrecked)}</Figure> · {weapon.name} ·{' '}
                  {tf('battle.bar.damage', { n: <Figure accent="value">{weapon.damage}</Figure> })}
                  {/* Выбитые гнёзда — п. 8: осмотр и нужен затем, чтобы видеть, чем
                      противник уже не ответит. */}
                  {weapon.wrecked > 0 ? (
                    <span className="text-danger">
                      {' · '}
                      {tf('battle.bar.wrecked', { n: <Figure accent="value" tone="danger">{weapon.wrecked}</Figure> })}
                    </span>
                  ) : null}
                </span>
                <span className={'flex-none ' + figureLabelClass('value')}>
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
      <dt className={figureLabelClass('value')}>{label}</dt>
      <dd><Figure accent="value">{value}</Figure></dd>
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
  lines: ReactNode[];
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
      {lines.map((line, index) => (
        <div key={index} className={'pl-3 ' + figureLabelClass('value')}>
          · {line}
        </div>
      ))}
    </div>
  );
}
