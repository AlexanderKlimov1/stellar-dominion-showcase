import { useEffect, useState } from 'react';
import { useGameStore } from '../state/gameStore';
import type { MenuScreen } from '../state/session';
import { readAccount, readMenuScreen, writeMenuScreen } from '../state/session';
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
import { LocaleSwitch } from './LocaleSwitch';
import { NewGameScreen } from './newgame/NewGameScreen';
import { TextScaleSwitch } from './TextScaleSwitch';
import { useT } from '../i18n';

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
    /** Идут ли в партии случайные галактические события — п. 11.1. */
    galacticEvents: boolean;
    /** Сколько всего империй в партии, считая свою, — п. 3. */
    totalPlayers: number;
  }) => void;
  onJoin: (payload: {
    gameId: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    /** Код готовой расы MOO II — п. 5; пусто — раса достанется по разнарядке. */
    raceCode?: string;
  }) => void;
  onRefresh: () => void;
  onLoadSave: (saveId: string) => void;
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
  onSignOut,
  initial,
}: MainMenuProps) {
  const { t } = useT();
  // Кто вошёл — п. 3.1: имя из учётной записи, а не из настроек партии.
  const account = readAccount()?.account ?? null;
  const galaxySizes = useGameStore((state) => state.galaxySizes);
  const openGames = useGameStore((state) => state.openGames);
  const lastError = useGameStore((state) => state.lastError);

  const defaultSize = galaxySizes.find((size) => size.defaultChoice)?.code ?? 'HUGE';
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
  const [demo, setDemo] = useState<DemoBattle | null>(null);
  const [demoBusy, setDemoBusy] = useState(false);
  const [setup, setSetup] = useState<SetupTarget | null>(null);

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
    setSetup(null);

    if (target.kind === 'create') {
      onCreate({ gameName: gameName.trim(), galaxySize, galacticEvents, totalPlayers, ...value });
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

      {costsOpen ? <RaceCostEditor onClose={() => setScreen(null)} /> : null}
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
          onGameName={setGameName}
          onGalaxySize={setGalaxySize}
          onTotalPlayers={setTotalPlayers}
          onGalacticEvents={setGalacticEvents}
          onAccept={() => {
            setScreen(null);
            setSetup({ kind: 'create' });
          }}
          onCancel={() => setScreen(null)}
        />
      ) : null}
      {balanceOpen ? <BalanceRunScreen onClose={() => setScreen(null)} /> : null}
      {orionometerOpen ? <OrionometerScreen onClose={() => setScreen(null)} /> : null}

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
                {account.name || account.login}
                {/*
                  Роль переводится здесь, а не берётся из roleLabel: тот пришёл на языке
                  запроса входа и лежит в localStorage — переключение языка его бы не тронуло.
                */}
                <span className="ml-2 text-ink-faint">
                  {t(account.role === 'ADMIN' ? 'account.role.ADMIN' : 'account.role.PLAYER')}
                </span>
              </div>
              <div className="flex justify-end gap-3">
                {/*
                  Учётные записи — только администратору (п. 3.1): подтвердить застрявшую
                  регистрацию больше некому, если почта не работает вовсе.
                */}
                {account.role === 'ADMIN' ? (
                  <button
                    type="button"
                    className="link text-11"
                    onClick={() => setScreen('accounts')}
                  >
                    {t('menu.accounts')}
                    {waitingAccounts > 0 ? (
                      <span className="ml-1 text-warn">{waitingAccounts}</span>
                    ) : null}
                  </button>
                ) : null}
                <button type="button" className="link text-11" onClick={onSignOut}>
                  {t('menu.signOut')}
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
            Настройки партии — название, размер галактики, число империй и случайные
            события — спрашивает ОКНО НОВОЙ ИГРЫ (п. 3), а не меню. Так в MOO II: меню
            предлагает начать, а New Game Menu спрашивает, какую. Здесь они занимали
            пол-колонки списком и абзацем пояснений, ради которых игрок листал меню.
          */}
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

          {/*
            Цены сторон расы правятся до партии и общие для всех: справочник живёт на
            сервере, поэтому и ссылка стоит здесь, а не внутри конструктора расы — п. 7.
          */}
          <button
            type="button"
            className="link mt-2 block text-xs"
            onClick={() => setScreen('costs')}
          >
            ▸ {t('menu.costs')}
          </button>

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
          <button
            type="button"
            className="link mt-2 block text-xs"
            onClick={() => setScreen('milkyway')}
          >
            ▸ {t('menu.milkyway')}
          </button>

          {/*
            Запросы на регистрацию — п. 3.1, и только администратору: подтвердить
            застрявшую регистрацию больше некому, когда почта не работает вовсе. Ссылка
            стоит здесь, в общем списке, а не только при имени вошедшего: то, что ждёт
            решения, должно попадаться на глаза само.
          */}
          {account?.role === 'ADMIN' ? (
            <button
              type="button"
              className="link mt-2 block text-xs"
              onClick={() => setScreen('accounts')}
            >
              ▸ {t('menu.requests')}
              {waitingAccounts > 0 ? (
                <span className="ml-2 text-warn">{waitingAccounts}</span>
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

        {/* Список открытых игр */}
        <section className="panel">
          <div className="flex items-baseline justify-between">
            <div className="panel-title">
              {t('menu.openGames')}
              {openGames.length > 0 ? (
                <span className="ml-2 text-ink-faint">{openGames.length}</span>
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
      </div>
    </div>
  );
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
      <div className="text-11 text-ink-faint">
        {t('menu.game.summary', {
          size: game.galaxySize, w: game.widthParsecs, h: game.heightParsecs,
          have: game.humanPlayers, max: game.maxHumanPlayers, total: game.totalPlayers,
        })}
      </div>
    </li>
  );
}
