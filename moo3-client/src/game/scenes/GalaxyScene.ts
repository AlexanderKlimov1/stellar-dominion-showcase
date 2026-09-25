import Phaser from 'phaser';
import type { FleetGroup, GalaxyMap, Player, StarSystem } from '../../api/types';
import { gameEvents } from '../../events/gameEvents';
import { sidePanelWidthPx } from '../../ui/layout';

/**
 * Высота поля галактики в мировых пикселях — опорное поле Huge: 27 парсеков.
 * Ширина берётся из пропорций окна карты, поэтому поле любого размера галактики
 * занимает свободную от панелей область целиком: меняется не размер окна, а плотность
 * звёзд в нём.
 */
const WORLD_HEIGHT = 1600;

/** Радиус звезды на экране — п. 11.3. Держится постоянным на любом масштабе. */
const STAR_RADIUS = 3;

/** Радиус зоны клика: сама звезда слишком мелкая, чтобы попадать по ней курсором. */
const HIT_RADIUS = 9;

/** Отступ подписи под звездой в экранных пикселях. */
const LABEL_OFFSET = 6;

/**
 * Значок флота — треугольник высотой FLEET_SIZE у звезды, где флот стоит (п. 8).
 * Треугольник взят не случайно: круг на карте — звезда, и второй круг рядом читался бы
 * спутником, а не кораблями.
 */
const FLEET_SIZE = 9;

/** Куда от звезды отходит значок флота, экранные пиксели: вправо и вверх. */
const FLEET_OFFSET = 9;

/** Радиус зоны клика по значку флота: сам он мелкий, как и звезда. */
const FLEET_HIT_RADIUS = 9;

/** Глубина значка флота: выше звезды и её колец, ниже подписей. */
const FLEET_DEPTH = 3.5;

/**
 * Линия к системе назначения — п. 8. Зелёная сплошная, если топлива хватит, красная
 * пунктирная, если нет.
 *
 * Пока курсор не подведён к звезде, линии нет вовсе: она вела бы в пустоту и говорила бы
 * о цели, которой ещё не выбрано.
 */
const COURSE_REACHABLE = 0x4ade80;
const COURSE_UNREACHABLE = 0xf87171;

/** Толщина линии курса и длина штриха с промежутком — в экранных пикселях. */
const COURSE_WIDTH = 1;
const COURSE_DASH = 6;
const COURSE_GAP = 5;

/** Глубина линии курса: поверх звёзд, под значками флотов. */
const COURSE_DEPTH = 3.2;

/** Кегль подписи звезды в экранных пикселях для самой разреженной галактики (Small). */
const LABEL_FONT_SIZE = 18;

/**
 * Насколько подпись мельче опорного кегля. Окно карты одно на все размеры галактики,
 * поэтому чем крупнее галактика, тем гуще в нём звёзды и тем меньше места под названия.
 */
const LABEL_FONT_SCALE: Record<string, number> = {
  HUGE: 0.7,
  LARGE: 0.8,
  MEDIUM: 0.9,
};

/** Зазор между соседними подписями в экранных пикселях: вплотную они читаются как одна. */
const LABEL_GAP = 3;

/**
 * Куда подпись сдвигается, если под звездой её место занято: доли собственной ширины
 * вбок и целые высоты строки вниз. Порядок — от лучшего варианта к худшему, поэтому
 * подпись отходит от звезды только когда центральное место занято.
 */
const LABEL_SHIFTS = [0, -0.35, 0.35, -0.7, 0.7];
const LABEL_ROWS = [0, 1, 2];

/** Глубина подписи и её же под курсором: подсвеченная должна лечь поверх соседних. */
const LABEL_DEPTH = 4;
const LABEL_HOVER_DEPTH = 6;

const MAX_ZOOM = 5;

/**
 * Отступ звёзд от края поля — внутри самого поля, а не вокруг него.
 *
 * Раньше на столько же расширялась область камеры, и при масштабе «вся галактика» вокруг
 * поля оставались чёрные полосы: камера показывала больше, чем занимает поле. Теперь поле
 * занимает окно карты целиком, а от края его берегутся сами звёзды — крайняя звезда стоит
 * не в нулевом парсеке экрана, и её подпись не срезается краем окна.
 */
const STAR_MARGIN = 24;

/**
 * Атрибут, которым в разметке подписаны панели поверх карты: сцена снимает с них границы
 * и сжимает область камеры до полосы между ними. Панелей сверху может быть несколько
 * (меню и HUD), поэтому берётся не высота панели, а её дальний от края экрана край.
 */
const MAP_INSET_ATTRIBUTE = 'data-map-inset';

/**
 * Полоса справа, которую карта не занимает: там во всю высоту экрана стоит SidePanel.
 * Ровно ширина панели, без зазора: зазор был чёрной полосой между картой и панелью.
 *
 * Спрашивается КАЖДЫЙ РАЗ, а не запоминается числом: ширина панели растёт вместе с
 * размером текста (п. 11.1), а смена масштаба шлёт сцене то же событие, что и смена
 * размера окна, — запомненное значение оставило бы карту под панелью.
 */
