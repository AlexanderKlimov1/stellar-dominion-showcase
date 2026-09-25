import { useEffect, useRef, useState } from 'react';
import { useModalEscape } from '../useModalEscape';
import { MilkyWayControls } from './MilkyWayControls';
import { MilkyWayCanvas } from './MilkyWayCanvas';
import { t, useT, type Key } from '../../i18n';
import {
  ACCRETION_RINGS,
  COMPOSITION,
  DISK_GLOW_LY,
  ELEMENT_COUNT,
  FERMI_HEIGHT_LY,
  JET_LAYERS,
  MILKY_WAY_MARKS,
  ZOOM_MAX,
  ZOOM_MIN,
  accretionRing,
  diskRing,
  jetOutline,
  project,
  ringPoints,
  toScreen,
  type ElementKind,
  type JetLayer,
  type ViewAngles,
} from './milkyWayModel';

/**
 * Схема Млечного Пути — демонстрационная сцена из главного меню.
 *
 * Сто тысяч сто точек трёхмерной схемы можно поворачивать: кнопкой вращения,
 * перетаскиванием прямо по полю и ползунками поворота и наклона. Объём виден не по самой
 * картинке, а по тому, как она меняется при повороте, — поэтому вращение включено с самого
 * начала, и первое, что видит игрок, это движение, а не застывший рисунок.
 *
 * Считает всё {@link milkyWayModel}: там и сама схема, и повороты с проекцией. Экран
 * только рисует и держит углы — так сцену можно крутить, ничего не пересобирая.
 *
 * Рисуется в два слоя, и оба свои: сто тысяч звёзд фона — канвой
 * ({@link MilkyWayCanvas}), сотня именованных элементов — SVG. Канва кладёт звёзды прямо
 * в растр, SVG даёт подписи, кольца и анимацию; движка ради этого по-прежнему не заводим —
 * трёхмерность здесь одна лишь проекция.
 */

/**
 * Внутренние единицы поля по высоте: SVG растягивается по месту, а числа проекции
 * остаются целыми. Ширина поля считается от пропорций окна — поле прямоугольное.
 */
const FIELD_HEIGHT = 1000;

/**
 * Предел растра звёзд в пикселях. Поле занимает всю ширину окна, и на большом экране
 * буфер вырос бы до нескольких мегапикселей: очистить и выгрузить его на каждом кадре
 * дороже, чем посчитать сами звёзды. За пределом растр считается мельче и растягивается
 * стилями — на точках в пиксель это незаметно.
 */
const MAX_STAR_PIXELS = 2_200_000;

/** Цвет по виду элемента: тёплое ядро, голубые рукава, сиреневое гало, жёлтое Солнце. */
const COLORS: Record<ElementKind, string> = {
  blackhole: '#05070c',
  bar: '#ffcf8f',
  arm: '#9ec8ff',
  spur: '#7fd4ff',
  halo: '#c3b1ff',
  sun: '#ffe66b',
  star: '#dbe7ff',
};

/**
 * Размер точки по виду элемента, во внутренних единицах поля.
 *
 * Именованные элементы стали мельче с тех пор, как звёзд стало сто тысяч: прежде они
 * держали форму схемы, а теперь форму держит само поле звёзд, и крупные кружки поверх
 * него читались бусами на нитке. Осталось у них другое дело — назвать рукав, показать
 * Солнце и шаровые скопления.
 */
const SIZES: Record<ElementKind, number> = {
  blackhole: 11,
  bar: 4,
  arm: 3.5,
  spur: 3,
  halo: 5.5,
  sun: 9.5,
  star: 2.2,
};

/**
 * Насколько приглушить точку поверх звёздного поля. Рукава и перемычка нарисованы
 * звёздами, а их метки — только подсказка, где что; Солнце, скопления и чёрная дыра
 * остаются в полную силу: их в поле звёзд не разглядеть.
 */
const MARK_FADE: Partial<Record<ElementKind, number>> = {
  bar: 0.45,
  arm: 0.5,
  spur: 0.5,
};

/** Сколько градусов поворота даёт пиксель перетаскивания. */
const DRAG_DEGREES_PER_PX = 0.35;

