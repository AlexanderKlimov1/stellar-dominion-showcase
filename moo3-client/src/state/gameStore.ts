import { create } from 'zustand';
import { writeView } from './session';
import type {
  Battle,
  GalaxyMap,
  GalaxySizeOption,
  GameSummary,
  Planet,
  PlanetClimateRef,
  Player,
  PlayerCredentials,
  PlayerResearch,
  Espionage,
  Fleet,
  Race,
  RaceDesign,
  Encounter,
  ResearchTree,
  StarSystem,
  TurnReport,
} from '../api/types';

/**
 * Экраны, которые открываются поверх карты — п. 11.1.
 *
 * diplomacy остался от прежнего верхнего меню: сам экран жив, но пункта, который бы
 * его открывал, в нижнем меню сейчас нет. research открывается из правой панели.
 */
export type OverlayPanel =
  | 'none'
  | 'colonies'
  | 'planets'
  | 'fleet'
  | 'leaders'
  | 'races'
  | 'info'
  | 'research'
  | 'diplomacy';

interface GameState {
  /** Справочники, загруженные при подключении к серверу. */
  galaxySizes: GalaxySizeOption[];
  planetClimates: PlanetClimateRef[];
  races: Race[];
  /** Конструктор расы — п. 7; пусто, пока справочники не загружены. */
  raceDesign: RaceDesign | null;
  /** Разведка империи — п. 13; приходит вместе с картой. */
  espionage: Espionage | null;
  /** Флот империи — п. 8; приходит вместе с картой. */
  fleet: Fleet | null;
  researchTree: ResearchTree | null;

  /** Лобби и текущая игра. */
  openGames: GameSummary[];
  game: GameSummary | null;
  players: Player[];
  credentials: PlayerCredentials | null;

  /** Карта галактики. */
  map: GalaxyMap | null;
  selectedSystem: StarSystem | null;
  hoveredSystem: StarSystem | null;
  /** Система, открытая на отдельном экране двойным кликом по звезде. */
  openedSystem: StarSystem | null;
  /**
   * Колония, открытая на экране управления двойным кликом по планете — п. 4.1.
   *
   * Хранится идентификатором, а не объектом планеты: планета в карте заменяется при
   * каждом действии игрока, и ссылка на прежний объект устарела бы. Экран каждый раз
   * достаёт свежую планету из открытой системы.
   */
  openedColonyId: string | null;

  /**
   * Исследования игрока — экран «Наука». Состояние серверное: цель, вложенные в неё
   * очки и изученное считает сервер, клиент только показывает и меняет цель.
   */
  research: PlayerResearch | null;

  /**
   * Кого ждёт партия — п. 11.1: игроки, не объявившие конец текущего хода.
   *
   * Игроки ходят одновременно, и галактика считается, когда закончили все. Пустой
   * список значит, что ход уже посчитан и идёт следующий.
   */
  waitingFor: string[];

  /**
   * Что изменилось за последний посчитанный ход — п. 11.1.
   *
   * Приходит либо в ответе на свой конец хода, либо подпиской: тот, кто закончил ход
   * первым, узнаёт о пересчёте не из своего запроса.
   */
  turnReport: TurnReport | null;

  /**
   * Встречи флотов, ждущие решения — п. 8.
   *
   * Приходят вместе с итогами хода: флоты встретились в конце хода, а выбор — атаковать
   * или разойтись — игрок делает в начале следующего.
   */
  encounters: Encounter[];

  /**
   * Бой, который показывает сцена — п. 8.
   *
   * Держится отдельно от встречи: решённая встреча уходит из списка сразу, а сцену
   * игрок ещё смотрит. Рядом с исходом лежит состав флотов до боя: во встрече после боя
   * их уже нет, а показать надо, кто с чем сходился. Признак auto подписан в заголовке.
   */
  battle: { encounter: Encounter; before: { yours: number; theirs: number } } | null;

  /**
   * Идущий тактический бой — п. 8. Держится отдельно от {@link battle}: тот показывает
   * итог посчитанного «авто» боя, а этот — живое поле, на котором ходят корабли.
   */
  activeBattle: Battle | null;

