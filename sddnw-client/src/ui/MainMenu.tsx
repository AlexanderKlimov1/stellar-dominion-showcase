import { useEffect, useState } from 'react';
import { useGameStore } from '../state/gameStore';
import type { MenuScreen } from '../state/session';
import { readAccount, readMenuScreen, takeGuestPlay, writeMenuScreen } from '../state/session';
import type { GameSummary } from '../api/types';
import { LoadGameDialog } from './LoadGameDialog';
import { gameApi } from '../api/client';
import type { DemoBattle } from '../api/types';
import { BattleScreen } from './battle/BattleScreen';
import { BalanceRunScreen } from './balance/BalanceRunScreen';
import { OrionometerScreen } from './orionometer/OrionometerScreen';
import { MilkyWayScreen } from './milkyway/MilkyWayScreen';
import { RegistrationRequestsScreen } from './auth/RegistrationRequestsScreen';
import { RaceCostEditor } from './race/RaceCostEditor';
import { RaceSetupFlow, type PlayerSetup } from './race/RaceSetupFlow';
import type { BannerCode } from './race/banners';
import { LocaleSwitch } from './LocaleSwitch';
import { NewGameScreen } from './newgame/NewGameScreen';
import { PreferencesScreen } from './preferences/PreferencesScreen';
import { BuildTemplateScreen } from './build/BuildTemplateScreen';
import { TextScaleSwitch } from './TextScaleSwitch';
import { tf, useT } from '../i18n';
import { Figure } from './Figure';
import { ACCENTS, figureLabelClass } from './accent';

interface MainMenuProps {
  onCreate: (payload: {
    gameName: string;
    galaxySize: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    /** Код готовой расы MOO II — п. 5; пусто — раса достанется по разнарядке. */
    raceCode?: string;
    /** Знамя — цвет империи, п. 3.2; пусто — знамя расы. */
    banner?: BannerCode;
    /** Идут ли в партии случайные галактические события — п. 11.1. */
    galacticEvents: boolean;
    /** Сколько всего империй в партии, считая свою, — п. 3. */
    totalPlayers: number;
    /** Пути к победе — п. 3: окно новой игры включает их по умолчанию. */
    council?: boolean;
    wardenholdVictory?: boolean;
    mightVictory?: boolean;
    /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
    turnSeconds?: number;
    /** Партию заводит обучение: она стартует сама и идёт с подсказками — п. 3. */
    tutorial?: boolean;
    /** Партию заводит гость: стартует сама, минуя лобби — backlog-promo, пункт 1. */
    guest?: boolean;
  }) => void;
  onJoin: (payload: {
    gameId: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    /** Код готовой расы MOO II — п. 5; пусто — раса достанется по разнарядке. */
    raceCode?: string;
    /** Знамя — цвет империи, п. 3.2. */
    banner?: BannerCode;
  }) => void;
  onRefresh: () => void;
  onLoadSave: (saveId: string) => void;
  onRejoin: (gameId: string) => void;
  /** Выход из учётной записи — п. 3.1: возвращает к форме входа. */
  onSignOut: () => void;
  /**
   * Ввод прошлой попытки: имя игрока, родная звезда, собранная раса, название партии
   * и размер галактики.
   *
   * Меню разбирается и собирается заново на каждый заход в него, поэтому своё состояние
   * оно пережить не может: неудачная попытка создать партию возвращает игрока сюда, и без
   * этих значений собранная раса пропадала бы вместе с ней. Значения живут в машине
   * экранов — она переживает смену экранов, а меню нет.
   */
  initial: {
    gameName: string;
    galaxySize: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    totalPlayers: number;
    council: boolean;
    wardenholdVictory: boolean;
    mightVictory: boolean;
    turnSeconds?: number;
  };
}

/** Что открыл игрок: свою партию или чужую из списка. */
type SetupTarget = { kind: 'create' } | { kind: 'join'; game: GameSummary };

