import { assign, fromCallback, fromPromise, setup } from 'xstate';
import { gameApi, GameApiError } from '../api/client';
import type { GameDetails } from '../api/types';
import { useGameStore } from './gameStore';
import { readRevealGalaxy } from './settings';
import { t } from '../i18n';
import {
  clearAccount,
  clearActiveSession,
  clearView,
  readAccount,
  readActiveSession,
  restoreAccount,
  writeActiveSession,
  type StoredSession,
} from './session';

/**
 * Поток экранов клиента.
 *
 * XState отвечает за переходы между экранами и вызовы сервера, Zustand —
 * за сами данные. Такое разделение позволяет добавлять экраны (бой, колонизация,
 * производство) как новые состояния, не трогая хранилище.
 *
 *   connecting → menu → lobby → loadingMap → inGame
 *
 * Партия, в которой игрок уже находится, запоминается в браузере, поэтому после
 * перезагрузки страницы клиент возвращается в неё через состояние restoring, а не
 * начинает с главного меню:
 *
 *   connecting → restoring → loadingMap → inGame
 */

export interface AppContext {
  /** Имя игрока-человека, сохраняется между экранами. */
  playerName: string;
  /** Название родной звезды, выбранное игроком на входе в партию; пусто — имя от генератора. */
  homeStarName: string;
  /** Выбранный размер галактики; по умолчанию Huge — п. 3. */
  galaxySize: string;
  gameName: string;
  /** Идут ли в новой партии случайные галактические события — п. 11.1. */
  galacticEvents: boolean;
  /**
   * Сколько всего империй в партии, считая свою, — п. 3: в MOO II число соперников
   * выбирают в окне новой игры.
   */
  totalPlayers: number;
  /** Раса, собранная игроком в конструкторе — п. 7: держится между экранами. */
  raceName: string;
  raceTraits: string[];
  /** Код выбранной готовой расы — п. 5; пусто — раса достанется по разнарядке. */
  raceCode: string;
  error: string | null;
  /** Партия, в которую нужно вернуться: из хранилища браузера или из сохранения. */
  session: StoredSession | null;
}

export type AppEvent =
  | { type: 'RETRY' }
  | { type: 'REFRESH_GAMES' }
  | {
      type: 'CREATE_GAME';
      playerName: string;
      homeStarName: string;
      raceName: string;
      raceTraits: string[];
      raceCode?: string;
      galaxySize: string;
      gameName: string;
      galacticEvents: boolean;
      totalPlayers: number;
    }
  | {
      type: 'JOIN_GAME';
      gameId: string;
      playerName: string;
      homeStarName: string;
      raceName: string;
      raceTraits: string[];
      raceCode?: string;
    }
  | { type: 'START_GAME' }
  | { type: 'END_TURN' }
  /** Ход посчитан — пришло подпиской: считал его тот, кто закончил последним. */
  | { type: 'TURN_ADVANCED' }
  /** Состав партии изменился: кто-то вошёл, стартовал или закончил ход. */
  | { type: 'PLAYERS_CHANGED' }
  | { type: 'LOAD_SAVE'; saveId: string }
  /** Игрок вошёл в игру — п. 3.1: экран входа отдал пропуск учётной записи. */
  | { type: 'AUTHORIZED' }
  /** Выход из учётной записи: пропуск гасится, игрок возвращается к форме входа. */
  | { type: 'SIGN_OUT' }
  | { type: 'LEAVE' };

/** Партия идёт: после восстановления клиенту нужна карта, а не экран лобби. */
const isStarted = (details: GameDetails): boolean => details.game.status === 'IN_PROGRESS';

const sessionOf = (details: GameDetails, credentials: StoredSession['credentials']): StoredSession => ({
  gameId: details.game.id,
  gameName: details.game.name,
  credentials,
  savedAt: new Date().toISOString(),
});

/**
 * Сообщение об ошибке для игрока — вместе с подробностями сервера.
 *
 * Сервер к «Запрос не прошёл валидацию» прикладывает список полей, из-за которых запрос
 * отклонён, — без него игрок видит только возврат на прошлый экран и не понимает, что
 * исправить. Так и вышло с длинным названием партии: игра не создавалась, а причина
 * оставалась в `details`, которые клиент выбрасывал.
 */
