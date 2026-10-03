import { useEffect, useRef, useState } from 'react';
import { gameApi, GameApiError } from '../api/client';
import { useGameStore } from '../state/gameStore';
import { readAccount } from '../state/session';
import { HOTKEY_ACTIONS, hotkeyLabel, useHotkeys } from './hotkeys';
import { TextScaleSwitch } from './TextScaleSwitch';
import { t, useT } from '../i18n';

interface GameMenuProps {
  /** «Новая» и «Выход» ведут на один экран — создания игры: другого выхода из партии нет. */
  onLeave: () => void;
  /**
   * Открыть список сохранений — п. 3.
   *
   * Окно, как и настройки ниже, рисует `App`: нарисованное отсюда, оно оставалось в слое
   * нижней полосы и уходило под правую панель.
   */
  onLoad: () => void;
  /**
   * Открыть настройки игрока — п. 11.1.
   *
   * <b>Экран открывает НЕ меню, а `App`</b>, и это не прихоть: нижняя полоса лежит в своём
   * слое (`z-10`), а слой — это клетка. Экран, нарисованный из меню, оставался внутри неё
   * и уходил ПОД правую панель (`z-20`), хотя у самого стоит `z-50`: номер слоя считается
   * внутри своего слоя, а не поперёк всех. Поэтому меню только говорит «открыть», а рисует
   * `App` — там же, где и все прочие экраны поверх карты.
   */
  onPreferences: () => void;
}

/** Сколько миллисекунд висит подсказка о сохранении. */
const NOTICE_MS = 4000;

/**
 * Кто слушает горячую клавишу меню — п. 11.1. Меню держит своё «открыто» в себе (это
 * раскрывающийся список полосы, а не экран хранилища), поэтому клавиша карты не ставит
 * признак, а ПРОСИТ меню переключиться — тем же действием, что и нажатие на пункт.
 */
const toggleListeners = new Set<() => void>();

/** Открыть или закрыть меню «Игра» — горячая клавиша карты (G). */
export const toggleGameMenu = (): void => toggleListeners.forEach((listener) => listener());

/**
 * Меню «Игра» — п. 11.1: пункт НИЖНЕЙ полосы, открывающийся вверх.
 *
 * <b>Своей полосы вверху у него больше нет.</b> Прежде меню занимало ряд во всю ширину
 * экрана ради одной ссылки, а карта начиналась под ним; теперь оно стоит в нижнем меню
 * рядом с прочими разделами — там же, где в оригинале стоит `ZOOM`, — и весь верх экрана
 * отдан галактике.
 *
 * Единственный выход из партии: «Игра» → «Новая» или «Игра» → «Выход»; закрытие или
 * перезагрузка страницы партию не бросает — клиент возвращается в неё сам.
 *
 * Подсказка о сохранении и ошибка действия показываются НАД полосой: места в самой полосе
 * нет, а терять их нельзя — других мест для них внутри партии не осталось.
 */
