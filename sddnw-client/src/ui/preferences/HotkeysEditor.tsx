import { useEffect, useState } from 'react';
import { forgetHotkeys, readAccount, rememberHotkeys } from '../../state/session';
import {
  DEFAULT_HOTKEYS,
  HOTKEY_ACTIONS,
  applyHotkeys,
  assignable,
  hotkeyLabel,
  takenBy,
  useHotkeys,
  type HotkeyAction,
} from '../hotkeys';
import { t, useT } from '../../i18n';

/**
 * Переназначение горячих клавиш карты — п. 11.1, раздел «Горячие клавиши» настроек.
 *
 * <b>Клавиша назначается нажатием, а не выбором из списка.</b> Список клавиш — это сотня
 * строк, из которых игроку нужна одна, и та, которую он уже держит под пальцем. Поэтому
 * строка ждёт нажатия: нажал — назначено, Esc — передумал.
 *
 * <b>Применяется сразу, как язык и размер текста</b> (п. 3.5, п. 11.1): кнопки «сохранить»
 * здесь нет нарочно. Раскладка — не документ, а настройка; отложенное сохранение завело бы
 * третье состояние («правлено, но не сохранено»), которое надо показывать, стеречь при
 * закрытии экрана и объяснять. Ушедшее в запись едет за игроком на другую машину, а без
 * входа остаётся в браузере — об этом сказано под таблицей, чтобы это не выяснялось потом.
 *
 * <b>Занятую клавишу экран не отнимает молча.</b> Отнятая у соседнего действия, она
 * оставила бы его без клавиши вовсе — и узнал бы об этом игрок, только нажав. Поэтому
 * такое назначение отвергается, и сказано, кто клавишу держит.
 */
export function HotkeysEditor({ onCapturing }: {
  /** Экран должен знать, что строка ждёт нажатия: пока ждёт, Esc принадлежит ей. */
  onCapturing: (waiting: boolean) => void;
}) {
  const map = useHotkeys();
  /** Действие, которое ждёт нажатия; пусто — никто не ждёт. */
  const [capturing, setCapturingAction] = useState<HotkeyAction | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  useT();

  const setCapturing = (action: HotkeyAction | null) => {
    setCapturingAction(action);
    onCapturing(action !== null);
  };

  useEffect(() => {
    if (!capturing) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      /*
        Нажатие ловится ДО всех прочих (`capture`) и дальше не идёт: пока строка ждёт
        клавишу, эта клавиша не должна ни открывать экранов, ни доставаться браузеру —
        игрок назначает F3, а не просит у Chrome поиск.
      */
      event.preventDefault();
      event.stopPropagation();

      if (event.key === 'Escape') {
        setCapturing(null);
        setRefusal(null);
        return;
      }
      // Модификатор сам по себе — это не клавиша, а половина сочетания: ждём дальше.
      if (['Shift', 'Control', 'Alt', 'Meta'].includes(event.key)) {
        return;
      }
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || !assignable(event.code)) {
        setRefusal(t('preferences.hotkeys.forbidden'));
        return;
      }
      const owner = takenBy(map, event.code, capturing);
      if (owner) {
        setRefusal(t('preferences.hotkeys.taken', { action: t(labelOf(owner)) }));
        return;
      }
      save({ ...map, [capturing]: event.code });
      setCapturing(null);
      setRefusal(null);
    };

    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [capturing, map]);

  /** Раскладка правится и тут же запоминается: и в браузере, и в учётной записи. */
  const save = (next: Record<HotkeyAction, string>) => {
    applyHotkeys(next);
    rememberHotkeys(next);
  };

  return (
    <div>
      <p className="mb-3 max-w-2xl leading-relaxed text-ink-dim">
        {t('preferences.hotkeys.intro')}
      </p>

      <table className="w-full max-w-2xl">
        <thead>
          <tr className="text-left text-ink-faint">
            <th className="pb-2 font-normal">{t('preferences.hotkeys.col.action')}</th>
            <th className="pb-2 font-normal">{t('preferences.hotkeys.col.key')}</th>
          </tr>
        </thead>
        <tbody>
          {HOTKEY_ACTIONS.map(({ action, label }) => (
            <tr key={action} className="border-t border-space-800">
              <td className="py-1 text-ink">{t(label)}</td>
              <td className="py-1">
                <button
                  type="button"
                  /*
                    Сама клавиша и есть кнопка: нажимать «переназначить» рядом с ней —
                    лишний шаг, а на что нажимать, видно без подписи.
                  */
                  className={
                    'min-w-[6rem] border px-3 py-0.5 text-center '
                    + (capturing === action
                      ? 'border-accent text-accent'
                      : 'border-space-600 text-ink-bright hover:border-accent hover:text-accent')
                  }
                  onClick={() => {
                    setRefusal(null);
                    setCapturing(capturing === action ? null : action);
                  }}
                >
                  {capturing === action
                    ? t('preferences.hotkeys.press')
                    : hotkeyLabel(map[action])}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/*
        Отказ стоит под таблицей и держит её высоту постоянной: строка, появляющаяся между
        рядами, сдвигала бы кнопку из-под курсора ровно в тот миг, когда по ней метятся.
      */}
      <p className="mt-2 h-4 text-danger">{refusal ?? ''}</p>

      <div className="mt-2 flex items-center gap-4">
        {/*
          «Как было» — это ПУСТАЯ раскладка в записи, а не записанная в неё раскладка по
          умолчанию: иначе у одного состояния было бы два вида, а поменяйся однажды сама
          раскладка по умолчанию — нажавшие эту кнопку остались бы со старой.
        */}
        <button
          type="button"
          className="link"
          onClick={() => {
            applyHotkeys({ ...DEFAULT_HOTKEYS });
            forgetHotkeys();
          }}
        >
          {t('preferences.hotkeys.reset')}
        </button>
        <span className="text-11 text-ink-faint">
          {readAccount()
            ? t('preferences.hotkeys.stored')
            : t('preferences.hotkeys.storedLocally')}
        </span>
      </div>
    </div>
  );
}

/** Подпись действия — та же, что у кнопки, которую клавиша дублирует. */
const labelOf = (action: HotkeyAction) =>
  HOTKEY_ACTIONS.find((one) => one.action === action)!.label;