const message = (error: unknown): string => {
  if (!(error instanceof GameApiError)) {
    return t('error.unknown');
  }
  return error.details.length > 0
    ? `${error.message}: ${error.details.join('; ')}`
    : error.message;
};

/**
 * Перечитывает всё, что меняет ход — п. 11.1.
 *
 * Ход трогает всю галактику: колонии выросли, стройки продвинулись, очки исследований
 * начислены, шпионы отработали. Поэтому за ходом сразу идут карта, исследования и
 * состояние империи — иначе соседний экран остался бы с прошлым ходом.
 *
 * Вызывается и после своего конца хода, и по событию от сервера: ход считает тот, кто
 * закончил последним, а остальные узнают о нём подпиской.
 */
async function refreshAfterTurn(): Promise<void> {
  const { game, credentials } = useGameStore.getState();
  if (!game || !credentials) {
    return;
  }
  const [details, map, research, espionage, fleet, report, encounters] = await Promise.all([
    gameApi.getGame(game.id),
    gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
    gameApi.getResearch(game.id, credentials.accessToken),
    gameApi.getEspionage(game.id, credentials.accessToken),
    gameApi.getFleet(game.id, credentials.accessToken),
    gameApi.getTurnReport(game.id, credentials.accessToken),
    gameApi.getEncounters(game.id, credentials.accessToken),
  ]);
  const store = useGameStore.getState();
  store.setLobby(details.game, details.players);
  store.setMap(map);
  store.setResearch(research);
  store.setEmpire(espionage, fleet);
  store.setTurnReport(report);
  store.setEncounters(encounters);
  store.setWaitingFor([]);

  // Достроенная колониальная база ждёт, какую планету заселить — п. 4.1. Экран системы
  // открывается сам: иначе игроку пришлось бы искать по карте, где именно база готова.
  const playerId = credentials.playerId;
  const waiting = map.systems.find((system) =>
    system.planets.some((planet) => planet.colonyBaseReady && planet.ownerPlayerId === playerId),
  );
  if (waiting) {
    store.setOpenedSystem(waiting);
  }
}