const sidePanelInset = (): number => sidePanelWidthPx();

/** Предельный интервал между кликами по одной звезде, который считается двойным. */
const DOUBLE_CLICK_MS = 320;

/**
 * Допуск смещения курсора в пикселях, до которого нажатие ещё считается кликом.
 * Панорама карты идёт тем же указателем, и без допуска протяжка, закончившаяся
 * на звезде, срабатывала бы как клик по ней.
 */
const CLICK_DRAG_TOLERANCE = 4;

/**
 * Движок помечен на уничтожение. Свойство существует в рантайме Phaser,
 * но отсутствует в его файле типов, поэтому читаем его через узкий каст.
 */
const isBeingDestroyed = (game: Phaser.Game): boolean =>
  (game as Phaser.Game & { pendingDestroy?: boolean }).pendingDestroy === true;

/** Особая звезда и занятые системы подписываются в первую очередь — их названия важнее прочих. */
const labelPriority = (view: LabeledStarView): number => {
  if (view.system.special) {
    return 0;
  }
  return view.ownerRing ? 1 : 2;
};

interface StarView {
  system: StarSystem;
  star: Phaser.GameObjects.Arc;
  /** Подпись есть только у разведанной системы: у остальных названия нет. */
  label: Phaser.GameObjects.Text | null;
  ownerRing: Phaser.GameObjects.Arc | null;
  specialRing: Phaser.GameObjects.Arc | null;
  /** Кольцо сторожа: систему стережёт чудище — п. 11.1. */
  monsterRing: Phaser.GameObjects.Arc | null;
  /**
   * Метка сканера: в неразведанной системе кто-то есть — п. 15.
   *
   * Сканеры показывают присутствие, но не состав: звезда остаётся безымянной, а метка
   * говорит только «там кто-то есть». Разведать систему может лишь флот.
   */
  scannerRing: Phaser.GameObjects.Arc | null;
  /** Подпись убрана раскладкой: на этом масштабе она перекрыла бы соседнюю. */
  hiddenByLayout: boolean;
}

/** Звезда с подписью: раскладка названий работает только с такими. */
interface LabeledStarView extends StarView {
  label: Phaser.GameObjects.Text;
}

const isLabeled = (view: StarView): view is LabeledStarView => view.label !== null;

/** Значок флота на карте и сам флот, состав которого откроется по нажатию. */
interface FleetView {
  fleet: FleetGroup;
  icon: Phaser.GameObjects.Triangle;
}

/**
 * Карта галактики — п. 11.3.
 *
 * Прямоугольное поле без границ ячеек: звёзды стоят в случайных клетках,
 * координаты приходят с сервера в парсеках и переводятся в пиксели так, чтобы поле любого
 * размера галактики занимало всё окно карты — свободную от панелей область экрана.
 * Звезда — закрашенный круг радиусом 3 пикселя одного из трёх цветов, рядом подпись
 * с названием реальной звезды.
 *
 * Сцена ничего не знает про React: команды приходят через {@link gameEvents},
 * ответы уходят туда же.
 */
export class GalaxyScene extends Phaser.Scene {
  static readonly KEY = 'galaxy';

  private stars: StarView[] = [];
  private starsById = new Map<string, StarView>();
  /** Порядок раскладки подписей: кто раньше в списке, тот и занимает место. */
  private labelOrder: LabeledStarView[] = [];
  private field: Phaser.GameObjects.Rectangle | null = null;
  private selectionRing: Phaser.GameObjects.Arc | null = null;
  /** Значки флотов игрока: по одному на систему — флот в системе у игрока один. */
  private fleetViews: FleetView[] = [];
  /** Флоты, присланные React'ом: держим их, чтобы перерисовать после смены карты. */
  private fleets: FleetGroup[] = [];
  /** Цвет расы игрока — им и рисуются его корабли. */
  private fleetColor = 0x7fd4ff;
  /** Ход партии: по нему считается, какую часть пути прошёл летящий флот — п. 8. */
  private turn = 1;
  /** Линия к системе назначения и линии уже отданных приказов — п. 8. */
  private courseLine: Phaser.GameObjects.Graphics | null = null;
  /** Флот, которому выбирают цель; null — прицел снят. */
  private targetingFleetId: string | null = null;
  /** Куда флоты империи долетают: по этому списку линия и красится. */
  private reachableSystemIds = new Set<string>();
  /** Звезда под курсором: к ней тянется линия, пока включён прицел. */
  private hoveredSystemId: string | null = null;
  /** Карта текущей партии: по ней поле пересчитывается при каждой смене окна. */
  private map: GalaxyMap | null = null;
  /** Выделенная система: кольцо выделения переставляется вместе со звёздами. */
  private selectedSystemId: string | null = null;
  /** Последний клик по звезде — для распознавания двойного. */
  private lastClick: { systemId: string; at: number } | null = null;
  /** Пройденный указателем путь с момента нажатия. */
  private dragDistance = 0;
  private worldWidth = 0;
  private worldHeight = 0;
  /** Пикселей на один парсек по каждой оси: поле растягивается на всё окно карты. */
  private pxPerParsecX = 1;
  private pxPerParsecY = 1;
  /** Кегль подписи для текущего размера галактики. */
  private labelFontSize = LABEL_FONT_SIZE;
  private labelsVisible = true;
  /** Нижняя граница масштаба — «вся галактика»; считается в fitToScreen под размер окна. */
  private minZoom = 1;
  private unsubscribe: Array<() => void> = [];

