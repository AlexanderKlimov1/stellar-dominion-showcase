import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type {
  DiplomacyActionCode,
  DiplomacyRelation,
  TreatyCode,
} from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { TechTradePanel } from './TechTradePanel';
import { t, useT, type Key } from '../../i18n';

/**
 * Переговоры с послом — п. 15, экран Diplomacy MOO II
 * (`docs/moo2/README.md`, `diplomacy-talks.png`).
 *
 * В оригинале это РАЗГОВОР С ОДНИМ послом, а не пульт со всеми сразу: зал занимает экран
 * целиком, сверху по центру «<раса> Ambassador» цветом его империи, слева вверху строка
 * «How may I serve you:» и под ней предложения простыми строками без рамок, а внизу по
 * центру — речь посла. Всё. Ни таблицы отношений, ни доверия числом, ни агентов: этому
 * место на пульте «Расы», откуда к послу и приходят.
 *
 * <b>Прежде здесь был пульт</b>: карточка на каждую знакомую империю со всеми кнопками
 * разом и список своих агентов внизу. Разговором это не было, а всё, что он показывал,
 * уже есть на пульте «Расы» (`ui/races/RaceRelationsScreen.tsx`) — включая задания
 * разведки, которые в оригинале стоят именно там.
 *
 * <b>Предложения открывают подменю</b> — как в оригинале, где выбор «Propose Treaty»
 * сменяет список: договор выбирается из четырёх, расторжение — из подписанных, обмен
 * технологиями открывает свой ящик.
 *
 * <b>У отталкивающей расы список короче</b> (п. 7): остаются война и мир. Это правило
 * самой игры — договоров, дани и обмена для такой империи не существует, — и признак
 * приходит с сервера (`negotiates`), а не угадывается по расе: отталкивающей может быть
 * и раса самого игрока.
 */