  /**
   * Флот, которому выбирают систему назначения, — п. 8: порядок MOO II, сперва корабли,
   * потом цель. Пока он выбран, карта тянет за курсором линию до звезды под ним: зелёную
   * до достижимой, красную до той, куда не хватит топлива.
   */
  /**
   * С кем идёт аудиенция — п. 15: экран переговоров это разговор С ОДНИМ послом, и
   * открывают его с пульта «Расы», выбрав империю. Пусто — собеседник не выбран, и экран
   * спрашивает, с кем говорить.
   */
  audiencePlayerId: string | null;
  targetingFleetId: string | null;

  /**
   * Отданный на карте приказ, по которому ещё не отобраны корабли, — п. 8. Игрок выбрал
   * звезду, и теперь диалог спрашивает, кто именно летит.
   */
  fleetOrder: { fleetId: string; targetSystemId: string } | null;

  /** Состояние интерфейса. */
  overlay: OverlayPanel;
  showLabels: boolean;
  zoom: number;
  lastError: string | null;

  setReference: (data: {
    galaxySizes: GalaxySizeOption[];
    planetClimates: PlanetClimateRef[];
    races: Race[];
    raceDesign: RaceDesign;
    researchTree: ResearchTree;
  }) => void;
  /**
   * Справочник особенностей расы после правки цен — п. 7. Отдельно от setReference:
   * редактор стоимости меняет только его, а остальные справочники трогать незачем.
   */
  setRaceDesign: (raceDesign: RaceDesign) => void;
  setOpenGames: (games: GameSummary[]) => void;
  setLobby: (game: GameSummary, players: Player[], credentials?: PlayerCredentials) => void;
  setMap: (map: GalaxyMap) => void;
  updatePlanet: (planet: Planet) => void;
  updateSystem: (system: StarSystem) => void;
  setSelectedSystem: (system: StarSystem | null) => void;
  setHoveredSystem: (system: StarSystem | null) => void;
  setOpenedSystem: (system: StarSystem | null) => void;
  setOpenedColony: (planetId: string | null) => void;
  /** Выбрать флот, которому ищут систему назначения, или снять прицел — п. 8. */
  setAudience: (audiencePlayerId: string | null) => void;
  setTargetingFleet: (targetingFleetId: string | null) => void;
  setFleetOrder: (fleetOrder: { fleetId: string; targetSystemId: string } | null) => void;
  setResearch: (research: PlayerResearch | null) => void;
  setEmpire: (espionage: Espionage | null, fleet: Fleet | null) => void;
  setWaitingFor: (waitingFor: string[]) => void;
  setEncounters: (encounters: Encounter[]) => void;
  setBattle: (
    battle: { encounter: Encounter; before: { yours: number; theirs: number } } | null,
  ) => void;
  /** Открыть или закрыть сцену тактического боя — п. 8. */
  setActiveBattle: (battle: Battle | null) => void;
  setTurnReport: (report: TurnReport | null) => void;
  setOverlay: (overlay: OverlayPanel) => void;
  setShowLabels: (visible: boolean) => void;
  setZoom: (zoom: number) => void;
  setError: (message: string | null) => void;
  reset: () => void;
}

/** Игрок-человек, за которого играет этот клиент. */
export const selectSelf = (state: GameState): Player | null => {
  if (!state.credentials) {
    return null;
  }
  return state.players.find((player) => player.id === state.credentials?.playerId) ?? null;
};

/**
 * Запоминает открытые экраны и выделение: перезагрузка страницы возвращает игрока
 * не только в партию, но и туда, где он был. Пишется после каждого изменения вида —
 * их немного, и все они делаются рукой игрока.
 */
const persistView = (state: GameState): void => {
  if (!state.game) {
    return;
  }
  writeView({
    gameId: state.game.id,
    selectedSystemId: state.selectedSystem?.id ?? null,
    openedSystemId: state.openedSystem?.id ?? null,
    openedColonyId: state.openedColonyId,
    overlay: state.overlay,
    showLabels: state.showLabels,
  });
};

/** Справочники сервера: загружаются один раз при подключении и переживают выход в меню. */
const initialReferenceState = {
  galaxySizes: [] as GalaxySizeOption[],
  planetClimates: [] as PlanetClimateRef[],
  races: [] as Race[],
  raceDesign: null as RaceDesign | null,
  espionage: null as Espionage | null,
  fleet: null as Fleet | null,
  researchTree: null as ResearchTree | null,
  openGames: [] as GameSummary[],
};

