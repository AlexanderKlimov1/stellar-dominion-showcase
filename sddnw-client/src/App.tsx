import { useEffect, useRef, useState } from 'react';
import { useMachine } from '@xstate/react';
import type { Council } from './api/types';
import { gameApi } from './api/client';
import { appMachine } from './state/appMachine';
import { useGameStore } from './state/gameStore';
import { PhaserGame } from './game/PhaserGame';
import { MainMenu } from './ui/MainMenu';
import { Lobby } from './ui/Lobby';
import { Hud } from './ui/Hud';
import { GalaxyHotkeys } from './ui/GalaxyHotkeys';
import { LoadGameDialog } from './ui/LoadGameDialog';
import { PreferencesScreen } from './ui/preferences/PreferencesScreen';
import { ResearchScreen } from './ui/research/ResearchScreen';
import { asksForNextTarget } from './ui/research/researchRules';
import { AudienceScreen } from './ui/diplomacy/AudienceScreen';
import { PlanetsScreen } from './ui/planets/PlanetsScreen';
import { LeadersScreen } from './ui/leaders/LeadersScreen';
import { RaceRelationsScreen } from './ui/races/RaceRelationsScreen';
import { InfoScreen } from './ui/info/InfoScreen';
import { SidePanel } from './ui/SidePanel';
import { BattleScene } from './ui/BattleScene';
import { EncounterDialog } from './ui/EncounterDialog';
import { BattleScreen } from './ui/battle/BattleScreen';
import { FleetDialog } from './ui/FleetDialog';
import { FleetTargetingHint } from './ui/FleetTargetingHint';
import { FleetOperationsScreen } from './ui/fleet/FleetOperationsScreen';
import { FleetInFlightWindow } from './ui/fleet/FleetInFlightWindow';
import { FleetLandingDialog } from './ui/fleet/FleetLandingDialog';
import { canColonizeHere } from './ui/fleet/fleetShips';
import { readRevealGalaxy } from './state/settings';
import { TurnResults, exploredSystemId, worthShowing } from './ui/TurnResults';
import { TutorialHints } from './ui/tutorial/TutorialHints';
import { ColoniesScreen } from './ui/colonies/ColoniesScreen';
import { ColonyScreen } from './ui/system/ColonyScreen';
import { SystemScreen } from './ui/system/SystemScreen';
import { CouncilScreen } from './ui/council/CouncilScreen';
import { TechStolenScene, stolenTechnology, type Theft } from './ui/espionage/TechStolenScene';
import { StoryScene } from './ui/story/StoryScene';
import { storiesOf } from './ui/story/storyEvents';
import { Splash } from './ui/Splash';
import { AuthScreen } from './ui/auth/AuthScreen';
import { useT } from './i18n';

/**
 * Корневой экран.
 *
 * Поток экранов задаёт XState, данные лежат в Zustand, карту рисует Phaser 3.
 * React-слой (меню, HUD, исследования, дипломатия) общается с движком
 * только через шину событий.
 */
