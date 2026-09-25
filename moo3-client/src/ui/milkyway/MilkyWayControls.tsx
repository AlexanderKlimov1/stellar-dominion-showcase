import { ZOOM_MAX, ZOOM_MIN, type ViewAngles } from './milkyWayModel';
import { t } from '../../i18n';

/**
 * Элементы управления схемой Млечного Пути: вращение, поворот, наклон, приближение.
 *
 * Кнопка вращения стоит первой и нарисована рамкой, а не ссылкой: это главное действие
 * сцены — схему включают и останавливают чаще, чем что-либо ещё, и искать её среди
 * текстовых ссылок игрок не должен.
 *
 * Ползунки, а не только перетаскивание: мышью схему крутят на глаз, а ползунком —
 * на точный угол, и он же единственный способ повернуть схему с клавиатуры. Готовые виды
 * («плашмя», «с ребра») стоят рядом потому, что именно эти два и хочется сравнить: сверху
 * видны рукава, с ребра — толщина диска и то, что гало объёмное.
 *
 * Углы держит экран, а панель только показывает и просит их поменять: иначе после
 * перетаскивания ползунки отставали бы от схемы.
 */
export function MilkyWayControls({
  view,
  spinning,
  labels,
  radiation,
  onView,
  onSpinning,
  onLabels,
  onRadiation,
}: {
  view: ViewAngles;
  spinning: boolean;
  labels: boolean;
  radiation: boolean;
  onView: (next: Partial<ViewAngles>) => void;
  onSpinning: (value: boolean) => void;
  onLabels: (value: boolean) => void;
  onRadiation: (value: boolean) => void;
}) {
  return (
    <section className="flex w-[20rem] shrink-0 flex-col gap-3 overflow-auto border border-space-700 bg-space-950/60 p-3">
      <div className="text-15 uppercase tracking-[0.2em] text-ink-dim">{t('milkyway.controls')}</div>

      {/*
        Пуск и остановка вращения — главная кнопка сцены, поэтому она полной ширины и
        в рамке. Подпись говорит, что случится по нажатию, а не в каком состоянии сцена:
        «остановить» на вращающейся схеме читается однозначно.
      */}
      <button
        type="button"
        className={
          'border px-3 py-2 text-center text-18 transition-colors '
          + (spinning
            ? 'border-accent text-accent hover:bg-space-800'
            : 'border-space-600 text-ink hover:border-accent hover:text-accent')
        }
        aria-pressed={spinning}
        onClick={() => onSpinning(!spinning)}
      >
        {spinning ? t('milkyway.stop') : t('milkyway.spin')}
      </button>

      <Slider
        label={t('milkyway.yaw')}
        value={view.yawDeg}
        min={0}
        max={360}
        suffix="°"
        onChange={(yawDeg) => onView({ yawDeg })}
      />
      <Slider
        label={t('milkyway.pitch')}
        value={view.pitchDeg}
        min={-90}
        max={90}
        suffix="°"
        onChange={(pitchDeg) => onView({ pitchDeg })}
      />
      <Slider
        label={t('milkyway.roll')}
        value={view.rollDeg}
        min={0}
        max={360}
        suffix="°"
        onChange={(rollDeg) => onView({ rollDeg })}
      />
      <Slider
        label={t('milkyway.zoom')}
        value={view.zoom}
        min={ZOOM_MIN}
        max={ZOOM_MAX}
        step={0.05}
        suffix="×"
        onChange={(zoom) => onView({ zoom })}
      />

      <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-space-800 pt-2 text-17">
        <button type="button" className="link" onClick={() => onView({ pitchDeg: 0 })}>
          {t('milkyway.flat')}
        </button>
        <button type="button" className="link" onClick={() => onView({ pitchDeg: 62 })}>
          {t('milkyway.tilted')}
        </button>
        <button type="button" className="link" onClick={() => onView({ pitchDeg: 90 })}>
          {t('milkyway.edge')}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => onView({ yawDeg: 20, pitchDeg: 62, rollDeg: 0, zoom: 1 })}
        >
          {t('milkyway.reset')}
        </button>
      </div>

      <div className="flex flex-col gap-1 border-t border-space-800 pt-2 text-17">
        <button type="button" className="link" onClick={() => onRadiation(!radiation)}>
          {radiation ? t('milkyway.hideRadiation') : t('milkyway.showRadiation')}
        </button>
        <button type="button" className="link" onClick={() => onLabels(!labels)}>
          {labels ? t('milkyway.hideLabels') : t('milkyway.showLabels')}
        </button>
      </div>

      <p className="mt-auto border-t border-space-800 pt-2 text-15 leading-relaxed text-ink-faint">
        {t('milkyway.dragHint')}
      </p>

      <p className="text-15 leading-relaxed text-ink-faint">
        {t('milkyway.about')}
      </p>
    </section>
  );
}

function Slider({
  label,
  value,
  min,
  max,
  step = 1,
  suffix,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  suffix: string;
  onChange: (value: number) => void;
}) {
  return (
    <label className="flex flex-col gap-1 text-17">
      <span className="flex items-baseline justify-between text-ink-faint">
        {label}
        <span className="text-ink">
          {step < 1 ? value.toFixed(2) : Math.round(value)}
          {suffix}
        </span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        className="accent-accent"
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  );
}
