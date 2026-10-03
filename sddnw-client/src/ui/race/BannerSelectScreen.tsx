import { useState } from 'react';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { BANNERS, bannerTaken, type BannerCode } from './banners';

/**
 * Выбор знамени перед партией — п. 3.2, окно выбора цвета MOO II.
 *
 * В оригинале после расы игрок выбирает знамя: восемь флагов, и цвет выбранного — это
 * цвет его империи на карте, в списках и в бою. Оправа та же, что у выбора расы: плашка
 * названия сверху, поле картинками, полоса кнопок внизу; выбор — нажатием по флагу, как
 * и портрет расы, отдельного «принять» нет. Снимка этого окна у меня нет — разметка взята
 * с соседнего окна выбора расы (`docs/moo2/race-select.png`), с которым оно в оригинале
 * идёт подряд.
 *
 * **Занятое знамя гаснет** — тем, кто уже сидит в партии (вход в чужую игру). Сервер
 * откажет и сам, но узнавать об этом после трёх экранов входа — плохой обмен.
 */
export function BannerSelectScreen({
  title,
  takenColors,
  initial,
  escapeActive,
  onPick,
  onBack,
}: {
  /** Заголовок: экран открывается и на создание партии, и на вход в чужую. */
  title: string;
  /** Цвета империй, уже сидящих в партии: их знамёна не взять. */
  takenColors: string[];
  /** Знамя, с которым экран открывается: последнее выбранное в этом сеансе. */
  initial: BannerCode | null;
  /** Слушает ли экран Esc: пока поверх него окошко с именами, нажатие принадлежит ему. */
  escapeActive: boolean;
  onPick: (banner: BannerCode) => void;
  onBack: () => void;
}) {
  const [looking, setLooking] = useState<BannerCode | null>(initial);
  useModalEscape(escapeActive, onBack);
  useT();

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(94vw,46rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        <h2 className="mx-auto w-[52%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('banner.select.title')}
        </h2>
        <p className="mb-[3%] mt-1 text-center text-12 text-ink-dim">{title}</p>

        {/* Восемь знамён двумя рядами — по числу империй в партии, у каждой своё. */}
        <ul className="grid grid-cols-4 gap-[3%]">
          {BANNERS.map((banner) => {
            const taken = bannerTaken(banner.hex, takenColors);
            const lit = looking === banner.code && !taken;
            return (
              <li key={banner.code}>
                <button
                  type="button"
                  className="block w-full text-left disabled:cursor-not-allowed"
                  disabled={taken}
                  title={taken ? t('banner.taken') : t(banner.label)}
                  aria-label={t(banner.label)}
                  onClick={() => onPick(banner.code)}
                  onMouseEnter={() => setLooking(banner.code)}
                  onFocus={() => setLooking(banner.code)}
                >
                  <span
                    className={
                      'block aspect-square w-full border bg-space-950 p-[12%] '
                      + (lit ? 'border-accent' : 'border-space-600')
                      + (taken ? ' opacity-30' : '')
                    }
                  >
                    <BannerFlag colour={banner.hex} />
                  </span>
                  <span
                    className={
                      'mt-1 block truncate border border-space-700 bg-space-800 px-1 py-0.5 text-center text-12 '
                      + (lit ? 'text-accent' : taken ? 'text-ink-off' : 'text-ink-soft')
                    }
                  >
                    {taken ? t('banner.takenShort') : t(banner.label)}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>

        <p className="mt-[3%] text-center text-13 text-ink-faint">{t('banner.select.hint')}</p>

        <div className="mt-[4%]">
          <button
            type="button"
            className="w-[22%] border border-space-600 bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-ink hover:text-accent"
            onClick={onBack}
          >
            {t('banner.select.back')}
          </button>
        </div>
      </section>
    </div>
  );
}

/**
 * Флаг на древке — тот же рисунок, каким заселённая планета помечена на схеме системы
 * (`OrbitDiagram.OwnerFlag`): знамя, выбранное здесь, игрок потом и увидит над своими
 * колониями.
 */
function BannerFlag({ colour }: { colour: string }) {
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden="true">
      <rect x={18} y={8} width={5} height={86} rx={2} fill="#9aa4b4" />
      <circle cx={20.5} cy={8} r={5} fill="#c9d1dc" />
      <path d="M23 14 L86 14 Q76 31 86 48 L23 48 Z" fill={colour} stroke="#05070f" strokeWidth={1.5} />
      <path d="M23 14 L86 14 Q80 22 84 27 L23 27 Z" fill="#ffffff" opacity={0.18} />
    </svg>
  );
}