/**
 * Главное меню — п. 11.1: только текстовые ссылки.
 *
 * Здесь же выбирается размер галактики перед стартом (п. 3): по умолчанию Huge,
 * значение приходит из справочника сервера.
 *
 * Имя игрока, название его родной звезды и собранная им раса (п. 7) спрашиваются экраном
 * выбора расы — и на входе в свою партию, и на входе в чужую: это настройки участника,
 * а не галактики. Экран занимает окно целиком, как окно race picks в MOO II. Последние
 * введённые значения меню помнит и подставляет при следующем открытии.
 */
export function MainMenu({
  onCreate,
  onJoin,
  onRefresh,
  onLoadSave,
  onRejoin,
  onSignOut,
  initial,
}: MainMenuProps) {
  const { t } = useT();
  // Кто вошёл — п. 3.1: имя из учётной записи, а не из настроек партии.
  const account = readAccount()?.account ?? null;
  /*
    ГОСТЬ — backlog-promo, пункт 1. Меню у него короче: одна своя партия с настройками,
    которые назначает сервер, и ни чужих партий, ни сохранений — сервер их гостю и так не
    отдаст, а ссылка, отвечающая отказом, читается как поломка.
  */
  const guest = account?.role === 'GUEST';
  /**
   * Выход гостя спрашивает подтверждения вторым нажатием: ключ от гостевой записи — только
   * пропуск в этом браузере, и выход теряет её вместе с партиями навсегда.
   */
  const [guestLeaving, setGuestLeaving] = useState(false);
  const signOut = () => {
    if (guest && !guestLeaving) {
      setGuestLeaving(true);
      return;
    }
    onSignOut();
  };
  const galaxySizes = useGameStore((state) => state.galaxySizes);
  const openGames = useGameStore((state) => state.openGames);
  const myGames = useGameStore((state) => state.myGames);
  const lastError = useGameStore((state) => state.lastError);

  // Запасное значение нужно только до загрузки справочника: умолчание называет сервер.
  const defaultSize = galaxySizes.find((size) => size.defaultChoice)?.code ?? 'SMALL';
  const [playerName, setPlayerName] = useState(initial.playerName || t('menu.player.default'));
  const [homeStarName, setHomeStarName] = useState(initial.homeStarName);
  const [raceName, setRaceName] = useState(initial.raceName);
  const [raceTraits, setRaceTraits] = useState<string[]>(initial.raceTraits);
  const [gameName, setGameName] = useState(initial.gameName);
  const [galaxySize, setGalaxySize] = useState(initial.galaxySize || defaultSize);
  // Случайные события — п. 11.1: в MOO II их выключают в окне новой игры, и здесь тоже.
  const [galacticEvents, setGalacticEvents] = useState(true);
  // Сколько империй в партии — п. 3: там же, в окне новой игры.
  const [totalPlayers, setTotalPlayers] = useState(initial.totalPlayers);
  // Пути к победе — п. 3, там же, в окне новой игры, и включены по умолчанию.
  const [council, setCouncil] = useState(initial.council);
  const [wardenholdVictory, setWardenholdVictory] = useState(initial.wardenholdVictory);
  const [mightVictory, setMightVictory] = useState(initial.mightVictory);
  const [turnSeconds, setTurnSeconds] = useState<number | undefined>(initial.turnSeconds);
  /*
    ОТКРЫТЫЙ ЭКРАН ПОМНИТСЯ МЕЖДУ ПЕРЕСБОРКАМИ ДЕРЕВА — state/session.ts.

    Было пять булевых флагов в состоянии самого меню, и жили они ровно до следующей
    перерисовки всего приложения: перезагрузка страницы, горячая замена модуля у
    dev-сервера, возврат машины экранов в `menu` — и пульт закрывался сам, выбрасывая
    хозяина на первый экран. За прогоном сидят часами, и это потеря работы, а не мелочь.
    Экраны эти и так открываются по одному, поэтому вместо пяти флагов одно поле.
  */
  const [screen, setScreenState] = useState<MenuScreen>(() => readMenuScreen());
  const setScreen = (next: MenuScreen) => {
    setScreenState(next);
    writeMenuScreen(next);
  };
  const loadOpen = screen === 'saves';
  const costsOpen = screen === 'costs';
  const accountsOpen = screen === 'accounts';
  /**
   * Сколько регистраций ждут подтверждения — п. 3.1: число рядом со ссылкой.
   *
   * Спрашивается только у администратора и только в меню: остальным список не отдадут
   * вовсе, а без числа ссылка ничем не отличается от прочих, и застрявшая регистрация
   * ждала бы, пока о ней вспомнят. Перечитывается при закрытии страницы — подтверждённая
   * запись должна уходить из счётчика сразу.
   */
  const [waitingAccounts, setWaitingAccounts] = useState(0);
  const milkyWayOpen = screen === 'milkyway';
  const newGameOpen = screen === 'newgame';
  const balanceOpen = screen === 'balance';
  const orionometerOpen = screen === 'orionometer';
  const preferencesOpen = screen === 'preferences';
  const templatesOpen = screen === 'templates';
  const [demo, setDemo] = useState<DemoBattle | null>(null);
  const [demoBusy, setDemoBusy] = useState(false);
  const [setup, setSetup] = useState<SetupTarget | null>(null);
  /*
    «Играть» с экрана входа ведёт сразу к выбору расы (backlog-promo, пункт 19): гость
    нажимал «играть» и попадал в меню, где нужно было нажать «новая партия» ещё раз — лишний
    шаг ровно там, где новичок решает, остаться ли. Метку ставит экран входа
    (`GUEST_PLAY_KEY`) и только на этот переход: вернувшийся в меню из партии видит меню.
  */
  useEffect(() => {
    if (guest && takeGuestPlay()) {
      setSetup({ kind: 'create' });
    }
  }, [guest]);
  // Знамя — п. 3.2: последнее выбранное подставляется при следующем входе в партию.
  const [banner, setBanner] = useState<BannerCode | null>(null);
  /*
    Цвета тех, кто уже сидит в чужой партии: их знамёна на экране выбора гаснут. Список
    открытых игр состава не несёт, поэтому он берётся запросом в миг входа — а на создание
    своей партии занятых знамён нет вовсе.
  */
  const [takenColors, setTakenColors] = useState<string[]>([]);
  useEffect(() => {
    if (setup?.kind !== 'join') {
      setTakenColors([]);
      return;
    }
    let alive = true;
    gameApi
      .getGame(setup.game.id)
      .then((details) => {
        if (alive) {
          setTakenColors(details.players.map((player) => player.color).filter(Boolean));
        }
      })
      // Не узнали — экран покажет все знамёна, а занятое отклонит сервер словами.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [setup]);

  // Счётчик запросов: пока страница открыта, его не трогаем — она сама показывает список.
  useEffect(() => {
    if (account?.role !== 'ADMIN' || accountsOpen) {
      return;
    }
    let cancelled = false;
    gameApi
      .listAccounts()
      .then((accounts) => {
        if (!cancelled) {
          setWaitingAccounts(accounts.filter((entry) => !entry.confirmed).length);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [account?.role, accountsOpen]);

  const confirmSetup = (target: SetupTarget, value: PlayerSetup) => {
    setPlayerName(value.playerName);
    setHomeStarName(value.homeStarName);
    setRaceName(value.raceName);
    setRaceTraits(value.raceTraits);
    setBanner(value.banner);
    setSetup(null);

    if (target.kind === 'create') {
      // Гостю настройки назначает сервер (`GuestGameRules`); клиент шлёт те же, чтобы
      // экран и партия говорили об одном и том же, а не полагались на подмену.
      onCreate(guest
        ? { gameName: gameName.trim(), galaxySize: 'SMALL', galacticEvents: true, totalPlayers: 4,
            council: true, wardenholdVictory: true, mightVictory: true, guest: true, ...value }
        : { gameName: gameName.trim(), galaxySize, galacticEvents, totalPlayers,
            council, wardenholdVictory, mightVictory, turnSeconds, ...value });
      return;
    }
    onJoin({ gameId: target.game.id, ...value });
  };

  return (
    /*
      Прокрутка, а не обрезка: у страницы `overflow: hidden` (карта галактики не должна
      уезжать под курсором), поэтому меню обязано прокручиваться само. Раньше оно было
      выровнено по центру без прокрутки — на невысоком окне список открытых игр уходил
      за край экрана, и добраться до него было нечем.

      Центрирование при этом осталось: `my-auto` у содержимого центрирует его, пока оно
      помещается, и схлопывается в ноль, когда не помещается, — тогда экран прокручивается
      от самого верха. Одним `items-center` так не выходит: верх содержимого срезается и
      прокруткой не достаётся.
    */
    <div className="relative flex h-full justify-center overflow-y-auto p-8">
      {loadOpen ? (
        <LoadGameDialog
          onLoad={(saveId) => {
            setScreen(null);
            onLoadSave(saveId);
          }}
          onClose={() => setScreen(null)}
        />
      ) : null}

      {/*
        Экраны администратора заперты и НА ОТРИСОВКЕ, а не только ссылкой: меню помнит
        открытый экран между перезагрузками (state/session.ts), и спрятанная ссылка дверь
        не закрывает. Редактор цен до сих пор был виден всем — у кого он запомнился
        открытым, тот увидел бы его снова и без ссылки.
      */}
      {costsOpen && account?.role === 'ADMIN' ? (
        <RaceCostEditor onClose={() => setScreen(null)} />
      ) : null}
      {/*
        Запросы на регистрацию — п. 3.1: страница администратора. Открывается из двух мест
        (ссылка при имени вошедшего и ссылка в списке до партии), но экран один: действие
        там одно и то же, и второй такой же список разъезжался бы с первым.
      */}
      {accountsOpen ? (
        <RegistrationRequestsScreen onClose={() => setScreen(null)} />
      ) : null}
      {/* Демонстрационная сцена: трёхмерная схема Млечного Пути на сто элементов. */}
      {milkyWayOpen ? <MilkyWayScreen onClose={() => setScreen(null)} /> : null}
      {/*
        Окно новой игры — п. 3: настройки партии спрашиваются до выбора расы, как в
        MOO II, где New Game Menu стоит перед Race Selection.
      */}
      {newGameOpen ? (
        <NewGameScreen
          gameName={gameName}
          galaxySize={galaxySize}
          totalPlayers={totalPlayers}
          galacticEvents={galacticEvents}
          council={council}
          wardenholdVictory={wardenholdVictory}
          mightVictory={mightVictory}
          turnSeconds={turnSeconds}
          onTurnSeconds={setTurnSeconds}
          onGameName={setGameName}
          onGalaxySize={setGalaxySize}
          onTotalPlayers={setTotalPlayers}
          onGalacticEvents={setGalacticEvents}
          onCouncil={setCouncil}
          onWardenholdVictory={setWardenholdVictory}
          onMightVictory={setMightVictory}
          onAccept={() => {
            setScreen(null);
            setSetup({ kind: 'create' });
          }}
          onCancel={() => setScreen(null)}
        />
      ) : null}
      {balanceOpen && account?.role === 'ADMIN' ? (
        <BalanceRunScreen onClose={() => setScreen(null)} />
      ) : null}
      {orionometerOpen && account?.role === 'ADMIN' ? (
        <OrionometerScreen onClose={() => setScreen(null)} />
      ) : null}
      {/*
        Настройки игрока — п. 11.1: раскладка горячих клавиш. Экран ОДИН на оба входа — из
        меню и из партии: настройка там и там одна и та же, а второй такой же экран
        разъехался бы с первым.
      */}
      {preferencesOpen ? <PreferencesScreen onClose={() => setScreen(null)} /> : null}
      {templatesOpen ? <BuildTemplateScreen onClose={() => setScreen(null)} /> : null}

      {/* Демонстрация боя — п. 8: та же сцена, что и в партии, но ведёт её сервер. */}
      {demo ? (
        <BattleScreen battle={demo.battle} demo={demo} onClose={() => setDemo(null)} />
      ) : null}

      {setup ? (
        <RaceSetupFlow
          title={setup.kind === 'create' ? t('menu.setup.new') : t('menu.setup.join', { name: setup.game.name })}
          confirmLabel={setup.kind === 'create' ? t('menu.setup.create') : t('menu.setup.confirmJoin')}
          playerName={playerName}
          homeStarName={homeStarName}
          raceName={raceName}
          raceTraits={raceTraits}
          banner={banner}
          takenColors={takenColors}
          onConfirm={(value) => confirmSetup(setup, value)}
          onClose={() => setSetup(null)}
        />
      ) : null}
      <div className="my-auto grid w-full max-w-5xl grid-cols-1 gap-6 lg:grid-cols-2">
        <header className="lg:col-span-2 flex items-baseline justify-between gap-4">
          <div>
            <h1 className="text-2xl tracking-[0.3em] text-accent">{t('app.name')}</h1>
            <p className="mt-1 text-xs uppercase tracking-[0.35em] text-ink-dim">
              {t('app.tagline')}
            </p>
          </div>
          {/* Кто вошёл и выход — п. 3.1: без этого сменить учётную запись было бы нечем. */}
          {account ? (
            <div className="text-right text-11 text-ink-dim">
              <div>
                {guest ? t('account.guest.name', { n: account.name }) : account.name || account.login}
                {/*
                  Роль переводится здесь, а не берётся из roleLabel: тот пришёл на языке
                  запроса входа и лежит в localStorage — переключение языка его бы не тронуло.
                */}
                {/* У гостя роль уже названа в имени («Гость 4821») — второй раз незачем. */}
                {guest ? null : (
                  <span className="ml-2 text-ink-faint">
                    {t(account.role === 'ADMIN' ? 'account.role.ADMIN' : 'account.role.PLAYER')}
                  </span>
                )}
              </div>
              <div className="flex justify-end gap-3">
                {/*
                  Учётные записи — только администратору (п. 3.1): подтвердить застрявшую
                  регистрацию больше некому, если почта не работает вовсе.
                */}
                {account.role === 'ADMIN' ? (
                  <button
                    type="button"
                    className={`link ${ACCENTS.note.type}`}
                    onClick={() => setScreen('accounts')}
                  >
                    {t('menu.accounts')}
                    {waitingAccounts > 0 ? (
                      <Figure accent="note" tone="warn" className="ml-1">{waitingAccounts}</Figure>
                    ) : null}
                  </button>
                ) : null}
                <button type="button" className={`link text-11${guestLeaving ? ' text-warn' : ''}`}
                        onClick={signOut}>
                  {guestLeaving ? t('menu.guest.signOut.confirm') : t('menu.signOut')}
                </button>
                <LocaleSwitch />
                <TextScaleSwitch />
              </div>
            </div>
          ) : null}
        </header>

        {lastError ? (
          <div className="border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger lg:col-span-2">
            {lastError}
          </div>
        ) : null}

        {/* Новая игра */}
        <section className="panel">
          <div className="panel-title">{t('menu.newGame')}</div>

          {/*
            Гостевая партия: окна новой игры гость не видит — настройки назначены, — и
            сразу выбирает расу. Строкой под ссылкой сказано, на чём партия кончается:
            цель, которую видно с первого хода, — то, что держит в короткой партии.
          */}
          {guest ? (
            <>
              <button type="button" className="link text-accent" onClick={() => setSetup({ kind: 'create' })}>
                ▸ {t('menu.guest.create')}
              </button>
              <p className="mb-3 mt-1 text-11 text-ink-faint">{t('menu.guest.rules')}</p>
            </>
          ) : null}

          {/*
            Настройки партии — название, размер галактики, число империй и случайные
            события — спрашивает ОКНО НОВОЙ ИГРЫ (п. 3), а не меню. Так в MOO II: меню
            предлагает начать, а New Game Menu спрашивает, какую. Здесь они занимали
            пол-колонки списком и абзацем пояснений, ради которых игрок листал меню.
          */}
          {/*
            ОБУЧЕНИЕ — п. 3, и оно стоит ПЕРВЫМ пунктом меню нарочно.

            Игра ничего не объясняет сама: новичок, нажавший «создать игру», попадает на
            карту из восьмидесяти одинаковых точек и закрывает вкладку (найдено проходом
            по пути новичка, 26.09.2026). Обучение — единственный вход, который ведёт за
            руку, и пропустить его мимо глаз не должно быть проще, чем найти.

            Оно НИЧЕГО НЕ СПРАШИВАЕТ: ни галактики, ни расы, ни имени. Три экрана вопросов
            перед первой партией — это три возможности уйти, ни разу не увидев игру.
            Настройки назначены и объяснены строкой под ссылкой: малая галактика, один
            соперник. События и совет выключены — случайная беда и внезапные выборы
            посреди обучения учат только тому, что игра непонятна.
          */}
          {/*
            Обучение, окно новой игры и сохранения гостю не показываются: сервер всё равно
            подменил бы обучение гостевой партией на четверых, а сохранений гостю не отдаст.
          */}
          {guest ? null : (
          <>
          <button
            type="button"
            className="link text-accent"
            onClick={() =>
              onCreate({
                gameName: t('menu.tutorial'),
                galaxySize: 'SMALL',
                playerName: initial.playerName || t('menu.player.default'),
                homeStarName: '',
                raceName: '',
                raceTraits: [],
                // Земляне: раса без перекосов, и первую партию новичок играет ею, а не
                // тем, что достанется по разнарядке.
                raceCode: 'HUMANS',
                galacticEvents: false,
                totalPlayers: 2,
                tutorial: true,
              })
            }
          >
            ▸ {t('menu.tutorial')}
          </button>
          <p className="mb-3 mt-1 text-11 text-ink-faint">{t('menu.tutorial.hint')}</p>

          {/* Настройки партии спрашивает окно новой игры, а расу — экран за ним. */}
          <button type="button" className="link text-accent" onClick={() => setScreen('newgame')}>
            ▸ {t('menu.create')}
          </button>

          {/* Сохранения лежат на сервере, поэтому список доступен и до входа в партию. */}
          <button
            type="button"
            className="link mt-3 block text-xs"
            onClick={() => setScreen('saves')}
          >
            ▸ {t('menu.load')}
          </button>
          </>
          )}

          {/*
            Цены сторон расы правятся до партии и общие для всех: справочник живёт на
            сервере, поэтому и ссылка стоит здесь, а не внутри конструктора расы — п. 7.

            ТОЛЬКО АДМИНИСТРАТОРУ, как пульт балансировки рядом. Запись справочника сервер
            обычному игроку и так запрещает (`requireAdmin`), так что ссылка не была
            опасна — она просто ругалась: игрок открывал редактор, правил цену и получал
            отказ. В меню игры это выглядит поломкой (трек техдолга, пункт 6).
          */}
          {account?.role === 'ADMIN' ? (
            <button
              type="button"
              className="link mt-2 block text-xs"
              onClick={() => setScreen('costs')}
            >
              ▸ {t('menu.costs')}
            </button>
          ) : null}

          {/*
            Демонстрационный бой — п. 8: две эскадры из разных кораблей сходятся сами.
            Ссылка стоит здесь, рядом со справочником: и то и другое смотрят до партии.
          */}
          <button
            type="button"
            className="link mt-2 block text-xs"
            disabled={demoBusy}
            onClick={() => {
              setDemoBusy(true);
              gameApi.reference
                .startDemoBattle()
                .then(setDemo)
                .finally(() => setDemoBusy(false));
            }}
          >
            ▸ {demoBusy ? t('menu.demo.busy') : t('menu.demo')}
          </button>

          {/*
            Схема Млечного Пути — такая же демонстрация, как и бой, и стоит рядом с ним.
            К партии она отношения не имеет: галактику игры генерирует сервер, а здесь — настоящая
            Галактика в ста точках, и вертеть её можно до всякой игры.
          */}
          {/* Меню гостя — без лишнего (backlog-promo, пункт 23): схема Млечного Пути и шаблоны
              стройки — для тех, кто уже играет, а новичка они уводят от единственного нужного
              действия — новой партии. Зарегистрированному они остаются. */}
          {guest ? null : (
          <button
            type="button"
            className="link mt-2 block text-xs"
            onClick={() => setScreen('milkyway')}
          >
            ▸ {t('menu.milkyway')}
          </button>
          )}

          {/*
            Настройки игрока — п. 11.1: раскладка горячих клавиш. Стоят в общем списке, а не
            только в меню «Игра»: клавиши смотрят и до партии — чтобы знать, что нажимать, —
            а запись, в которой они живут, существует и без всякой партии.
          */}
          <button
            type="button"
            className="link mt-2 block text-xs"
            onClick={() => setScreen('preferences')}
          >
            ▸ {t('menu.preferences')}
          </button>

          {/*
            Шаблоны стройки — п. 10. Стоят здесь по той же причине, что и настройки: шаблон
            принадлежит УЧЁТНОЙ ЗАПИСИ, а не партии, и пишут его чаще всего между партиями —
            когда вспомнили, чего не хватало в прошлой.
          */}
          {guest ? null : (
          <button
            type="button"
            className="link mt-2 block text-xs"
            onClick={() => setScreen('templates')}
          >
            ▸ {t('menu.templates')}
          </button>
          )}

          {/*
            Запросы на регистрацию — п. 3.1, и только администратору: подтвердить
            застрявшую регистрацию больше некому, когда почта не работает вовсе. Ссылка
            стоит здесь, в общем списке, а не только при имени вошедшего: то, что ждёт
            решения, должно попадаться на глаза само.
          */}
          {account?.role === 'ADMIN' ? (
            <button
              type="button"
              className={`link mt-2 block ${ACCENTS.note.type}`}
              onClick={() => setScreen('accounts')}
            >
              ▸ {t('menu.requests')}
              {waitingAccounts > 0 ? (
                <Figure accent="note" tone="warn" className="ml-2">{waitingAccounts}</Figure>
              ) : null}
            </button>
          ) : null}

          {/*
            Пульт балансировки — этап 2 плана, и тоже только администратору: он заводит
            прогоны, которые занимают сервер на десятки минут. Ссылка стоит рядом с
            демонстрационным боем и схемой Галактики: всё это открывается до партии.
          */}
          {account?.role === 'ADMIN' ? (
            <button
              type="button"
              className="link mt-2 block text-xs"
              onClick={() => setScreen('balance')}
            >
              ▸ {t('menu.balance')}
            </button>
          ) : null}
          {/*
            Орионометр — запись того, как человек играет в ОРИГИНАЛ, и тоже только
            администратору: он поднимает чужой процесс и пишет файлы на диск. Стоит рядом с
            пультом балансировки: оба — хозяйство машины, а не игра.
          */}
          {account?.role === 'ADMIN' ? (
            <button
              type="button"
              className="link mt-2 block text-xs"
              onClick={() => setScreen('orionometer')}
            >
              ▸ {t('menu.orionometer')}
            </button>
          ) : null}
        </section>

        {/*
          Свои партии — п. 3. Стоят ВЫШЕ открытых нарочно: партия, где тебя ждут, важнее
          чужой, в которую только предстоит проситься. Панели нет вовсе, пока возвращаться
          некуда: пустой раздел «ваши партии» читался бы как поломка.
        */}
        {myGames.length > 0 ? (
          <section className="panel">
            <div className={`panel-title ${figureLabelClass('note')}`}>
              {t('menu.myGames')}
              <Figure accent="note" className="ml-2">{myGames.length}</Figure>
            </div>
            <p className="text-11 text-ink-faint">{t('menu.myGames.hint')}</p>
            <ul className="mt-2 max-h-[14rem] space-y-2 overflow-y-auto pr-1">
              {myGames.map((game) => (
                <li key={game.id} className="border-b border-space-700 pb-2 last:border-b-0">
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="text-ink">{game.name}</span>
                    <button type="button" className="link text-xs text-accent"
                            onClick={() => onRejoin(game.id)}>
                      {t('menu.rejoin')}
                    </button>
                  </div>
                  <div className={figureLabelClass('note')}>
                    {gameSummary(game)}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/*
          Гостю вместо списка открытых игр — зов к регистрации: войти в чужую партию он не
          может, и пустая дверь без объяснения читалась бы как поломка. Регистрация отсюда —
          это выход с подтверждением: гостевую запись в зарегистрированную пока не
          превращаем (backlog-promo, пункт 1, — следующий шаг).
        */}
        {guest ? (
          <section className="panel">
            <div className="panel-title">{t('menu.guest.register.title')}</div>
            <p className="mb-3 text-xs text-ink-soft">{t('menu.guest.register.text')}</p>
            <button type="button" className={`link text-xs${guestLeaving ? ' text-warn' : ''}`} onClick={signOut}>
              ▸ {guestLeaving ? t('menu.guest.signOut.confirm') : t('menu.guest.register.action')}
            </button>
          </section>
        ) : (
        /* Список открытых игр */
        <section className="panel">
          <div className="flex items-baseline justify-between">
            <div className={`panel-title ${figureLabelClass('note')}`}>
              {t('menu.openGames')}
              {openGames.length > 0 ? (
                <Figure accent="note" className="ml-2">{openGames.length}</Figure>
              ) : null}
            </div>
            <button type="button" className="link text-xs" onClick={onRefresh}>
              {t('menu.refresh')}
            </button>
          </div>

          {openGames.length === 0 ? (
            <p className="text-xs text-ink-dim">{t('menu.noGames')}</p>
          ) : (
            /*
              Список со своей прокруткой: открытых партий на сервере бывает много, и без
              предела высоты панель растягивала бы меню на несколько экранов — а рядом с
              ней стоит «Новая игра», до которой тогда надо было прокручивать.
            */
            <ul className="max-h-[22rem] space-y-2 overflow-y-auto pr-1">
              {openGames.map((game) => (
                <GameRow
                  key={game.id}
                  game={game}
                  onJoin={() => setSetup({ kind: 'join', game })}
                  canJoin={game.humanPlayers < game.maxHumanPlayers}
                />
              ))}
            </ul>
          )}
        </section>
        )}
      </div>
    </div>
  );
}

/** Строка-сводка партии в списках меню: числа — сопутствующей ступенью, фраза — одна строка словаря. */
function gameSummary(game: GameSummary) {
  const n = (value: number) => <Figure accent="note">{value}</Figure>;
  return tf('menu.game.summary', {
    size: game.galaxySize, w: n(game.widthParsecs), h: n(game.heightParsecs),
    have: n(game.humanPlayers), max: n(game.maxHumanPlayers), total: n(game.totalPlayers),
  });
}

function GameRow({
  game,
  onJoin,
  canJoin,
}: {
  game: GameSummary;
  onJoin: () => void;
  canJoin: boolean;
}) {
  const { t } = useT();
  return (
    <li className="border-b border-space-700 pb-2 last:border-b-0">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-ink">{game.name}</span>
        <button type="button" className="link text-xs" disabled={!canJoin} onClick={onJoin}>
          {t('menu.join')}
        </button>
      </div>
      <div className={figureLabelClass('note')}>
        {gameSummary(game)}
      </div>
    </li>
  );
}
