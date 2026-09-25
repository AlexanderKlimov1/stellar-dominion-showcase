import { LOCALES, useT } from '../i18n';
import { rememberLocale } from '../state/session';

/**
 * Переключатель языка — две буквы в углу, как в играх, а не выпадающий список: языков два,
 * и оба названия читаются на любом из них. Стоит на экране входа и в главном меню — там,
 * где игрок ещё не в партии и меняет настройки, а не играет.
 */
export function LocaleSwitch() {
  const { t, locale, setLocale } = useT();
  return (
    <div className="flex gap-2 text-11 uppercase tracking-widest">
      {LOCALES.map((code) => (
        <button
          key={code}
          type="button"
          className={`link ${code === locale ? 'link-active text-accent' : 'text-ink-faint'}`}
          onClick={() => {
            setLocale(code);
            // Вошедшему язык запоминается в записи — п. 3.5: он поедет за ним на другую
            // машину, где `localStorage` пуст.
            rememberLocale(code);
          }}
        >
          {t(`locale.${code}`)}
        </button>
      ))}
    </div>
  );
}