  constructor() {
    super(GalaxyScene.KEY);
  }

  create(): void {
    // StrictMode в dev создаёт движок, тут же уничтожает его и создаёт заново.
    // Phaser при этом продолжает загрузку уже уничтоженного движка, и его сцена
    // создаётся после того, как React успел всё почистить. Подписывать такую сцену
    // на общую шину нельзя: отложенный destroy незагрузившегося движка не выполнится,
    // и её обработчики останутся висеть навсегда.
    if (isBeingDestroyed(this.game)) {
      return;
    }

    this.cameras.main.setBackgroundColor('#05070f');
    this.installCameraControls();
    this.installUiListeners();
    gameEvents.emit('scene:ready', undefined);
  }

  /** Полная перерисовка карты по данным сервера. */
  private renderMap(map: GalaxyMap, players: Player[]): void {
    this.clearMap();

    this.map = map;
    this.labelFontSize = LABEL_FONT_SIZE * (LABEL_FONT_SCALE[map.galaxySize] ?? 1);

    // Прямоугольное поле галактики: заливка без сетки и без границ ячеек.
    // Размеры ставит rebuildWorld — они зависят от свободного под карту места.
    // Рамки у поля нет: его край совпадает с краем окна карты, и рамка читалась бы
    // не границей галактики, а лишней линией вдоль панелей.
    this.field = this.add.rectangle(0, 0, 1, 1, 0x080c18).setOrigin(0, 0);
    this.rebuildWorld();

    const colorByPlayer = new Map(players.map((player) => [player.id, player.color]));

    for (const system of map.systems) {
      this.stars.push(this.createStar(system, colorByPlayer.get(system.ownerPlayerId ?? '')));
    }
    this.starsById = new Map(this.stars.map((view) => [view.system.id, view]));

    // Приоритет подписей: особая звезда, затем занятые системы, затем остальные сверху вниз.
    // Порядок постоянный, поэтому при зуме и панораме пропадают и возвращаются
    // одни и те же подписи, а не случайные.
    this.labelOrder = this.stars.filter(isLabeled).sort(
      (a, b) =>
        labelPriority(a) - labelPriority(b) || a.star.y - b.star.y || a.star.x - b.star.x,
    );

    this.selectionRing = this.add
      .circle(0, 0, STAR_RADIUS + 6)
      .setStrokeStyle(1.5, 0x7fd4ff)
      .setVisible(false)
      .setDepth(5);

    // Один слой на все линии: и на прицел, и на пути уже отправленных флотов. Линий
    // единицы, и перерисовать их дешевле, чем держать объект на каждую.
    this.courseLine = this.add.graphics().setDepth(COURSE_DEPTH);

    this.renderFleets();
    this.fitToScreen();

    gameEvents.emit('map:rendered', {
      systems: map.systems.length,
      planets: map.systems.reduce((total, system) => total + system.planets.length, 0),
    });
  }