/** Состояние партии и карты: только оно сбрасывается при выходе в меню. */
const initialSessionState = {
  game: null as GameSummary | null,
  players: [] as Player[],
  credentials: null as PlayerCredentials | null,
  map: null as GalaxyMap | null,
  selectedSystem: null as StarSystem | null,
  hoveredSystem: null as StarSystem | null,
  openedSystem: null as StarSystem | null,
  openedColonyId: null as string | null,
  audiencePlayerId: null as string | null,
  targetingFleetId: null as string | null,
  fleetOrder: null as { fleetId: string; targetSystemId: string } | null,
  research: null as PlayerResearch | null,
  waitingFor: [] as string[],
  turnReport: null as TurnReport | null,
  encounters: [] as Encounter[],
  activeBattle: null as Battle | null,

  battle: null as
    | { encounter: Encounter; before: { yours: number; theirs: number } }
    | null,
  overlay: 'none' as OverlayPanel,
  showLabels: true,
  zoom: 1,
  lastError: null as string | null,
};

const initialState = {
  ...initialReferenceState,
  ...initialSessionState,
};

export const useGameStore = create<GameState>((set, get) => ({
  ...initialState,

  setReference: (data) =>
    set({
      galaxySizes: data.galaxySizes,
      planetClimates: data.planetClimates,
      races: data.races,
      raceDesign: data.raceDesign,
      researchTree: data.researchTree,
    }),

  setRaceDesign: (raceDesign) => set({ raceDesign }),

  setOpenGames: (openGames) => set({ openGames }),

  setLobby: (game, players, credentials) =>
    set((state) => ({ game, players, credentials: credentials ?? state.credentials })),

  setWaitingFor: (waitingFor) => set({ waitingFor }),

  setEncounters: (encounters) => set({ encounters }),

  setBattle: (battle) => set({ battle }),

  setActiveBattle: (activeBattle) => set({ activeBattle }),

  setTurnReport: (turnReport) => set({ turnReport }),

  /**
   * Замена карты галактики — при входе в партию и после каждого хода.
   *
   * Выделенная и открытая системы указывают на объекты прежней карты, поэтому
   * переносятся на одноимённые объекты новой: иначе экран системы остался бы с
   * состоянием на прошлый ход.
   */
  setMap: (map) =>
    set((state) => {
      const byId = (system: StarSystem | null) =>
        system ? (map.systems.find((candidate) => candidate.id === system.id) ?? null) : null;
      return {
        map,
        selectedSystem: byId(state.selectedSystem),
        openedSystem: byId(state.openedSystem),
      };
    }),

  /**
   * Замена одной планеты в карте — после перераспределения жителей колонии (п. 4.1).
   * Сервер возвращает планету с новой выработкой, и перезапрашивать карту галактики
   * целиком ради одной колонии незачем.
   *
   * Открытая система — отдельная ссылка на объект, поэтому её планета меняется тоже:
   * иначе карточка планеты осталась бы со старыми числами.
   */
  updatePlanet: (planet) =>
    set((state) => {
      const replace = (candidate: Planet) => (candidate.id === planet.id ? planet : candidate);
      return {
        map: state.map
          ? {
              ...state.map,
              systems: state.map.systems.map((system) => ({
                ...system,
                planets: system.planets.map(replace),
              })),
            }
          : state.map,
        openedSystem: state.openedSystem
          ? { ...state.openedSystem, planets: state.openedSystem.planets.map(replace) }
          : state.openedSystem,
      };
    }),

  /**
   * Замена системы целиком — после заселения планеты колониальной базой (п. 4.1):
   * планет в системе изменилось две, и владелец системы на карте вместе с ними.
   */
  updateSystem: (system) =>
    set((state) => ({
      map: state.map
        ? {
            ...state.map,
            systems: state.map.systems.map((candidate) =>
              candidate.id === system.id ? system : candidate,
            ),
          }
        : state.map,
      openedSystem: state.openedSystem?.id === system.id ? system : state.openedSystem,
      selectedSystem: state.selectedSystem?.id === system.id ? system : state.selectedSystem,
    })),

  setSelectedSystem: (selectedSystem) => {
    set({ selectedSystem });
    persistView(get());
  },

  setHoveredSystem: (hoveredSystem) => set({ hoveredSystem }),

  // Экран системы и панели п. 11.1 занимают одно место, поэтому открытие
  // экрана системы закрывает панель, и наоборот.
  setOpenedSystem: (openedSystem) => {
    // Закрытие системы уносит и открытую в ней колонию: её экран лежит поверх системного.
    set(openedSystem
      ? { openedSystem, overlay: 'none' as OverlayPanel }
      : { openedSystem, openedColonyId: null });
    persistView(get());
  },

  setOpenedColony: (openedColonyId) => {
    set({ openedColonyId });
    persistView(get());
  },

  /*
    Выбор цели и отбор кораблей — два шага одного приказа, и одновременно они не идут:
    начатое прицеливание закрывает диалог отбора, а выбранная звезда закрывает прицел.
  */
  setAudience: (audiencePlayerId) => set({ audiencePlayerId }),
  setTargetingFleet: (targetingFleetId) => set({ targetingFleetId, fleetOrder: null }),

  setFleetOrder: (fleetOrder) => set({ fleetOrder, targetingFleetId: null }),

  setResearch: (research) => set({ research }),

  /**
   * Разведка и флот империи — п. 13 и п. 8. Приходят вместе с картой и обновляются
   * после конца хода: очки шпионажа копятся в фазе конца хода, а состав флота меняется
   * от построек.
   */
  setEmpire: (espionage, fleet) => set({ espionage, fleet }),

  setOverlay: (overlay) => {
    set(overlay === 'none' ? { overlay } : { overlay, openedSystem: null });
    persistView(get());
  },

  setShowLabels: (showLabels) => {
    set({ showLabels });
    persistView(get());
  },

  setZoom: (zoom) => set({ zoom }),

  setError: (lastError) => set({ lastError }),

  // Выход в меню сбрасывает партию, но не справочники: они грузятся один раз
  // в состоянии connecting и после сброса не перезапрашиваются.
  reset: () => set({ ...initialSessionState }),
}));

