import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../api/client';
import type { GameSave } from '../api/types';
import { readAccount } from '../state/session';
import { locale, t, useT } from '../i18n';

interface LoadGameDialogProps {
  onLoad: (saveId: string) => void;
  onClose: () => void;
}

/**
 * Диалог выбора сохранённой партии — «Игра» → «Загрузить».
 *
 * Список приходит с сервера: состояние партий лежит в его базе, а не в браузере,
 * поэтому сохранение видно с любой машины, где открыт клиент. Автосохранения сервер
 * отдаёт первыми — их и предлагаем загрузить по умолчанию.
 *
 * **Удалять сохранения может только администратор** (п. 3.1). Хозяина у сохранения нет:
 * оно лежит на сервере общей кучей, его видят и загружают все, — поэтому убирать чужое
 * вправе тот, кто отвечает за сервер. Обычному игроку столбца с удалением не показывают
 * вовсе, а решает всё равно сервер: без пропуска администратора он ответит отказом.
 *
 * Удаление в два нажатия: первое взводит строку («точно?»), второе удаляет. Возврата
 * нет — слепок партии стирается насовсем, и переспросить об этом дешевле, чем потерять
 * партию от одного промаха.
 */
export function LoadGameDialog({ onLoad, onClose }: LoadGameDialogProps) {
  const [saves, setSaves] = useState<GameSave[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Строка, у которой удаление уже взведено: второе нажатие сотрёт сохранение. */
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useT();

  const admin = readAccount()?.account.role === 'ADMIN';

  useEffect(() => {
    let cancelled = false;
    gameApi
      .listSaves()
      .then((loaded) => {
        if (cancelled) {
          return;
        }
        setSaves(loaded);
        setSelected(loaded[0]?.id ?? null);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setSaves([]);
          setError(cause instanceof GameApiError ? cause.message : t('load.listFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Удаление сохранения администратором.
   *
   * Список правится на месте, а не перезапрашивается: сервер уже сказал, что сохранения
   * нет, и лишний поход за списком ничего не добавит. Выделение перескакивает на
   * соседнюю строку — иначе кнопка «Загрузить» указывала бы на стёртое.
   */
  const remove = (saveId: string) => {
    setBusy(true);
    setError(null);
    gameApi
      .deleteSave(saveId)
      .then(() => {
        setSaves((current) => {
          const left = (current ?? []).filter((save) => save.id !== saveId);
          setSelected((chosen) => (chosen === saveId ? left[0]?.id ?? null : chosen));
          return left;
        });
        setConfirming(null);
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('load.deleteFailed')),
      )
      .finally(() => setBusy(false));
  };

  // Esc закрывает диалог, Enter загружает выбранную партию.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
      if (event.key === 'Enter' && selected) {
        onLoad(selected);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose, onLoad, selected]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-8"
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t('load.title')}
        className="flex max-h-full w-full max-w-2xl flex-col border border-space-600 bg-space-900 shadow-lg shadow-black/60"
      >
        <div className="flex items-baseline justify-between border-b border-space-700 px-4 py-2">
          <div className="panel-title">{t('load.title')}</div>
          <button type="button" className="link text-xs" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
          {saves === null ? <p className="text-xs text-ink-dim">{t('load.loading')}</p> : null}

          {error ? <p className="text-xs text-danger">{error}</p> : null}

          {saves !== null && saves.length === 0 && !error ? (
            <p className="text-xs text-ink-dim">
              {t('load.empty')}
            </p>
          ) : null}

          {saves !== null && saves.length > 0 ? (
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-ink-faint">
                  <th className="pb-2 font-normal">{t('load.col.game')}</th>
                  <th className="pb-2 font-normal">{t('load.col.turn')}</th>
                  <th className="pb-2 font-normal">{t('load.col.galaxy')}</th>
                  <th className="pb-2 font-normal">{t('load.col.players')}</th>
                  <th className="pb-2 font-normal">{t('load.col.savedAt')}</th>
                  {admin ? <th className="pb-2 font-normal" /> : null}
                </tr>
              </thead>
              <tbody>
                {saves.map((save) => (
                  <tr
                    key={save.id}
                    className={`cursor-pointer border-t border-space-700 ${
                      save.id === selected ? 'text-accent' : 'text-ink'
                    }`}
                    onPointerDown={() => setSelected(save.id)}
                    onDoubleClick={() => onLoad(save.id)}
                  >
                    <td className="py-1">
                      {save.id === selected ? '▸ ' : '  '}
                      {save.name}
                    </td>
                    <td className="py-1">{turnLabel(save.turn)}</td>
                    <td className="py-1 text-ink-dim">
                      {t('load.galaxy', { size: save.galaxySize, w: save.widthParsecs, h: save.heightParsecs, stars: save.starCount })}
                    </td>
                    <td className="py-1 text-ink-dim">
                      {t('load.players', { humans: save.humanPlayers, total: save.totalPlayers })}
                    </td>
                    <td className="py-1 text-ink-dim">{formatSavedAt(save.savedAt)}</td>
                    {admin ? (
                      <td className="py-1 text-right">
                        <button
                          type="button"
                          disabled={busy}
                          className={`link text-11 ${
                            confirming === save.id ? 'text-danger' : 'text-ink-faint'
                          } disabled:opacity-40`}
                          title={t('load.delete.title')}
                          onPointerDown={(event) => event.stopPropagation()}
                          onDoubleClick={(event) => event.stopPropagation()}
                          onClick={() =>
                            confirming === save.id ? remove(save.id) : setConfirming(save.id)
                          }
                        >
                          {confirming === save.id ? t('load.delete.confirm') : t('load.delete')}
                        </button>
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>

        <div className="flex items-center justify-between border-t border-space-700 px-4 py-2">
          <span className="text-11 text-ink-faint">
            {t('load.footer')}
            {admin ? t('load.footer.admin') : ''}
          </span>
          <div className="flex gap-4">
            <button type="button" className="link text-xs" onClick={onClose}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className={`link text-xs ${selected ? 'text-accent' : 'pointer-events-none opacity-40'}`}
              onClick={() => selected && onLoad(selected)}
            >
              {t('load.submit')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Ход 0 — состояние сразу после старта партии: ни одного хода ещё не завершено. */
const turnLabel = (turn: number): string => (turn === 0 ? t('load.turn.start') : t('turn.n', { n: turn }));

const formatSavedAt = (savedAt: string): string =>
  new Date(savedAt).toLocaleString(locale() === 'ru' ? 'ru-RU' : 'en-GB', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
