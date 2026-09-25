import { useEffect, useState } from 'react';
import { useMachine } from '@xstate/react';
import type { Council, StarSystem } from './api/types';
import { gameApi } from './api/client';
import { appMachine } from './state/appMachine';
import { useGameStore } from './state/gameStore';
import { PhaserGame } from './game/PhaserGame';
import { MainMenu } from './ui/MainMenu';
import { Lobby } from './ui/Lobby';
import { Hud } from './ui/Hud';
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
import { TurnResults, exploredSystemId, worthShowing } from './ui/TurnResults';
import { ColoniesScreen } from './ui/colonies/ColoniesScreen';
import { ColonyScreen } from './ui/system/ColonyScreen';
import { SystemScreen } from './ui/system/SystemScreen';
import { SystemDiscoveryDialog } from './ui/system/SystemDiscoveryDialog';
import { CouncilScreen } from './ui/council/CouncilScreen';
import { TechStolenScene, stolenTechnology, type Theft } from './ui/espionage/TechStolenScene';
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
    Разведанная система, в которой что-то НАШЛОСЬ, — п. 4.1. Живёт в самом экране, а не в
    хранилище: окно показывается один раз, по горячим следам хода, и пережить перезагрузку
    страницы ему незачем — находка никуда с планеты не денется.
  */
  const [discovered, setDiscovered] = useState<StarSystem | null>(null);
  /*
    Выборы Высшего совета — п. 3. Сцена показывается по горячим следам хода, на котором
    совет собрался: голосование уже посчитано и записано на сервере, а экран просит его
    отдельным запросом — в карте его нет и быть не может.
  */
  const [council, setCouncil] = useState<Council | null>(null);
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
  const setFleetOrder = useGameStore((store) => store.setFleetOrder);

  /**
   * Итоги хода показываются один раз на ход: закрыл — до следующего хода не всплывают.
   * Номер прочитанного хода держим здесь, а не в хранилище: это состояние окна, а не партии.
   */
  const [seenResultsTurn, setSeenResultsTurn] = useState<number | null>(null);

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
  const closeResults = (turn: number) => {
    setSeenResultsTurn(turn);

    const exploredId = exploredSystemId(turnReport);
    const explored = map?.systems.find((system) => system.id === exploredId);
    if (explored && !openedSystem) {
      /*
        Разведанная система сперва показывается окном разведки, а её экран открывается за
        ним — так же, как в оригинале, где «Scouts arrive at…» стоит перед видом системы.
        Окно открывается на ЛЮБУЮ систему: нашлась находка — покажет её, не нашлось —
        схему самой системы (`docs/moo2/system-discovery-empty.png`). Порядок здесь, а не
        в разметке, нарочно: два модальных окна разом спорили бы за Esc, а окно, открытое
        ПОСЛЕ, ни с чем спорить не может.
      */
      setDiscovered(explored);
    }

    if (asksForNextTarget(researchTree, research, turnReport)) {
      setOverlay('research');
    }

    // Кража технологии — п. 13: сцена говорит, ЧТО именно принёс агент; по названию
    // технологии этого не вспомнить, а искать её в дереве после каждой кражи — плохой обмен.
    setTheft(stolenTechnology(turnReport));

    // Совет собрался — это главная новость хода, и о ней рассказывают сценой, а не
    // строкой отчёта (п. 3).
    if (turnReport?.events.some((event) => event.code === 'COUNCIL') && game && credentials) {
      gameApi
        .getCouncil(game.id, credentials.accessToken)
        .then((session) => setCouncil(session ?? null))
        // Молча: о самом голосовании игрок уже прочёл в итогах хода, и отказ в сцене —
        // не повод показывать ошибку поверх карты.
        .catch(() => undefined);
    }
  };

  /** Окно разведки закрыто — теперь можно посмотреть на саму систему. */
  const closeDiscovery = () => {
    const explored = discovered;
    setDiscovered(null);
    if (explored && !openedSystem) {
      setOpenedSystem(explored);
    }
  };

  /*
    Итоги хода всплывают, пока их не закрыли, и только если есть о чём рассказать. Одного
    пополнения казны для этого мало: оно случается каждый ход, и окно ради него всплывало
    бы всегда — см. worthShowing. Достроенное здание, убыточная колония, голодающая
    колония и всё остальное окно открывают.
  */
  const resultsOpen =
    turnReport !== null && worthShowing(turnReport) && seenResultsTurn !== turnReport.turn;
  // Встреча, где ход решать этому игроку: остальные ждут соперника и окна не требуют.
  const myEncounter = encounters.find((encounter) => encounter.yourTurn) ?? null;
  /*
    Флот достаётся из списка заново: после конца хода состав меняется, и держать ссылку
    на прежний нельзя. Улетевший или погибший флот закрывает и прицел, и диалог отбора
    сам собой — искать его больше негде.
  */
  const targetedFleet = fleet?.fleets.find((group) => group.id === targetingFleetId) ?? null;
  const orderedFleet = fleet?.fleets.find((group) => group.id === fleetOrder?.fleetId) ?? null;

  return (
    <div className="relative h-full w-full overflow-hidden">
      <PhaserGame />
      <Hud onLeave={() => send({ type: 'LEAVE' })} onLoadSave={(saveId) => send({ type: 'LOAD_SAVE', saveId })} />
      <SidePanel
        onEndTurn={() => send({ type: 'END_TURN' })}
        endingTurn={state.matches({ inGame: 'endingTurn' })}
      />
      {/*
        Список колоний империи — п. 4.1.1. Стоит выше остальных панелей нижнего меню:
        из него открывается экран колонии, а тот должен лечь поверх списка. При равном z-слое
        выше оказывается тот, кто позже в разметке, — поэтому список идёт до экрана колонии.
      */}
      {overlay === 'colonies' ? <ColoniesScreen onClose={closeOverlay} /> : null}
      {/*
        Окна, всплывающие за итогами хода, показываются ПО ОДНОМУ: сперва совет галактики,
        потом кража, потом разведанная система. Два модальных окна разом спорили бы за Esc
        (см. useModalEscape), а закрыв верхнее, игрок видит следующее.
      */}
      {council ? (
        <CouncilScreen council={council} onClose={() => setCouncil(null)} />
      ) : theft ? (
        <TechStolenScene theft={theft} tree={researchTree} onClose={() => setTheft(null)} />
      ) : discovered ? (
        <SystemDiscoveryDialog system={discovered} onClose={closeDiscovery} />
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
      {targetedFleet && !resultsOpen && !activeBattle ? (
        <FleetTargetingHint fleetName={t('fleet.targeting.name', { system: targetedFleet.systemName })} />
      ) : null}
      {orderedFleet && fleetOrder ? (
        <FleetDialog
          fleet={orderedFleet}
          targetSystemId={fleetOrder.targetSystemId}
          onClose={() => setFleetOrder(null)}
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