  private createStar(system: StarSystem, ownerColor?: string): StarView {
    const x = STAR_MARGIN + system.x * this.pxPerParsecX;
    const y = STAR_MARGIN + system.y * this.pxPerParsecY;
    const color = Phaser.Display.Color.HexStringToColor(system.colorHex).color;

    // Кольцо владельца рисуем под звездой, чтобы не перекрывать её.
    const ownerRing = ownerColor
      ? this.add
          .circle(x, y, STAR_RADIUS + 3)
          .setStrokeStyle(1.5, Phaser.Display.Color.HexStringToColor(ownerColor).color)
          .setDepth(1)
      : null;

    const star = this.add.circle(x, y, STAR_RADIUS, color).setDepth(3);

    // Orion выделяем ободком: это специальная звезда, а не владение игрока (п. 4.2.1).
    const specialRing = system.special
      ? this.add.circle(x, y, STAR_RADIUS + 5).setStrokeStyle(1, 0xffd447, 0.8).setDepth(2)
      : null;

    /*
      Систему стережёт чудище — п. 11.1. Это ГЛАВНОЕ, что нужно знать о звезде, глядя на
      карту: пока сторож жив, там нельзя ни поселиться, ни поставить заставу, а флот,
      вошедший туда, примет бой на подлёте. Кольцо красное и толстое — заметнее прочих.

      Сервер присылает сторожа только для РАЗВЕДАННОЙ системы (и всевидящей расе — везде),
      поэтому путать его с тусклым кольцом сканера нельзя: то бывает лишь у неразведанных.
      До прихода разведчика звезда выглядит обычной — так и задумано.
    */
    const monsterRing = system.monster
      ? this.add.circle(x, y, STAR_RADIUS + 5).setStrokeStyle(2, 0xf87171, 0.9).setDepth(2)
      : null;

    // Сканеры видят чужое присутствие в неразведанной системе — п. 15: пунктирного круга
    // в Phaser нет, поэтому метка это тусклое красноватое кольцо. Что именно там —
    // колония или флот, чей — неизвестно: за этим нужно послать корабли.
    const scannerRing =
      !system.explored && system.occupied
        ? this.add.circle(x, y, STAR_RADIUS + 4).setStrokeStyle(1, 0xf87171, 0.7).setDepth(2)
        : null;

    // Неразведанная система — безымянная точка: названия сервер не присылает, подписывать
    // нечем. Подпись появится вместе с разведкой, при следующей загрузке карты.
    const label = system.explored
      ? this.add
          .text(x, y + STAR_RADIUS + LABEL_OFFSET, system.name ?? '', {
            fontFamily: 'Consolas, monospace',
            fontSize: `${this.labelFontSize}px`,
            color: system.special ? '#ffd447' : '#93a3c4',
          })
          .setOrigin(0.5, 0)
          .setDepth(LABEL_DEPTH)
          .setVisible(this.labelsVisible)
      : null;

    const view: StarView = {
      system,
      star,
      label,
      ownerRing,
      specialRing,
      monsterRing,
      scannerRing,
      hiddenByLayout: false,
    };

    star
      .setInteractive(new Phaser.Geom.Circle(STAR_RADIUS, STAR_RADIUS, HIT_RADIUS), Phaser.Geom.Circle.Contains)
      .on('pointerover', () => {
        this.hoveredSystemId = system.id;
        this.drawCourses();
        star.setFillStyle(0xffffff);
        label?.setColor('#ffffff');
        // Подпись, убранную раскладкой, под курсором показываем: иначе на плотной
        // карте до названия было бы не добраться, не приблизив её.
        label?.setDepth(LABEL_HOVER_DEPTH).setVisible(this.labelsVisible);
        gameEvents.emit('system:hovered', { system });
      })
      .on('pointerout', () => {
        if (this.hoveredSystemId === system.id) {
          this.hoveredSystemId = null;
          this.drawCourses();
        }
        star.setFillStyle(color);
        label?.setColor(system.special ? '#ffd447' : '#93a3c4');
        label?.setDepth(LABEL_DEPTH).setVisible(this.labelsVisible && !view.hiddenByLayout);
        gameEvents.emit('system:hovered', { system: null });
      })
      .on('pointerup', () => {
        // Протяжку карты, закончившуюся на звезде, кликом не считаем.
        if (this.dragDistance > CLICK_DRAG_TOLERANCE) {
          return;
        }
        this.handleStarClick(system);
      });

    return view;
  }

  /**
   * Значки флотов: треугольник у каждой звезды, где стоит флот игрока — п. 8.
   *
   * Перерисовываются целиком: флоты приходят с сервера списком, и после конца хода в нём
   * меняется всё сразу — где-то построили корабль, где-то флот улетел или погиб. Десяток
   * значков дешевле пересоздать, чем сводить разницу.
   *
   * Флот в неразведанной системе значка не получает: её на карте ещё нет как системы,
   * и ставить значок было бы некуда.
   */
  private renderFleets(): void {
    this.fleetViews.forEach((view) => view.icon.destroy());
    this.fleetViews = [];

    for (const fleet of this.fleets) {
      const star = this.starsById.get(fleet.starSystemId);
      if (!star) {
        continue;
      }
      const icon = this.add
        .triangle(0, 0, FLEET_SIZE / 2, 0, FLEET_SIZE, FLEET_SIZE, 0, FLEET_SIZE, this.fleetColor)
        .setDepth(FLEET_DEPTH)
        .setInteractive(
          new Phaser.Geom.Circle(FLEET_SIZE / 2, FLEET_SIZE / 2, FLEET_HIT_RADIUS),
          Phaser.Geom.Circle.Contains,
        );

      icon
        .on('pointerover', () => icon.setFillStyle(0xffffff))
        .on('pointerout', () => icon.setFillStyle(this.fleetColor))
        .on('pointerup', () => {
          // Протяжку карты, закончившуюся на значке, кликом не считаем — как и у звезды.
          if (this.dragDistance > CLICK_DRAG_TOLERANCE) {
            return;
          }
          gameEvents.emit('fleet:selected', { fleetId: fleet.id });
        });

      this.fleetViews.push({ fleet, icon });
    }

    this.positionFleets();
  }

