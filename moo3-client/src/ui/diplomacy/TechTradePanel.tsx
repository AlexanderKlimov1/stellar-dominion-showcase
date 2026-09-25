import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { DiplomacyActionCode, TechTrade, TechnologyOffer } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { t, useT } from '../../i18n';

/**
 * Обмен технологиями и подарки — п. 15, самое ходовое в дипломатии MOO II.
 *
 * Открывается под карточкой знакомой империи и работает тремя действиями оригинала:
 *
 * * **обмен** — «моя технология за твою». Обе половины называются сразу, поэтому и
 *   списка два: слева то, чего нет у соседа, справа то, чего нет у меня. Технологии,
 *   которые есть у обоих, в сделке бессмысленны, и сервер их не присылает;
 * * **подарок технологией** — то же, но без встречной просьбы;
 * * **подарок деньгами** — сумма из казны.
 *
 * У каждой строки стоит её ценность в очках исследований: сосед соглашается на обмен,
 * когда получает не дешевле, чем отдаёт, — правило оригинала. Без этих чисел отказ
 * выглядел бы произволом, поэтому окно показывает их, а не прячет.
 *
 * Списки живут на сервере и меняются от чужих ходов (сосед мог доисследовать своё),
 * поэтому запрашиваются при каждом открытии, а не берутся из общего состояния.
 */
export function TechTradePanel({
  playerId,
  playerName,
  busy,
  onAct,
}: {
  /** Империя, с которой торгуют. */
  playerId: string;
  playerName: string;
  busy: boolean;
  onAct: (
    action: DiplomacyActionCode,
    deal: { offeredTech?: string; requestedTech?: string; credits?: number },
  ) => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const self = useGameStore((state) => state.players.find((p) => p.id === credentials?.playerId));

  const [trade, setTrade] = useState<TechTrade | null>(null);
  const [offered, setOffered] = useState<string>('');
  const [requested, setRequested] = useState<string>('');
  const [credits, setCredits] = useState<string>('');
  const [error, setError] = useState<string | null>(null);

  /*
    Списки перезапрашиваются и после каждой сделки: обмен меняет обе стороны сразу, и
    отданная технология должна уйти из «моего», а полученная — из «их». Поэтому в
    зависимостях стоит busy: он падает в false, когда сервер ответил.
  */
  useEffect(() => {
    if (!game || !credentials || busy) {
      return;
    }
    let alive = true;
    gameApi
      .getTradeableTechnologies(game.id, credentials.accessToken, playerId)
      .then((fresh) => {
        if (alive) {
          setTrade(fresh);
          setError(null);
        }
      })
      .catch((failure: Error) => alive && setError(failure.message));
    return () => {
      alive = false;
    };
  }, [game, credentials, playerId, busy]);

  useT();
  if (error) {
    return <p className="mt-2 text-11 text-danger">{error}</p>;
  }
  if (!trade) {
    return <p className="mt-2 text-11 text-ink-faint">{t('trade.loading')}</p>;
  }

  const offer = trade.offer.find((tech) => tech.code === offered) ?? null;
  const want = trade.wanted.find((tech) => tech.code === requested) ?? null;
  // То же правило, что на сервере: сосед соглашается, получая не дешевле, чем отдаёт.
  // Показываем это заранее — отказ не должен быть неожиданностью.
  const fair = offer !== null && want !== null && offer.value >= want.value;

  return (
    <div className="mt-2 border-t border-space-800 pt-2 text-11">
      <div className="grid gap-2 md:grid-cols-2">
        <TechSelect
          label={t('trade.give', { name: playerName })}
          empty={t('trade.give.empty')}
          options={trade.offer}
          value={offered}
          onChange={setOffered}
        />
        <TechSelect
          label={t('trade.receive')}
          empty={t('trade.receive.empty')}
          options={trade.wanted}
          value={requested}
          onChange={setRequested}
        />
      </div>

      <div className="mt-2 flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <button
          type="button"
          className="link disabled:cursor-not-allowed disabled:text-ink-off"
          disabled={busy || !offer || !want}
          title={
            offer && want && !fair
              ? t('trade.unfair.title')
              : t('trade.propose.title')
          }
          onClick={() => onAct('EXCHANGE_TECH', { offeredTech: offered, requestedTech: requested })}
        >
          {t('trade.exchange')}
        </button>
        {offer && want ? (
          <span className={fair ? 'text-ink-dim' : 'text-warn'}>
            {t('trade.values', { offer: offer.value, want: want.value })}
            {fair ? t('trade.fair') : t('trade.unfair')}
          </span>
        ) : null}

        <button
          type="button"
          className="link disabled:cursor-not-allowed disabled:text-ink-off"
          disabled={busy || !offer}
          title={t('trade.giftTech.title')}
          onClick={() => onAct('GIFT_TECH', { offeredTech: offered })}
        >
          {t('trade.giftTech')}
        </button>
      </div>

      <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="text-ink-dim">{t('trade.giftCredits')}</span>
        <input
          type="number"
          min={1}
          max={self?.credits ?? undefined}
          value={credits}
          onChange={(event) => setCredits(event.target.value)}
          className="w-24 border border-space-700 bg-space-900 px-2 py-0.5 text-11 text-ink-bright"
          placeholder={t('trade.credits.placeholder')}
        />
        <button
          type="button"
          className="link disabled:cursor-not-allowed disabled:text-ink-off"
          disabled={busy || !(Number(credits) > 0)}
          onClick={() => onAct('GIFT_CREDITS', { credits: Number(credits) })}
        >
          {t('trade.gift')}
        </button>
        <span className="text-ink-faint">{t('trade.treasury', { n: self?.credits ?? 0 })}</span>
      </div>
    </div>
  );
}

/** Список технологий с ценностью: тем же порядком, что и в дереве. */
function TechSelect({
  label,
  empty,
  options,
  value,
  onChange,
}: {
  label: string;
  empty: string;
  options: TechnologyOffer[];
  value: string;
  onChange: (code: string) => void;
}) {
  return (
    <label className="block">
      <span className="block text-10 uppercase tracking-[0.2em] text-ink-dim">{label}</span>
      {options.length === 0 ? (
        <span className="text-ink-faint">{empty}</span>
      ) : (
        <select
          className="mt-0.5 w-full border border-space-700 bg-space-900 px-1 py-0.5 text-11 text-ink"
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          <option value="">{t('trade.notChosen')}</option>
          {options.map((tech) => (
            <option key={tech.code} value={tech.code}>
              {t('trade.option', { name: tech.name, value: tech.value })}
            </option>
          ))}
        </select>
      )}
    </label>
  );
}
