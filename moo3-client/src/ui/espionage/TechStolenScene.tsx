import type { ResearchTree, TurnReport } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Украденная технология — п. 13, сцена MOO II (`docs/moo2/README.md`, `tech-stolen.png`).
 *
 * В оригинале кража — не строка отчёта, а маленькая сцена: лаборатория, окно с названием
 * и описанием технологии и строка во всю ширину — «Adamantium Armor technology was stolen
 * from the Psilon by your spy!». Смысл её в том, ЧТО именно принёс агент: по названию
 * технологии игрок не помнит, что она даёт, а искать её в дереве после каждой кражи —
 * плохой обмен.
 *
 * Здесь то же самое: лаборатория набрана линиями (картинок у игры нет), название и
 * описание берутся из дерева технологий, которое у клиента и так есть, а внизу — та самая
 * строка.
 *
 * <b>Сцена показывает и ПОТЕРЮ.</b> Кража видна обеим сторонам: у кого украли, узнаёт об
 * этом тем же ходом, и для него сцена говорит другое — «у вашей империи выкрали». Кто
 * украл, жертве неизвестно (так и в игре: {@code turn.espionage.stolenFromYou} имени не
 * называет).
 */
export function TechStolenScene({
  theft,
  tree,
  onClose,
}: {
  theft: Theft;
  /** Дерево технологий: из него берутся название и описание украденного. */
  tree: ResearchTree | null;
  onClose: () => void;
}) {
  useT();
  useModalEscape(true, onClose);

  const technology = findTechnology(tree, theft.code);

  return (
    <div className="fixed inset-0 z-[58] flex flex-col bg-space-950">
      <Laboratory />

      <div className="relative flex min-h-0 flex-1 items-center justify-center p-6">
        {/* Окно с самой технологией — как в оригинале, поверх картины лаборатории. */}
        <div className="panel w-[min(34rem,90vw)] border-2 border-space-600 p-4">
          <p className="mb-2 text-center text-18 text-accent">
            {technology?.name ?? theft.code}
          </p>
          <p className="text-14 leading-relaxed text-ink">
            {technology?.description ?? t('espionage.stolen.noDescription')}
          </p>
          {technology ? (
            <p className="mt-3 text-12 uppercase tracking-[0.2em] text-ink-dim">
              {technology.category} · {technology.level}
            </p>
          ) : null}
        </div>
      </div>

      {/* Строка во всю ширину внизу — главная надпись сцены. */}
      <footer className="relative flex flex-none flex-col items-center gap-4 px-6 pb-8 text-center">
        <p className="text-18 leading-snug text-good">
          {theft.mine
            ? t('espionage.stolen.byYou', {
                technology: technology?.name ?? theft.code,
                empire: theft.empire ?? '',
              })
            : t('espionage.stolen.fromYou', {
                technology: technology?.name ?? theft.code,
              })}
        </p>
        <button
          type="button"
          className="border border-space-600 bg-space-900 px-8 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {t('common.close')}
        </button>
      </footer>
    </div>
  );
}

/** Что и у кого украдено — всё, что нужно сцене. */
export interface Theft {
  /** Код технологии: по нему она ищется в дереве. */
  code: string;
  /** У кого украли; пусто — украли у нас, а вор неизвестен. */
  empire?: string;
  /** Украл наш агент (иначе украли у нас). */
  mine: boolean;
}

/** Ключи событий кражи — те же, что у сервера (`EspionageService`). */
const STOLE = 'turn.espionage.stole';
const STOLEN_FROM_YOU = 'turn.espionage.stolenFromYou';

/** Ссылка на технологию в подстановке события: `catalog.tech.<код>` — п. 3.5. */
const TECH_PREFIX = 'catalog.tech.';

/**
 * Кража технологии из отчёта хода; `null` — её не было.
 *
 * <b>Событие узнаётся по КЛЮЧУ, а не по тексту</b>: текст собран на языке игрока и
 * меняется вместе с ним, а ключ один на оба языка. Код технологии лежит в подстановке
 * ссылкой на справочник — из неё он и берётся.
 */
export function stolenTechnology(report: TurnReport | null): Theft | null {
  const event = report?.events.find(
    (one) => one.key === STOLE || one.key === STOLEN_FROM_YOU,
  );
  if (!event) {
    return null;
  }
  const reference = (event.args ?? []).find((arg) => arg.startsWith(TECH_PREFIX));
  if (!reference) {
    return null;
  }
  const mine = event.key === STOLE;
  return {
    code: reference.slice(TECH_PREFIX.length),
    // У кражи в нашу пользу первой подстановкой стоит имя обокраденной империи;
    // у кражи из наших рук имени нет вовсе — жертва не знает вора.
    empire: mine ? event.args?.[0] : undefined,
    mine,
  };
}

/** Название и описание украденного — из дерева, которое у клиента уже есть. */
function findTechnology(tree: ResearchTree | null, code: string) {
  for (const category of tree?.categories ?? []) {
    for (const level of category.levels) {
      const option = level.options.find((one) => one.code === code);
      if (option) {
        return {
          name: option.name,
          description: option.description,
          category: category.name,
          level: level.name,
        };
      }
    }
  }
  return null;
}

/**
 * Лаборатория: пол, стойка с лучом и фигура у края — линиями и заливками.
 *
 * Картинок у игры нет, а пустой чёрный экран под окном с технологией читался бы как
 * поломка. Луч зелёный — тот же цвет, каким в оригинале светится установка на кадре.
 */
function Laboratory() {
  return (
    <svg
      viewBox="0 0 100 60"
      preserveAspectRatio="none"
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="lab-beam" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#4ade80" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#4ade80" stopOpacity="0.04" />
        </linearGradient>
        <linearGradient id="lab-floor" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#0b1220" stopOpacity="0" />
          <stop offset="100%" stopColor="#1c2440" stopOpacity="0.7" />
        </linearGradient>
      </defs>
      <rect x="0" y="0" width="100" height="60" fill="url(#lab-floor)" />

      {/* Установка справа: луч из потолка в основание — на кадре он зелёный. */}
      <polygon points="74,0 86,0 82,44 78,44" fill="url(#lab-beam)" />
      <ellipse cx="80" cy="45" rx="9" ry="2.4" fill="#16203a" />
      <ellipse cx="80" cy="44" rx="6" ry="1.6" fill="#4ade80" fillOpacity="0.25" />

      {/* Фигура слева: силуэт в балахоне — тот, кто принимает добытое. */}
      <path
        d="M 16 50 C 16 34, 20 26, 26 24 C 30 22, 33 26, 33 32 L 33 50 Z"
        fill="#1c2440"
        stroke="#2b3765"
        strokeWidth="0.4"
      />
      <ellipse cx="26" cy="22" rx="4" ry="4.6" fill="#1c2440" stroke="#2b3765" strokeWidth="0.4" />

      {/* Стойки и панели по стенам — чтобы зал читался залом. */}
      {[6, 44, 58, 94].map((x) => (
        <line key={x} x1={x} y1="2" x2={x} y2="50" stroke="#16203a" strokeWidth="0.5" />
      ))}
      <line x1="0" y1="50" x2="100" y2="50" stroke="#16203a" strokeWidth="0.6" />
    </svg>
  );
}
