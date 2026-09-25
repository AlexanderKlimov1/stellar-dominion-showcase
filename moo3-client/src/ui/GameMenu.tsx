import { useEffect, useRef, useState } from 'react';
import { gameApi, GameApiError } from '../api/client';
import { useGameStore } from '../state/gameStore';
import { LoadGameDialog } from './LoadGameDialog';
import { TextScaleSwitch } from './TextScaleSwitch';
import { t, useT } from '../i18n';

interface GameMenuProps {
  /** «Новая» и «Выход» ведут на один экран — создания игры: другого выхода из партии нет. */
  onLeave: () => void;
  onLoadSave: (saveId: string) => void;
}

/** Сколько миллисекунд висит подсказка о сохранении. */
const NOTICE_MS = 4000;

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
export function GameMenu({ onLeave, onLoadSave }: GameMenuProps) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const lastError = useGameStore((state) => state.lastError);
  const setError = useGameStore((state) => state.setError);
  const [open, setOpen] = useState(false);
  const [loadOpen, setLoadOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

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
          <MenuItem onClick={run(() => setLoadOpen(true))}>{t('gameMenu.load')}</MenuItem>
          <MenuItem onClick={run(onLeave)}>{t('gameMenu.quit')}</MenuItem>
          {/*
            Размер текста — п. 11.1: здесь, а не только в главном меню. Настройку эту
            трогают не до партии, а посреди неё — когда впервые вглядываешься в подписи
            колоний, — и ради неё выходить из игры было бы нелепо. Отделено чертой: это
            не действие над партией, а настройка.
          */}
          <div className="mt-1 border-t border-space-700 px-3 py-2">
            <TextScaleSwitch />
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

      {loadOpen ? (
        <LoadGameDialog
          onLoad={(saveId) => {
            setLoadOpen(false);
            onLoadSave(saveId);
          }}
          onClose={() => setLoadOpen(false)}
        />
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