export function GameMenu({ onLeave, onLoad, onPreferences }: GameMenuProps) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const lastError = useGameStore((state) => state.lastError);
  const setError = useGameStore((state) => state.setError);
  const [open, setOpen] = useState(false);
  // Раскладка спрашивается хуком: переназначенная клавиша обязана появиться в списке сразу.
  const keys = useHotkeys();
  const [notice, setNotice] = useState<string | null>(null);
  // «Покинуть партию» — в два нажатия: первое спрашивает, второе уводит (пункт 11).
  const [leaving, setLeaving] = useState(false);
  const humans = useGameStore((state) => state.players.filter((player) => player.playerType === 'HUMAN').length);
  const menuRef = useRef<HTMLDivElement>(null);

  // Закрытое меню забывает недоговорённое «покинуть?»: следующее открытие начинается заново.
  useEffect(() => {
    if (!open) {
      setLeaving(false);
    }
  }, [open]);

  useEffect(() => {
    const toggle = () => setOpen((current) => !current);
    toggleListeners.add(toggle);
    return () => {
      toggleListeners.delete(toggle);
    };
  }, []);

  // Меню закрывается кликом мимо него и клавишей Esc — как привычное меню приложения.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!notice) {
      return;
    }
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);

  // Ошибки действий внутри партии (например, конца хода) больше показывать негде:
  // экраны лобби и меню сюда не заходят. Гаснут они так же, как подсказка.
  useEffect(() => {
    if (!lastError) {
      return;
    }
    const timer = window.setTimeout(() => setError(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [lastError, setError]);

  const run = (action: () => void) => () => {
    setOpen(false);
    action();
  };

  // Сохранение — слепок всего состояния партии в базе сервера: в него уходит конец
  // последнего завершённого хода. Сохраняет только игрок: автосохранения нет, оно
  // писало сотню килобайт каждый ход, а пользовались им редко.
  useT();
  const save = () => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .saveGame(game.id, credentials.accessToken)
      .then((saved) => setNotice(t('gameMenu.saved', { name: game.name, turn: turnLabel(saved.turn) })))
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('gameMenu.saveFailed')),
      );
  };

  return (
    <div ref={menuRef} className="relative flex-1 basis-[9ch]">
      <button
        type="button"
        className={`link w-full px-1 py-2 text-center ${open ? 'link-active' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        title={t('hotkey.title', { action: t('gameMenu.game'), key: hotkeyLabel(keys.game) })}
        onClick={() => setOpen(!open)}
      >
        {t('gameMenu.game')}
      </button>

      {open ? (
        // Раскрывается ВВЕРХ: полоса стоит у нижнего края экрана, и вниз открываться некуда.
        <div
          role="menu"
          className="absolute bottom-full left-0 z-40 mb-1 w-max min-w-[11rem] border border-space-600 bg-space-900 py-1 text-left shadow-lg shadow-black/60"
        >
          <MenuItem onClick={run(onLeave)}>{t('gameMenu.new')}</MenuItem>
          <MenuItem onClick={run(save)}>{t('gameMenu.save')}</MenuItem>
          {/* Гостю сохранений не поднимают (backlog-promo, пункт 1): пункт ответил бы отказом. */}
          {readAccount()?.account.role === 'GUEST' ? null : (
            <MenuItem onClick={run(onLoad)}>{t('gameMenu.load')}</MenuItem>
          )}
          {/*
            Настройки игрока — п. 11.1: раскладка горячих клавиш. Пункт стоит и здесь, и в
            главном меню, потому что настройки принадлежат УЧЁТНОЙ ЗАПИСИ, а не партии:
            клавишу переназначают тогда, когда она помешала, то есть посреди игры, и ради
            этого выходить из партии было бы нелепо.
          */}
          <MenuItem onClick={run(onPreferences)}>{t('gameMenu.preferences')}</MenuItem>
          <MenuItem onClick={run(onLeave)}>{t('gameMenu.quit')}</MenuItem>
          {/*
            Покинуть партию людей — backlog-promo, пункт 11: империю поведёт ИИ, и партия
            больше не ждёт ушедшего. Обычный выход («выйти») партию не отпускает — она ждёт,
            пока часы не сочтут игрока ушедшим. В одиночной партии пункта нет: ждать там
            некому, и уходить не от кого.
          */}
          {humans >= 2 && game?.status === 'IN_PROGRESS' ? (
            <MenuItem
              onClick={() => {
                if (!leaving) {
                  setLeaving(true);
                  return;
                }
                setLeaving(false);
                setOpen(false);
                if (game && credentials) {
                  gameApi
                    .leaveGame(game.id, credentials.accessToken)
                    .then(onLeave)
                    .catch((failure: Error) => setError(failure.message));
                }
              }}
            >
              {leaving ? t('gameMenu.leave.confirm') : t('gameMenu.leave')}
            </MenuItem>
          ) : null}
          {/*
            Размер текста — п. 11.1: здесь, а не только в главном меню. Настройку эту
            трогают не до партии, а посреди неё — когда впервые вглядываешься в подписи
            колоний, — и ради неё выходить из игры было бы нелепо. Отделено чертой: это
            не действие над партией, а настройка.
          */}
          <div className="mt-1 border-t border-space-700 px-3 py-2">
            <TextScaleSwitch />
          </div>
          {/*
            Горячие клавиши карты — п. 11.1: СПИСОК, а не настройка. Переназначают их в
            «Настройках», а здесь они просто написаны — потому что написать их больше негде:
            у «Ход» и «Колонии» подсказка есть на самой кнопке, а у родного мира кнопки нет
            вовсе, и клавиша, о которой негде прочитать, это механика без пути к ней.
            Читается список из живой раскладки: переназначив клавишу, игрок увидит здесь
            новую, а не ту, что была при сборке.
          */}
          <div className="mt-1 border-t border-space-700 px-3 py-2 text-11">
            <div className="uppercase tracking-[0.2em] text-ink-dim">{t('gameMenu.hotkeys')}</div>
            {HOTKEY_ACTIONS.map(({ action, label }) => (
              <div key={action} className="flex justify-between gap-4 text-ink-faint">
                <span>{t(label)}</span>
                <span className="text-ink-dim">{hotkeyLabel(keys[action])}</span>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {/*
        Подсказка и ошибка — над полосой, и только пока меню закрыто: раскрытое меню
        стоит на том же месте, и спорить им там незачем.
      */}
      {!open && (notice || lastError) ? (
        <span
          /*
            Подсказка встаёт НАД пунктом и по центру его, а не от левого края: пункт
            стоит посреди полосы, и строка, пущенная вправо одной линией, уходила под
            правую панель — «Партия „Fleet“ сохран…». Ширина ограничена, перенос
            разрешён: длинное имя партии складывается в две строки, а не пропадает.
          */
          className={
            'absolute bottom-full left-1/2 mb-1 w-max max-w-[min(26rem,60vw)] '
            + '-translate-x-1/2 border border-space-700 bg-space-950/90 px-2 py-0.5 '
            + 'text-center ' + (lastError ? 'text-danger' : 'text-ink-dim')
          }
        >
          {lastError ?? notice}
        </span>
      ) : null}
    </div>
  );
}

/** Ход 0 — состояние сразу после старта партии: ни одного хода ещё не завершено. */
const turnLabel = (turn: number): string => (turn === 0 ? t('turn.start') : t('turn.n', { n: turn }));

function MenuItem({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <button
      type="button"
      role="menuitem"
      className="block w-full px-3 py-1 text-left text-ink hover:bg-space-800 hover:text-accent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}
