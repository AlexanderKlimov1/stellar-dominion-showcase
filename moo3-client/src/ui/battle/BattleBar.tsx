import { useState } from 'react';
import type { Battle, BattleShip } from '../../api/types';
import { shipLabel, sideColor } from './battleVisuals';
import { t, useT } from '../../i18n';

/**
 * Нижняя полоса тактического боя — п. 8, полоса окна боя MOO II
 * (`docs/moo2/battle.png`).
 *
 * Пять сегментов слева направо, как в оригинале: карточка корабля, чей ход; его оружие и
 * особые модули вкладками; кнопки хода сеткой; ящик систем числами; мини-карта поля.
 * Больше на полосе нет ничего, и это правило самого оригинала: **всё, что нужно в бою,
 * стоит внизу**, а поле остаётся полем.
 *
 * Прежде у сцены были заголовок, полоса очереди, полоса сил сторон и журнал во всю
 * правую треть экрана — четыре ряда служебного текста вокруг поля. Полоса заменила их
 * все: очередь видна по тому, чей корабль сейчас в карточке, силы сторон — в мини-карте,
 * а журнал ушёл за поле (его показывает сама сцена).
 */
export function BattleBar({
  battle,
  ship,
  mine,
  busy,
  demo,
  onPass,
  onRetreat,
  onSelfDestruct,
  onScan,
  onOptions,
  onClose,
}: {
  battle: Battle;
  /** Корабль, чей ход; пусто — бой кончился. */
  ship: BattleShip | null;
  /** Ход этого игрока: только тогда кнопки и работают. */
  mine: boolean;
  busy: boolean;
  demo: boolean;
  onPass: () => void;
  onRetreat: () => void;
  /** Самоподрыв — п. 8: гарантированный взрыв своего корабля по воле игрока. */
  onSelfDestruct: () => void;
  onScan: () => void;
  onOptions: () => void;
  onClose: () => void;
}) {
  useT();
  return (
    <footer className="flex flex-none gap-2 border-t-2 border-space-700 bg-space-900/50 p-2">
      <ShipCard ship={ship} mine={mine} demo={demo} />
      <Loadout ship={ship} />
      <Actions
        mine={mine}
        busy={busy}
        demo={demo}
        over={battle.state !== 'IN_PROGRESS'}
        canScan={ship !== null}
        onPass={onPass}
        onRetreat={onRetreat}
        onSelfDestruct={onSelfDestruct}
        onScan={onScan}
        onOptions={onOptions}
        onClose={onClose}
      />
      <Systems ship={ship} />
      <MiniMap battle={battle} />
    </footer>
  );
}

/** Рамка сегмента полосы: у всех пяти она одна. */
function Segment({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <section className={`flex flex-col border border-space-700 bg-space-950/60 ${className ?? ''}`}>
      {children}
    </section>
  );
}

/** Плашка названия сегмента — как «Systems» в оригинале. */
function Plaque({ children }: { children: React.ReactNode }) {
  return (
    <span className="truncate border-b border-space-800 bg-space-900/60 px-2 py-0.5 text-center text-13 text-accent">
      {children}
    </span>
  );
}

/**
 * Карточка корабля, чей ход, — левый сегмент полосы.
 *
 * Имя плашкой сверху, под ней силуэт, внизу полоски состояния делениями: щит, броня,
 * прочность. Деления, а не числа, — как в оригинале: в бою важно «сколько осталось от
 * целого», а точные числа стоят рядом, в ящике систем.
 */