export function App() {
  const [state, send] = useMachine(appMachine);
  const overlay = useGameStore((store) => store.overlay);
  const setOverlay = useGameStore((store) => store.setOverlay);
  const lastError = useGameStore((store) => store.lastError);
  const turnReport = useGameStore((store) => store.turnReport);
  // Дерево и состояние исследований нужны, чтобы после итогов хода решить, спрашивать ли
  // о новой цели: прорыв освобождает прежнюю — п. 9.
  const researchTree = useGameStore((store) => store.researchTree);
  const research = useGameStore((store) => store.research);
  // Карта и открытая система — чтобы после итогов хода показать разведанную флотом
  // систему: сервер шлёт в событии только её идентификатор — п. 15.
  const map = useGameStore((store) => store.map);
  const openedSystem = useGameStore((store) => store.openedSystem);
  /*
    Разведанная система, которая ждёт показа, — п. 15. Живёт в самом экране, а не в
    хранилище: экран системы открывается один раз, по горячим следам хода, и пережить
    перезагрузку страницы ему незачем. Держится ИДЕНТИФИКАТОР, а не сама система: пока
    идёт бой с чудищем, карта успеет перечитаться, и открыть нужно свежую систему, а не
    снятую до боя.
  */
  const [exploredPendingId, setExploredPendingId] = useState<string | null>(null);
  /*
    Выборы Высшего совета — п. 3. Сцена показывается по горячим следам хода, на котором
    совет собрался: голосование уже посчитано и записано на сервере, а экран просит его
    отдельным запросом — в карте его нет и быть не может.
  */
  const [council, setCouncil] = useState<Council | null>(null);
  /*
    Открытый совет, который ждёт голоса ЭТОГО игрока — п. 3. Бюллетень можно отложить
    («решить позже») или потерять перезагрузкой страницы, а совет всё равно подведёт итог в
    конце хода — поэтому, пока голос не подан, над картой висит напоминание.
  */
  const [ballot, setBallot] = useState<Council | null>(null);
  /*
    Украденная технология — п. 13: сцена показывается тому, чей агент её принёс, и тому,
    у кого её унесли. Разбирается она из отчёта хода по КЛЮЧУ события, а не по тексту.
  */
  const [theft, setTheft] = useState<Theft | null>(null);
  const setOpenedSystem = useGameStore((store) => store.setOpenedSystem);
  const encounters = useGameStore((store) => store.encounters);
  const battle = useGameStore((store) => store.battle);
  const setBattle = useGameStore((store) => store.setBattle);
  const activeBattle = useGameStore((store) => store.activeBattle);
  const setActiveBattle = useGameStore((store) => store.setActiveBattle);
  const game = useGameStore((store) => store.game);
  const credentials = useGameStore((store) => store.credentials);
  const fleet = useGameStore((store) => store.fleet);
  const targetingFleetId = useGameStore((store) => store.targetingFleetId);
  const fleetOrder = useGameStore((store) => store.fleetOrder);
  const flightFleet = useGameStore((store) => store.flightFleet);
  const stories = useGameStore((store) => store.stories);
  const pushStories = useGameStore((store) => store.pushStories);
  const shiftStory = useGameStore((store) => store.shiftStory);
  const players = useGameStore((store) => store.players);
  const flightFleetId = flightFleet?.fleetId ?? null;
  const setFlightFleet = useGameStore((store) => store.setFlightFleet);
  const setFleetOrder = useGameStore((store) => store.setFleetOrder);
  const setMap = useGameStore((store) => store.setMap);
  const setTargetingFleet = useGameStore((store) => store.setTargetingFleet);
  const espionage = useGameStore((store) => store.espionage);
  const setEmpire = useGameStore((store) => store.setEmpire);
  /*
    Окно высадки флота — п. 4.1. Открывают его двое: кнопка «Колонизовать планету» в окне
    флота и прилёт колониального корабля (`offer`), который сам предлагает заселение, как в
    оригинале. Рисуется здесь, а не в окне флота: то лежит в углу карты своей рамкой.
  */
  const [landing, setLanding] = useState<{ fleetId: string; offer: boolean } | null>(null);
  /** Ход, на котором прилёт уже предлагал заселение: отказ не должен всплывать снова. */
  const [offeredTurn, setOfferedTurn] = useState<number | null>(null);

  /**
   * Итоги хода показываются один раз на ход: закрыл — до следующего хода не всплывают.
   * Номер прочитанного хода держим здесь, а не в хранилище: это состояние окна, а не партии.
   */
  const [seenResultsTurn, setSeenResultsTurn] = useState<number | null>(null);

  /**
   * Открыты ли настройки игрока — п. 11.1: раскладка горячих клавиш.
   *
   * Зовут их из меню «Игра», а рисуются они ЗДЕСЬ: нижняя полоса лежит в своём слое
   * (`z-10`), и экран, нарисованный из неё, уходил бы под правую панель — номер слоя
   * считается внутри своего слоя, а не поперёк всех.
   */
  const [preferencesOpen, setPreferencesOpen] = useState(false);

  /**
   * Открыт ли список сохранений — п. 3. Зовут его из меню «Игра», а рисуется он ЗДЕСЬ, и
   * по той же причине, что и настройки: слой нижней полосы — клетка, и окно, нарисованное
   * из неё, уходило под правую панель, хотя у самого стоит `z-50`.
   */
  const [loadOpen, setLoadOpen] = useState(false);

  /*
    Долетевший или погибший флот закрывает своё окно «флот в пути» и забывает, что оно было
    открыто: без этого пометка осталась бы в хранилище, и окно само всплыло бы снова, когда
    тот же флот через десяток ходов опять выйдет в полёт.
  */
  useEffect(() => {
    // Окно флота одно на стоянку и полёт (п. 8), поэтому закрывается оно, только когда флота
    // больше нет вовсе, а не когда он долетел: долетевший стоит, и окно говорит «на орбите».
    if (flightFleetId && !fleet?.fleets.some((group) => group.id === flightFleetId)) {
      setFlightFleet(null);
    }
  }, [flightFleetId, fleet, setFlightFleet]);

  /*
    Бой мог начать соперник: тактическая сцена живёт на сервере, и вторая сторона узнаёт
    о ней не по своему нажатию — п. 8. Поэтому при входе в партию и в начале каждого хода
    клиент спрашивает, не идёт ли бой с его участием, и открывает сцену сам.
  */
  useEffect(() => {
    // В лобби боёв нет и быть не может: сервер отвечает на такой вопрос отказом (409),
    // а лобби опрашивает партию каждую секунду — и каждый опрос ронял в консоль ошибку.
    if (!game || game.status !== 'IN_PROGRESS' || !credentials || activeBattle) {
      return;
    }
    let alive = true;
    gameApi
      .getBattles(game.id, credentials.accessToken)
      .then((battles) => {
        if (alive && battles.length > 0) {
          setActiveBattle(battles[0]);
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [game, credentials, activeBattle, setActiveBattle]);

  /*
    Разведанная система открывается ПОСЛЕДНЕЙ — п. 15, решение хозяина проекта (30.09.2026).
    Флот, вошедший в систему под сторожем, разведывает её и тут же встречает чудище
    (`ArrivalPhase`: прибытие, затем бой) — и игрок сперва дерётся, а смотрит на добытое
    уже потом. Поэтому экран системы ждёт, пока закрыты итоги хода, бой (тактический и
    «авто») и сцены совета и кражи: всплыв раньше, он лёг бы под бой и спорил бы с ним за
    Esc, а после боя игрок нашёл бы его открытым неизвестно когда.

    Окна разведки («Разведчики достигли системы…») перед экраном системы больше нет: оно
    показывало схему без подписей и строку чисел, а ответ на «что там нашлось» и так даёт
    экран системы — карточка планеты называет и находку (п. 4.1), и климат, и минералы.
  */
  const resultsPending =
    turnReport !== null && worthShowing(turnReport) && seenResultsTurn !== turnReport.turn;
  useEffect(() => {
    // Ждёт и предложения заселения (эффект ниже): пока оно не проверено на этот ход
    // (`offeredTurn`), оба эффекта сработали бы в одном кадре, и экран системы лёг бы под окно.
    if (!exploredPendingId || resultsPending || activeBattle || battle || council || theft || landing
      || stories.length > 0
      || (turnReport !== null && fleet !== null && map !== null && offeredTurn !== turnReport.turn)) {
      return;
    }
    const explored = map?.systems.find((system) => system.id === exploredPendingId);
    setExploredPendingId(null);
    // Уже открытую систему разведка не отнимает (см. closeResults): там может ждать выбора
    // планеты колониальная база.
    if (explored && !openedSystem) {
      setOpenedSystem(explored);
    }
  }, [exploredPendingId, resultsPending, activeBattle, battle, council, theft, landing, stories, map,
    openedSystem, setOpenedSystem, turnReport, offeredTurn]);

  /*
    Прилёт колониального корабля предлагает заселение сам — п. 4.1, как в оригинале: флот
    пришёл туда, ради чего его строили, и искать кнопку по экранам игрок не должен. Ждёт
    предложение того же, что и экран разведанной системы, — итогов хода и боя, — и
    всплывает раньше него: разведанную систему покажут уже с новой колонией.

    Предлагается ОДИН раз на ход (`offeredTurn`): отказавшийся найдёт «Колонизовать
    планету» в окне флота на следующих ходах.
  */
  useEffect(() => {
    if (!turnReport || offeredTurn === turnReport.turn || resultsPending || activeBattle || battle
      || council || theft || landing || stories.length > 0 || !fleet || !map) {
      return;
    }
    setOfferedTurn(turnReport.turn);
    const arrived = new Set(turnReport.events
      .filter((event) => event.code === 'FLEET_ARRIVED' && event.systemId)
      .map((event) => event.systemId));
    const settler = fleet.fleets.find((group) => arrived.has(group.starSystemId)
      && canColonizeHere(group, map.systems.find((system) => system.id === group.starSystemId)));
    if (settler) {
      setLanding({ fleetId: settler.id, offer: true });
    }
  }, [turnReport, offeredTurn, resultsPending, activeBattle, battle, council, theft, landing, stories, fleet,
    map]);

  /*
    Совет спрашивается при входе в партию и в начале каждого хода: созыв мог застать игрока
    при закрытой странице, и строка итогов хода к нему уже не придёт.
  */
  const gameId = game?.id;
  const gameTurn = game?.turn;
  const gameRunning = game?.status === 'IN_PROGRESS';
  const accessToken = credentials?.accessToken;

  /*
    Первый ход открывает выбор исследования сам (backlog-promo, пункт 16) — как после
    прорыва. До первого прорыва ход без цели теряет очки целиком (`keepSection` продолжает
    только раздел прошлого прорыва), а правая панель говорит об этом одним словом «не
    выбрано», которое новичок читает как название. Один раз на партию за сессию: отказавшийся
    («отмена») решил сам, и навязываться ему каждым перерисовыванием незачем.
  */
  const askedFirstResearch = useRef<string | null>(null);
  useEffect(() => {
    if (!gameId || !gameRunning || gameTurn !== 1 || !research || research.categoryCode
      || overlay !== 'none' || askedFirstResearch.current === gameId) {
      return;
    }
    askedFirstResearch.current = gameId;
    setOverlay('research');
  }, [gameId, gameRunning, gameTurn, research, overlay, setOverlay]);
  useEffect(() => {
    if (!gameId || !gameRunning || !accessToken) {
      setBallot(null);
      return;
    }
    let alive = true;
    gameApi
      .getCouncil(gameId, accessToken)
      .then((session) => {
        if (alive) {
          setBallot(awaitsMyVote(session) ? session : null);
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [gameId, gameTurn, gameRunning, accessToken]);

  const { t } = useT();

  if (state.matches('connecting')) {
    return <Splash title={t('splash.connecting')} hint={t('splash.connecting.hint')} />;
  }

  if (state.matches('offline')) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3">
        <div className="text-sm uppercase tracking-[0.3em] text-danger">{t('splash.offline')}</div>
        <p className="max-w-md text-center text-xs text-ink-dim">{lastError}</p>
        <button type="button" className="link text-accent" onClick={() => send({ type: 'RETRY' })}>
          {t('splash.retry')}
        </button>
      </div>
    );
  }

  /*
    Вход в игру — п. 3.1: до партии игрок называет себя. Экран стоит перед меню, а не
    рядом с ним: сервер спрашивает пропуск учётной записи на входе в любую партию, и без
    входа меню показывало бы кнопки, каждая из которых ответит отказом.
  */
  if (state.matches('auth')) {
    return <AuthScreen onAuthorized={() => send({ type: 'AUTHORIZED' })} />;
  }

  if (state.matches('restoring')) {
    return <Splash title={t('splash.restoring')} hint={t('splash.restoring.hint')} />;
  }

  if (state.matches('menu')) {
    return (
      <MainMenu
        onCreate={(payload) => send({ type: 'CREATE_GAME', ...payload })}
        onJoin={(payload) => send({ type: 'JOIN_GAME', ...payload })}
        onRefresh={() => send({ type: 'REFRESH_GAMES' })}
        onLoadSave={(saveId) => send({ type: 'LOAD_SAVE', saveId })}
        onRejoin={(gameId) => send({ type: 'REJOIN_GAME', gameId })}
        onSignOut={() => send({ type: 'SIGN_OUT' })}
        // Ввод прошлой попытки: сюда возвращает и неудачное создание партии, и выход
        // из лобби, а меню собирается заново и своего состояния не помнит.
        initial={{
          gameName: state.context.gameName,
          galaxySize: state.context.galaxySize,
          playerName: state.context.playerName,
          homeStarName: state.context.homeStarName,
          raceName: state.context.raceName,
          raceTraits: state.context.raceTraits,
          totalPlayers: state.context.totalPlayers,
          council: state.context.council,
          wardenholdVictory: state.context.wardenholdVictory,
          mightVictory: state.context.mightVictory,
          turnSeconds: state.context.turnSeconds,
        }}
      />
    );
  }

  if (state.matches('creatingGame')) {
    return <Splash title={t('splash.creating')} />;
  }

  if (state.matches('joiningGame')) {
    return <Splash title={t('splash.joining')} />;
  }

  if (state.matches('lobby')) {
    return (
      <Lobby onStart={() => send({ type: 'START_GAME' })} onLeave={() => send({ type: 'LEAVE' })} />
    );
  }

  if (state.matches('startingGame')) {
    return <Splash title={t('splash.starting')} hint={t('splash.starting.hint')} />;
  }

  if (state.matches('loadingSave')) {
    return <Splash title={t('splash.loadingSave')} hint={t('splash.loadingSave.hint')} />;
  }

  if (state.matches('loadingMap')) {
    return <Splash title={t('splash.loadingMap')} />;
  }

  const closeOverlay = () => setOverlay('none');

  /*
    Итоги хода закрыты — и то, что случилось за ход, открывается само. Поверх итогов эти
    экраны не всплывают: сперва игрок читает, что произошло, и только потом смотрит.

    * **Разведанная система** (п. 15) — флот дошёл туда, где ещё не бывали. Ради этого его
      и посылали, а искать по карте, какая из звёзд перестала быть безымянной, игроку негде.
    * **Прорыв в исследованиях** (п. 9) — он освобождает цель, и без новой очки следующего
      хода пропадают. Спросить нужно сейчас, а не ждать, пока игрок заглянет в «Науку».

    Случиться может и то, и другое разом: экран системы ложится под окно исследований
    (порядок в разметке ниже), и закрыв выбор технологии, игрок увидит открытую систему.

    Разведка не отнимает экран у уже открытой системы, и это важно: достроенную
    колониальную базу `refreshAfterTurn` показывает **до** итогов хода, и она ждёт от
    игрока действия — выбора планеты под заселение (п. 4.1). Перебить её видом разведанной
    звезды значило бы потерять требование действия ради сообщения; разведка о себе и
    строкой в итогах хода расскажет.
  */
  // Планета сцены колонии — из карты: по ней рисуется снимок (backlog-promo, пункт 10).
  const shownStory = stories[0];
  const storyPlanet = shownStory?.kind === 'colony' && shownStory.planetId
    ? map?.systems.flatMap((system) => system.planets).find((one) => one.id === shownStory.planetId)
    : undefined;

  const closeResults = (turn: number) => {
    setSeenResultsTurn(turn);

    // Экран разведанной системы откроется сам, когда закроется всё, что идёт впереди
    // него, — бой с чудищем в первую очередь (см. эффект выше).
    const exploredId = exploredSystemId(turnReport);
    if (exploredId && !openedSystem) {
      setExploredPendingId(exploredId);
    }

    if (asksForNextTarget(researchTree, research, turnReport)) {
      setOverlay('research');
    }

    // Кража технологии — п. 13: сцена говорит, ЧТО именно принёс агент; по названию
    // технологии этого не вспомнить, а искать её в дереве после каждой кражи — плохой обмен.
    setTheft(stolenTechnology(turnReport));

    // Редкие события — сценой, а не строкой (backlog-promo, пункт 10): посол со знакомством
    // или войной, отнятая или отбитая колония, наследие Стражей. Встают в очередь за советом
    // и кражей и показываются по одной.
    pushStories(storiesOf(turnReport, players, credentials?.playerId));

    // Совет собрался — это главная новость хода, и о ней рассказывают сценой, а не
    // строкой отчёта (п. 3).
    if (turnReport?.events.some((event) => event.code === 'COUNCIL') && game && credentials) {
      gameApi
        .getCouncil(game.id, credentials.accessToken)
        .then((session) => {
          setCouncil(session ?? null);
          setBallot(awaitsMyVote(session) ? session : null);
        })
        // Молча: о самом голосовании игрок уже прочёл в итогах хода, и отказ в сцене —
        // не повод показывать ошибку поверх карты.
        .catch(() => undefined);
    }
  };

  /*
    Итоги хода всплывают, пока их не закрыли, и только если есть о чём рассказать. Одного
    пополнения казны для этого мало: оно случается каждый ход, и окно ради него всплывало
    бы всегда — см. worthShowing. Достроенное здание, убыточная колония, голодающая
    колония и всё остальное окно открывают.
  */
  const resultsOpen = resultsPending;
  // Встреча, где ход решать этому игроку: остальные ждут соперника и окна не требуют.
  const myEncounter = encounters.find((encounter) => encounter.yourTurn) ?? null;
  /*
    Флот достаётся из списка заново: после конца хода состав меняется, и держать ссылку
    на прежний нельзя. Улетевший или погибший флот закрывает и прицел, и диалог отбора
    сам собой — искать его больше негде.
  */
  const targetedFleet = fleet?.fleets.find((group) => group.id === targetingFleetId) ?? null;
  const orderedFleet = fleet?.fleets.find((group) => group.id === fleetOrder?.fleetId) ?? null;
  /*
    Флот в полёте, чьё окно открыто, — п. 8. Долетевший (или погибший) флот летящим быть
    перестаёт, и окно закрывается само: показывать «0 ходов до цели» у флота, который уже
    стоит в системе, значило бы врать.
  */
  const flyingFleet = fleet?.fleets.find((group) => group.id === flightFleetId) ?? null;
  const landingFleet = fleet?.fleets.find((group) => group.id === landing?.fleetId) ?? null;
  const landingSystem = map?.systems.find((system) => system.id === landingFleet?.starSystemId) ?? null;
  const windowSystem = map?.systems.find((system) => system.id === flyingFleet?.starSystemId) ?? null;

  return (
    <div className="relative h-full w-full overflow-hidden">
      <PhaserGame />
      <Hud
        onLeave={() => send({ type: 'LEAVE' })}
        onLoad={() => setLoadOpen(true)}
        onPreferences={() => setPreferencesOpen(true)}
      />
      <SidePanel
        onEndTurn={() => send({ type: 'END_TURN' })}
        endingTurn={state.matches({ inGame: 'endingTurn' })}
      />
      {/*
        Горячие клавиши карты — п. 11.1: T объявляет ход, C открывает колонии, F4 — родной
        мир. Стоят В ДЕРЕВЕ ПАРТИИ, а не хуком в самом `App`: хук зовётся до всех выходов по
        условию, то есть жил бы и на входе, и в меню, и в лобби, а этот компонент попросту не
        существует, пока карта не открыта. Рисовать ему нечего.
      */}
      <GalaxyHotkeys
        endingTurn={state.matches({ inGame: 'endingTurn' })}
        onEndTurn={() => send({ type: 'END_TURN' })}
      />
      {/*
        Список колоний империи — п. 4.1.1. Стоит выше остальных панелей нижнего меню:
        из него открывается экран колонии, а тот должен лечь поверх списка. При равном z-слое
        выше оказывается тот, кто позже в разметке, — поэтому список идёт до экрана колонии.
      */}
      {overlay === 'colonies' ? <ColoniesScreen onClose={closeOverlay} /> : null}
      {/*
        Окна, всплывающие за итогами хода, показываются ПО ОДНОМУ: сперва совет галактики,
        потом кража, а разведанная система — последней, после боя (эффект выше). Два модальных окна разом спорили бы за Esc
        (см. useModalEscape), а закрыв верхнее, игрок видит следующее.
      */}
      {/*
        Напоминание прячется, пока открыты итоги хода: за ними сцена совета откроется сама,
        а нажатое поверх итогов оно открывало бы вторую модалку под первой — и Esc закрывал
        бы не то окно.
      */}
      {ballot && !council && !resultsOpen ? (
        <button
          type="button"
          className="fixed left-1/2 top-3 z-30 -translate-x-1/2 border border-accent bg-space-900 px-5 py-1 text-13 uppercase tracking-[0.2em] text-accent hover:text-ink-bright"
          onClick={() => setCouncil(ballot)}
        >
          {t('council.ballot.reminder')}
        </button>
      ) : null}
      {council ? (
        <CouncilScreen
          council={council}
          onClose={() => setCouncil(null)}
          onVoted={(session) => setBallot(awaitsMyVote(session) ? session : null)}
        />
      ) : theft ? (
        <TechStolenScene theft={theft} tree={researchTree} onClose={() => setTheft(null)} />
      ) : stories.length > 0 ? (
        <StoryScene
          story={stories[0]}
          planet={storyPlanet}
          tree={researchTree}
          onClose={shiftStory}
        />
      ) : null}
      <SystemScreen />
      {/* Экран колонии ложится поверх экрана системы и списка колоний — п. 4.1. */}
      <ColonyScreen />
      {overlay === 'planets' ? <PlanetsScreen onClose={closeOverlay} /> : null}
      {overlay === 'fleet' ? <FleetOperationsScreen onClose={closeOverlay} /> : null}
      {overlay === 'leaders' ? <LeadersScreen onClose={closeOverlay} /> : null}
      {overlay === 'races' ? <RaceRelationsScreen onClose={closeOverlay} /> : null}
      {overlay === 'info' ? <InfoScreen onClose={closeOverlay} /> : null}
      {overlay === 'research' ? <ResearchScreen onClose={closeOverlay} /> : null}
      {overlay === 'diplomacy' ? <AudienceScreen onClose={closeOverlay} /> : null}

      {/*
        Подсказки обучающей партии — п. 3. Стоят НИЖЕ всех окон и модальными не являются:
        обучение ведут рядом с игрой, а не вместо неё. Карточка сама решает, показываться
        ли, — по тому, та ли это партия, которую завело обучение.
      */}
      <TutorialHints />
      {/*
        Итоги хода и встречи флотов лежат поверх всего и по одному за раз: сперва игрок
        читает, что случилось за ход (п. 11.1), и только потом решает по флотам (п. 8).
        Иначе два модальных окна спорили бы за Esc — см. useModalEscape.
      */}
      {resultsOpen && turnReport ? (
        <TurnResults report={turnReport} onClose={() => closeResults(turnReport.turn)} />
      ) : null}
      {!resultsOpen && !battle && !activeBattle && myEncounter ? (
        <EncounterDialog key={myEncounter.id} encounter={myEncounter} />
      ) : null}
      {/*
        Тактический бой — п. 8: живое поле, на котором корабли ходят по инициативе.
        Лежит поверх итога «авто» боя: если идёт настоящий бой, итог считать нечему.
      */}
      {activeBattle ? (
        <BattleScreen
          key={activeBattle.id}
          battle={activeBattle}
          onClose={() => setActiveBattle(null)}
        />
      ) : null}
      {/*
        Отправка флота — п. 8, порядок MOO II: нажатие по значку флота включает прицел
        (подсказка внизу), выбранная на карте звезда открывает отбор кораблей. Диалог
        лежит поверх карты, но ниже боёв и итогов хода: те требуют решения немедленно.
      */}
      {/*
        Флоту в пути прицел включает кнопка ALL его окна, и подсказку тогда показывает само
        окно: общая полоса легла бы поверх него.
      */}
      {targetedFleet && targetedFleet.id !== flyingFleet?.id && !resultsOpen && !activeBattle ? (
        <FleetTargetingHint fleetName={t('fleet.targeting.name', { system: targetedFleet.systemName })} />
      ) : null}
      {/*
        Флот в полёте — п. 8, сцена оригинала «2 turns to Draconis». Лежит поверх карты, но
        ниже итогов хода и боя: те требуют решения немедленно.
      */}
      {flyingFleet && !resultsOpen && !activeBattle ? (
        <FleetInFlightWindow
          fleet={flyingFleet}
          course={flightFleet?.course}
          onClose={() => {
            setFlightFleet(null);
            setTargetingFleet(null);
          }}
          onColonize={canColonizeHere(flyingFleet, windowSystem)
            ? () => setLanding({ fleetId: flyingFleet.id, offer: false })
            : undefined}
        />
      ) : null}
      {landing && landingFleet && landingSystem && game && credentials && !resultsOpen && !activeBattle ? (
        <FleetLandingDialog
          fleet={landingFleet}
          system={landingSystem}
          gameId={game.id}
          accessToken={credentials.accessToken}
          ownerPlayerId={credentials.playerId}
          telepathic={fleet?.telepathic ?? false}
          offer={landing.offer}
          onClose={() => setLanding(null)}
          onDone={() => {
            // Высадка меняет и флот, и карту: колония появляется на планете, корабль уходит
            // из состава (п. 4.1). Перечитываем оба, иначе окно флота и карта отстанут.
            setLanding(null);
            void Promise.all([
              gameApi.getFleet(game.id, credentials.accessToken),
              gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
            ]).then(([freshFleet, freshMap]) => {
              setEmpire(espionage, freshFleet);
              setMap(freshMap);
            }).catch(() => undefined);
          }}
        />
      ) : null}
      {orderedFleet && fleetOrder ? (
        <FleetDialog
          fleet={orderedFleet}
          targetSystemId={fleetOrder.targetSystemId}
          onClose={() => setFleetOrder(null)}
        />
      ) : null}

      {/*
        Настройки игрока — п. 11.1: раскладка горячих клавиш. Экран поверх всего, потому
        что он модальный, и ОДИН на оба входа — из меню «Игра» и из главного меню.
      */}
      {preferencesOpen ? <PreferencesScreen onClose={() => setPreferencesOpen(false)} /> : null}

      {/* Список сохранений — п. 3: окно поверх карты, как и всё прочее модальное. */}
      {loadOpen ? (
        <LoadGameDialog
          onLoad={(saveId) => {
            setLoadOpen(false);
            send({ type: 'LOAD_SAVE', saveId });
          }}
          onClose={() => setLoadOpen(false)}
        />
      ) : null}

      {/* Итог боя, посчитанного «авто» (п. 8): сцены в нём нет, только исход. */}
      {!activeBattle && battle ? (
        <BattleScene
          encounter={battle.encounter}
          before={battle.before}
          onClose={() => setBattle(null)}
        />
      ) : null}
    </div>
  );
}

/** Совет открыт и ждёт голоса этого игрока — п. 3. */
function awaitsMyVote(session: Council | null): boolean {
  return !!session && session.open && session.ballot
    && session.voters.some((voter) => voter.yours && voter.pending);
}