export const appMachine = setup({
  types: {
    context: {} as AppContext,
    events: {} as AppEvent,
  },
  actors: {
    /** Подключение к серверу: проверка доступности и загрузка справочников. */
    loadReference: fromPromise(async () => {
      await gameApi.health();
      const [galaxySizes, planetClimates, races, raceDesign, researchTree] = await Promise.all([
        gameApi.reference.galaxySizes(),
        gameApi.reference.planetClimates(),
        gameApi.reference.races(),
        gameApi.reference.raceTraits(),
        gameApi.reference.research(),
      ]);
      useGameStore
        .getState()
        .setReference({ galaxySizes, planetClimates, races, raceDesign, researchTree });
      return galaxySizes;
    }),

    /** Список открытых игр — п. 3.1. */
    loadGames: fromPromise(async () => {
      const games = await gameApi.listGames(true);
      useGameStore.getState().setOpenGames(games);
      return games;
    }),

    createGame: fromPromise(
      async ({
        input,
      }: {
        input: {
          gameName: string;
          galaxySize: string;
          playerName: string;
          homeStarName: string;
          raceName: string;
          raceTraits: string[];
          raceCode: string;
          galacticEvents: boolean;
          totalPlayers: number;
        };
      }) => {
        const joined = await gameApi.createGame({
          name: input.gameName,
          galaxySize: input.galaxySize,
          playerName: input.playerName,
          homeStarName: input.homeStarName,
          raceName: input.raceName,
          raceTraits: input.raceTraits,
          raceCode: input.raceCode || undefined,
          galacticEvents: input.galacticEvents,
          totalPlayers: input.totalPlayers,
        });
        useGameStore.getState().setLobby(joined.game, joined.players, joined.credentials);
        writeActiveSession(sessionOf(joined, joined.credentials));
        return joined;
      },
    ),

    joinGame: fromPromise(
      async ({
        input,
      }: {
        input: {
          gameId: string;
          playerName: string;
          homeStarName: string;
          raceName: string;
          raceTraits: string[];
          raceCode: string;
        };
      }) => {
        const joined = await gameApi.joinGame(
          input.gameId,
          input.playerName,
          input.homeStarName,
          input.raceName,
          input.raceTraits,
          input.raceCode || undefined,
        );
        useGameStore.getState().setLobby(joined.game, joined.players, joined.credentials);
        writeActiveSession(sessionOf(joined, joined.credentials));
        return joined;
      },
    ),

    /**
     * Возврат в партию по ссылке из хранилища браузера: после перезагрузки страницы
     * и при загрузке сохранения. Состояние партии лежит на сервере, клиент забирает
     * его заново — так восстановленная игра ничем не отличается от только что начатой.
     */
    restoreSession: fromPromise(
      async ({ input }: { input: { session: StoredSession | null } }) => {
        const session = input.session;
        if (!session) {
          throw new GameApiError(0, t('error.noSavedGame'));
        }
        const details = await gameApi.getGame(session.gameId);
        useGameStore.getState().setLobby(details.game, details.players, session.credentials);
        writeActiveSession(sessionOf(details, session.credentials));
        return details;
      },
    ),

    /**
     * Загрузка сохранения: сервер поднимает из слепка новую партию, клиент получает
     * её состояние и пропуск игрока — дальше всё как в только что начатой игре.
     */
    loadSave: fromPromise(async ({ input }: { input: { saveId: string } }) => {
      const joined = await gameApi.loadSave(input.saveId);
      useGameStore.getState().setLobby(joined.game, joined.players, joined.credentials);
      writeActiveSession(sessionOf(joined, joined.credentials));
      return joined;
    }),

    startGame: fromPromise(async () => {
      const { game, credentials } = useGameStore.getState();
      if (!game || !credentials) {
        throw new GameApiError(0, t('error.noActiveGame'));
      }
      const details = await gameApi.startGame(game.id, credentials.accessToken);
      useGameStore.getState().setLobby(details.game, details.players);
      return details;
    }),

    /**
     * Конец хода игрока — п. 11.1.
     *
     * Игрок объявляет, что закончил, и ждёт остальных: галактика считается, когда
     * закончили все люди партии. Если пересчёт случился по этому же запросу — перечитываем
     * всё, что ход поменял; если нет — запоминаем, кого ждём, и ждём события от сервера.
     */
    endTurn: fromPromise(async () => {
      const { game, credentials } = useGameStore.getState();
      if (!game || !credentials) {
        throw new GameApiError(0, t('error.noActiveGame'));
      }
      const result = await gameApi.endTurn(game.id, credentials.accessToken);
      useGameStore.getState().setLobby(result.state.game, result.state.players);
      useGameStore.getState().setWaitingFor(result.waitingFor);

      if (!result.advanced) {
        return result;
      }

      useGameStore.getState().setTurnReport(result.report ?? null);
      await refreshAfterTurn();
      return result;
    }),

    /** Ход посчитал кто-то другой: перечитываем состояние по событию подписки. */
    refreshTurn: fromPromise(async () => {
      await refreshAfterTurn();
    }),

    /** Состав партии изменился: обновляем только его, галактику трогать незачем. */
    refreshPlayers: fromPromise(async () => {
      const { game } = useGameStore.getState();
      if (!game) {
        return;
      }
      const details = await gameApi.getGame(game.id);
      useGameStore.getState().setLobby(details.game, details.players);
    }),

    /**
     * Подписка на события партии — п. 11.1.
     *
     * Пока игрок думает над ходом, партия живёт: соседи заканчивают ходы, галактика
     * пересчитывается. Опрашивать сервер ради этого пришлось бы постоянно и всем, поэтому
     * сервер сам присылает события, а машина превращает их в переходы.
     */
    gameEvents: fromCallback(({ sendBack }) => {
      const { game, credentials } = useGameStore.getState();
      if (!game || !credentials) {
        return () => undefined;
      }
      return gameApi.subscribe(game.id, credentials.accessToken, (event) => {
        if (event.type === 'TURN_ADVANCED') {
          sendBack({ type: 'TURN_ADVANCED' });
        }
        if (event.type === 'PLAYER_READY' || event.type === 'PLAYER_JOINED') {
          sendBack({ type: 'PLAYERS_CHANGED' });
        }
      });
    }),

    /**
     * Карта генерируется на сервере при старте, клиент её только загружает. Вместе с
     * картой приходит состояние исследований — п. 9: оно серверное и нужно сразу,
     * потому что правая панель показывает текущее исследование.
     */
    loadMap: fromPromise(async () => {
      const { game, credentials } = useGameStore.getState();
      if (!game || !credentials) {
        throw new GameApiError(0, t('error.noActiveGame'));
      }
      const [map, research, espionage, fleet, report, encounters] = await Promise.all([
        gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
        gameApi.getResearch(game.id, credentials.accessToken),
        gameApi.getEspionage(game.id, credentials.accessToken),
        gameApi.getFleet(game.id, credentials.accessToken),
        gameApi.getTurnReport(game.id, credentials.accessToken),
        gameApi.getEncounters(game.id, credentials.accessToken),
      ]);
      useGameStore.getState().setMap(map);
      useGameStore.getState().setResearch(research);
      useGameStore.getState().setEmpire(espionage, fleet);
      // Итоги прошлого хода и незакрытые встречи флотов — то же, что после конца хода:
      // игрок мог перезагрузить страницу, не прочитав их (п. 11.1, п. 8).
      useGameStore.getState().setTurnReport(report);
      useGameStore.getState().setEncounters(encounters);
      return map;
    }),

    /** Обновление состава лобби, пока хост не стартовал игру — п. 3.2. */
    pollLobby: fromPromise(async () => {
      const { game } = useGameStore.getState();
      if (!game) {
        return null;
      }
      const details = await gameApi.getGame(game.id);
      useGameStore.getState().setLobby(details.game, details.players);
      return details;
    }),
  },
  actions: {
    clearError: assign({ error: null }),
    storeError: assign({ error: ({ event }) => message((event as { error?: unknown }).error) }),
    publishError: ({ context }) => useGameStore.getState().setError(context.error),
    // Сообщение об ошибке живёт до следующего экрана: там его гасим и в хранилище тоже,
    // иначе ошибка прошлого шага висела бы поверх нового экрана.
    hideError: () => useGameStore.getState().setError(null),
    // Выход в главное меню: партия на сервере остаётся, но клиент в неё больше
    // не возвращается — ни сейчас, ни после перезагрузки страницы.
    resetGame: () => {
      clearActiveSession();
      clearView();
      useGameStore.getState().reset();
    },
    readSession: assign({ session: () => readActiveSession() }),
    /*
      Вход в игру — п. 3.1. Пропуск учётной записи поднимается из хранилища браузера до
      первого запроса: без него сервер не пустит ни в новую партию, ни в чужую, ни в
      сохранение.
    */
    restoreAccount: () => restoreAccount(),
    /*
      Выход из учётной записи — п. 3.1: гасим пропуск на сервере и забываем всё своё.
      Партию на сервере это не трогает: игрок в неё вернётся, войдя заново.
    */
    signOut: () => {
      gameApi.logoutAccount().catch(() => undefined);
      clearAccount();
      clearActiveSession();
      clearView();
      useGameStore.getState().reset();
    },
    forgetSession: assign({
      session: () => {
        clearActiveSession();
        return null;
      },
    }),
  },
  guards: {
    hasSession: ({ context }) => context.session !== null,
    /** Не авторизован — п. 3.1: дальше экрана входа игрока не пускают. */
    noAccount: () => readAccount() === null,
    isStartedGame: ({ event }) => isStarted((event as unknown as { output: GameDetails }).output),
  },
}).createMachine({
  id: 'moo3',
  initial: 'connecting',
  /*
    Выход из учётной записи возможен с любого экрана, включая партию, — п. 3.1: событие
    верхнего уровня, а не своё у каждого состояния. Партия при этом остаётся на сервере,
    уходит только этот клиент.
  */
  on: {
    SIGN_OUT: { target: '.auth', actions: ['signOut', 'hideError'] },
  },
  context: {
    playerName: t('player.default'),
    homeStarName: '',
    galaxySize: 'HUGE',
    gameName: '',
    // События включены, пока их не выключили, — как в оригинале (п. 11.1).
    galacticEvents: true,
    // Полная галактика на восемь империй — столько же задано настройкой сервера.
    totalPlayers: 8,
    raceName: '',
    raceTraits: [],
    raceCode: '',
    error: null,
    session: null,
  },
  states: {
    /** Подключение клиента к серверу. */
    connecting: {
      entry: ['clearError', 'hideError', 'readSession', 'restoreAccount'],
      invoke: {
        src: 'loadReference',
        onDone: [
          // До партии игрок называет себя — п. 3.1: без входа дальше не пускают.
          { target: 'auth', guard: 'noAccount' },
          // Игрок обновил страницу, не выходя из партии, — возвращаем его в неё.
          { target: 'restoring', guard: 'hasSession' },
          { target: 'menu' },
        ],
        onError: { target: 'offline', actions: ['storeError', 'publishError'] },
      },
    },

    /**
     * Вход в игру — п. 3.1: регистрация и авторизация до всякой партии.
     *
     * Отсюда выходят только с пропуском учётной записи, поэтому справочники уже
     * загружены: экран входа стоит после подключения, а не до него — иначе неудачный
     * вход выглядел бы как недоступный сервер.
     */
    auth: {
      entry: ['clearError', 'hideError'],
      on: {
        AUTHORIZED: [
          { target: 'restoring', guard: 'hasSession' },
          { target: 'menu' },
        ],
      },
    },

    /** Возврат в партию, из которой игрок не выходил: после перезагрузки страницы. */
    restoring: {
      entry: ['clearError', 'hideError'],
      invoke: {
        src: 'restoreSession',
        input: ({ context }) => ({ session: context.session }),
        onDone: [
          { target: 'loadingMap', guard: 'isStartedGame' },
          { target: 'lobby' },
        ],
        onError: {
          target: 'menu',
          actions: ['forgetSession', 'resetGame', 'storeError', 'publishError'],
        },
      },
    },

    offline: {
      on: { RETRY: 'connecting' },
    },

    /**
     * Главное меню: список игр, создание новой, присоединение к чужой.
     * Сообщение об ошибке здесь не гасится — именно сюда возвращают неудачные попытки
     * создать, найти или восстановить партию, и игрок должен увидеть причину.
     */
    menu: {
      entry: 'clearError',
      invoke: { src: 'loadGames' },
      on: {
        REFRESH_GAMES: { target: 'menu', reenter: true },
        CREATE_GAME: {
          target: 'creatingGame',
          actions: assign({
            playerName: ({ event }) => event.playerName,
            homeStarName: ({ event }) => event.homeStarName,
            raceName: ({ event }) => event.raceName,
            raceTraits: ({ event }) => event.raceTraits,
            raceCode: ({ event }) => event.raceCode ?? '',
            galaxySize: ({ event }) => event.galaxySize,
            gameName: ({ event }) => event.gameName,
            galacticEvents: ({ event }) => event.galacticEvents,
            totalPlayers: ({ event }) => event.totalPlayers,
          }),
        },
        JOIN_GAME: {
          target: 'joiningGame',
          actions: assign({
            playerName: ({ event }) => event.playerName,
            homeStarName: ({ event }) => event.homeStarName,
            raceName: ({ event }) => event.raceName,
            raceTraits: ({ event }) => event.raceTraits,
            raceCode: ({ event }) => event.raceCode ?? '',
          }),
        },
        LOAD_SAVE: 'loadingSave',
      },
    },

    /** Загрузка сохранённой партии — «Игра» → «Загрузить». */
    loadingSave: {
      entry: ['clearError', 'hideError'],
      invoke: {
        src: 'loadSave',
        input: ({ event }) => ({
          saveId: (event as Extract<AppEvent, { type: 'LOAD_SAVE' }>).saveId,
        }),
        onDone: [
          { target: 'loadingMap', guard: 'isStartedGame' },
          { target: 'lobby' },
        ],
        onError: { target: 'menu', actions: ['resetGame', 'storeError', 'publishError'] },
      },
    },

    creatingGame: {
      invoke: {
        src: 'createGame',
        input: ({ context }) => ({
          gameName: context.gameName,
          galaxySize: context.galaxySize,
          playerName: context.playerName,
          homeStarName: context.homeStarName,
          raceName: context.raceName,
          raceTraits: context.raceTraits,
          raceCode: context.raceCode,
          galacticEvents: context.galacticEvents,
          totalPlayers: context.totalPlayers,
        }),
        onDone: { target: 'lobby' },
        onError: { target: 'menu', actions: ['storeError', 'publishError'] },
      },
    },

    joiningGame: {
      invoke: {
        src: 'joinGame',
        input: ({ context, event }) => ({
          gameId: (event as Extract<AppEvent, { type: 'JOIN_GAME' }>).gameId,
          playerName: context.playerName,
          homeStarName: context.homeStarName,
          raceName: context.raceName,
          raceTraits: context.raceTraits,
          raceCode: context.raceCode,
        }),
        onDone: { target: 'lobby' },
        onError: { target: 'menu', actions: ['storeError', 'publishError'] },
      },
    },

    /** Лобби: ждём игроков, хост может стартовать в любой момент — п. 3.2. */
    lobby: {
      entry: ['clearError', 'hideError'],
      invoke: { src: 'pollLobby' },
      after: {
        3000: { target: 'lobby', reenter: true },
      },
      on: {
        START_GAME: 'startingGame',
        LEAVE: { target: 'menu', actions: 'resetGame' },
      },
    },

    startingGame: {
      invoke: {
        src: 'startGame',
        onDone: { target: 'loadingMap' },
        onError: { target: 'lobby', actions: ['storeError', 'publishError'] },
      },
    },

    loadingMap: {
      invoke: {
        src: 'loadMap',
        onDone: { target: 'inGame' },
        onError: { target: 'lobby', actions: ['storeError', 'publishError'] },
      },
    },

    /**
     * Игра идёт: карта галактики отрисована, работают экраны HUD.
     *
     * Конец хода — вложенное состояние, а не соседнее: экран остаётся тем же, и вход
     * в inGame при возврате не повторяется, поэтому сообщение о неудавшемся ходе
     * не гаснет сразу же.
     */
    inGame: {
      entry: ['clearError', 'hideError'],
      initial: 'idle',
      // Подписка живёт всё время партии: события приходят и пока игрок думает над ходом.
      invoke: { src: 'gameEvents' },
      states: {
        idle: {
          on: {
            END_TURN: 'endingTurn',
            TURN_ADVANCED: 'refreshing',
            PLAYERS_CHANGED: 'refreshingPlayers',
          },
        },
        /**
         * Свой конец хода событий не слушает: он и так перечитает состояние, когда
         * сервер ответит. Иначе своё же «ход посчитан» обрывало бы собственный запрос.
         */
        endingTurn: {
          invoke: {
            src: 'endTurn',
            onDone: { target: 'idle' },
            onError: { target: 'idle', actions: ['storeError', 'publishError'] },
          },
        },
        /**
         * Ход посчитал кто-то другой: перечитываем галактику. Отдельное состояние нужно,
         * чтобы событие, пришедшее во время своего конца хода, не запускало второй
         * пересчёт поверх первого.
         */
        refreshing: {
          invoke: {
            src: 'refreshTurn',
            onDone: { target: 'idle' },
            onError: { target: 'idle', actions: ['storeError', 'publishError'] },
          },
          // Следующий ход посчитался, пока читали предыдущий: начинаем чтение заново.
          on: { TURN_ADVANCED: { target: 'refreshing', reenter: true } },
        },
        /** Кто-то вошёл или закончил ход: обновляем только состав партии. */
        refreshingPlayers: {
          invoke: {
            src: 'refreshPlayers',
            onDone: { target: 'idle' },
            onError: { target: 'idle' },
          },
          // Ход важнее состава: он меняет всё, а состав придёт вместе с ним.
          on: { TURN_ADVANCED: 'refreshing' },
        },
      },
      on: {
        LOAD_SAVE: { target: 'loadingSave', actions: 'resetGame' },
        LEAVE: { target: 'menu', actions: 'resetGame' },
      },
    },
  },
});
