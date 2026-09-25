import { useState } from 'react';
import type { Planet, StarSystem } from '../../api/types';
import { climateColor } from './planetVisuals';
import { OrbitDiagram } from './OrbitDiagram';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Разведка новой системы — п. 15, окно оригинала «Scouts arrive at the … system».
 *
 * Открывается на КАЖДУЮ разведанную систему, и показывает разное — как в MOO II:
 * нашлось что-то (п. 4.1) — картинка находки и её описание
 * (`docs/moo2/system-discovery.png`); не нашлось ничего — СХЕМА САМОЙ СИСТЕМЫ: солнце,
 * орбиты и планеты на них (`docs/moo2/system-discovery-empty.png`).
 *
 * **Зачем окно, когда есть строка в итогах хода.** Ради этой системы корабль и посылали:
 * разведка отвечает на вопрос «что там», а строка отчёта тонет среди достроенных зданий и
 * доходов. В оригинале разведка — одно из немногих событий, ради которых игру
 * останавливают отдельным окном.
 *
 * **Схема берётся та же, что на экране системы** (`OrbitDiagram`): второй способ рисовать
 * систему завёл бы вторую правду о том, как она выглядит. Здесь она только показывается —
 * выбирать в ней нечего, и нажатий она не ловит.
 *
 * **Находок в системе бывает несколько**, и показываются они по одной: кнопка ведёт к
 * следующей, а счётчик говорит, сколько их всего. Ссыпать две находки в одно окно значило
 * бы сломать его разметку ради редкого случая.
 */
export function SystemDiscoveryDialog({
  system,
  onClose,
}: {
  system: StarSystem;
  onClose: () => void;
}) {
  const [shown, setShown] = useState(0);
  useT();
  useModalEscape(true, onClose);

  const found = (system.planets ?? []).filter((planet) => planet.find);
  // Пусто — находок в системе нет, и окно показывает её схему: разведка о системе, а не
  // только о кладе (`docs/moo2/system-discovery-empty.png`).
  const planet = found.length > 0 ? found[Math.min(shown, found.length - 1)] : null;
  const last = shown >= found.length - 1;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-space-950/80 p-4">
      <div className="panel flex w-[min(52rem,94vw)] flex-col gap-2 p-2">
        {/* Плашка с именем системы во всю ширину окна — заголовок оригинала. */}
        <header className="border border-space-600 bg-space-800 py-1 text-center text-16 text-accent">
          {t('discovery.title', { name: system.name ?? '' })}
        </header>

        {planet ? (
          <>
            <SkyBand planet={planet} />

            <div className="flex min-h-[9rem] gap-2">
              {/* Картинка находки: своего изображения нет, поэтому знак — см. FindEmblem. */}
              <div className="flex w-2/5 flex-none items-center justify-center border border-space-700 bg-space-950">
                <FindEmblem planet={planet} />
              </div>

              {/* Описание: название по центру, под ним слова оригинала — как в окне MOO II. */}
              <div className="flex flex-1 flex-col gap-2 border border-space-700 bg-space-950 p-3">
                <div className="text-center text-15 text-accent">{planet.findLabel}</div>
                <p className="text-13 leading-relaxed text-ink">{planet.findDescription}</p>
                <p className="mt-auto text-12 text-ink-dim">
                  {t('discovery.planet', { name: planet.name, climate: planet.climateLabel })}
                </p>
              </div>
            </div>
          </>
        ) : (
          <div className="pointer-events-none border border-space-700 bg-space-950 p-2">
            <OrbitDiagram
              system={system}
              selectedPlanetId={null}
              onSelect={() => undefined}
              onOpen={() => undefined}
            />
          </div>
        )}

        {/*
          Что в системе есть, одной строкой: планеты, пригодные из них, сторож и чужое
          присутствие. Одна схема на вопрос «что там» отвечает не полностью — размер и
          климат планет с неё не прочесть.
        */}
        <p className="text-center text-12 text-ink-dim">
          {t('discovery.summary', {
            planets: system.planets?.length ?? 0,
            habitable: (system.planets ?? []).filter((one) => one.colonizable).length,
          })}
          {system.monster ? ' · ' + t('discovery.guarded', { monster: system.monster }) : ''}
          {(system.planets ?? []).some((one) => one.ownerPlayerId)
            ? ' · ' + t('discovery.occupied')
            : ''}
        </p>

        <footer className="flex items-center justify-between border-t border-space-700 pt-2">
          <span className="text-12 text-ink-faint">
            {found.length > 1 ? t('discovery.counter', { n: shown + 1, total: found.length }) : ''}
          </span>
          <button
            type="button"
            className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
            onClick={() => (last ? onClose() : setShown(shown + 1))}
          >
            {last ? t('common.close') : t('discovery.next')}
          </button>
        </footer>
      </div>
    </div>
  );
}

