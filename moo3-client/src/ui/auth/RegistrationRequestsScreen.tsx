import { useEffect, useState } from 'react';

import { gameApi, GameApiError } from '../../api/client';
import type { AccountSummary } from '../../api/types';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { locale, t, useT } from '../../i18n';

/**
 * Запросы на регистрацию — п. 3.1: страница администратора.
 *
 * <b>Зачем она есть.</b> Обычно игрок подтверждает почту сам, ссылкой из письма, и может
 * выслать письмо заново. Но письма может не быть вовсе: почтовый сервер не настроен, адрес
 * закрыт, письмо съел спам-фильтр. Тогда игрок остаётся с занятой почтой и закрытым
 * входом, и открыть запись может только тот, кто отвечает за сервер. Подтверждение отсюда
 * равносильно переходу по ссылке и гасит саму ссылку: подтверждать второй раз нечего.
 *
 * Страница, а не окошко: это про сервер, а не про партию, и открывается она из главного
 * меню — рядом с остальным, что смотрят до игры. Внутри партии ей делать нечего.
 *
 * Наверху — сами запросы, ради которых страница и заведена; ниже, отдельным списком, все
 * записи сервера: администратору нужно видеть и то, что уже открыто, иначе «этой почты
 * здесь нет» и «эта почта уже вошла» неразличимы.
 */