  /**
   * Ставит значки флотов рядом со звёздами.
   *
   * Отступ от звезды экранный, а не мировой: звёзды держат постоянный размер на любом
   * масштабе, и значок, отодвинутый на мировые пиксели, при приближении уехал бы от своей
   * звезды на пол-экрана.
   */
  private positionFleets(): void {
    const inverse = 1 / this.cameras.main.zoom;
    for (const view of this.fleetViews) {
      const star = this.starsById.get(view.fleet.starSystemId);
      if (!star) {
        continue;
      }
      const flight = this.flightPoint(view.fleet);
      view.icon
        .setScale(inverse)
        .setPosition(
          (flight?.x ?? star.star.x) + FLEET_OFFSET * inverse,
          (flight?.y ?? star.star.y) - FLEET_OFFSET * inverse,
        );
    }
    this.drawCourses();
  }

  /**
   * Где сейчас летящий флот — п. 8: точка на прямой между системой вылета и целью.
   *
   * Сервер шлёт маршрут и сроки, а не координаты: положение считается по номерам ходов,
   * и на карте флот идёт равномерно. У стоящего флота полёта нет — возвращается null,
   * и значок остаётся у своей звезды.
   */
  private flightPoint(fleet: FleetGroup): { x: number; y: number } | null {
    if (!fleet.targetSystemId || fleet.departureTurn === undefined || fleet.arrivalTurn === undefined) {
      return null;
    }
    const from = this.starsById.get(fleet.starSystemId);
    const to = this.starsById.get(fleet.targetSystemId);
    if (!from || !to) {
      return null;
    }
    const total = Math.max(1, fleet.arrivalTurn - fleet.departureTurn);
    const passed = Phaser.Math.Clamp((this.turn - fleet.departureTurn) / total, 0, 1);
    return {
      x: from.star.x + (to.star.x - from.star.x) * passed,
      y: from.star.y + (to.star.y - from.star.y) * passed,
    };
  }

  /**
   * Включает или снимает прицел — п. 8. Пока он включён, нажатие по звезде задаёт цель
   * полёта, а не выделяет систему.
   */
  private setTargeting(fleetId: string | null, reachable: Set<string>): void {
    this.targetingFleetId = fleetId;
    this.reachableSystemIds = reachable;
    this.drawCourses();
  }

  /**
   * Рисует линии курсов — п. 8: путь выбираемый и пути уже отправленных флотов.
   *
   * Линия выбираемого курса тянется от значка флота к звезде под курсором: зелёная
   * сплошная, если топлива хватит, и красная пунктирная, если нет. Пока курсор не над
   * звездой, она серая и идёт за ним — иначе непонятно, чего от игрока ждут.
   *
   * Толщина и штрих задаются в экранных пикселях, а линия живёт в мире: делим на зум,
   * иначе на приближении тонкая линия становилась бы жирной полосой.
   */
  private drawCourses(): void {
    const line = this.courseLine;
    if (!line) {
      return;
    }
    line.clear();

    const inverse = 1 / this.cameras.main.zoom;
    const width = COURSE_WIDTH * inverse;

    // Уже отданные приказы: путь от системы вылета до цели, пунктиром цвета расы.
    for (const fleet of this.fleets) {
      const to = fleet.targetSystemId ? this.starsById.get(fleet.targetSystemId) : undefined;
      const point = this.flightPoint(fleet);
      if (!to || !point) {
        continue;
      }
      line.lineStyle(width, this.fleetColor, 0.5);
      this.dashedLine(line, point.x, point.y, to.star.x, to.star.y, inverse);
    }

    if (!this.targetingFleetId) {
      return;
    }

    const fleet = this.fleets.find((group) => group.id === this.targetingFleetId);
    const from = fleet ? (this.flightPoint(fleet) ?? this.starsById.get(fleet.starSystemId)?.star) : null;
    if (!from) {
      return;
    }

    // Цель — звезда под курсором. Нет звезды — нет и линии: тянуть её за курсором значит
    // обещать полёт в пустое место карты.
    const hovered = this.hoveredSystemId ? this.starsById.get(this.hoveredSystemId) : undefined;
    if (!hovered) {
      return;
    }
    const target = { x: hovered.star.x, y: hovered.star.y };

    if (this.reachableSystemIds.has(hovered.system.id)) {
      // Долетит: сплошная зелёная — приказ можно отдавать.
      line.lineStyle(width, COURSE_REACHABLE, 0.9);
      line.lineBetween(from.x, from.y, target.x, target.y);
      return;
    }
    // Не долетит: пунктирная красная — топлива не хватит даже от ближайшей колонии.
    line.lineStyle(width, COURSE_UNREACHABLE, 0.9);
    this.dashedLine(line, from.x, from.y, target.x, target.y, inverse);
  }

