import type {
  Account,
  AccountSession,
  AccountSummary,
  ApiError,
  CreateGamePayload,
  GalaxyMap,
  GalaxySizeOption,
  GameDetails,
  GameEvent,
  GameSave,
  GameSummary,
  EndTurnResult,
  TurnReport,
  JoinedGame,
  MineralRichnessRef,
  Planet,
  PlanetClimateRef,
  PlanetSizeRef,
  PlayerResearch,
  DiplomacyActionCode,
  DiplomacyRelation,
  EmpireInfo,
  Encounter,
  Espionage,
  Leaders,
  Battle,
  BattleActionPayload,
  DemoBattle,
  Fleet,
  FleetGroup,
  SaveShipDesignPayload,
  ShipCatalog,
  ShipDesign,
  InvasionResult,
  Spy,
  SpyMissionCode,
  TreatyCode,
  TechTrade,
  Race,
  RaceDesign,
  RegisterResult,
  RegistrationChallenge,
  SaveRaceTraitCostsPayload,
  ResearchTree,
  StarSystem,
  BalanceCombination,
  BalanceRun,
  Orionometer,
  BalanceRunPayload,
  Council,
} from './types';
import { locale, t } from '../i18n';

/**
 * Базовый адрес игрового сервера.
 * В dev-режиме пусто — запросы идут через прокси Vite на localhost:8080.
 * Для подключения к серверу в сети задайте VITE_API_BASE, например http://192.168.1.10:8080.
 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

export class GameApiError extends Error {
  readonly status: number;
  readonly details: string[];
  /**
   * Через сколько секунд повторять — заголовок `Retry-After` ответа 429.
   *
   * Приходит с защитой от подбора пароля (п. 3.1): экран входа по нему запирает форму на
   * это время, вместо того чтобы разбирать текст сообщения. Форма, которая продолжает
   * стучаться в запертый вход, только продлевает замок.
   */
  readonly retryAfterSeconds?: number;

  constructor(status: number, message: string, details: string[] = [], retryAfterSeconds?: number) {
    super(message);
    this.name = 'GameApiError';
    this.status = status;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * Пропуск игрока уходит заголовком, а не в адресе: адреса оседают в журналах сервера,
 * прокси и истории браузера. Исключение одно — подписка на события: её открывает
 * EventSource, а он заголовков не умеет.
 */
function auth(accessToken: string): HeadersInit {
  return { 'X-Access-Token': accessToken };
}

/**
 * Пропуск учётной записи — п. 3.1: им игра узнаёт, что пришедший авторизован.
 *
 * Хранится здесь, а не передаётся в каждый вызов: спрашивают его только три запроса —
 * создать партию, войти в чужую и загрузить сохранение, — но знать о нём приходилось бы
 * всем, кто их зовёт. Ставит его `session.ts` при входе и при запуске клиента.
 */
let accountToken: string | null = null;

export function setAccountToken(token: string | null): void {
  accountToken = token;
}

/** Заголовок с пропуском учётной записи; пусто — игрок ещё не вошёл. */
function account(): HeadersInit {
  return accountToken ? { 'X-Account-Token': accountToken } : {};
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        // Язык экрана — серверу: ошибки, письма и отчёты хода приходят на нём же.
        'Accept-Language': locale(),
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    throw new GameApiError(0, t('app.unreachable'));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const body = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const error = body as ApiError | null;
    const retryAfter = Number(response.headers.get('Retry-After'));
    throw new GameApiError(
      response.status,
      error?.message ?? t('error.request', { status: response.status }),
      error?.details ?? [],
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
    );
  }

  return body as T;
}