/**
 * Полоса неба с дугой орбиты и планетой на ней — верх и низ картины оригинала одной
 * лентой. Дуга та же, что на схеме системы: окно рассказывает о месте, а не о вещи.
 */
function SkyBand({ planet }: { planet: Planet }) {
  return (
    <svg viewBox="0 0 800 72" className="h-16 w-full border border-space-700 bg-space-950">
      {/* Звёзды расставлены синусом от номера: поле не должно меняться на каждой
          перерисовке — то же правило, что у схемы Млечного Пути. */}
      {Array.from({ length: 90 }, (_, i) => (
        <circle
          key={i}
          cx={((Math.sin(i * 12.9898) + 1) / 2) * 800}
          cy={((Math.sin(i * 78.233) + 1) / 2) * 72}
          r={i % 9 === 0 ? 1.3 : 0.7}
          fill="#c8d4ea"
          opacity={i % 5 === 0 ? 0.9 : 0.45}
        />
      ))}
      <path d="M 40 66 Q 400 -14 760 66" fill="none" stroke="#3d4b78" strokeWidth="2" />
      <circle cx="400" cy="27" r="12" fill={climateColor(planet.climate)} />
    </svg>
  );
}

/**
 * Знак находки вместо картинки: у игры нет ни спрайтов, ни рисунков, а пустая рамка
 * читалась бы как поломка. Каждый знак — о том, что именно нашли: жила в породе, гранёный
 * камень, обелиск ушедшей цивилизации, фигурки туземцев.
 */
function FindEmblem({ planet }: { planet: Planet }) {
  const color = climateColor(planet.climate);
  return (
    <svg viewBox="0 0 120 120" className="h-full max-h-40 w-full">
      <circle cx="60" cy="60" r="46" fill={color} opacity="0.18" />
      <circle cx="60" cy="60" r="46" fill="none" stroke={color} strokeWidth="1.5" opacity="0.6" />
      {planet.find === 'GOLD_DEPOSITS' ? (
        <g stroke="#d9b451" strokeWidth="3" fill="none" strokeLinecap="round">
          <path d="M 34 78 L 52 54 L 66 66 L 88 40" />
          <path d="M 40 62 L 52 54" />
          <path d="M 74 72 L 66 66" />
        </g>
      ) : null}
      {planet.find === 'GEM_DEPOSITS' ? (
        <g stroke="#7fd4ff" strokeWidth="2.5" fill="none" strokeLinejoin="round">
          <path d="M 60 34 L 86 56 L 60 90 L 34 56 Z" />
          <path d="M 34 56 L 86 56" />
          <path d="M 60 34 L 48 56 L 60 90 L 72 56 Z" />
        </g>
      ) : null}
      {planet.find === 'ARTIFACTS' ? (
        <g stroke="#b79bff" strokeWidth="2.5" fill="none" strokeLinejoin="round">
          <path d="M 48 88 L 48 44 L 60 30 L 72 44 L 72 88 Z" />
          <path d="M 48 60 L 72 60" />
          <path d="M 60 44 L 60 88" />
        </g>
      ) : null}
      {planet.find === 'NATIVES' ? (
        <g stroke="#7fd48a" strokeWidth="2.5" fill="none" strokeLinecap="round">
          <circle cx="44" cy="48" r="7" />
          <path d="M 44 55 L 44 78 M 34 64 L 54 64 M 44 78 L 36 90 M 44 78 L 52 90" />
          <circle cx="76" cy="52" r="6" />
          <path d="M 76 58 L 76 78 M 68 66 L 84 66 M 76 78 L 70 88 M 76 78 L 82 88" />
        </g>
      ) : null}
    </svg>
  );
}