  /**
   * Пунктирная линия: в Phaser её нет, поэтому рисуем штрихами. Длина штриха экранная —
   * на любом масштабе пунктир выглядит одинаково.
   */
  private dashedLine(
    line: Phaser.GameObjects.Graphics,
    fromX: number,
    fromY: number,
    toX: number,
    toY: number,
    inverse: number,
  ): void {
    const dash = COURSE_DASH * inverse;
    const gap = COURSE_GAP * inverse;
    const length = Math.hypot(toX - fromX, toY - fromY);
    if (length < 1) {
      return;
    }
    const stepX = (toX - fromX) / length;
    const stepY = (toY - fromY) / length;
    for (let at = 0; at < length; at += dash + gap) {
      const end = Math.min(at + dash, length);
      line.lineBetween(
        fromX + stepX * at,
        fromY + stepY * at,
        fromX + stepX * end,
        fromY + stepY * end,
      );
    }
  }

  /**
   * Одиночный клик выделяет систему, двойной — открывает её экран.
   *
   * Неразведанную систему открыть нельзя: сервер не прислал ни названия, ни планет,
   * и показывать в её экране нечего.
   *
   * Время берём из performance.now(), а не из часов сцены: они идут от игрового
   * цикла, а он останавливается, когда вкладка не отрисовывается.
   */
  private handleStarClick(system: StarSystem): void {
    // Прицел перехватывает нажатие целиком: игрок выбирает не систему для просмотра, а
    // цель полёта — п. 8. Недостижимую выбрать тоже можно: откажет сервер, и игрок
    // услышит причину, а не молчание карты.
    if (this.targetingFleetId) {
      const fleetId = this.targetingFleetId;
      this.setTargeting(null, this.reachableSystemIds);
      gameEvents.emit('fleet:target-picked', { fleetId, systemId: system.id });
      return;
    }

    const at = performance.now();
    // Неразведанную систему тоже открываем — п. 15: её экран пуст, но именно оттуда
    // в неё отправляется шпион.
    const isDoubleClick =
      this.lastClick?.systemId === system.id && at - this.lastClick.at <= DOUBLE_CLICK_MS;

    this.select(system.id);
    gameEvents.emit('system:selected', { system });

    if (isDoubleClick) {
      // Сбрасываем историю, иначе третий клик подряд открыл бы экран повторно.
      this.lastClick = null;
      gameEvents.emit('system:opened', { system });
      return;
    }

    this.lastClick = { systemId: system.id, at };
  }

  private select(systemId: string | null): void {
    this.selectedSystemId = systemId;
    const view = systemId ? this.starsById.get(systemId) : undefined;
    if (!this.selectionRing) {
      return;
    }
    if (!view) {
      this.selectionRing.setVisible(false);
      return;
    }
    this.selectionRing.setPosition(view.star.x, view.star.y).setVisible(true);
  }

  /** Показывает галактику целиком — в свободной от панелей части экрана. */
  private fitToScreen(): void {
    if (!this.map) {
      return;
    }
    this.rebuildWorld();
    this.applyZoom(Math.min(this.minZoom, MAX_ZOOM));
    this.cameras.main.centerOn(this.worldWidth / 2, this.worldHeight / 2);
  }

  /**
   * Растягивает поле галактики на всё окно карты и раскладывает в нём звёзды.
   *
   * Поле повторяет пропорции окна, поэтому при масштабе «вся галактика» оно занимает
   * его целиком — до полосы, отведённой карточке системы. Координаты звёзд переводятся
   * из парсеков своим множителем по каждой оси, так что галактика любого размера заполняет
   * одно и то же окно, отличаясь только плотностью звёзд.
   */
  private rebuildWorld(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    this.applyHudInsets();
    const camera = this.cameras.main;

    this.worldHeight = WORLD_HEIGHT;
    this.worldWidth = WORLD_HEIGHT * (camera.width / camera.height);
    // Парсеки раскладываются не на всё поле, а на его внутреннюю часть: по краям
    // остаётся STAR_MARGIN, чтобы крайняя звезда с подписью не упиралась в край окна.
    this.pxPerParsecX = (this.worldWidth - STAR_MARGIN * 2) / map.widthParsecs;
    this.pxPerParsecY = (this.worldHeight - STAR_MARGIN * 2) / map.heightParsecs;

    this.field?.setSize(this.worldWidth, this.worldHeight);
    for (const view of this.stars) {
      const x = STAR_MARGIN + view.system.x * this.pxPerParsecX;
      const y = STAR_MARGIN + view.system.y * this.pxPerParsecY;
      view.star.setPosition(x, y);
      view.ownerRing?.setPosition(x, y);
      view.specialRing?.setPosition(x, y);
      view.monsterRing?.setPosition(x, y);
      view.scannerRing?.setPosition(x, y);
    }
    this.positionFleets();
    this.select(this.selectedSystemId);

    // Границы камеры — ровно поле: за его край карту не увести, и чёрному фону движка
    // взяться неоткуда ни на каком масштабе.
    camera.setBounds(0, 0, this.worldWidth, this.worldHeight);
    // Дальше «всей галактики» не отдаляем: карта только уменьшалась бы в окне, а подписи
    // держат постоянный экранный размер, и части звёзд переставало хватать места.
    // Берётся больший из двух масштабов: при нём поле накрывает окно по обеим сторонам
    // (пропорции у них общие, так что стороны сходятся, а `max` страхует от округления).
    this.minZoom = Math.max(
      camera.width / this.worldWidth,
      camera.height / this.worldHeight,
    );
  }