export const gameApi = {
  /** Проверка доступности сервера при подключении клиента. */
  health: () => request<{ status: string }>('/actuator/health'),

  /*
    Вход в игру — п. 3.1. Пропуск учётной записи эти четыре запроса не шлют: три первых
    его и выдают, а четвёртый им же и проверяется — заголовок ставит `account()`.
  */

  /**
   * Регистрация: ссылка подтверждения уходит на почту — п. 3.1.
   *
   * Логина в заявке нет: логином служит сама почта, а имя идёт только на показ в
   * интерфейсе игры.
   */
  register: (
    email: string,
    name: string,
    password: string,
    challengeId?: string,
    challengeAnswer?: string,
  ) =>
    request<RegisterResult>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, name, password, challengeId, challengeAnswer }),
    }),

  /**
   * Задачка перед регистрацией — п. 3.1. Пусто значит, что она выключена на сервере:
   * форма тогда о ней и не спрашивает.
   */
  registrationChallenge: () =>
    request<RegistrationChallenge | null>('/api/auth/challenge'),

  /**
   * Балансовые прогоны — этап 2 плана: список свежими сверху.
   *
   * Хозяйство сервера, а не своя партия, поэтому пропуск идёт учётной записи
   * (`X-Account-Token`), и сервер спрашивает роль.
   */
  /**
   * Орионометр: запись того, как человек играет в оригинал.
   *
   * Хозяйство машины, а не своя партия, поэтому пропуск идёт учётной записи, а сервер
   * спрашивает роль.
   */
  orionometer: () =>
    request<Orionometer>('/api/orionometer', { headers: account() }),

  /** Начать запись: игра должна быть уже открыта. */
  startOrionometer: (minutes?: number) =>
    request<Orionometer>(
      `/api/orionometer/start${minutes ? `?minutes=${minutes}` : ''}`,
      { method: 'POST', headers: account() },
    ),

  /** Остановить запись: протокол дописывается и остаётся на диске. */
  stopOrionometer: () =>
    request<Orionometer>('/api/orionometer/stop', { method: 'POST', headers: account() }),

  /** Пустить нейросеть играть в оригинал через Орионометр. */
  orionometerBrain: () =>
    request<Orionometer>('/api/orionometer/brain', { method: 'POST', headers: account() }),

  listBalanceRuns: () =>
    request<BalanceRun[]>('/api/balance/runs', { headers: account() }),

  /** Один прогон целиком — со снимком цен и приговорами. */
  balanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}`, { headers: account() }),

  /**
   * Заказать прогон. Отвечает сразу: играет его сервер в фоне, и пульт показывает,
   * сколько партий уже сыграно.
   */
  startBalanceRun: (payload: BalanceRunPayload) =>
    request<BalanceRun>('/api/balance/runs', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(payload),
    }),

  /**
   * Пересудить прогон нынешними правилами, не переигрывая его.
   *
   * Замеры у прогона свои, цены — из его же снимка; меняются только правила чтения. Без
   * этого всякая правка правил оценки требовала бы играть двести партий заново.
   */
  reassessBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/reassess`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Поставить прогон на паузу.
   *
   * Пауза мягкая: новых партий прогон не начинает, а начатые доигрывает — бросать партию
   * посередине нельзя, её замер пропал бы. Поэтому между нажатием и остановкой проходит
   * до нескольких минут, и число сыгранных всё это время растёт.
   */
  pauseBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/pause`, {
      method: 'POST',
      headers: account(),
    }),

  /** Продолжить приостановленный прогон. */
  resumeBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/resume`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Связки, которые стоит проверить, и что о них известно.
   *
   * Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл имеют
   * считанные — те, о которых есть догадка. Здесь они копятся вместе с ответами прогонов.
   */
  listBalanceCombinations: () =>
    request<BalanceCombination[]>('/api/balance/combinations', { headers: account() }),

  /** Запомнить связку: что проверяем и зачем. */
  rememberBalanceCombination: (traits: string[], note?: string) =>
    request<BalanceCombination>('/api/balance/combinations', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ traits, note }),
    }),

  /** Забыть связку — догадка не оправдалась. */
  forgetBalanceCombination: (id: string) =>
    request<void>(`/api/balance/combinations/${id}`, {
      method: 'DELETE',
      headers: account(),
    }),

  /** Учётные записи — п. 3.1: список видит только администратор. */
  listAccounts: () =>
    request<AccountSummary[]>('/api/auth/accounts', { headers: account() }),

  /**
   * Подтверждение чужой записи администратором — п. 3.1.
   *
   * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе, и тогда игрок
   * остаётся с занятой почтой и закрытым входом.
   */
  confirmAccountByAdmin: (accountId: string) =>
    request<AccountSummary>(`/api/auth/accounts/${accountId}/confirm`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Удаление учётной записи администратором — п. 3.1.
   *
   * Брошенные заявки и опечатки в адресе иначе висят в очереди вечно, а занятая почта не
   * освобождается сама. Сыгранное при этом остаётся: запись ни на что в партии не
   * ссылается.
   */
  deleteAccountByAdmin: (accountId: string) =>
    request<void>(`/api/auth/accounts/${accountId}`, {
      method: 'DELETE',
      headers: account(),
    }),

  /**
   * Выслать письмо подтверждения заново — п. 3.1.
   *
   * Спрашивает те же логин и пароль, что и вход: сервер иначе слал бы письма на чужие
   * адреса по чужой просьбе. Старая ссылка после этого не годится.
   */
  resendConfirmation: (login: string, password: string) =>
    request<RegisterResult>('/api/auth/resend', {
      method: 'POST',
      body: JSON.stringify({ login, password }),
    }),

  /** Подтверждение почты по ссылке из письма: в ответ приходит готовый пропуск — п. 3.1. */
  confirmAccount: (token: string) =>
    request<AccountSession>(`/api/auth/confirm?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    }),

  /** Вход по логину или почте — п. 3.1. */
  loginAccount: (login: string, password: string) =>
    request<AccountSession>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ login, password }),
    }),

  /** Выход: пропуск перестаёт действовать сразу, а не по сроку. */
  logoutAccount: () =>
    request<void>('/api/auth/logout', { method: 'POST', headers: account() }),

  /** Кто вошёл: этим клиент проверяет при запуске, жив ли сохранённый пропуск — п. 3.1. */
  currentAccount: () => request<Account>('/api/auth/me', { headers: account() }),

  /**
   * Запомнить язык в учётной записи — п. 3.5.
   *
   * Уходит следом за переключением языка, а не вместо него: экран переключается сразу, а
   * запись — когда ответит сервер. Отказ здесь не беда (в игре без входа записи нет вовсе),
   * поэтому зовущий его гасит.
   */
  saveLocale: (value: string) =>
    request<Account>('/api/auth/locale', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ locale: value }),
    }),

  /**
   * Запомнить размер текста в учётной записи — п. 11.1.
   *
   * Всё то же, что и у языка: интерфейс перерисовывается сразу, запись догоняет, а отказ
   * гасит зовущий — без входа записи нет вовсе, и настройка остаётся в браузере.
   */
  saveTextScale: (value: number) =>
    request<Account>('/api/auth/text-scale', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ textScale: value }),
    }),

  /** Список игр, видимых всем, кто видит IP сервера — п. 3.1. */
  listGames: (openOnly = true) => request<GameSummary[]>(`/api/games?openOnly=${openOnly}`),

  createGame: (payload: CreateGamePayload) =>
    request<JoinedGame>('/api/games', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(payload),
    }),

  getGame: (gameId: string) => request<GameDetails>(`/api/games/${gameId}`),

  /** Присоединение к чужой игре — п. 3.2. */
  joinGame: (
    gameId: string,
    playerName: string,
    homeStarName?: string,
    raceName?: string,
    raceTraits?: string[],
    raceCode?: string,
  ) =>
    request<JoinedGame>(`/api/games/${gameId}/join`, {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ playerName, homeStarName, raceName, raceTraits, raceCode }),
    }),

  /** Старт игры: свободные слоты добираются ИИ-игроками до восьми — п. 3.2. */
  startGame: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/start`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Конец хода игрока — п. 11.1: игрок объявляет, что закончил, и ждёт остальных.
   * Галактика считается, когда закончили все люди партии, поэтому ответ говорит, посчитан
   * ли ход (`advanced`) и кого ещё ждут. Тем, кто закончил раньше, о пересчёте расскажет
   * подписка на события.
   */
  endTurn: (gameId: string, accessToken: string) =>
    request<EndTurnResult>(`/api/games/${gameId}/turn/end`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Что изменилось за последний посчитанный ход — п. 11.1. Нужен, когда подписки не
   * было: после перезагрузки страницы или обрыва связи.
   */
  getTurnReport: (gameId: string, accessToken: string) =>
    request<TurnReport | null>(`/api/games/${gameId}/turn/report`, { headers: auth(accessToken) }),

  /**
   * Подписка на события партии — п. 11.1: сосед закончил ход, галактика пересчиталась.
   * Возвращает функцию отписки. Пропуск идёт параметром: EventSource заголовков не умеет.
   */
  subscribe: (gameId: string, accessToken: string, onEvent: (event: GameEvent) => void) => {
    const source = new EventSource(
      `${API_BASE}/api/games/${gameId}/events?accessToken=${accessToken}`,
    );
    const handler = (message: MessageEvent<string>) => {
      try {
        onEvent(JSON.parse(message.data) as GameEvent);
      } catch {
        // Битое сообщение — не повод ронять подписку: следующее придёт целым.
      }
    };
    for (const type of ['SUBSCRIBED', 'PLAYER_READY', 'TURN_ADVANCED', 'GAME_STARTED', 'PLAYER_JOINED']) {
      source.addEventListener(type, handler as EventListener);
    }
    return () => source.close();
  },

  /**
   * Перераспределение жителей колонии — п. 4.1. Присылаются все три занятия сразу:
   * их сумма должна сойтись с населением планеты. В ответ приходит планета с новой
   * выработкой, поэтому перезапрашивать карту галактики не нужно.
   */
  setPopulation: (
    gameId: string,
    planetId: string,
    accessToken: string,
    farmers: number,
    workers: number,
    scientists: number,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/population`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, farmers, workers, scientists }),
    }),

  /**
   * Перевозка жителей в другую свою колонию — п. 4.1.1.
   *
   * Грузовой флот резервируется из расчёта один грузовик на единицу населения и
   * освобождается, когда рейс дойдёт — в конце следующего хода. В ответ приходит
   * колония-отправитель: жители сходят с неё сразу.
   */
  transferPopulation: (
    gameId: string,
    planetId: string,
    accessToken: string,
    targetPlanetId: string,
    population: number,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/transfer`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId, population }),
    }),

  /**
   * Смена стройки колонии — п. 10: код здания либо особый проект HOUSING или TRADE_GOODS.
   * В ответ приходит планета с новой стройкой, доходом и приростом.
   */
  setProject: (gameId: string, planetId: string, accessToken: string, projectCode: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/project`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, projectCode }),
    }),

  /**
   * Выкупить стройку колонии за кредиты — п. 10: казна платит за недостающие единицы
   * производства, и вещь достраивается тем же ходом. Не хватило денег — 409.
   */
  buyProject: (gameId: string, planetId: string, accessToken: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/buy`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /**
   * Продать постройку колонии — п. 10: казна получает половину цены здания, содержание
   * платить больше не за что. За ход колония продаёт одну постройку — вторая получит 409.
   */
  sellBuilding: (gameId: string, planetId: string, accessToken: string, buildingCode: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/sell`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, buildingCode }),
    }),

  /**
   * Добавить проект в очередь стройки — п. 10. Колония без стройки берётся за него сразу:
   * очередь забирается только после достроенного, и первый проект ждал бы вечно.
   */
  enqueueProject: (gameId: string, planetId: string, accessToken: string, projectCode: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, projectCode }),
    }),

  /**
   * Поставить один проект сразу многим колониям — п. 10, список колоний.
   *
   * Проект встаёт в голову очереди (`top`), сдвигая её вниз: приказ отсюда касается всей
   * империи, и ждать за тем, что каждая колония успела набрать, он не может. Проект
   * должен быть доступен каждой колонии набора — иначе 409 с именем той, которой он не по
   * силам, и очереди остальных не меняются. В ответ приходят все изменённые колонии.
   */
  enqueueProjects: (
    gameId: string,
    planetIds: string[],
    accessToken: string,
    projectCode: string,
  ) =>
    request<Planet[]>(`/api/games/${gameId}/colonies/queue`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, planetIds, projectCode, top: true }),
    }),

  /** Убрать проект из очереди стройки — п. 10. Вложенное производство остаётся колонии. */
  removeFromQueue: (gameId: string, planetId: string, accessToken: string, index: number) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue/${index}`, {
      method: 'DELETE',
      headers: auth(accessToken),
    }),

  /** Переставить проект в очереди стройки — п. 10: `toIndex` считается от нуля. */
  moveInQueue: (
    gameId: string,
    planetId: string,
    accessToken: string,
    index: number,
    toIndex: number,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue/${index}/move`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, toIndex }),
    }),

  /**
   * Заселение планеты готовой колониальной базой — п. 4.1. `planetId` — колония, которая
   * построила базу, `targetPlanetId` — свободная планета той же системы. В ответ приходит
   * система целиком: планет в ней изменилось две.
   */
  colonize: (gameId: string, planetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/planets/${planetId}/colonize`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /**
   * Высадка десанта на чужую колонию той же системы — п. 12. `planetId` — своя колония,
   * откуда идёт десант. В ответ приходит исход боя и система целиком.
   */
  invade: (
    gameId: string,
    planetId: string,
    accessToken: string,
    targetPlanetId: string,
    troops: number,
  ) =>
    request<InvasionResult>(`/api/games/${gameId}/planets/${planetId}/invade`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId, troops }),
    }),

  /** Знакомые империи и отношения с ними — п. 15. */
  getRelations: (gameId: string, accessToken: string) =>
    request<DiplomacyRelation[]>(`/api/games/${gameId}/diplomacy`, { headers: auth(accessToken) }),

  /**
   * Дипломатическое действие — п. 15: мир, война, договор, его разрыв или требование
   * дани. Договор нужен действиям PROPOSE_TREATY и BREAK_TREATY.
   */
  diplomacy: (
    gameId: string,
    accessToken: string,
    targetPlayerId: string,
    action: DiplomacyActionCode,
    treaty?: TreatyCode,
    /** Обмен и подарки — п. 15: что отдаём, что просим, сколько дарим. */
    deal?: { offeredTech?: string; requestedTech?: string; credits?: number },
  ) =>
    request<DiplomacyRelation>(`/api/games/${gameId}/diplomacy`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlayerId, action, treaty, ...deal }),
    }),

  /** Что можно обменять с этой империей — п. 15: чем поделиться и что попросить. */
  getTradeableTechnologies: (gameId: string, accessToken: string, otherPlayerId: string) =>
    request<TechTrade>(
      `/api/games/${gameId}/diplomacy/${otherPlayerId}/technologies`,
      { headers: auth(accessToken) },
    ),

  /**
   * Задание шпиону — п. 13: отправить к сопернику воровать технологии или устраивать
   * саботаж либо отозвать домой, где агент пополняет запас очков разведки.
   */
  assignSpy: (
    gameId: string,
    accessToken: string,
    spyId: string,
    mission: SpyMissionCode,
    targetPlayerId?: string,
  ) =>
    request<Spy>(`/api/games/${gameId}/spies`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, spyId, mission, targetPlayerId }),
    }),

  /** Разведка империи — п. 13: накопленные очки и приход за ход. */
  getEspionage: (gameId: string, accessToken: string) =>
    request<Espionage>(`/api/games/${gameId}/espionage`, { headers: auth(accessToken) }),

  /**
   * Офицерский резерв — п. 6: кто предлагает службу, кто служит, сколько мест занято.
   *
   * Спрашивается при открытии экрана: предложения приходят и уходят каждый ход, и держать
   * их в хранилище значило бы показывать вчерашний резерв.
   */
  getLeaders: (gameId: string, accessToken: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders`, { headers: auth(accessToken) }),

  /** Нанять лидера — п. 6: плата разовая, технологии лидера достаются сразу. */
  hireLeader: (gameId: string, accessToken: string, leaderId: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/hire`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /** Отказать предложившему или уволить служащего — п. 6. */
  dismissLeader: (gameId: string, accessToken: string, leaderId: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/dismiss`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /**
   * Назначить лидера — п. 6: колониального в систему, корабельного во флот.
   * Пустая цель возвращает его в резерв.
   */
  assignLeader: (gameId: string, accessToken: string, leaderId: string, targetId: string | null) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/assign`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ targetId }),
    }),

  /**
   * Окно «Инфо» — п. 11.1: летопись империй для графика и описание знакомых рас.
   * Запрашивается при открытии экрана: летопись растёт каждый ход, и держать её в
   * хранилище значило бы показывать вчерашний график.
   */
  getInfo: (gameId: string, accessToken: string) =>
    request<EmpireInfo>(`/api/games/${gameId}/info`, { headers: auth(accessToken) }),

  /**
   * Последние выборы Высшего совета — п. 3; пусто, если совет ещё не собирался.
   *
   * Голосование случается внутри посчитанного хода, поэтому сцена спрашивает его
   * отдельно, а не берёт из карты: в карте его нет и быть не может.
   */
  getCouncil: (gameId: string, accessToken: string) =>
    request<Council | null>(`/api/games/${gameId}/council`, { headers: auth(accessToken) }),

  /**
   * Не подчиниться избранному правителю галактики — п. 3.
   *
   * Партия после отказа продолжается, поэтому вызвавший обязан перечитать её целиком:
   * победы больше нет, а у отказника — война со всеми, кто голосовал за избранного.
   */
  refuseCouncil: (gameId: string, accessToken: string) =>
    request<Council>(`/api/games/${gameId}/council/refuse`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /** Флот империи — п. 8: проекты кораблей с учётом расы. */
  getFleet: (gameId: string, accessToken: string) =>
    request<Fleet>(`/api/games/${gameId}/ships`, { headers: auth(accessToken) }),

  /** Флоты игрока по системам — п. 8. */
  getFleets: (gameId: string, accessToken: string) =>
    request<FleetGroup[]>(`/api/games/${gameId}/fleets`, { headers: auth(accessToken) }),

  /**
   * Перелёт флота в другую известную систему — п. 8. Времени в пути нет: флот
   * оказывается на месте сразу.
   */
  /** Бои партии, которые ещё идут: по ним открывается сцена — п. 8. */
  getBattles: (gameId: string, accessToken: string) =>
    request<Battle[]>(`/api/games/${gameId}/battles`, { headers: auth(accessToken) }),

  /** Состояние поля боя: корабли, очередь хода и чей ход сейчас — п. 8. */
  getBattle: (gameId: string, battleId: string, accessToken: string) =>
    request<Battle>(`/api/games/${gameId}/battles/${battleId}`, { headers: auth(accessToken) }),

  /**
   * Ход корабля: перелёт, залп, пропуск или отступление — п. 8. В ответе приходит поле
   * после хода и события, случившиеся за это время: свой залп и ходы кораблей ИИ.
   */
  battleAction: (gameId: string, battleId: string, accessToken: string, payload: BattleActionPayload) =>
    request<Battle>(`/api/games/${gameId}/battles/${battleId}/action`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify(payload),
    }),

  /** Корпуса и компоненты, доступные империи — п. 8. */
  getShipCatalog: (gameId: string, accessToken: string) =>
    request<ShipCatalog>(`/api/games/${gameId}/ship-designs/catalog`, { headers: auth(accessToken) }),

  /** Действующие проекты кораблей игрока — п. 8. */
  getShipDesigns: (gameId: string, accessToken: string) =>
    request<ShipDesign[]>(`/api/games/${gameId}/ship-designs`, { headers: auth(accessToken) }),

  /**
   * Сохраняет проект в ячейку — п. 8. Занятая ячейка переписывается, а построенные по
   * прежнему проекту корабли остаются прежними.
   */
  saveShipDesign: (gameId: string, accessToken: string, payload: SaveShipDesignPayload) =>
    request<ShipDesign>(`/api/games/${gameId}/ship-designs`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify(payload),
    }),

  /** Убирает проект из ячейки — построенные корабли остаются в строю (п. 8). */
  deleteShipDesign: (gameId: string, designId: string, accessToken: string) =>
    request<ShipDesign[]>(`/api/games/${gameId}/ship-designs/${designId}`, {
      method: 'DELETE',
      headers: auth(accessToken),
    }),

  /**
   * Перелёт флота — п. 8. Лететь может весь флот или его часть: `ships` перечисляет,
   * сколько кораблей какого проекта уходит. Без списка летит весь флот целиком.
   */
  moveFleet: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    targetSystemId: string,
    ships?: { designId: string; ships: number }[],
  ) =>
    request<FleetGroup>(`/api/games/${gameId}/fleets/${fleetId}/move`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetSystemId, ships }),
    }),

  /**
   * Списание кораблей со службы — п. 8: экран флота и есть то место, откуда корабль
   * убирают из строя. В ответ приходят все флоты игрока: списанный целиком флот из
   * списка исчезает, и обновлять его строкой было бы нечего.
   */
  scrapFleetShips: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    ships: { designId: string; ships: number }[],
  ) =>
    request<FleetGroup[]>(`/api/games/${gameId}/fleets/${fleetId}/scrap`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, ships }),
    }),

  /**
   * Основать колонию колониальным кораблём флота — п. 4.1.
   *
   * В ответ приходит система целиком: в ней появилась колония, а во флоте стало на
   * корабль меньше.
   */
  colonizeWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/colonize`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Поставить заставу кораблём-заставой — п. 8: дальность империи растёт. */
  outpostWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/outpost`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Высадить десант с транспортов флота — п. 12: захват чужой колонии. */
  /** Подчинить чужую колонию телепатией — п. 7, п. 12: вместо десанта. */
  mindControlWithFleet: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    targetPlanetId: string,
  ) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/mind-control`, {
      method: 'POST',
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  invadeWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<InvasionResult>(`/api/games/${gameId}/fleets/${fleetId}/invade`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Встречи флотов, ждущие решения, — п. 8. */
  getEncounters: (gameId: string, accessToken: string) =>
    request<Encounter[]>(`/api/games/${gameId}/encounters`, { headers: auth(accessToken) }),

  /**
   * Решение при встрече флотов — п. 8: атаковать или разойтись. «Авто» включено по
   * умолчанию: ручной бой пока заглушка, и сцена показывает уже посчитанный исход.
   */
  decideEncounter: (
    gameId: string,
    encounterId: string,
    accessToken: string,
    decision: 'ATTACK' | 'IGNORE',
    auto = true,
  ) =>
    request<Encounter>(`/api/games/${gameId}/encounters/${encounterId}`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, decision, auto }),
    }),

  /** Исследования игрока — п. 9: текущая цель, вложенные очки и изученное. */
  getResearch: (gameId: string, accessToken: string) =>
    request<PlayerResearch>(`/api/games/${gameId}/research`, { headers: auth(accessToken) }),

  /**
   * Выбор технологии для исследования — п. 9. Уровень идёт вместе с разделом: сервер
   * проверяет, что игрок не перепрыгнул через неизученные уровни раздела.
   */
  chooseResearch: (
    gameId: string,
    accessToken: string,
    categoryCode: string,
    levelOrder: number,
    optionCode: string,
  ) =>
    request<PlayerResearch>(`/api/games/${gameId}/research`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, categoryCode, levelOrder, optionCode }),
    }),

  /** Сохранение партии: сервер снимает слепок на конец завершённого хода. */
  saveGame: (gameId: string, accessToken: string) =>
    request<GameSave>(`/api/games/${gameId}/save`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /** Сохранённые партии, свежие сверху — список для диалога загрузки. */
  listSaves: () => request<GameSave[]>('/api/saves'),

  /** Загрузка сохранения: сервер поднимает из слепка новую партию. */
  loadSave: (saveId: string) =>
    request<JoinedGame>(`/api/saves/${saveId}/load`, { method: 'POST', headers: account() }),

  /**
   * Удаление сохранения — только администратором (п. 3.1).
   *
   * Сохранения лежат на сервере общей кучей и хозяина не имеют: их видят и загружают все.
   * Поэтому убирать их — дело того, кто отвечает за сервер; обычному игроку сервер
   * ответит отказом, сколько бы кнопок ни нарисовал клиент.
   */
  deleteSave: (saveId: string) =>
    request<void>(`/api/saves/${saveId}`, { method: 'DELETE', headers: account() }),

  /**
   * Карта галактики — п. 11.3. Игрок видит только свои системы; `revealAll` («Показать
   * галактику» в «Инфо») отдаёт галактику целиком.
   */
  getMap: (gameId: string, accessToken: string, revealAll = false) =>
    request<GalaxyMap>(`/api/games/${gameId}/map?revealAll=${revealAll}`, {
      headers: auth(accessToken),
    }),

  deleteGame: (gameId: string, accessToken: string) =>
    request<void>(`/api/games/${gameId}`, { method: 'DELETE', headers: auth(accessToken) }),

  reference: {
    galaxySizes: () => request<GalaxySizeOption[]>('/api/reference/galaxy-sizes'),
    planetSizes: () => request<PlanetSizeRef[]>('/api/reference/planet-sizes'),
    planetClimates: () => request<PlanetClimateRef[]>('/api/reference/planet-climates'),
    minerals: () => request<MineralRichnessRef[]>('/api/reference/minerals'),
    races: () => request<Race[]>('/api/reference/races'),
    /**
     * Заводит демонстрационный бой — п. 8: две эскадры сходятся сами, без игрока.
     * Пропуска не требует: сцену смотрят из главного меню, до входа в партию.
     */
    startDemoBattle: () =>
      request<DemoBattle>('/api/reference/demo-battle', { method: 'POST' }),

    /** Шаг демонстрации — п. 8: ходит корабль, чья очередь. */
    demoBattleStep: (battleId: string) =>
      request<Battle>(`/api/reference/demo-battle/${battleId}/step`, { method: 'POST' }),

    /** Конструктор расы — п. 7: бюджет очков и группы особенностей. */
    raceTraits: () => request<RaceDesign>('/api/reference/race-traits'),

    /**
     * Правка цен особенностей — п. 7. Сервер пишет их в свой JSON-справочник и отдаёт
     * конструктор заново: экран показывает то, что действительно легло в файл.
     */
    saveRaceTraitCosts: (payload: SaveRaceTraitCostsPayload) =>
      request<RaceDesign>('/api/reference/race-traits', {
        method: 'PUT',
        body: JSON.stringify(payload),
      }),
    /** Дерево технологий для экрана исследований — п. 9. */
    research: () => request<ResearchTree>('/api/reference/research'),
  },
};
