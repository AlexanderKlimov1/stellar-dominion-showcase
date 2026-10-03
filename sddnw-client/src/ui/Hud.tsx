import { useGameStore, type OverlayPanel } from '../state/gameStore';
import { GameMenu } from './GameMenu';
import { hotkeyLabel, useHotkeys } from './hotkeys';
import { HUD_FONT_SIZE, SIDE_PANEL_WIDTH } from './layout';
import { t, useT } from '../i18n';

/**
 * Нижнее меню — п. 11.1: все разделы игры текстовыми ссылками, включая саму «Игру».
 *
 * Меню занимает ровно ширину галактики и не заходит на правую панель, а пункты делят
 * эту ширину поровну.
 *
 * <b>«Игра» переехала сюда с верхней полосы</b>, а кнопки «Зум+» и «Зум-» убраны вовсе:
 * ряд во всю ширину экрана ради одной ссылки стоил карте верхней полосы, а масштаб и так
 * меняется колесом мыши — в оригинале на этом месте нижнего меню стоит `ZOOM`.
 */
export function Hud({ onLeave, onLoad, onPreferences }: {
  /** «Новая» и «Выход» меню «Игра» ведут на один экран — создания партии. */
  onLeave: () => void;
  /** Список сохранений — п. 3: полоса только передаёт вызов, рисует окно `App`. */
  onLoad: () => void;
  /** Настройки игрока — п. 11.1: полоса только передаёт вызов, рисует экран `App`. */
  onPreferences: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const overlay = useGameStore((state) => state.overlay);
  const setOverlay = useGameStore((state) => state.setOverlay);
  // Раскладку игрок правит сам (п. 11.1), поэтому клавиша в подсказке спрашивается хуком:
  // иначе на кнопке осталась бы прежняя буква, а работала бы новая — и поверят кнопке.
  const keys = useHotkeys();
  const { t } = useT();

  if (!game) {
    return null;
  }

  const toggle = (panel: OverlayPanel) => setOverlay(overlay === panel ? 'none' : panel);

  return (
    // data-map-inset — метка для сцены: панель лежит поверх карты,
    // и «вся галактика» вписывает поле в свободную от панелей полосу.
    <div
      data-map-inset="bottom"
      style={{ right: SIDE_PANEL_WIDTH, fontSize: HUD_FONT_SIZE }}
      /*
        Полоса ПЕРЕНОСИТСЯ на вторую строку, когда не помещается (`flex-wrap`), — это
        плата за крупный текст (п. 11.1). При 130 % на окне в тысячу точек карте остаётся
        полтысячи, а пунктов восемь: в одну строку они налезали друг на друга буквами.
        Высоту полосы сцена меряет сама по метке `data-map-inset`, поэтому вторая строка
        карту не закрывает.
      */
      className="absolute bottom-0 left-0 z-10 flex flex-wrap items-stretch border-t border-space-700 bg-space-950/85"
    >
      {/*
        У каждого пункта есть и горячая клавиша (п. 11.1) — она названа во всплывающей подсказке:
        иначе узнать о ней негде, а клавиша, о которой игрок не знает, для игры всё равно
        что её нет.
      */}
      <MenuItem
        active={overlay === 'colonies'}
        hotkey={hotkeyLabel(keys.colonies)}
        onClick={() => toggle('colonies')}
      >
        {t('hud.colonies')}
      </MenuItem>
      <MenuItem
        active={overlay === 'planets'}
        hotkey={hotkeyLabel(keys.planets)}
        onClick={() => toggle('planets')}
      >
        {t('hud.planets')}
      </MenuItem>
      <MenuItem
        active={overlay === 'fleet'}
        hotkey={hotkeyLabel(keys.fleet)}
        onClick={() => toggle('fleet')}
      >
        {t('hud.fleet')}
      </MenuItem>
      {/*
        «Игра» стоит там, где прежде были кнопки масштаба, — и там же, где `ZOOM` в
        нижнем меню оригинала. Меню раскрывается вверх: полоса у нижнего края экрана.
      */}
      <GameMenu onLeave={onLeave} onLoad={onLoad} onPreferences={onPreferences} />
      <MenuItem
        active={overlay === 'leaders'}
        hotkey={hotkeyLabel(keys.leaders)}
        onClick={() => toggle('leaders')}
      >
        {t('hud.leaders')}
      </MenuItem>
      {/*
        Отдельного пункта «Дипломатия» в нижнем меню нет — его нет и в оригинале:
        разговор с правителем открывается аудиенцией с пульта «Расы», а не своей
        кнопкой карты. Сам экран дипломатии никуда не делся, туда ведёт «аудиенция»
        (`RaceRelationsScreen`).
      */}
      <MenuItem
        active={overlay === 'races'}
        hotkey={hotkeyLabel(keys.races)}
        onClick={() => toggle('races')}
      >
        {t('hud.races')}
      </MenuItem>
      {/*
        Инфо — п. 11.1, окно Information MOO II: летопись империй графиком, свойства
        рас знакомых соседей и обе страницы технологий.
      */}
      <MenuItem
        active={overlay === 'info'}
        hotkey={hotkeyLabel(keys.info)}
        onClick={() => toggle('info')}
      >
        {t('hud.info')}
      </MenuItem>
    </div>
  );
}

/**
 * Пункт меню: все пункты одной ширины — flex-1 от общей ширины полосы.
 *
 * Основа в `ch` — ширина самой длинной подписи: пункт уже неё не сжимается, а вместо
 * этого полоса переносит его на вторую строку. `basis-0` такого не умеет вовсе: он делит
 * ширину поровну независимо от того, влезают ли буквы.
 */
function MenuItem({
  children,
  active = false,
  hotkey,
  onClick,
}: {
  children: string;
  active?: boolean;
  /** Горячая клавиша этого раздела — п. 11.1; пусто, если её нет. */
  hotkey?: string;
  onClick: () => void;
}) {
  // Пункт сам зовёт `t` ради подсказки — значит, сам и подписывается на язык: без этого
  // он остался бы с прежним словарём до первой перерисовки по другой причине.
  useT();
  return (
    <button
      type="button"
      /*
        Основа — по слову, а не 9ch на каждый пункт (backlog-promo, пункт 32): «Info» и
        «Колонии» разной длины, и равные основы перенесли «Info» на вторую строку уже на
        ширине 1100, хотя словам места хватало. Растягиваются пункты по-прежнему поровну.
      */
      className={`link flex-auto whitespace-nowrap px-1.5 py-2 text-center ${active ? 'link-active' : ''}`}
      title={hotkey ? t('hotkey.title', { action: children, key: hotkey }) : undefined}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