  /**
   * Сжимает область камеры до свободного от панелей места. Панели непрозрачны и лежат
   * поверх канваса, поэтому иначе полосы карты под ними — вместе с подписями звёзд —
   * оказывались не видны. Справа полоса под панель состояния вычитается всегда: окно
   * галактики одно и то же независимо от того, что происходит в панели.
   */
  private applyHudInsets(): void {
    let top = 0;
    let bottom = 0;
    for (const element of document.querySelectorAll(`[${MAP_INSET_ATTRIBUTE}]`)) {
      const rect = element.getBoundingClientRect();
      if (element.getAttribute(MAP_INSET_ATTRIBUTE) === 'top') {
        top = Math.max(top, rect.bottom);
      } else {
        bottom = Math.max(bottom, this.scale.height - rect.top);
      }
    }

    this.cameras.main.setViewport(
      0,
      top,
      Math.max(1, this.scale.width - sidePanelInset()),
      Math.max(1, this.scale.height - top - bottom),
    );
  }

  private zoomBy(delta: number): void {
    const camera = this.cameras.main;
    this.applyZoom(Phaser.Math.Clamp(camera.zoom * (1 + delta), this.minZoom, MAX_ZOOM));
  }

  /**
   * Ставит масштаб камеры и компенсирует его для звёзд и подписей.
   *
   * По п. 11.3 звезда — круг радиусом 3 пикселя, поэтому её экранный размер не должен
   * зависеть от зума: объекты масштабируются обратно, а подпись отодвигается на
   * постоянное экранное расстояние.
   */
  private applyZoom(zoom: number): void {
    const camera = this.cameras.main;
    camera.setZoom(zoom);

    const inverse = 1 / zoom;
    for (const view of this.stars) {
      view.star.setScale(inverse);
      view.ownerRing?.setScale(inverse);
      view.specialRing?.setScale(inverse);
      view.monsterRing?.setScale(inverse);
      view.scannerRing?.setScale(inverse);
      view.label?.setScale(inverse);
    }
    this.selectionRing?.setScale(inverse);
    this.positionFleets();
    this.layoutLabels();

    gameEvents.emit('camera:changed', { zoom });
  }

  /**
   * Раскладывает подписи: каждая идёт строго под своей звездой и целиком лежит
   * внутри прямоугольника галактики. Подписи не пересекаются — та, которой не хватило
   * свободного места, убирается до следующего приближения карты.
   *
   * Название всегда под звездой: над звезду подпись не переносится никогда. Если место
   * прямо под звездой занято, подпись сдвигается вбок или на строку ниже — так подписи
   * находятся почти всем звёздам даже на самом мелком масштабе.
   *
   * Подписи держат постоянный экранный размер, поэтому в мировых координатах они тем
   * крупнее, чем дальше отодвинута камера: пересчитывать раскладку нужно на каждом зуме.
   */
  private layoutLabels(): void {
    const placed: Phaser.Geom.Rectangle[] = [];
    const starPoints = this.stars.map((view) => ({ x: view.star.x, y: view.star.y }));

    for (const view of this.labelOrder) {
      const bounds = this.placeLabel(view, placed, starPoints);
      const hidden = bounds === null;

      view.hiddenByLayout = hidden;
      view.label.setVisible(this.labelsVisible && !hidden);
      if (bounds) {
        view.label.setPosition(bounds.centerX, bounds.y);
        placed.push(bounds);
      }
    }
  }