function ShipCard({ ship, mine, demo }: { ship: BattleShip | null; mine: boolean; demo: boolean }) {
  if (!ship) {
    return (
      <Segment className="w-40 flex-none">
        <Plaque>{t('battle.bar.noShip')}</Plaque>
      </Segment>
    );
  }
  return (
    <Segment className="w-40 flex-none">
      <Plaque>{shipLabel(ship)}</Plaque>
      <div className="flex min-h-0 flex-1 items-center justify-center bg-space-950 p-1">
        <svg viewBox="0 0 60 40" className="h-14 w-full">
          <ShipGlyph ship={ship} />
        </svg>
      </div>
      <span className="truncate px-2 text-12 text-ink-dim">
        {demo ? ship.ownerName : mine ? t('battle.bar.yourTurn') : t('battle.bar.theirTurn')}
      </span>
      {/*
        Сделан ли залп этим кораблём: стрелять он может раз за ход, и без этой строки
        игрок узнавал бы об этом отказом сервера. Остаток хода стоит рядом, в ящике систем.
      */}
      <span className="truncate px-2 text-12 text-ink-faint">
        {ship.fired ? t('battle.bar.fired') : t('battle.bar.ready')}
      </span>
      <div className="space-y-0.5 p-1">
        <Bar value={ship.shield} max={Math.max(ship.shield, 1)} color="#7dd3fc" />
        <Bar value={ship.armour} max={Math.max(ship.maxArmour, 1)} color="#fbbf24" />
        <Bar value={ship.structure} max={Math.max(ship.maxStructure, 1)} color="#f87171" />
      </div>
    </Segment>
  );
}

/**
 * Полоска состояния делениями — как в оригинале, где прочность и броня показаны рядами
 * цветных квадратиков. Делений всегда двадцать: длина полоски не должна прыгать от того,
 * сколько у корабля брони.
 */
function Bar({ value, max, color }: { value: number; max: number; color: string }) {
  const filled = Math.round((Math.max(0, value) / Math.max(1, max)) * 20);
  return (
    <span className="flex gap-px">
      {Array.from({ length: 20 }, (_, index) => (
        <span
          key={index}
          className="h-1.5 flex-1"
          style={{ background: index < filled ? color : '#16203a' }}
        />
      ))}
    </span>
  );
}

/** Силуэт корабля: платформа — сооружением, корабль — стрелкой по ходу. */
function ShipGlyph({ ship }: { ship: BattleShip }) {
  const color = sideColor(ship);
  if (ship.platform) {
    return (
      <g stroke={color} fill="none" strokeWidth="1.6">
        <circle cx="30" cy="20" r="11" />
        <circle cx="30" cy="20" r="4" fill={color} fillOpacity="0.4" />
        <path d="M 30 9 L 30 3 M 30 31 L 30 37 M 19 20 L 13 20 M 41 20 L 47 20" />
      </g>
    );
  }
  return (
    <g stroke={color} fill={color} fillOpacity="0.35" strokeWidth="1.6">
      <path d="M 16 12 L 44 20 L 16 28 L 21 20 Z" />
    </g>
  );
}

/**
 * Оружие и особые модули корабля — второй сегмент, вкладки `WEAPONS | SPECIALS`
 * оригинала.
 *
 * Список у разведчика пуст, и это не поломка: вспомогательный корабль оружия не несёт
 * вовсе — так и в оригинале, где на кадре у Scout обе вкладки пусты.
 */
function Loadout({ ship }: { ship: BattleShip | null }) {
  const [tab, setTab] = useState<'WEAPON' | 'SPECIAL'>('WEAPON');
  const weapons = ship?.weapons ?? [];
  const specials = ship?.specials ?? [];
  return (
    <Segment className="min-w-0 flex-1">
      <span className="flex border-b border-space-800 text-13">
        <Tab active={tab === 'WEAPON'} onClick={() => setTab('WEAPON')}>
          {t('battle.bar.weapons')}
        </Tab>
        <Tab active={tab === 'SPECIAL'} onClick={() => setTab('SPECIAL')}>
          {t('battle.bar.specials')}
        </Tab>
      </span>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 py-1 text-13 leading-snug">
        {tab === 'WEAPON' ? (
          weapons.length === 0 ? (
            <p className="text-ink-faint">{t('battle.bar.none')}</p>
          ) : (
            weapons.map((weapon, index) => (
              <p key={`${weapon.name}-${index}`} className="truncate text-ink">
                <span className="text-ink-bright">
                  {Math.max(0, weapon.count - weapon.wrecked)}
                </span>{' '}
                · {weapon.name} ·{' '}
                <span className="text-ink-soft">{t('battle.bar.damage', { n: weapon.damage })}</span>
                {weapon.modifications.length > 0 ? (
                  <span className="text-ink-dim"> · {weapon.modifications.join(', ')}</span>
                ) : null}
                {/* Выбитые гнёзда — п. 8: ствол на корабле есть, а стрелять им уже нечем. */}
                {weapon.wrecked > 0 ? (
                  <span className="text-danger"> · {t('battle.bar.wrecked', { n: weapon.wrecked })}</span>
                ) : null}
              </p>
            ))
          )
        ) : specials.length === 0 ? (
          <p className="text-ink-faint">{t('battle.bar.none')}</p>
        ) : (
          specials.map((special) => (
            <p key={special} className="truncate text-ink">
              {special}
            </p>
          ))
        )}
      </div>
    </Segment>
  );
}

