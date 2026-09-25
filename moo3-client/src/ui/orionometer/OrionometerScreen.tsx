import { useCallback, useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Orionometer } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Пульт Орионометра — записи того, как человек играет в ОРИГИНАЛ.
 *
 * Зачем это есть. Наш инструмент игры в оригинал водит игру сам, и каждое незнакомое
 * управление приходится выводить по снимкам: где кнопка, что она делает, каким нажатием
 * отдаётся приказ. Догадки стоят дорого — одна неверная стоит получасовой партии.
 * Посмотреть, как ту же дорогу проходит человек, дешевле любого разбора.
 *
 * Экран нарочно прост: две кнопки и состояние. Всё остальное — протокол на диске, его
 * читает не игрок, а тот, кто правит инструмент.
 *
 * Пока запись идёт, пульт спрашивает состояние раз в пару секунд — и только пока идёт:
 * висеть на сервере без нужды незачем. Но и в затишье он спрашивает, только реже: запись
 * могли начать не отсюда, и замерший экран нельзя было бы отличить от «не запустилось».
 */
export function OrionometerScreen({ onClose }: { onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const [state, setState] = useState<Orionometer | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setState(await gameApi.orionometer());
    } catch (error) {
      setFailure(error instanceof Error ? error.message : String(error));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    const often = state?.running || state?.brainRunning;
    const timer = window.setInterval(() => void refresh(), often ? 2000 : 8000);
    return () => window.clearInterval(timer);
  }, [refresh, state?.running, state?.brainRunning]);

  const act = async (what: () => Promise<Orionometer>) => {
    setBusy(true);
    setFailure(null);
    try {
      setState(await what());
    } catch (error) {
      setFailure(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  };

  const running = Boolean(state?.running);
  const gameOpen = Boolean(state?.gameOpen);

  return (
    <div className="fixed inset-0 z-40 flex justify-center overflow-y-auto bg-space/95">
      <div className="my-auto w-full max-w-3xl p-6">
        <div className="panel">
          <div className="panel-title text-center text-16">{t('orionometer.title')}</div>

          <p className="mt-3 text-13 text-ink-soft">{t('orionometer.what')}</p>

          {/* Состояние: открыта ли игра и идёт ли запись. */}
          <div className="mt-4 grid grid-cols-2 gap-3 text-13">
            <div>
              <div className="text-ink-dim">{t('orionometer.game')}</div>
              <div className={gameOpen ? 'text-good' : 'text-warn'}>
                {gameOpen ? t('orionometer.gameOpen') : t('orionometer.gameClosed')}
              </div>
            </div>
            <div>
              <div className="text-ink-dim">{t('orionometer.state')}</div>
              <div className={running ? 'text-good' : 'text-ink'}>
                {running ? t('orionometer.recording') : t('orionometer.idle')}
              </div>
            </div>
          </div>

          {state && (state.clicks !== undefined || state.seconds !== undefined) ? (
            <div className="mt-3 text-13 text-ink-soft">
              {t('orionometer.counted', {
                clicks: String(state.clicks ?? 0),
                shots: String(state.shots ?? 0),
                seconds: String(Math.round(state.seconds ?? 0)),
              })}
              {state.scene ? ` · ${t('orionometer.lastScene')}: ${state.scene}` : ''}
            </div>
          ) : null}

          {state?.log ? (
            <div className="mt-2 text-11 text-ink-faint">{state.log}</div>
          ) : null}

          {/* Две кнопки, о которых и шла речь. */}
          <div className="mt-5 flex flex-wrap gap-3">
            <button
              type="button"
              className="btn"
              disabled={busy || running || !gameOpen}
              onClick={() => void act(() => gameApi.startOrionometer())}
            >
              {t('orionometer.start')}
            </button>
            <button
              type="button"
              className="btn"
              disabled={busy || !running}
              onClick={() => void act(() => gameApi.stopOrionometer())}
            >
              {t('orionometer.stop')}
            </button>
            <button
              type="button"
              className="btn"
              disabled={busy || running}
              onClick={() => void act(() => gameApi.orionometerBrain())}
            >
              {t('orionometer.brain')}
            </button>
          </div>

          <p className="mt-3 text-11 text-ink-faint">{t('orionometer.brainNote')}</p>

          {failure ? <div className="mt-3 text-13 text-danger">{failure}</div> : null}
          {state?.failure ? (
            <div className="mt-2 text-13 text-danger">{state.failure}</div>
          ) : null}

          <div className="mt-6 text-center">
            <button type="button" className="link text-13" onClick={onClose}>
              {t('orionometer.back')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