/**
 * Скорость своего вращения, градусов в секунду. Знак отрицательный: схема поворачивается
 * <b>против часовой стрелки</b>.
 *
 * Так Галактику видно с южного галактического полюса — с северного она вращается по
 * часовой. Знак в одном месте: угол растёт по часовой (экранная ось Y смотрит вниз),
 * поэтому вычитание здесь и есть смена направления.
 */
const SPIN_DEGREES_PER_SEC = -9;

/**
 * Чем рисуется каждый слой луча и как он дышит.
 *
 * Сроки дыхания взяты несоизмеримыми (7 : 4,5 : 3,1 секунды): от несовпадения сроков
 * свечение живёт, а не мигает разом. Южной доле вдобавок дан сдвиг — иначе обе доли
 * вспыхивали бы в лад, и выброс читался бы одной мигающей фигурой.
 */
const JET_STYLE: Record<JetLayer['code'], { fill: string; animation: string }> = {
  haze: { fill: 'url(#mw-jet-haze)', animation: 'moo3-jet-haze 7s ease-in-out infinite' },
  beam: { fill: 'url(#mw-jet)', animation: 'moo3-jet 4.5s ease-in-out infinite' },
  core: { fill: 'url(#mw-jet-core)', animation: 'moo3-jet 3.1s ease-in-out infinite' },
};

