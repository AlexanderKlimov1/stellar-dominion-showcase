import { t } from '../i18n';

/**
 * «Ваш ход» вне вкладки — backlog-promo, пункт 11.
 *
 * В партии людей ход считается, когда закончили все, и закончивший первым уходит в другую
 * вкладку ждать. Не узнав, что ход наступил, он и держит партию следующим ходом. Поэтому,
 * когда вкладка не на виду, о наступившем ходе говорят двое: заголовок вкладки (виден в
 * полосе вкладок всегда, разрешения не требует) и системное уведомление — если игрок его
 * разрешил. Разрешение спрашивается не при загрузке, а в миг, когда игрок впервые закончил
 * ход в партии людей: браузер даёт спросить только по нажатию, и вопрос в этот миг понятен —
 * сейчас придётся ждать.
 */

const MARK = '● ';

/** Заголовок вкладки без пометки: его и возвращаем, когда игрок вернулся. */
let plainTitle: string | null = null;

/** Спросить разрешение на уведомления — один раз, по нажатию «ход» в партии людей. */
export function askTurnNotices(humans: number): void {
  if (humans < 2 || typeof Notification === 'undefined' || Notification.permission !== 'default') {
    return;
  }
  // Отказ — тоже ответ: дальше говорит один заголовок вкладки.
  void Notification.requestPermission().catch(() => undefined);
}

/** Наступил ход: если вкладка не на виду — пометить заголовок и, если можно, уведомить. */
export function noticeYourTurn(turn: number, gameName: string): void {
  if (typeof document === 'undefined' || !document.hidden) {
    return;
  }
  if (plainTitle === null) {
    plainTitle = document.title;
    document.title = `${MARK}${t('notify.yourTurn')} — ${plainTitle}`;
    const back = () => {
      if (!document.hidden && plainTitle !== null) {
        document.title = plainTitle;
        plainTitle = null;
        document.removeEventListener('visibilitychange', back);
      }
    };
    document.addEventListener('visibilitychange', back);
  }
  if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
    try {
      // Метка заменяет прежнее уведомление той же партии, а не копит стопку.
      new Notification(t('notify.yourTurn'), {
        body: t('notify.yourTurn.body', { turn, game: gameName }),
        tag: `sddnw-turn-${gameName}`,
      });
    } catch {
      // Уведомление — удобство, а не правило: без него остаётся заголовок вкладки.
    }
  }
}
