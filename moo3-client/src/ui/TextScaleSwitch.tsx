import { useState } from 'react';
import { t, useT } from '../i18n';
import { rememberTextScale } from '../state/session';
import { TEXT_SCALES, applyTextScale, textScale, type TextScale } from './textScale';

/**
 * Переключатель размера текста — п. 11.1. Стоит рядом с переключателем языка: обе
 * настройки меняются одним нажатием, обе живут в учётной записи, и экрана настроек у игры
 * нет вовсе.
 *
 * Ступенями, а не ползунком: ступеней четыре, каждая проверена глазами на всех экранах, и
 * попасть в нужную нажатием проще, чем целиться мышью. Сотня — прежний размер игры.
 *
 * Своё состояние здесь нужно только для подсветки выбранного: сам размер живёт в корне
 * стилей, и перерисовывать ради него дерево не нужно — всё, что меряется в `rem`, меняется
 * само.
 */
export function TextScaleSwitch() {
  useT();
  const [current, setCurrent] = useState<TextScale>(textScale());
  return (
    <div className="flex items-baseline gap-2 text-11 uppercase tracking-widest">
      <span className="text-ink-faint">{t('textScale.label')}</span>
      {TEXT_SCALES.map((value) => (
        <button
          key={value}
          type="button"
          className={`link ${value === current ? 'link-active text-accent' : 'text-ink-faint'}`}
          title={t('textScale.hint')}
          onClick={() => {
            applyTextScale(value);
            setCurrent(value);
            // Вошедшему размер запоминается в записи — он поедет за ним на другую машину,
            // где `localStorage` пуст. Ровно как язык.
            rememberTextScale(value);
          }}
        >
          {value}
        </button>
      ))}
    </div>
  );
}