export function MilkyWayScreen({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<ViewAngles>({ yawDeg: 20, pitchDeg: 62, rollDeg: 0, zoom: 1 });
  const [spinning, setSpinning] = useState(true);
  const [labels, setLabels] = useState(true);
  const [radiation, setRadiation] = useState(true);
  const [dragging, setDragging] = useState(false);
  /** Размер поля в пикселях экрана: от него зависят и пропорции схемы, и растр звёзд. */
  const [box, setBox] = useState({ width: 0, height: 0 });

  useModalEscape(true, onClose);
  useT();

  /*
    Поле занимает всё место между краем экрана и панелью управления, поэтому его размер
    известен только из разметки. Наблюдатель следит и за сменой размера окна: схема
    перестраивается под новые пропорции, а не обрезается по прежним.
  */
  const fieldBox = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = fieldBox.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setBox({ width: Math.round(width), height: Math.round(height) });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  /*
    Своё вращение: угол двигается по времени кадра, а не на постоянную величину за кадр.
    Иначе на медленной машине схема крутилась бы медленнее, чем на быстрой, — а это одна
    и та же сцена.
  */
  useEffect(() => {
    if (!spinning) {
      return;
    }
    let frame = 0;
    let last = performance.now();
    const step = (now: number) => {
      const seconds = (now - last) / 1000;
      last = now;
      setView((current) => ({
        ...current,
        // +360 перед остатком: при вращении против часовой угол уходит в минус, а
        // ползунок поворота и подпись ждут числа от 0 до 360.
        yawDeg: (current.yawDeg + SPIN_DEGREES_PER_SEC * seconds + 360) % 360,
      }));
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [spinning]);

  /*
    Перетаскивание: за указателем следит окно, а не поле, — рука уходит с поля в первый
    же момент, и бросать схему за краем можно спокойно. Тот же приём, что у переноса
    жителей: на нём здесь уже обжигались.
  */
  const from = useRef<{ x: number; y: number; roll: boolean } | null>(null);
  useEffect(() => {
    if (!dragging) {
      return;
    }
    const onMove = (event: PointerEvent) => {
      const start = from.current;
      if (!start) {
        return;
      }
      const dx = event.clientX - start.x;
      const dy = event.clientY - start.y;
      from.current = { x: event.clientX, y: event.clientY, roll: start.roll };
      setView((current) => {
        if (start.roll) {
          // Крен: правой кнопкой или с Shift. Горизонтальное движение кренит картину,
          // вертикальное по-прежнему наклоняет — так рукой удобнее, чем двумя осями
          // крена сразу.
          return {
            ...current,
            rollDeg: (current.rollDeg + dx * DRAG_DEGREES_PER_PX + 360) % 360,
            pitchDeg: clamp(current.pitchDeg + dy * DRAG_DEGREES_PER_PX, -90, 90),
          };
        }
        return {
          ...current,
          yawDeg: (current.yawDeg + dx * DRAG_DEGREES_PER_PX + 360) % 360,
          // Наклон дальше «ребра» и «плашмя» не пускаем: за ними схема переворачивается,
          // и понять, с какой стороны на неё смотрят, уже нельзя.
          pitchDeg: clamp(current.pitchDeg + dy * DRAG_DEGREES_PER_PX, -90, 90),
        };
      });
    };
    const onUp = () => {
      from.current = null;
      setDragging(false);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, [dragging]);

  // Ширина поля в своих единицах — из пропорций окна: высота всегда 1000, а ширина во
  // столько же раз больше, во сколько поле шире, чем выше. Пока разметка не измерена,
  // берём квадрат: первый кадр всё равно перерисуется по замеру.
  const aspect = box.height > 0 ? box.width / box.height : 1;
  const fieldWidth = Math.round(FIELD_HEIGHT * aspect);

  // Растр звёзд — в пикселях экрана, но не больше предела: на большом окне он считается
  // мельче и растягивается стилями.
  const pixels = Math.max(1, box.width * box.height);
  const shrink = Math.min(1, Math.sqrt(MAX_STAR_PIXELS / pixels));
  const canvasWidth = Math.max(1, Math.round(box.width * shrink));
  const canvasHeight = Math.max(1, Math.round(box.height * shrink));

  // Проецируются только именованные элементы: звёзды фона считает и рисует канва под
  // схемой — заслонять ими рукава, скопления и Солнце нельзя, схема ради них и затевалась.
  const marks = project(MILKY_WAY_MARKS, view, fieldWidth, FIELD_HEIGHT);
  const ring = diskRing(view, fieldWidth, FIELD_HEIGHT);
  const poles = [FERMI_HEIGHT_LY, -FERMI_HEIGHT_LY].map((height) =>
    toScreen({ x: 0, y: 0, z: height }, view, fieldWidth, FIELD_HEIGHT),
  );

  return (
    <div className="absolute inset-0 z-50 flex flex-col bg-space-950 p-2">
      <div className="mb-2 flex flex-none items-baseline justify-between gap-6 border-b border-space-700 pb-2">
        <h2 className="text-26 uppercase tracking-[0.3em] text-ink-bright">{t('milkyway.title')}</h2>
        <p className="text-17 text-ink-faint">
          {t('milkyway.summary.1')} <span className="text-ink">{ELEMENT_COUNT}</span> {t('milkyway.summary.2')}
        </p>
      </div>

      <div className="flex min-h-0 flex-1 gap-4">
        {/*
          Поле занимает всё место от края экрана до панели управления: канва и SVG стоят в
          нём друг на друге и растянуты на весь прямоугольник. Раньше здесь был квадрат по
          высоте, и по бокам оставались чёрные поля, обрезавшие гало и ближний край диска.
        */}
        <div ref={fieldBox} className="relative min-h-0 min-w-0 flex-1">
          {/* Канва появляется по первому замеру поля: до него её размер неизвестен. */}
          {box.height > 0 ? (
            <MilkyWayCanvas view={view} width={canvasWidth} height={canvasHeight} />
          ) : null}
          <svg
            viewBox={`0 0 ${fieldWidth} ${FIELD_HEIGHT}`}
            className={
              'h-full max-h-full w-full max-w-full touch-none select-none '
              + (dragging ? 'cursor-grabbing' : 'cursor-grab')
            }
            role="img"
            aria-label={t('milkyway.aria', {
              n: ELEMENT_COUNT,
              yaw: Math.round(view.yawDeg),
              pitch: Math.round(view.pitchDeg),
              roll: Math.round(view.rollDeg),
            })}
            onPointerDown={(event) => {
              from.current = {
                x: event.clientX,
                y: event.clientY,
                // Правая кнопка или Shift — крен; левая без Shift — поворот и наклон.
                roll: event.button === 2 || event.shiftKey,
              };
              setDragging(true);
              /*
                Своё вращение при этом НЕ останавливается: Галактика крутится вокруг своей
                оси всегда, а рука лишь меняет, с какой стороны на неё смотрят. Прежде
                вращение гасилось первым же нажатием, и стоило тронуть сцену мышкой — она
                застывала макетом, а включать движение обратно приходилось галочкой.
                Углы складываются сами: и рука, и вращение правят угол поворота через
                функцию от прежнего значения, поэтому спорить им нечем.
              */
            }}
            onContextMenu={(event) => event.preventDefault()}
            onWheel={(event) => {
              // Колесо приближает и отдаляет. Шаг умножением, а не сложением: у зума
              // важно отношение, и на дальнем краю шаг сложением полз бы еле-еле.
              // Предел тот же, что у ползунка приближения: разойдись они — ползунок
              // показывал бы своё крайнее значение, а схема стояла бы в другом.
              setView((current) => ({
                ...current,
                zoom: clamp(current.zoom * (event.deltaY < 0 ? 1.12 : 1 / 1.12),
                  ZOOM_MIN, ZOOM_MAX),
              }));
            }}
          >
            <defs>
              {/* Свечение ядра: центр Галактики ярче всего, и точками это не передать. */}
              <radialGradient id="mw-core">
                <stop offset="0%" stopColor="#ffdca8" stopOpacity="0.4" />
                <stop offset="60%" stopColor="#ff9d5c" stopOpacity="0.09" />
                <stop offset="100%" stopColor="#ff9d5c" stopOpacity="0" />
              </radialGradient>
              {/* Свет аккреционного диска у самой дыры: он и есть её излучение. */}
              <radialGradient id="mw-accretion">
                <stop offset="0%" stopColor="#fff4d6" stopOpacity="0.9" />
                <stop offset="45%" stopColor="#ffb347" stopOpacity="0.45" />
                <stop offset="100%" stopColor="#ff7043" stopOpacity="0" />
              </radialGradient>
              {/*
                Свечение диска: звёзды между рукавами по отдельности почти не видны — на
                пиксель их приходится меньше одной, — а вместе они дают ровный свет, из-за
                которого рукава на снимках лежат ВНУТРИ светящейся плоскости, а не лентами
                на чёрном. Ста тысячами точек такой свет не набрать (нужны миллионы),
                поэтому он нарисован заливкой.
              */}
              <radialGradient id="mw-disk-glow">
                <stop offset="0%" stopColor="#dfe9ff" stopOpacity="0.30" />
                <stop offset="38%" stopColor="#b9cdff" stopOpacity="0.20" />
                <stop offset="72%" stopColor="#8fb0ff" stopOpacity="0.09" />
                <stop offset="100%" stopColor="#7aa0ff" stopOpacity="0" />
              </radialGradient>
              {/*
                Лучи выброса. Три градиента на три слоя: дымка, луч и ядро. Все синие и
                все гаснут к вершине — выброс ярче всего у самой дыры, а к краю доли
                рассеивается. Градиент радиальный и привязан к середине поля: центр
                Галактики там, и свечение обязано считать расстояние от него, а не от
                угла собственного многоугольника.
              */}
              <radialGradient id="mw-jet-haze">
                <stop offset="0%" stopColor="#7cc4ff" stopOpacity="0.18" />
                <stop offset="30%" stopColor="#5aa8ff" stopOpacity="0.11" />
                <stop offset="62%" stopColor="#4a8bff" stopOpacity="0.05" />
                <stop offset="100%" stopColor="#3f7bff" stopOpacity="0" />
              </radialGradient>
              <radialGradient id="mw-jet">
                <stop offset="0%" stopColor="#bfe6ff" stopOpacity="0.34" />
                <stop offset="34%" stopColor="#84c4ff" stopOpacity="0.19" />
                <stop offset="68%" stopColor="#6db8ff" stopOpacity="0.07" />
                <stop offset="100%" stopColor="#4a8bff" stopOpacity="0" />
              </radialGradient>
              <radialGradient id="mw-jet-core">
                <stop offset="0%" stopColor="#eaf7ff" stopOpacity="0.55" />
                <stop offset="42%" stopColor="#b6e2ff" stopOpacity="0.26" />
                <stop offset="74%" stopColor="#9ad4ff" stopOpacity="0.10" />
                <stop offset="100%" stopColor="#7cc4ff" stopOpacity="0" />
              </radialGradient>
            </defs>

            {/* Свет диска: эллипс в плоскости Галактики, поэтому он наклоняется вместе
                с ней и с ребра ложится в полосу — как и положено светящейся плоскости. */}
            <polygon
              points={ringPoints(DISK_GLOW_LY, 0, view, fieldWidth, FIELD_HEIGHT, 40)}
              fill="url(#mw-disk-glow)"
              style={{ mixBlendMode: 'screen' }}
            />

            <circle
              cx={fieldWidth / 2}
              cy={FIELD_HEIGHT / 2}
              r={FIELD_HEIGHT * 0.17}
              fill="url(#mw-core)"
            />

            {/* Край диска: по нему видно, как повёрнута плоскость Галактики. */}
            <polyline
              points={ring}
              fill="none"
              stroke="#3b4a63"
              strokeWidth={1.5}
              strokeDasharray="6 10"
            />

            {/*
              Излучение чёрной дыры — пузыри Ферми: две доли гамма-излучения над центром
              и под ним, по 25 000 световых лет каждая, и вдвое большая рентгеновская
              оболочка вокруг них (JET_REACH_LY). Рисуются ЛУЧАМИ: прозрачное синее
              свечение, расходящееся от центра вдоль оси вращения. Прежде здесь были
              кольца, и доля читалась чертежом — пять окружностей одна над другой, между
              ними пусто; при повороте они складывались в решётку.

              Слоёв три, один в другом: широкая дымка, сам луч и тонкое ядро (JET_LAYERS).
              Каждый слой дышит своим сроком и рябит своим зерном, и от несовпадения ни
              сроки, ни края не сходятся — свечение живёт и не выглядит отлитым по форме.
              Складываются слои светом (mix-blend-mode: screen) — так же, как складываются
              звёзды в поле: прозрачность поверх тёмного неба выглядела бы плёнкой, а свет
              прибавляется.
            */}
            {radiation ? (
              <g style={{ mixBlendMode: 'screen' }}>
                {[1, -1].map((side) => (
                  <g key={side}>
                    {JET_LAYERS.map((layer) => (
                      <polygon
                        key={layer.code}
                        points={jetOutline(side, view, fieldWidth, FIELD_HEIGHT, layer)}
                        fill={JET_STYLE[layer.code].fill}
                        style={{
                          animation: JET_STYLE[layer.code].animation,
                          animationDelay: side > 0 ? undefined : '-1.7s',
                        }}
                      />
                    ))}
                  </g>
                ))}
                {/*
                  Ось выброса: тонкая нить от полюса до полюса — по ней видно наклон.
                  Меряется она гамма-долей, а не всей оболочкой: с ребра нить во всю
                  оболочку прочерчивала поле насквозь и читалась линейкой поверх схемы.
                */}
                <line
                  x1={poles[0].x}
                  y1={poles[0].y}
                  x2={poles[1].x}
                  y2={poles[1].y}
                  stroke="#8fd4ff"
                  strokeWidth={1.1}
                  opacity={0.3}
                />
              </g>
            ) : null}

            {marks.map(({ element, x, y, perspective }) => {
              const radius = SIZES[element.kind] * perspective;
              // Дальняя половина схемы тусклее ближней — иначе объём читается только
              // по размеру точки, а этого мало.
              const opacity =
                Math.min(1, 0.35 + 0.5 * perspective) * (MARK_FADE[element.kind] ?? 1);

              if (element.kind === 'blackhole') {
                return (
                  <g key={element.id}>
                    {/*
                      Свет у самой дыры. Кругом он меньше колец нарочно: свечением во всю
                      их ширину диск заливало оранжевым шаром, и плоскости под ним было не
                      видно вовсе — а она здесь и есть главное.
                    */}
                    <circle cx={x} cy={y} r={radius * 2.6} fill="url(#mw-accretion)" />
                    {/*
                      АККРЕЦИОННЫЙ ДИСК. Не кружок вокруг точки, а настоящие кольца в
                      плоскости вращения дыры: при наклоне они сплющиваются вместе с
                      диском, с ребра ложатся в черту — потому что считаются теми же
                      поворотами, что и всё остальное. Круг такого не умеет: он остаётся
                      кругом при любом повороте, и диск читался бы наклейкой поверх схемы.

                      Размер колец — в единицах поля, и множится он на перспективу точки
                      дыры: диск нарисован увеличенным (настоящий меньше точки, см.
                      ACCRETION_RINGS), но увеличенным ВМЕСТЕ с самой дырой.

                      Вещество течёт по ним разметкой: пунктир, у которого едет смещение.
                      Внутреннее кольцо обходит круг вдвое быстрее внешнего — так и
                      вращается вещество на орбите, и этим диск сразу читается вращающимся,
                      а не мерцающим.
                    */}
                    {ACCRETION_RINGS.map((ring, order) => (
                      <polyline
                        key={ring}
                        points={accretionRing(ring * perspective, x, y, view)}
                        fill="none"
                        stroke={order < 2 ? '#fff0cf' : '#ffb765'}
                        strokeWidth={order < 2 ? 2.2 : 1.6}
                        strokeLinecap="round"
                        strokeDasharray={order < 2 ? '10 16' : '6 22'}
                        opacity={0.75 - order * 0.12}
                        style={{
                          animation: `moo3-accretion-flow ${2.2 + order * 1.6}s linear infinite`,
                        }}
                      />
                    ))}
                    {/*
                      Кольцо света у самого горизонта: светится вещество, а не дыра. Оно
                      единственное здесь остаётся КРУГОМ, и это не недосмотр: у горизонта
                      свет заворачивается вокруг дыры, и наблюдатель видит кольцо круглым
                      при любом наклоне самого диска.
                    */}
                    <circle
                      cx={x}
                      cy={y}
                      r={radius * 1.7}
                      fill="none"
                      stroke="#ffd08a"
                      strokeWidth={2.4}
                      style={{ animation: 'moo3-accretion 3s ease-in-out infinite' }}
                    />
                    <circle cx={x} cy={y} r={radius} fill={COLORS.blackhole} />
                    {labels ? (
                      <text x={x + radius * 2.2} y={y - radius} fill="#e7c9a0" fontSize={16}>
                        {t(element.label as Key)}
                      </text>
                    ) : null}
                  </g>
                );
              }

              return (
                <g key={element.id}>
                  {element.kind === 'sun' ? (
                    // Солнце помечено крестиком: это единственная точка схемы, которую
                    // игрок ищет глазами, а кружок среди тысячи кружков не найти.
                    <g stroke={COLORS.sun} strokeWidth={1.6} opacity={opacity}>
                      <line x1={x - radius * 2} y1={y} x2={x + radius * 2} y2={y} />
                      <line x1={x} y1={y - radius * 2} x2={x} y2={y + radius * 2} />
                    </g>
                  ) : null}
                  <circle cx={x} cy={y} r={radius} fill={COLORS[element.kind]} opacity={opacity} />
                  {labels && element.label ? (
                    <text
                      x={x + radius + 6}
                      y={y + 4}
                      fill="#93a3b8"
                      fontSize={15}
                      opacity={opacity}
                    >
                      {t(element.label as Key)}
                    </text>
                  ) : null}
                </g>
              );
            })}
        </svg>
        </div>

        <MilkyWayControls
          view={view}
          spinning={spinning}
          labels={labels}
          radiation={radiation}
          onView={(next) => setView((current) => ({ ...current, ...next }))}
          onSpinning={setSpinning}
          onLabels={setLabels}
          onRadiation={setRadiation}
        />
      </div>

      <div className="mt-2 flex flex-none items-baseline justify-between gap-6 border-t border-space-700 pt-2 text-16">
        <p className="text-ink-faint">
          {COMPOSITION.map((part) => (
            <span key={part.kind} className="mr-4 whitespace-nowrap">
              <span
                className="mr-1 inline-block h-[10px] w-[10px] align-middle"
                style={{
                  backgroundColor: COLORS[part.kind],
                  // Чёрную дыру чёрным квадратиком на чёрном фоне не показать: у неё
                  // в легенде рамка, как и у самой дыры кольцо на схеме.
                  border: part.kind === 'blackhole' ? '1px solid #ffd08a' : undefined,
                }}
              />
              {t(part.name)} <span className="text-ink-soft">{part.count}</span>
            </span>
          ))}
        </p>
        <button type="button" className="link shrink-0 text-18" onClick={onClose}>
          {t('common.closeEsc')}
        </button>
      </div>
    </div>
  );
}

const clamp = (value: number, min: number, max: number): number =>
  Math.max(min, Math.min(max, value));