export function AudienceScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const audiencePlayerId = useGameStore((state) => state.audiencePlayerId);
  const setAudience = useGameStore((state) => state.setAudience);
  const setLobby = useGameStore((state) => state.setLobby);
  const setResearch = useGameStore((state) => state.setResearch);

  const [relations, setRelations] = useState<DiplomacyRelation[] | null>(null);
  const [menu, setMenu] = useState<'main' | 'treaty' | 'break' | 'trade'>('main');
  const [busy, setBusy] = useState(false);
  const [speech, setSpeech] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useT();
  useModalEscape(true, onClose);

  // Отношения живут на сервере и меняются от чужих ходов, поэтому экран спрашивает их
  // при каждом открытии, а не берёт из общего состояния.
  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .getRelations(game.id, credentials.accessToken)
      .then(setRelations)
      .catch((failure: Error) => setError(failure.message));
  }, [game, credentials]);

  const partner = relations?.find((one) => one.playerId === audiencePlayerId) ?? null;

  const act = (
    action: DiplomacyActionCode,
    treaty?: TreatyCode,
    deal?: { offeredTech?: string; requestedTech?: string; credits?: number },
  ) => {
    if (!game || !credentials || !partner) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .diplomacy(game.id, credentials.accessToken, partner.playerId, action, treaty, deal)
      .then((updated) => {
        setRelations((current) =>
          (current ?? []).map((one) => (one.playerId === updated.playerId ? updated : one)),
        );
        // Ответ посла — то самое, что в оригинале написано внизу экрана.
        setSpeech(updated.answer ?? null);
        setMenu('main');
        /*
          Обмен и подарки меняют не только отношения: казна убывает, изученное
          прибавляется. Соседний экран остался бы с прошлыми числами, поэтому состав
          игроков и исследования перечитываются — та же грабля, что с концом хода.
        */
        Promise.all([
          gameApi.getGame(game.id),
          gameApi.getResearch(game.id, credentials.accessToken),
        ])
          .then(([details, research]) => {
            setLobby(details.game, details.players);
            setResearch(research);
          })
          .catch(() => undefined);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  // Собеседник не выбран — спрашиваем, с кем говорить: в оригинале к послу приходят с
  // пульта «Расы», но экран открывается и из меню, и пустым он быть не должен.
  if (!partner) {
    return (
      <div className="fixed inset-0 z-40 flex flex-col items-center justify-center gap-4 bg-space-950 p-6">
        <p className="text-16 text-accent">{t('audience.pick')}</p>
        {relations === null ? (
          <p className="text-13 text-ink-faint">{t('diplomacy.loading')}</p>
        ) : relations.length === 0 ? (
          <p className="text-13 text-ink-dim">{t('diplomacy.noContacts')}</p>
        ) : (
          <ul className="flex flex-col items-center gap-2">
            {relations.map((one) => (
              <li key={one.playerId}>
                <button
                  type="button"
                  className="text-15 hover:text-accent"
                  style={{ color: one.color ?? undefined }}
                  onClick={() => setAudience(one.playerId)}
                >
                  {one.playerName ?? one.raceName}
                </button>
              </li>
            ))}
          </ul>
        )}
        <button
          type="button"
          className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {t('audience.bye')}
        </button>
      </div>
    );
  }

  const colour = partner.color ?? '#7d8aa3';
  const signed = new Set(partner.treaties);
  const negotiates = partner.negotiates !== false;

  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-space-950">
      <Hall colour={colour} />
      <Ambassador colour={colour} />

      {/* Плашка с послом по центру сверху — цветом его империи, как в оригинале. */}
      <header className="relative py-4 text-center text-18" style={{ color: colour }}>
        {t('audience.title', { name: partner.playerName ?? partner.raceName ?? '' })}
      </header>

      {/* Список предложений слева вверху: строки без рамок — так в оригинале. */}
      <div className="relative min-h-0 flex-1 px-8">
        <div className="w-[min(26rem,60%)]">
          <p className="mb-1 text-15 text-good">{t('audience.serve')}</p>

          {menu === 'main' ? (
            <ul className="space-y-0.5">
              <Option
                label="audience.peace"
                disabled={busy || partner.stance !== 'WAR'}
                onClick={() => act('PROPOSE_PEACE')}
              />
              {negotiates ? (
                <>
                  <Option
                    label="audience.proposeTreaty"
                    disabled={busy || signed.size >= TREATIES.length}
                    onClick={() => setMenu('treaty')}
                  />
                  <Option
                    label="audience.breakTreaty"
                    disabled={busy || signed.size === 0}
                    onClick={() => setMenu('break')}
                  />
                  <Option
                    label="audience.demand"
                    disabled={busy}
                    onClick={() => act('DEMAND_TRIBUTE')}
                  />
                  <Option
                    label="audience.trade"
                    disabled={busy}
                    onClick={() => setMenu('trade')}
                  />
                </>
              ) : null}
              <Option
                label="audience.war"
                disabled={busy || partner.stance === 'WAR'}
                onClick={() => act('DECLARE_WAR')}
              />
              <Option label="audience.bye" disabled={false} onClick={onClose} />
            </ul>
          ) : null}

          {menu === 'treaty' ? (
            <ul className="space-y-0.5">
              {TREATIES.map((treaty) => (
                <li key={treaty.code}>
                  <Line
                    disabled={busy || signed.has(treaty.code)}
                    onClick={() => act('PROPOSE_TREATY', treaty.code)}
                  >
                    {t(treaty.label)}
                  </Line>
                </li>
              ))}
              <Option label="audience.back" disabled={false} onClick={() => setMenu('main')} />
            </ul>
          ) : null}

          {menu === 'break' ? (
            <ul className="space-y-0.5">
              {TREATIES.filter((treaty) => signed.has(treaty.code)).map((treaty) => (
                <li key={treaty.code}>
                  <Line disabled={busy} onClick={() => act('BREAK_TREATY', treaty.code)}>
                    {t(treaty.label)}
                  </Line>
                </li>
              ))}
              <Option label="audience.back" disabled={false} onClick={() => setMenu('main')} />
            </ul>
          ) : null}

          {menu === 'trade' ? (
            <div className="border border-space-700 bg-space-950/80 p-2">
              <TechTradePanel
                playerId={partner.playerId}
                playerName={partner.playerName ?? partner.raceName ?? ''}
                busy={busy}
                onAct={(action, deal) => act(action, undefined, deal)}
              />
              <ul className="mt-1">
                <Option label="audience.back" disabled={false} onClick={() => setMenu('main')} />
              </ul>
            </div>
          ) : null}

          {/*
            Почему список короток: отталкивающая раса переговоров не ведёт вовсе (п. 7).
            В оригинале это просто короткий список, но наш игрок не обязан помнить правило
            наизусть — иначе он решит, что у игры что-то сломалось.
          */}
          {negotiates ? null : (
            <p className="mt-2 text-12 text-ink-dim">{t('audience.repulsive')}</p>
          )}
        </div>
      </div>

      {/* Речь посла внизу по центру — главная строка экрана. */}
      <footer className="relative px-8 pb-8 text-center">
        <p className="mx-auto max-w-[min(56rem,90%)] text-16 leading-snug text-good">
          {speech
            ?? t('audience.greeting', {
              name: partner.playerName ?? partner.raceName ?? '',
              race: partner.raceName ?? '',
            })}
        </p>
        <p className="mt-2 text-12 text-ink-dim">
          {partner.stanceLabel}
          {partner.treatyLabels.length > 0 ? ' · ' + partner.treatyLabels.join(' · ') : ''}
          {partner.character ? ' · ' + partner.character : ''}
        </p>
        {error ? <p className="mt-2 text-12 text-danger">{error}</p> : null}
      </footer>
    </div>
  );
}

/** Договоры MOO II — те же четыре, что и на пульте «Расы». */
const TREATIES: { code: TreatyCode; label: Key }[] = [
  { code: 'NON_AGGRESSION', label: 'diplomacy.treaty.NON_AGGRESSION' },
  { code: 'TRADE', label: 'diplomacy.treaty.TRADE' },
  { code: 'RESEARCH', label: 'diplomacy.treaty.RESEARCH' },
  { code: 'ALLIANCE', label: 'diplomacy.treaty.ALLIANCE' },
];

/** Строка предложения: текст без рамки, недоступное гаснет — как в оригинале. */
function Option({
  label,
  disabled,
  onClick,
}: {
  label: Key;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <li>
      <Line disabled={disabled} onClick={onClick}>
        {t(label)}
      </Line>
    </li>
  );
}

function Line({
  children,
  disabled,
  onClick,
}: {
  children: React.ReactNode;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={
        'block w-full text-left text-15 '
        + (disabled ? 'cursor-not-allowed text-ink-off' : 'text-good hover:text-accent')
      }
    >
      {children}
    </button>
  );
}

/**
 * Зал приёмов: колонны, свод и силуэт посла посередине.
 *
 * Картин у игры нет, а разговор в пустоте — не разговор: в оригинале весь экран занимает
 * посол в своём зале. Силуэт красится цветом его империи — по нему и видно, с кем говорят.
 */
function Hall({ colour }: { colour: string }) {
  return (
    <svg
      viewBox="0 0 100 60"
      preserveAspectRatio="none"
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="audience-floor" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#0b1220" stopOpacity="0" />
          <stop offset="100%" stopColor="#1c2440" stopOpacity="0.75" />
        </linearGradient>
        <radialGradient id="audience-glow">
          <stop offset="0%" stopColor={colour} stopOpacity="0.28" />
          <stop offset="100%" stopColor={colour} stopOpacity="0" />
        </radialGradient>
      </defs>
      <rect x="0" y="0" width="100" height="60" fill="url(#audience-floor)" />
      <ellipse cx="50" cy="34" rx="26" ry="20" fill="url(#audience-glow)" />

      {/* Свод: три дуги одна над другой. */}
      {[6, 12, 18].map((depth) => (
        <path
          key={depth}
          d={`M -5 ${depth + 20} Q 50 ${depth - 12} 105 ${depth + 20}`}
          fill="none"
          stroke="#1c2440"
          strokeWidth="0.6"
        />
      ))}
      {/* Колонны по сторонам. */}
      {[8, 20, 80, 92].map((x) => (
        <line key={x} x1={x} y1="4" x2={x} y2="56" stroke="#16203a" strokeWidth="0.8" />
      ))}
      <line x1="0" y1="56" x2="100" y2="56" stroke="#16203a" strokeWidth="0.6" />
    </svg>
  );
}

/**
 * Посол посередине зала — капюшон, плечи и полы балахона.
 *
 * <b>Своим SVG, а не в фоне</b>: у зала растяжение выключено (`preserveAspectRatio="none"`,
 * иначе своды и колонны не заполнили бы экран любой ширины), и фигура в нём вытягивалась
 * вслед за окном — капюшон превращался в колпак вдвое выше головы. Здесь пропорции
 * сохраняются, а место задаётся высотой.
 *
 * Силуэт нарочно не человеческий в деталях: империи в игре разные, и одна фигура служит
 * всем; цвет у неё её империи — по нему и видно, с кем говорят.
 */
function Ambassador({ colour }: { colour: string }) {
  return (
    <svg
      viewBox="0 0 100 100"
      className="pointer-events-none absolute bottom-[16%] left-1/2 h-[48%] -translate-x-1/2"
      aria-hidden="true"
    >
      <g fill="#131a2e" stroke={colour} strokeOpacity="0.5" strokeWidth="1.2">
        {/* полы балахона */}
        <path d="M 20 100 C 22 72, 34 52, 50 48 C 66 52, 78 72, 80 100 Z" />
        {/* плечи внахлёст, чтобы фигура не читалась каплей */}
        <path d="M 26 62 C 34 50, 66 50, 74 62 C 64 54, 36 54, 26 62 Z" />
        {/* капюшон */}
        <path d="M 40 48 C 40 30, 44 22, 50 22 C 56 22, 60 30, 60 48 C 56 44, 44 44, 40 48 Z" />
        {/* лицо в тени капюшона */}
        <ellipse cx="50" cy="36" rx="5" ry="6" fill="#0b1220" strokeOpacity="0.25" />
      </g>
    </svg>
  );
}
