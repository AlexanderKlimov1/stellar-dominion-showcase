import { useState } from 'react';

import { gameApi, GameApiError } from '../../api/client';
import { readRevealGalaxy, writeRevealGalaxy } from '../../state/settings';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { t } from '../../i18n';

/**
 * Справочник по галактике — своя страница окна «Инфо», не из оригинала.
 *
 * В MOO II правила лежат в бумажном руководстве и во всплывающих подсказках по правой
 * кнопке, которых здесь нет. Пока их нет, числа галактики, планет и терраформирования
 * живут страницей окна «Инфо»: это ближайшее место, где игрок их ищет.
 *
 * Здесь же отладочный показ всей галактики: он про игру целиком, а не про партию, и
 * другого места ему не нашлось.
 */
export function GalaxyReference() {
  return (
    <div>
      <EmpireSummary />
      <RevealGalaxyOption />

      <div className="grid gap-8 text-xs leading-relaxed text-ink-soft lg:grid-cols-2">
        <section>
          <div className="panel-title">{t('reference.galaxy')}</div>
          <p>
            {t('reference.galaxy.text')}
          </p>
          <ul className="mt-2 space-y-1 text-ink-dim">
            <li>{t('reference.galaxy.small')}</li>
            <li>{t('reference.galaxy.medium')}</li>
            <li>{t('reference.galaxy.large')}</li>
            <li>{t('reference.galaxy.huge')}</li>
          </ul>
          <p className="mt-2">
            {t('reference.galaxy.range')}
          </p>
        </section>

        <section>
          <div className="panel-title">{t('reference.planets')}</div>
          <p>
            {t('reference.planets.text')}
          </p>
          <ul className="mt-2 space-y-1 text-ink-dim">
            <li>{t('reference.planets.sizes')}</li>
            <li>{t('reference.planets.climate')}</li>
            <li>{t('reference.planets.minerals')}</li>
          </ul>
        </section>

        <section>
          <div className="panel-title">{t('reference.terraforming')}</div>
          <p>
            {t('reference.terraforming.text')}
          </p>
        </section>

        <section>
          <div className="panel-title">{t('reference.controls')}</div>
          <ul className="space-y-1 text-ink-dim">
            <li>{t('reference.controls.drag')}</li>
            <li>{t('reference.controls.wheel')}</li>
            <li>{t('reference.controls.click')}</li>
            <li>{t('reference.controls.invade')}</li>
            <li>{t('reference.controls.dblclick')}</li>
            <li>{t('reference.controls.zoom')}</li>
          </ul>
        </section>
      </div>
    </div>
  );
}

/** Своя империя одной строкой: раса, правительство, разведка и корабли. */
function EmpireSummary() {
  const self = useGameStore(selectSelf);
  const espionage = useGameStore((state) => state.espionage);
  const fleet = useGameStore((state) => state.fleet);

  if (!self) {
    return null;
  }

  return (
    <div className="mb-4 grid grid-cols-2 gap-x-8 gap-y-1 border border-space-700 bg-space-950/60 px-3 py-2 text-11 lg:grid-cols-4">
      <Fact label={t('reference.race')} value={self.raceName ?? self.raceCode} />
      <Fact label={t('profiles.government')} value={self.government ?? t('profiles.notChosen')} />
      <Fact
        label={t('reference.espionage')}
        value={
          espionage
            ? t('reference.espionage.value', { points: espionage.points, perTurn: espionage.pointsPerTurn, spies: espionage.spies })
            : t('reference.espionage.points', { n: self.espionagePoints })
        }
      />
      <Fact
        label={t('reference.ships')}
        value={
          fleet
            ? t('reference.ships.value', { attack: percent(fleet.attackPercent), defence: percent(fleet.defensePercent) })
            : '—'
        }
      />
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

/** Проценты расы со знаком; ноль — обычная раса, а не «+0%». */
const percent = (value: number): string =>
  value === 0 ? t('side.fleet.normal') : `${value > 0 ? '+' : ''}${value}%`;

/**
 * Флаг «Показать галактику».
 *
 * Звёзды на карте видны все, но неразведанные системы безымянны: их названия, планеты и
 * владельцев открывает разведка кораблями (п. 15). Флаг раскрывает их сразу по всей
 * галактике — он отладочный, знакомств от него не случается. Разведанность считает
 * сервер, поэтому переключение перезапрашивает карту: сцена перерисуется сама, как
 * только новая карта попадёт в хранилище.
 */
function RevealGalaxyOption() {
  const setMap = useGameStore((state) => state.setMap);
  const setError = useGameStore((state) => state.setError);
  const map = useGameStore((state) => state.map);
  const [reveal, setReveal] = useState(readRevealGalaxy);
  const [pending, setPending] = useState(false);

  const toggle = () => {
    const { game, credentials } = useGameStore.getState();
    if (!game || !credentials || pending) {
      return;
    }
    const next = !reveal;
    setPending(true);
    gameApi
      .getMap(game.id, credentials.accessToken, next)
      .then((loaded) => {
        // Настройку запоминаем только после удачного ответа: иначе после перезагрузки
        // страницы флаг стоял бы, а карта осталась прежней.
        writeRevealGalaxy(next);
        setReveal(next);
        setMap(loaded);
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('reference.reloadFailed')),
      )
      .finally(() => setPending(false));
  };

  return (
    <section className="mb-6 border border-space-700 px-3 py-2">
      <div className="panel-title">{t('reference.map')}</div>
      <label className="flex cursor-pointer items-center gap-2 text-xs text-ink">
        <input
          type="checkbox"
          className="accent-accent"
          checked={reveal}
          disabled={pending}
          onChange={toggle}
        />
        {t('reference.reveal')}
      </label>
      <p className="mt-2 text-11 leading-relaxed text-ink-dim">
        {t('reference.reveal.hint', { n: map?.systems.length ?? 0 })}{pending ? t('reference.reveal.updating') : ''}.
      </p>
    </section>
  );
}
