import { useState } from 'react';
import { gameApi } from '../../api/client';
import type { ResearchCategory, ResearchLevel, ResearchOption } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { OverlayFrame } from '../OverlayFrame';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { ResearchField } from './ResearchField';
import { ResearchFieldList } from './ResearchFieldList';
import { givesWholeLevel, nextLevel } from './researchRules';
import { t, useT } from '../../i18n';

/**
 * Экран исследований — п. 9, окно CHANGE CURRENT RESEARCH из MOO II. Открывается
 * кликом по сегменту «Наука» в правой панели.
 *
 * <b>Сцена во весь экран и делится на два сегмента.</b> Справа — сетка разделов, снятая
 * со снимка оригинала: восемь клеток двумя столбцами по четыре строки, по разделу в
 * клетке, порядок совпадает с порядком разделов в дереве. Слева — картинка и описание
 * выбранной технологии: в оригинале они показываются окошком по правому щелчку, а здесь
 * стоят на виду постоянно, потому что выбор без описания — это выбор вслепую.
 *
 * <b>Пропорции сегментов взяты у оригинала.</b> Там окно с сеткой занимает примерно две
 * трети ширины экрана (в снимке 896×672 — от 134-го пикселя до 747-го), а оставшаяся
 * треть уходит полосе галактики слева и панели состояния справа. Здесь эта треть целиком
 * отдана новому сегменту, и он стоит слева — как и просили; сетке достаются те же две
 * трети, что и в оригинале, поэтому клетки не растягиваются в полосы.
 *
 * <b>Управляющих кнопок по-прежнему две:</b> плашка с названием сверху и одна широкая
 * кнопка снизу. Подпись кнопки меняется по делу — «Отмена», пока ничего не выбрано, и
 * «Ок» после выбора: сервер цель уже записал, отменять нечего.
 *
 * Весь раздел целиком показывается отдельным окошком поверх сетки (`ResearchFieldList`) —
 * так же он устроен и в оригинале. Отдельного окошка с описанием больше нет: описание
 * теперь всегда на виду в левом сегменте, и второе такое же окно было бы тем же самым.
 *
 * Пропорции клетки заданы в `em` (см. `ResearchField`), а размер шрифта — от самого поля
 * под сетку (см. {@link SCALE}), поэтому сетка занимает свой сегмент целиком и никуда из
 * него не вылезает.
 *
 * Состояние исследований серверное: очки, прорыв и изученное считает сервер в фазе
 * конца хода, экран их только показывает и меняет цель. Ход исследования и шанс прорыва
 * стоят в сегменте «Наука» правой панели — в оригинале они тоже там, а не на этом экране.
 */

/**
 * Размер шрифта технологии: от него в `em` посчитана вся сетка.
 *
 * Считается от самого поля под сетку, а не от окна браузера: сверху плашка названия,
 * снизу кнопка, слева сегмент с описанием — вычитать всё это из `100vh` значило бы
 * каждый раз угадывать, и окно так и выезжало за верхнюю границу экрана.
 *
 * <b>Контейнер объявляет не тот элемент, который меряется.</b> Единицы `cq*` считаются
 * от <i>ближайшего предка</i>-контейнера, а не от самого элемента: поставив
 * `container-type` и `font-size` на один и тот же div, получаешь проценты не его, а
 * окна браузера — сетка тогда вылезала за окно и закрывала собой плашку с кнопкой.
 * Поэтому контейнером объявлено поле, а кегль задан вложенному в него блоку.
 */
const SCALE = 'clamp(6px, min(2.45cqh, 2.4cqw), 26px)';

/** Технология, о которой рассказывает левый сегмент, и раздел, откуда она взята. */
type Highlighted = {
  category: ResearchCategory;
  level: ResearchLevel;
  option: ResearchOption;
};