  /**
   * Ищет подписи свободное место под её звездой: сначала прямо под ней, потом со сдвигом
   * вбок, потом строкой ниже. Подпись не должна перекрывать соседние подписи и чужие
   * звёзды и не должна выходить за прямоугольник галактики.
   *
   * @returns прямоугольник в мировых координатах или null, если места не нашлось
   */
  private placeLabel(
    view: LabeledStarView,
    placed: Phaser.Geom.Rectangle[],
    starPoints: Array<{ x: number; y: number }>,
  ): Phaser.Geom.Rectangle | null {
    const inverse = 1 / this.cameras.main.zoom;
    const width = view.label.displayWidth + LABEL_GAP * 2 * inverse;
    const height = view.label.displayHeight + LABEL_GAP * inverse;
    const halfWidth = width / 2;
    const firstTop = view.star.y + (STAR_RADIUS + LABEL_OFFSET) * inverse;

    for (const row of LABEL_ROWS) {
      const top = firstTop + row * height;
      // Ниже поля подпись не опускаем, и следующие строки тем более не поместятся.
      if (top + height > this.worldHeight) {
        break;
      }

      for (const shift of LABEL_SHIFTS) {
        // У крайних звёзд подпись сдвигается вдоль края поля, но за него не выходит.
        const centerX = Phaser.Math.Clamp(
          view.star.x + shift * width,
          Math.min(halfWidth, this.worldWidth / 2),
          Math.max(halfWidth, this.worldWidth - halfWidth),
        );
        const bounds = new Phaser.Geom.Rectangle(centerX - halfWidth, top, width, height);

        const busy =
          placed.some((other) => Phaser.Geom.Rectangle.Overlaps(other, bounds)) ||
          starPoints.some((point) => bounds.contains(point.x, point.y));
        if (!busy) {
          return bounds;
        }
      }
    }

    return null;
  }

  private installCameraControls(): void {
    const camera = this.cameras.main;

    this.input.on('pointerdown', () => {
      this.dragDistance = 0;
    });

    this.input.on('pointermove', (pointer: Phaser.Input.Pointer) => {
      if (!pointer.isDown) {
        return;
      }
      const dx = pointer.x - pointer.prevPosition.x;
      const dy = pointer.y - pointer.prevPosition.y;
      this.dragDistance += Math.hypot(dx, dy);
      camera.scrollX -= dx / camera.zoom;
      camera.scrollY -= dy / camera.zoom;
    });

    this.input.on(
      'wheel',
      (_pointer: Phaser.Input.Pointer, _over: unknown, _dx: number, dy: number) => {
        this.zoomBy(dy > 0 ? -0.12 : 0.12);
      },
    );

    this.scale.on('resize', () => this.fitToScreen());
  }

  /**
   * Снимает подписки сцены с общей шины. Вызывается из React при размонтировании:
   * иначе обработчики уничтоженной сцены остаются висеть и продолжают выполняться
   * на каждой команде интерфейса.
   */
  detachUiListeners(): void {
    this.unsubscribe.forEach((off) => off());
    this.unsubscribe = [];
  }

  /** Подписка на команды React → Phaser. */
  private installUiListeners(): void {
    this.unsubscribe = [
      gameEvents.on('map:load', ({ map, players }) => this.renderMap(map, players)),
      gameEvents.on('map:fit', () => this.fitToScreen()),
      gameEvents.on('map:fleets', ({ fleets, color, turn }) => {
        this.fleets = fleets;
        this.turn = turn;
        this.fleetColor = Phaser.Display.Color.HexStringToColor(color).color;
        this.renderFleets();
      }),
      gameEvents.on('fleet:targeting', ({ fleetId, reachableSystemIds }) =>
        this.setTargeting(fleetId, new Set(reachableSystemIds))),
      gameEvents.on('map:focus-system', ({ systemId }) => {
        const view = this.starsById.get(systemId);
        if (!view) {
          return;
        }
        this.cameras.main.pan(view.star.x, view.star.y, 300, 'Sine.easeInOut');
        this.select(systemId);
      }),
      gameEvents.on('map:toggle-labels', ({ visible }) => {
        this.labelsVisible = visible;
        this.stars.forEach((view) => view.label?.setVisible(visible && !view.hiddenByLayout));
      }),
      gameEvents.on('map:select-system', ({ systemId }) => this.select(systemId)),
      gameEvents.on('map:clear-selection', () => this.select(null)),
    ];

    // Подстраховка на штатное завершение сцены. На события Phaser полагаться нельзя:
    // если game.destroy() приходит во время загрузки движка, отложенный destroy уже
    // не выполняется и ни SHUTDOWN, ни DESTROY не приходят вовсе.
    this.events.once(Phaser.Scenes.Events.SHUTDOWN, () => this.detachUiListeners());
    this.events.once(Phaser.Scenes.Events.DESTROY, () => this.detachUiListeners());
  }

  private clearMap(): void {
    this.fleetViews.forEach((view) => view.icon.destroy());
    this.fleetViews = [];
    this.stars.forEach((view) => {
      view.star.destroy();
      view.label?.destroy();
      view.ownerRing?.destroy();
      view.specialRing?.destroy();
      view.monsterRing?.destroy();
      view.scannerRing?.destroy();
    });
    this.stars = [];
    this.labelOrder = [];
    this.starsById.clear();
    this.selectedSystemId = null;
    this.field?.destroy();
    this.field = null;
    this.selectionRing?.destroy();
    this.selectionRing = null;
    this.courseLine?.destroy();
    this.courseLine = null;
    this.hoveredSystemId = null;
  }
}
