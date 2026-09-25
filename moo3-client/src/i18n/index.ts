/**
 * Локализация клиента — без библиотек: два словаря, функция `t()` и хук.
 *
 * Английский — язык игры по умолчанию, русский игрок выбирает сам: переключатель стоит на
 * экране входа и в меню. Выбор живёт в `localStorage` (`moo3.locale`) и в учётной записи
 * (поле `locale` — едет за игроком между устройствами; подключается вместе с сервером),
 * а каждый запрос к серверу уходит с `Accept-Language`, чтобы ошибки, письма и отчёты хода
 * приходили на том же языке, что и экран.
 *
 * Своё, а не i18next, по той же причине, что и графики в «Инфо»: зависимости клиента
 * ставит `setup.cmd`, а нужны здесь ровно словарь, подстановка `{name}` и подписка на
 * смену языка — тридцать строк.
 */
import { useSyncExternalStore } from 'react';

import { en } from './en';
import { ru } from './ru';

export type Locale = 'en' | 'ru';
// Ключи — из английского словаря, значения — любые строки: `as const` у `en` нужен ради
// ключей, а буквальные типы значений переводу только мешали бы.
export type Dictionary = { [K in keyof typeof en]: string };
export type Key = keyof Dictionary;

export const LOCALES: Locale[] = ['en', 'ru'];
const STORAGE_KEY = 'moo3.locale';

const dictionaries: Record<Locale, Dictionary> = { en, ru };

function stored(): Locale {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === 'ru' ? 'ru' : 'en';
  } catch {
    return 'en';
  }
}

let current: Locale = stored();
const listeners = new Set<() => void>();

/** Текущий язык — для кода вне React (запросы к серверу). */
export function locale(): Locale {
  return current;
}

export function setLocale(next: Locale): void {
  if (next === current) {
    return;
  }
  current = next;
  try {
    window.localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // Хранилище закрыто (приватное окно) — язык поживёт до перезагрузки, и это не ошибка.
  }
  document.documentElement.lang = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export type Params = Record<string, string | number>;

/** Строка на текущем языке; `{name}` в ней заменяется значением из `params`. */
export function t(key: Key, params?: Params): string {
  const text = dictionaries[current][key] ?? dictionaries.en[key] ?? key;
  if (!params) {
    return text;
  }
  return text.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  );
}

/**
 * Хук: перерисовывает компонент при смене языка. `t` из него — та же функция, что и
 * выше, но компонент, взявший её через хук, узнаёт о смене языка; взявший напрямую — нет.
 */
export function useT() {
  const active = useSyncExternalStore(subscribe, locale, () => 'en' as Locale);
  return { t, locale: active, setLocale };
}

document.documentElement.lang = current;