export function ResearchScreen({ onClose }: { onClose: () => void }) {
  const tree = useGameStore((state) => state.researchTree);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const research = useGameStore((state) => state.research);
  const setResearch = useGameStore((state) => state.setResearch);

  /** Раздел, раскрытый лестницей поверх сетки; `null` — открыта сама сетка. */
  const [listed, setListed] = useState<ResearchCategory | null>(null);
  const [choosing, setChoosing] = useState(false);
  /**
   * О чём рассказывает левый сегмент. Ставится наведением и выбором: пока игрок водит
   * курсором по сетке, сегмент показывает то, на что он смотрит, — так описание успевает
   * прочитаться до нажатия, а не после.
   */
  const [highlighted, setHighlighted] = useState<Highlighted | null>(null);
  /**
   * Цель выбрана в этом окне — п. 9.
   *
   * От этого зависит подпись единственной кнопки окна. «Отмена» честна, пока игрок ничего
   * не выбрал: он уходит, ничего не изменив. Как только технология выбрана, сервер уже
   * записал её целью, отменять нечего — и кнопка становится «Ок»: она подтверждает
   * сделанный выбор, а не отменяет его.
   */
  const [picked, setPicked] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Экран модальный и перекрывает карту. Пока поверх него открыт список раздела, экран Esc
  // не слушает вовсе: окошко и экран рождаются в разных отрисовках, и полагаться на
  // порядок их появления в очереди useModalEscape нельзя — одно нажатие закрывало
  // оба разом. Условие надёжнее порядка.
  useModalEscape(listed === null, onClose);
  useT();

  const categories = tree?.categories ?? [];

  if (!tree || categories.length === 0 || !research || !game || !credentials) {
    return (
      <OverlayFrame title={t('research.title')} onClose={onClose}>
        <p className="text-xs text-ink-dim">{t('research.notLoaded')}</p>
      </OverlayFrame>
    );
  }

  const highlight = (category: ResearchCategory, option: ResearchOption) => {
    const level = nextLevel(category, research);
    if (level) {
      setHighlighted({ category, level, option });
    }
  };

  const choose = (category: ResearchCategory, option: ResearchOption) => {
    const level = nextLevel(category, research);
    if (!level) {
      return;
    }
    // Уровень, который выдаётся целиком (общий у всех, любой у изобретательной расы —
    // п. 7), выбирается целиком, и какая из его технологий записана целью — безразлично:
    // прорыв выдаёт все. Чтобы цель не зависела от того, по какой строке щёлкнули,
    // отправляем всегда первую.
    const target = givesWholeLevel(research, level) ? level.options[0] : option;

    setChoosing(true);
    setError(null);
    gameApi
      .chooseResearch(game.id, credentials.accessToken, category.code, level.order, target.code)
      .then((chosen) => {
        setResearch(chosen);
        setPicked(true);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setChoosing(false));
  };

  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-space-950 text-xs">
      {/*
        Плашка с названием по центру сверху — как в оригинале. Надписей на ней ДВЕ, и это
        не украшение: в MOO II окна тоже два — «SELECT NEW RESEARCH» (docs/moo2, снимок
        сцены выбора исследования) и «CHANGE CURRENT RESEARCH». Цели нет — значит, экран
        открылся сам после прорыва и ждёт выбора; цель есть — игрок пришёл её менять.
        Плашка это и говорит, а другого места объяснить, почему экран всплыл, на нём нет.
      */}
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="border border-space-600 bg-space-800 px-6 py-0.5 uppercase tracking-[0.3em] text-ink-bright">
          {research.categoryCode ? t('research.changeCurrent') : t('research.selectNew')}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        {/*
          Левый сегмент — треть ширины, как в оригинале досталось всему, что не сетка.
          Картинка сверху, под ней название, уровень с ценой и описание.
        */}
        <TechnologyPreview highlighted={highlighted} />

        {/*
          Поле под сетку объявлено контейнером: от его размеров считается кегль клеток,
          поэтому сетка подстраивается под то место, которое ей осталось после плашки,
          кнопки и левого сегмента, а не под размер окна браузера.
        */}
        <div
          className="relative min-h-0 flex-1 overflow-hidden border-l border-space-700"
          style={{ containerType: 'size' }}
        >
          <div className="flex h-full flex-col justify-center" style={{ fontSize: SCALE }}>
            {/*
              Ширина сетки задана в `em`, как и высота клетки: иначе на широком экране
              клетки растягивались бы в полосы, а в оригинале они почти квадратные
              (306×147 px).
            */}
            <div className="mx-auto grid w-[41.29em] max-w-full grid-cols-2 gap-x-[0.79em] gap-y-[0.13em]">
              {categories.map((category) => (
                <ResearchField
                  key={category.code}
                  category={category}
                  research={research}
                  busy={choosing}
                  onChoose={(option) => {
                    highlight(category, option);
                    choose(category, option);
                  }}
                  onHighlight={(option) => highlight(category, option)}
                  onOpenList={() => setListed(category)}
                />
              ))}
            </div>
          </div>

          {/*
            Раздел целиком ложится поверх сетки, отступив от её краёв: в MOO II это
            отдельное окно поверх экрана, а не часть его разметки.
          */}
          {listed ? (
            <div className="absolute inset-[6%] z-10">
              <ResearchFieldList
                category={listed}
                research={research}
                onClose={() => setListed(null)}
              />
            </div>
          ) : null}
        </div>
      </div>

      {/*
        Одна широкая кнопка по центру снизу — «CANCEL» оригинала. Ошибка сервера
        показывается рядом: другого места для неё на экране нет, а молчать о ней нельзя.
      */}
      <footer
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center gap-4 border-t border-space-600 bg-space-800 px-3"
      >
        {error ? <span className="absolute left-3 text-danger">{error}</span> : null}
        <button
          type="button"
          className="w-1/3 border border-space-600 bg-space-900 py-0.5 uppercase tracking-[0.3em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {picked ? t('research.ok') : t('research.cancel')}
        </button>
      </footer>
    </div>
  );
}

/**
 * Левый сегмент: картинка и описание выбранной технологии — п. 9.
 *
 * Картинок технологий в игре пока нет, поэтому рамка стоит пустой и говорит, чего в ней
 * не хватает: разметка под спрайт готова, останется заменить содержимое рамки. Пока
 * ничего не выбрано, сегмент объясняет, что делать, — пустой прямоугольник читался бы
 * как поломка.
 */
function TechnologyPreview({ highlighted }: { highlighted: Highlighted | null }) {
  return (
    <section className="flex w-1/3 flex-none flex-col gap-3 overflow-auto p-4">
      <div className="flex aspect-[4/3] max-h-[45%] flex-none items-center justify-center border border-space-700 bg-space-900/60 px-4">
        <span className="text-center leading-tight text-ink-faint">
          {highlighted ? t('research.image') : t('research.noImage')}
        </span>
      </div>

      {highlighted ? (
        <>
          <div>
            <div className="text-base leading-tight text-accent">{highlighted.option.name}</div>
            <div className="mt-1 uppercase tracking-[0.2em] text-ink-dim">
              {highlighted.category.name} · {highlighted.level.name} · {t('research.rp', { n: highlighted.level.cost })}
            </div>
          </div>
          <p className="leading-relaxed text-ink-soft">{highlighted.option.description}</p>
          {highlighted.level.general ? (
            <p className="leading-relaxed text-ink-faint">
              {t('research.generalLevel')}
            </p>
          ) : null}
        </>
      ) : (
        <p className="leading-relaxed text-ink-faint">
          {t('research.hint')}
        </p>
      )}
    </section>
  );
}
