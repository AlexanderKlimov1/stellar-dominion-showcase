import { useGameStore, type OverlayPanel } from '../state/gameStore';
import { GameMenu } from './GameMenu';
import { HUD_FONT_SIZE, SIDE_PANEL_WIDTH } from './layout';
import { useT } from '../i18n';

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
export function Hud({ onLeave, onLoadSave }: {
  /** «Новая» и «Выход» меню «Игра» ведут на один экран — создания партии. */
  onLeave: () => void;
  onLoadSave: (saveId: string) => void;
}) {
  const game = useGameStore((state) => state.game);
  const overlay = useGameStore((state) => state.overlay);
  const setOverlay = useGameStore((state) => state.setOverlay);
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
      <MenuItem active={overlay === 'colonies'} onClick={() => toggle('colonies')}>
        {t('hud.colonies')}
      </MenuItem>
      <MenuItem active={overlay === 'planets'} onClick={() => toggle('planets')}>
        {t('hud.planets')}
      </MenuItem>
      <MenuItem active={overlay === 'fleet'} onClick={() => toggle('fleet')}>
        {t('hud.fleet')}
      </MenuItem>
      {/*
        «Игра» стоит там, где прежде были кнопки масштаба, — и там же, где `ZOOM` в
        нижнем меню оригинала. Меню раскрывается вверх: полоса у нижнего края экрана.
      */}
      <GameMenu onLeave={onLeave} onLoadSave={onLoadSave} />
      <MenuItem active={overlay === 'leaders'} onClick={() => toggle('leaders')}>
        {t('hud.leaders')}
      </MenuItem>
      {/*
        Отдельного пункта «Дипломатия» в нижнем меню нет — его нет и в оригинале:
        разговор с правителем открывается аудиенцией с пульта «Расы», а не своей
        кнопкой карты. Сам экран дипломатии никуда не делся, туда ведёт «аудиенция»
        (`RaceRelationsScreen`).
      */}
      <MenuItem active={overlay === 'races'} onClick={() => toggle('races')}>
        {t('hud.races')}
      </MenuItem>
      {/*
        Инфо — п. 11.1, окно Information MOO II: летопись империй графиком, свойства
        рас знакомых соседей и обе страницы технологий.
      */}
      <MenuItem active={overlay === 'info'} onClick={() => toggle('info')}>
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
  onClick,
}: {
  children: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`link flex-1 basis-[9ch] px-1 py-2 text-center ${active ? 'link-active' : ''}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
