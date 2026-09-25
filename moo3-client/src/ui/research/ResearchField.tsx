import type {
  PlayerResearch,
  ResearchCategory,
  ResearchLevel,
  ResearchOption,
} from '../../api/types';
import { givesWholeLevel, isAcquired, isCurrent, isCurrentLevel, nextLevel } from './researchRules';
import { t, useT } from '../../i18n';

/**
 * Раздел дерева одной клеткой сетки — п. 9, клеткой окна CHANGE CURRENT RESEARCH MOO II.
 *
 * Устройство клетки снято со скриншота оригинала и держится на нём: при ширине клетки
 * 306 px заголовок занимает 32, тело — 115, название уровня набрано в 1,45 раза крупнее
 * технологий, технологии сдвинуты вправо на 14 px и идут с шагом 21 px. Здесь всё это
 * пересчитано в `em` от размера шрифта технологии, а сам размер задаёт экран — так
 * пропорции держатся на любом мониторе.
 *
 * В клетке всегда стоит тот уровень, который в разделе исследуется следующим: выбрать
 * можно только его, как и в оригинале. Весь раздел целиком показывает список, он
 * открывается щелчком по плашке с названием.
 */
export function ResearchField({
  category,
  research,
  busy,
  onChoose,
  onOpenList,
  onHighlight,
}: {
  category: ResearchCategory;
  research: PlayerResearch;
  /** Выбор уже отправлен на сервер: до ответа новый не принимается. */
  busy: boolean;
  onChoose: (option: ResearchOption) => void;
  onOpenList: () => void;
  /**
   * На технологию смотрят — курсором или с клавиатуры. Левый сегмент экрана показывает
   * её картинку и описание: раньше это открывалось окошком по правому щелчку, и до
   * нажатия игрок выбирал вслепую.
   */
  onHighlight: (option: ResearchOption) => void;
}) {
  useT();
  const level = nextLevel(category, research);
  const running = research.categoryCode === category.code;
  // Уровень выдаётся целиком: общий — у всех, любой — у изобретательной расы (п. 7).
  // Тогда клетка подсвечивается целиком, а не одной строкой в ней.
  const wholeLevel = level !== null && givesWholeLevel(research, level);

  /*
    Клетка раздела, который исследуется прямо сейчас, обведена рамкой — как в оригинале
    (docs/moo2, снимок сцены выбора исследования: рамкой выделена одна клетка сетки).
    Цвета названия раздела для этого мало: он стоит мелкой плашкой в углу клетки, а
    взгляд ищет, куда уходят очки, по всей сетке разом.
  */
  return (
    <section
      className={
        'border bg-space-950/60 '
        + (running ? 'border-accent/60' : 'border-space-700 hover:border-space-500')
      }
    >
      {/*
        Заголовок: слева плашка с названием раздела, справа стоимость уровня. Плашка —
        кнопка: в MOO II ею открывается список всего раздела.
      */}
      <button
        type="button"
        className="flex h-[2.11em] w-full items-center justify-between gap-[0.5em] border-b border-space-800 px-[0.26em]"
        onClick={onOpenList}
        title={t('research.wholeSection.title', { name: category.name })}
      >
        <span
          className={
            'bg-space-800 px-[0.6em] py-[0.1em] text-[0.8em] uppercase tracking-[0.2em] ' +
            (running ? 'text-accent' : 'text-ink-soft')
          }
        >
          {category.name}
        </span>
        {/*
          Справа обычно цена уровня, но у исследуемого раздела — ход исследования, и
          после оплаты цены он превращается в счётчик прорыва.

          Без него экран необъясним: прорыв наступает не на цене, а где-то между ценой и
          её двойным размером (п. 9), и всё это время технология остаётся выбранной, а
          следующий уровень закрыт. Тот же счётчик есть в сегменте «Наука» правой панели,
          но этот экран её закрывает — игрок видел бы «оплачено, и ничего не происходит».
        */}
        <span className="text-[0.95em] text-ink-dim">
          {level ? <LevelProgress level={level} research={research} running={running} /> : null}
          {/*
            «Все сразу» — уровень выдаётся целиком: общий уровень так устроен у любой расы,
            а у изобретательной так устроен весь список. Метка стоит у каждой клетки, а не
            только у общих уровней: иначе изобретательная раса не понимала бы, за что
            платит цену уровня.
          */}
          {wholeLevel ? <span className="ml-[0.6em] text-ink-faint">{t('research.allAtOnce')}</span> : null}
        </span>
      </button>

      <div className="h-[7.62em] overflow-hidden px-[0.26em] pt-[0.2em]">
        {level ? (
          <>
            {/* Высота блока с названием уровня задана снаружи: внутри свой размер шрифта. */}
            <div className="h-[1.72em]">
              <div className="truncate text-[1.45em] leading-[1.19] text-ink-bright">
                {level.name}
              </div>
            </div>

            {/*
              `group` нужен уровню, который выдаётся целиком: наведение на любую его
              строку подсвечивает весь список — выбирается-то не строка, а уровень, и
              подсветка одной строки обещала бы выбор, которого нет.
            */}
            <ul className={wholeLevel ? 'group' : undefined}>
              {level.options.map((option) => {
                const acquired = isAcquired(research, category.code, option.code);
                // Уровень, который выдаётся целиком, и выбирается целиком: отмечены все
                // его строки, а не та одна, что записана целью на сервере. Так у общих
                // уровней и у всего дерева изобретательной расы (п. 7).
                const current = wholeLevel
                  ? isCurrentLevel(research, category.code, level.order)
                  : isCurrent(research, category.code, option.code);
                return (
                  <li key={option.code}>
                    <button
                      type="button"
                      className={
                        'block w-full truncate pl-[0.93em] text-left leading-[1.39em] ' +
                        (current
                          ? 'text-accent'
                          : acquired
                            ? 'text-ink-faint'
                            : wholeLevel
                              ? 'text-ink-soft group-hover:text-ink-bright'
                              : 'text-ink-soft hover:text-ink-bright') +
                        (busy ? ' cursor-default' : '')
                      }
                      disabled={busy}
                      onClick={() => onChoose(option)}
                      // Описание показывает левый сегмент экрана, и показывает сразу —
                      // курсором или переходом с клавиатуры. Правый щелчок при этом
                      // отдан браузеру: своего окошка с описанием больше нет.
                      onMouseEnter={() => onHighlight(option)}
                      onFocus={() => onHighlight(option)}
                      title={
                        wholeLevel
                          ? t('research.allAtOnce.title', { description: option.description })
                          : option.description
                      }
                    >
                      {current ? '▸ ' : acquired ? '✓ ' : '· '}
                      {option.name}
                    </button>
                  </li>
                );
              })}
            </ul>
          </>
        ) : (
          <p className="text-ink-faint">{t('research.sectionComplete')}</p>
        )}
      </div>
    </section>
  );
}

/**
 * Ход исследования в заголовке клетки — п. 9.
 *
 * У раздела, который не исследуется, здесь цена его ближайшего уровня. У исследуемого —
 * сколько в него вложено, а после оплаты цены — шанс прорыва на ближайшем ходу: прорыв
 * наступает не на самой цене, и без этого счётчика ожидание в несколько ходов выглядит
 * так, будто игра забыла про выбранную технологию.
 */
function LevelProgress({
  level,
  research,
  running,
}: {
  level: ResearchLevel;
  research: PlayerResearch;
  /** Этот раздел исследуется прямо сейчас — только у него есть ход исследования. */
  running: boolean;
}) {
  if (!running || research.levelOrder !== level.order) {
    return <>{t('research.rp', { n: level.cost })}</>;
  }
  // Цена оплачена: дальше решает бросок, и игроку нужен его шанс, а не «50/50 ОИ».
  if ((research.remainingPoints ?? 0) <= 0) {
    return <span className="text-accent">{t('research.breakthrough', { n: research.breakthroughPercent ?? 0 })}</span>;
  }
  return (
    <>
      {t('research.progress', { points: research.researchPoints, cost: level.cost })}
    </>
  );
}