function Tab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex-1 px-2 py-0.5 uppercase tracking-[0.15em] ${
        active ? 'bg-space-800 text-accent' : 'text-ink-dim hover:text-ink'
      }`}
    >
      {children}
    </button>
  );
}

/**
 * Кнопки хода — средний сегмент. В оригинале их шесть сеткой два на три
 * (`AUTO`, `SCAN`, `BOARD`, `RETREAT`, `WAIT`, `DONE`) и `OPTIONS` под ними.
 *
 * У нас пять: осмотр, отход, самоподрыв, «готов» и настройки. `AUTO` (досчитать бой
 * формулой), `BOARD` (абордаж) и `WAIT` (пропустить очередь и вернуться в конце круга) —
 * механик этих в игре нет вовсе, и кнопка, которая ничего не делает, хуже её отсутствия:
 * она обещает то, чего нет. Место под них в сетке остаётся.
 *
 * **Самоподрыв спрашивает подтверждения** (п. 8): это единственная кнопка боя, которая
 * уничтожает свой корабль наверняка, и промах по ней стоил бы дредноута. Второе нажатие
 * той же кнопки и есть подтверждение — окошка ради одной строки не заводим.
 */
function Actions({
  mine,
  busy,
  demo,
  over,
  canScan,
  onPass,
  onRetreat,
  onSelfDestruct,
  onScan,
  onOptions,
  onClose,
}: {
  mine: boolean;
  busy: boolean;
  demo: boolean;
  over: boolean;
  canScan: boolean;
  onPass: () => void;
  onRetreat: () => void;
  onSelfDestruct: () => void;
  onScan: () => void;
  onOptions: () => void;
  onClose: () => void;
}) {
  const [arming, setArming] = useState(false);
  const busyNow = demo || !mine || busy || over;
  return (
    <Segment className="w-56 flex-none">
      <div className="grid grid-cols-2 gap-1 p-1">
        <Action onClick={onScan} disabled={!canScan}>
          {t('battle.action.scan')}
        </Action>
        <Action onClick={onRetreat} disabled={demo || !mine || busy || over}>
          {t('battle.action.retreat')}
        </Action>
        <Action onClick={onPass} disabled={busyNow}>
          {t('battle.action.done')}
        </Action>
        <Action onClick={onOptions}>{t('battle.action.options')}</Action>
        <span className="col-span-2">
          <Action
            wide
            danger={arming}
            disabled={busyNow}
            onClick={() => {
              if (arming) {
                setArming(false);
                onSelfDestruct();
              } else {
                setArming(true);
              }
            }}
          >
            {arming ? t('battle.action.selfDestructConfirm') : t('battle.action.selfDestruct')}
          </Action>
        </span>
        <span className="col-span-2">
          <Action onClick={onClose} wide>
            {over ? t('common.closeEsc') : t('battle.minimize')}
          </Action>
        </span>
      </div>
    </Segment>
  );
}

function Action({
  children,
  onClick,
  disabled,
  wide,
  danger,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  wide?: boolean;
  /** Взведённая кнопка самоподрыва: красным, чтобы второе нажатие не было случайным. */
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={
        'border bg-space-900 py-1 text-13 uppercase tracking-[0.15em] '
        + (danger ? 'border-danger text-danger ' : 'border-space-600 ')
        + (disabled ? 'text-ink-off' : danger ? '' : 'text-ink hover:border-space-500 hover:text-accent ')
        + (wide ? ' w-full' : '')
      }
    >
      {children}
    </button>
  );
}

/**
 * Ящик систем — четвёртый сегмент, `Systems` оригинала: двигатель, щит, компьютер,
 * прочность, броня, а внизу отдельной парой скорость и остаток хода.
 *
 * Названия систем приходят с сервера вместе с кораблём: на поле корабль хранится числами,
 * а чем он собран — знает его проект.
 */
function Systems({ ship }: { ship: BattleShip | null }) {
  return (
    <Segment className="w-52 flex-none">
      <Plaque>{t('battle.systems')}</Plaque>
      {ship ? (
        <dl className="flex-1 space-y-0.5 px-2 py-1 text-13">
          <Row
            label={ship.engineName ?? t('battle.systems.drive')}
            value={ship.speed}
            wrecked={ship.engineWrecked}
          />
          {/*
            У системы, которой на корабле нет, числа не показываем вовсе: в оригинале
            там стоит просто «No Shields», без нуля справа. Ноль читался бы как значение.
          */}
          <Row
            label={ship.shieldName ?? t('battle.systems.noShield')}
            value={ship.shieldName ? ship.shield : null}
            wrecked={ship.shieldWrecked}
          />
          <Row
            label={ship.computerName ?? t('battle.systems.noComputer')}
            value={ship.computerName ? ship.attack : null}
            wrecked={ship.computerWrecked}
          />
          <Row label={t('battle.systems.structure')} value={ship.structure} />
          <Row label={ship.armourName ?? t('battle.systems.armour')} value={ship.armour} />
          <span className="block pt-1">
            <Row label={t('battle.systems.maxSpeed')} value={ship.speed} />
            <Row label={t('battle.systems.remaining')} value={ship.moveLeft} />
          </span>
        </dl>
      ) : null}
    </Segment>
  );
}

/**
 * Строка ящика систем; разбитая перечёркнута и подписана — п. 8.
 *
 * Перечёркнутого мало: зачёркнутую строку легко принять за «этого у корабля нет», а
 * разница велика — разбитый двигатель значит, что корабль уже никуда не уйдёт.
 */
function Row({
  label,
  value,
  wrecked,
}: {
  label: string;
  value: number | null;
  wrecked?: boolean;
}) {
  return (
    <div className="flex justify-between gap-2">
      <dt className={'truncate ' + (wrecked ? 'text-danger line-through' : 'text-ink-dim')}>
        {label}
      </dt>
      <dd className={'flex-none ' + (wrecked ? 'text-danger' : 'text-ink-bright')}>
        {wrecked ? t('battle.systems.wrecked') : value ?? ''}
      </dd>
    </div>
  );
}

/**
 * Мини-карта поля — правый сегмент: всё поле целиком точками, свои и чужие своими
 * цветами, корабль, чей ход, — кольцом.
 *
 * В оригинале она показывает и то, какая часть поля видна на экране; у нас поле влезает
 * целиком, поэтому рамки видимости нет, зато есть силы сторон числами — их раньше
 * показывала своя полоса сверху, а смотреть на них нужно ровно тогда, когда смотришь на
 * расстановку.
 */
function MiniMap({ battle }: { battle: Battle }) {
  const alive = battle.ships.filter((ship) => !ship.destroyed);
  return (
    <Segment className="w-56 flex-none">
      <svg viewBox={`0 0 ${battle.width} ${battle.height}`} className="min-h-0 flex-1 bg-space-950">
        {alive.map((ship) => (
          <g key={ship.id}>
            <rect
              x={ship.x}
              y={ship.y}
              width={1}
              height={1}
              fill={sideColor(ship)}
              fillOpacity={0.9}
            />
            {ship.id === battle.currentShipId ? (
              <circle
                cx={ship.x + 0.5}
                cy={ship.y + 0.5}
                r={1.4}
                fill="none"
                stroke="#fde68a"
                strokeWidth={0.4}
              />
            ) : null}
          </g>
        ))}
      </svg>
      <span className="flex justify-between border-t border-space-800 px-2 py-0.5 text-12">
        <span className="truncate text-accent">
          {battle.attackerName} {battle.attackerPower}
        </span>
        <span className="truncate text-danger">
          {battle.defenderPower} {battle.defenderName}
        </span>
      </span>
    </Segment>
  );
}