export function RegistrationRequestsScreen({ onClose }: { onClose: () => void }) {
  const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * Запись, у которой удаление уже взведено: второе нажатие сотрёт её. Возврата нет,
   * поэтому в два нажатия — так же, как удаление сохранений.
   */
  const [confirming, setConfirming] = useState<string | null>(null);

  useModalEscape(true, onClose);
  useT();

  useEffect(() => {
    let cancelled = false;
    gameApi
      .listAccounts()
      .then((loaded) => {
        if (!cancelled) {
          setAccounts(loaded);
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled) {
          setAccounts([]);
          setError(failure instanceof GameApiError ? failure.message : t('requests.listFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const confirm = (account: AccountSummary) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .confirmAccountByAdmin(account.id)
      .then((updated) => {
        setAccounts((current) =>
          (current ?? []).map((entry) => (entry.id === updated.id ? updated : entry)),
        );
        setNotice(t('requests.confirmed', { name: updated.name, login: updated.login }));
      })
      .catch((failure: unknown) =>
        setError(failure instanceof GameApiError ? failure.message : t('requests.confirmFailed')),
      )
      .finally(() => setBusy(false));
  };

  /**
   * Удаление записи — п. 3.1: первое нажатие взводит строку, второе стирает.
   *
   * Своя запись сервером не удаляется вовсе (администратор, удаливший себя, запирает
   * сервер), поэтому отказ показывается как есть — придумывать для него отдельную
   * проверку на клиенте значило бы завести второе место, где живёт то же правило.
   */
  const remove = (account: AccountSummary) => {
    if (confirming !== account.id) {
      setConfirming(account.id);
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .deleteAccountByAdmin(account.id)
      .then(() => {
        setAccounts((current) => (current ?? []).filter((entry) => entry.id !== account.id));
        setNotice(t('requests.deleted', { name: account.name, login: account.login }));
      })
      .catch((failure: unknown) =>
        setError(failure instanceof GameApiError ? failure.message : t('requests.deleteFailed')),
      )
      .finally(() => {
        setBusy(false);
        setConfirming(null);
      });
  };

  const waiting = (accounts ?? []).filter((account) => !account.confirmed);

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="absolute left-3 uppercase tracking-[0.2em] text-ink-dim">
          {t('requests.admin')}
        </span>
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 uppercase tracking-[0.3em] text-accent">
          {t('requests.title')}
        </span>
      </header>

      <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
        <p className="mb-3 max-w-4xl leading-relaxed text-ink-dim">
          {t('requests.intro')}
        </p>

        {accounts === null ? <p className="text-ink-dim">{t('requests.loading')}</p> : null}
        {error ? <p className="mb-2 text-danger">{error}</p> : null}
        {notice ? <p className="mb-2 text-accent">{notice}</p> : null}

        {accounts !== null ? (
          <section className="mb-6">
            <div className="panel-title mb-2">
              {t('requests.waiting')}
              {waiting.length > 0 ? (
                <span className="ml-2 text-warn">{waiting.length}</span>
              ) : null}
            </div>

            {waiting.length === 0 ? (
              <p className="text-ink-faint">
                {t('requests.none')}
              </p>
            ) : (
              <table className="w-full max-w-4xl">
                <thead>
                  <tr className="text-left text-ink-faint">
                    <th className="pb-2 font-normal">{t('requests.col.name')}</th>
                    <th className="pb-2 font-normal">{t('requests.col.email')}</th>
                    <th className="pb-2 font-normal">{t('requests.col.applied')}</th>
                    <th className="pb-2 font-normal" />
                  </tr>
                </thead>
                <tbody>
                  {waiting.map((account) => (
                    <tr key={account.id} className="border-t border-space-700 text-ink">
                      <td className="py-1">{account.name}</td>
                      <td className="py-1 text-ink-dim">{account.email ?? account.login}</td>
                      <td className="py-1 text-ink-dim">{applied(account.createdAt)}</td>
                      <td className="py-1 text-right">
                        <span className="flex justify-end gap-3">
                          <button
                            type="button"
                            disabled={busy}
                            className="link disabled:opacity-40"
                            onClick={() => confirm(account)}
                          >
                            {t('requests.confirm')}
                          </button>
                          {/*
                            Отказ по заявке — то же удаление записи: подтверждать брошенную
                            регистрацию незачем, а висеть в очереди она будет вечно.
                          */}
                          <button
                            type="button"
                            disabled={busy}
                            className={
                              'link disabled:opacity-40 ' +
                              (confirming === account.id ? 'text-danger' : '')
                            }
                            onClick={() => remove(account)}
                          >
                            {confirming === account.id ? t('load.delete.confirm') : t('load.delete')}
                          </button>
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        ) : null}

        {accounts !== null && accounts.length > 0 ? (
          <section>
            <div className="panel-title mb-2">
              {t('requests.all')}<span className="ml-2 text-ink-faint">{accounts.length}</span>
            </div>
            <table className="w-full max-w-4xl">
              <thead>
                <tr className="text-left text-ink-faint">
                  <th className="pb-2 font-normal">{t('requests.col.name')}</th>
                  <th className="pb-2 font-normal">{t('requests.col.email')}</th>
                  <th className="pb-2 font-normal">{t('requests.col.role')}</th>
                  <th className="pb-2 font-normal">{t('requests.col.created')}</th>
                  <th className="pb-2 font-normal">{t('requests.col.confirmed')}</th>
                  <th className="pb-2 font-normal" />
                </tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr key={account.id} className="border-t border-space-700 text-ink">
                    <td className="py-1">{account.name}</td>
                    <td className="py-1 text-ink-dim">{account.email ?? account.login}</td>
                    <td className="py-1 text-ink-dim">{account.roleLabel}</td>
                    <td className="py-1 text-ink-dim">{applied(account.createdAt)}</td>
                    <td className={`py-1 ${account.confirmed ? 'text-ink-dim' : 'text-warn'}`}>
                      {account.confirmed ? t('requests.yes') : t('requests.pending')}
                    </td>
                    <td className="py-1 text-right">
                      <button
                        type="button"
                        disabled={busy}
                        className={
                          'link disabled:opacity-40 ' +
                          (confirming === account.id ? 'text-danger' : '')
                        }
                        onClick={() => remove(account)}
                      >
                        {confirming === account.id ? t('load.delete.confirm') : t('load.delete')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ) : null}
      </div>

      <footer
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="flex flex-none items-center justify-between border-t border-space-600 bg-space-800 px-3 uppercase tracking-[0.2em] text-ink"
      >
        <button type="button" className="link" onClick={onClose}>
          {t('requests.back')}
        </button>
        <span className="normal-case tracking-normal text-ink-dim">
          {t('requests.waitingCount', { n: waiting.length })}
        </span>
      </footer>
    </div>
  );
}

/**
 * Когда подана заявка. Дата и время местные: администратор смотрит на свои часы, а
 * сервер отдаёт отметку со смещением.
 */
function applied(createdAt: string): string {
  const moment = new Date(createdAt);
  return Number.isNaN(moment.getTime()) ? '—' : moment.toLocaleString(locale() === 'ru' ? 'ru-RU' : 'en-GB');
}