/*
  СОСТОЯНИЕ ПЕРЕЖИВАЕТ ГОРЯЧУЮ ЗАМЕНУ МОДУЛЯ — только в разработке.

  Хранилище живёт модулем, а dev-сервер при правке файла пересоздаёт модули: новое
  хранилище рождается пустым, и справочники в нём пропадают. Перезапросить их некому —
  машина экранов давно ушла из `connecting`, где они грузятся один раз. Выглядит это как
  поломка игры: экран выбора расы показывает одну клетку «своя раса» из четырнадцати, а
  окно новой игры — код размера галактики вместо названия. Дважды принял это за гонку в
  загрузке справочника, которой нет: страница при этом не перезагружалась вовсе
  (`performance.now()` продолжал считать).

  Снимок лежит НА ОКНЕ, а не в `import.meta.hot.data`: у этого модуля нет своего
  `accept`, замена уходит наверх, к компонентам, и `dispose` здесь не зовётся вовсе —
  проверено журналом, снимок в `hot.data` не появлялся ни разу.

  Переносятся только ДАННЫЕ, без действий: действия замкнуты на `set` СТАРОГО хранилища,
  и перенесённые писали бы в мёртвое. В сборку это не попадает — `import.meta.hot` там
  нет, и весь блок выбрасывается.
*/
if (import.meta.hot) {
  const CARRIER = '__moo3StoreSnapshot';
  const carrier = window as unknown as Record<string, Partial<GameState> | undefined>;
  const dataOnly = (state: GameState): Partial<GameState> =>
    Object.fromEntries(
      Object.entries(state).filter(([, value]) => typeof value !== 'function'),
    ) as Partial<GameState>;

  const kept = carrier[CARRIER];
  if (kept) {
    useGameStore.setState(kept);
  }
  // Снимок держится свежим подпиской: замена модуля происходит без предупреждения, и
  // снимать состояние в этот миг уже некому.
  useGameStore.subscribe((state) => {
    carrier[CARRIER] = dataOnly(state);
  });
  carrier[CARRIER] = dataOnly(useGameStore.getState());
}
