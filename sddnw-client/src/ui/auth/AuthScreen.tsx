import { useEffect, useState } from 'react';

import { gameApi, GameApiError } from '../../api/client';
import type { AccountSession, RegistrationChallenge } from '../../api/types';
import { useT } from '../../i18n';
import { markGuestPlay, writeAccount } from '../../state/session';
import { LocaleSwitch } from '../LocaleSwitch';
import { TextScaleSwitch } from '../TextScaleSwitch';

/**
 * Вход в игру — п. 3.1: до партии игрок называет себя.
 *
 * Экран стоит перед главным меню и другого пути дальше нет: сервер спрашивает пропуск
 * учётной записи на входе в любую партию, поэтому пускать к меню неавторизованного
 * значило бы показать ему кнопки, каждая из которых ответит отказом.
 *
 * Две формы на одном экране, как это принято в играх: вход и регистрация переключаются
 * ссылкой, а не разными экранами — человек, ошибшийся дверью, не должен искать обратную
 * дорогу. Цвета и разметка — общие с остальной игрой (`panel`, `field`, `link`): это тот
 * же экран той же игры, а не отдельное приложение сбоку.
 *
 * <b>Логин — это почта.</b> Отдельного логина нет: игрок называет себя тем же адресом, на
 * который придёт письмо, — одно имя вместо двух. Имя спрашивается отдельно и служит только
 * показу в интерфейсе игры.
 *
 * <b>Ссылка из письма ведёт сюда же.</b> Клиент открывается с `?confirm=<код>`, экран сам
 * меняет код на пропуск и пускает игрока дальше — второй раз вводить пароль после письма
 * не приходится.
 */
export function AuthScreen({ onAuthorized }: { onAuthorized: (session: AccountSession) => void }) {
  const { t } = useT();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  /** Почта: она же логин — по ней и входят, и на неё приходит письмо. */
  const [email, setEmail] = useState('');
  /** Имя для интерфейса игры: на вход не влияет. */
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Запись боя на обложке нашлась: файла может не быть — он не в git. */
  const [coverShown, setCoverShown] = useState(true);
  /**
   * Сколько секунд ждать, пока сервер не пускает к проверке пароля — п. 3.1.
   *
   * Сервер считает неудачные попытки и после порога отвечает 429 с `Retry-After`
   * (`LoginThrottle`). Форма на это время запирается сама: стучаться в закрытый вход
   * бессмысленно, а каждая новая попытка только продлевает замок. Заодно это единственный
   * внятный способ объяснить игроку, что он не «сломал вход», а перебрал попытки.
   */
  const [waitSeconds, setWaitSeconds] = useState(0);
  /**
   * Задачка перед регистрацией — п. 3.1: «докажите, что вы человек».
   *
   * Вопрос берётся у сервера и одноразовый, поэтому после каждой неудачной заявки форма
   * берёт новый: старый уже потрачен, и повтор с тем же ответом не прошёл бы. Пусто —
   * задачка выключена на сервере, и полю ответа тогда неоткуда взяться.
   */
  const [challenge, setChallenge] = useState<RegistrationChallenge | null>(null);
  const [challengeAnswer, setChallengeAnswer] = useState('');

  /** Вопрос спрашивается один раз при переходе к регистрации, а не при каждом наборе. */
  const askChallenge = () => {
    gameApi
      .registrationChallenge()
      .then((next) => setChallenge(next))
      .catch(() => setChallenge(null));
    setChallengeAnswer('');
  };

  useEffect(() => {
    if (mode === 'register') {
      askChallenge();
    }
  }, [mode]);

  // Обратный отсчёт замка: тикает раз в секунду и сам останавливается на нуле.
  useEffect(() => {
    if (waitSeconds <= 0) {
      return;
    }
    const timer = window.setTimeout(() => setWaitSeconds(waitSeconds - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [waitSeconds]);

  /**
   * Показывает отказ и, если это замок от подбора, запирает форму на его срок.
   * Один путь на все три запроса экрана: сервер запирает и вход, и повторное письмо.
   */
  const failure = (cause: unknown, fallback: string) => {
    setError(cause instanceof GameApiError ? cause.message : fallback);
    if (cause instanceof GameApiError && cause.retryAfterSeconds) {
      setWaitSeconds(cause.retryAfterSeconds);
    }
  };

  /*
    Ссылка подтверждения: код приходит параметром адреса. Разбирается один раз при
    открытии, а из адреса стирается сразу — иначе перезагрузка страницы пыталась бы
    подтвердить почту повторно уже использованным кодом и пугала бы отказом.
  */
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('confirm');
    if (!token) {
      return;
    }
    window.history.replaceState({}, '', window.location.pathname);
    setBusy(true);
    gameApi
      .confirmAccount(token)
      .then((session) => {
        writeAccount(session);
        onAuthorized(session);
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('auth.error.confirm')),
      )
      .finally(() => setBusy(false));
  }, [onAuthorized]);

  /**
   * Выслать письмо подтверждения заново — п. 3.1.
   *
   * Без этого регистрация оказывалась ловушкой: письмо не дошло — логин занят, вход
   * закрыт, и сделать с этим было нельзя ничего. Логин и пароль берутся из той же формы
   * входа: другого их места на экране нет, а сервер спрашивает ровно их.
   */
  const resend = () => {
    if (busy || waitSeconds > 0) {
      return;
    }
    if (!email.trim() || !password) {
      setError(t('auth.error.resend.fields'));
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .resendConfirmation(email.trim(), password)
      .then((result) => setNotice(result.notice))
      .catch((cause: unknown) => failure(cause, t('auth.error.resend')))
      .finally(() => setBusy(false));
  };

  /**
   * Гостевой вход — backlog-promo, пункт 1: партия одним нажатием, без почты и пароля.
   *
   * Сервер заводит гостевую запись и отдаёт обычный пропуск; дальше гость идёт тем же
   * путём, что и все, только меню у него короче, а настройки партии назначает сервер.
   * Отказ (предел гостей с адреса) показывается той же строкой, что и отказ входа, и
   * запирает кнопку на свой срок.
   */
  const playAsGuest = () => {
    if (busy || waitSeconds > 0) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .guestAccount()
      .then((session) => {
        writeAccount(session);
        markGuestPlay();
        onAuthorized(session);
      })
      .catch((cause: unknown) => failure(cause, t('auth.error.guest')))
      .finally(() => setBusy(false));
  };

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (busy || waitSeconds > 0) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);

    const done = (cause: unknown) => failure(cause, t('auth.error.server'));

    if (mode === 'login') {
      gameApi
        .loginAccount(email.trim(), password)
        .then((session) => {
          writeAccount(session);
          onAuthorized(session);
        })
        .catch(done)
        .finally(() => setBusy(false));
      return;
    }

    gameApi
      .register(email.trim(), name.trim(), password, challenge?.id, challengeAnswer.trim())
      .then((result) => {
        // Регистрация не пускает в игру сама: пока почта не подтверждена, вход закрыт.
        setNotice(result.notice);
        setMode('login');
        setPassword('');
      })
      .catch((cause: unknown) => {
        done(cause);
        // Вопрос одноразовый: сервер его уже потратил, и повтор с тем же ответом
        // отвалился бы «вопрос устарел», о чём игрок бы не догадался.
        askChallenge();
      })
      .finally(() => setBusy(false));
  };

  return (
    <div className="flex h-full w-full justify-center overflow-y-auto bg-space-950 p-4 lg:p-10">
      {/*
        ДВА СТОЛБЦА: слева обложка, справа вход.

        Прежде экран начинался прямо с формы входа, и человек, пришедший по ссылке, не
        получал ни одного повода её заполнять: ни слова о том, что это за игра, ни
        картинки. Нашлось это не чтением кода, а проходом по пути новичка (26.09.2026).

        Обложка стоит РЯДОМ с формой, а не отдельным экраном с кнопкой «играть»: лишний
        переход ничего не объясняет, зато отделяет пришедшего от входа. На узком окне
        столбцы складываются в один — обложка сверху, форма под ней.

        Внешняя пара `justify-center` + `overflow-y-auto` и `my-auto` у содержимого —
        общее правило полноэкранных экранов этой игры: у страницы прокрутка скрыта ради
        карты галактики, и то, что не поместилось, иначе не достать (см. правила проекта).
      */}
      <div className="my-auto flex w-full max-w-4xl flex-col gap-10 lg:flex-row lg:items-start">
        <section className="w-full lg:flex-1">
          <h1 className="text-2xl tracking-[0.3em] text-accent">{t('app.name')}</h1>
          <p className="mt-1 text-xs uppercase tracking-[0.35em] text-ink-dim">
            {t('app.tagline')}
          </p>
          {/*
            На узком экране (телефон) колонки встают друг под друга, и «играть» уезжало под
            описание и запись боя — до него приходилось листать (backlog-promo, пункт 21).
            Здесь та же кнопка, видна только узкому экрану; на широком она стоит справа.
          */}
          <button
            type="button"
            className="mt-4 border border-accent px-4 py-2 text-accent lg:hidden"
            disabled={busy || waitSeconds > 0}
            onClick={playAsGuest}
          >
            ▸ {busy ? '…' : t('auth.guest.action')}
          </button>
          <div className="mt-6 space-y-3 text-sm leading-relaxed text-ink-soft">
            <p>{t('cover.line1')}</p>
            <p>{t('cover.line2')}</p>
            <p>{t('cover.line3')}</p>
          </div>
          {/*
            Запись НАСТОЯЩЕГО боя этой игры, а не рисунок: показывать игру её собственным
            кадром честнее всего, и кадр уже есть — его пишет tools/demo_battle_gif.py.

            Берётся ПО АДРЕСУ, а не импортом в сборку. Анимации игре нужны, но в git их
            пока нет (.gitignore, решение хозяина проекта), а импорт делал файл условием
            сборки: свежий клон без GIF не собирался бы вовсе. Файл лежит в
            sddnw-client/public/, Vite кладёт его в корень сборки, и сервер отдаёт его
            рядом с игрой.

            Нет файла — нет и рамки: пустой прямоугольник с подписью «бой, как его играет
            сама игра» читался бы как поломка обложки, а без картинки обложка остаётся
            тремя предложениями и ничего не теряет, кроме наглядности.
          */}
          {coverShown ? (
            <figure className="mt-6">
              <img
                src="/demo-battle.gif"
                alt={t('cover.battle.alt')}
                className="w-full border border-space-600"
                onError={() => setCoverShown(false)}
              />
              <figcaption className="mt-2 text-11 text-ink-faint">
                {t('cover.battle.caption')}
              </figcaption>
            </figure>
          ) : null}
        </section>

        <div className="w-full lg:max-w-md">
        <header className="mb-6 flex items-start justify-end gap-4">
          <div className="flex flex-col items-end gap-1">
            <LocaleSwitch />
            <TextScaleSwitch />
          </div>
        </header>

        {/*
          ИГРА БЕЗ РЕГИСТРАЦИИ — backlog-promo, пункт 1. Стоит ВЫШЕ формы входа нарочно:
          пришедшему по ссылке нужно сперва поиграть, а регистрироваться — тогда, когда
          появится что терять. Четыре шага до первой партии (форма, задачка, письмо,
          ссылка) отсекали людей раньше, чем они видели игру.
        */}
        <section className="panel mb-4">
          <div className="panel-title">{t('auth.guest.title')}</div>
          <p className="mb-3 text-xs text-ink-soft">{t('auth.guest.hint')}</p>
          {/* Кнопкой, а не ссылкой (backlog-promo, пункт 29): это главное действие страницы,
              и весом оно не должно равняться «Войти» под ним. */}
          <button
            type="button"
            className="border border-accent bg-space-800 px-5 py-2 text-15 uppercase tracking-[0.2em] text-accent hover:bg-space-700 disabled:cursor-wait"
            disabled={busy || waitSeconds > 0}
            onClick={playAsGuest}
          >
            ▸ {busy ? '…' : t('auth.guest.action')}
          </button>
        </section>

        <section className="panel">
          <div className="panel-title">
            {mode === 'login' ? t('auth.title.login') : t('auth.title.register')}
          </div>

          {error ? (
            <div className="mb-3 border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger">
              {error}
            </div>
          ) : null}
          {notice ? (
            <div className="mb-3 border border-space-600 bg-space-800/60 px-3 py-2 text-xs text-ink">
              {notice}
            </div>
          ) : null}

          <form onSubmit={submit}>
            <label className="mb-3 block text-xs text-ink-soft">
              {mode === 'login' ? t('auth.email.login') : t('auth.email.register')}{' '}
              {/* У входа подсказки нет (backlog-promo, пункт 22): прежняя «у администратора
                  свой логин» была про хозяина сервера, а видел её каждый новичок. */}
              {mode === 'login' ? null : (
                <span className="text-ink-faint">{t('auth.email.register.hint')}</span>
              )}
              {/*
                На входе поле обычное, а не `type="email"`: адрес — логин у всех, кроме
                администратора, у него логин свой («admin») и почты нет вовсе. Браузер
                проверял поле сам и до сервера такую заявку просто не пускал — войти
                администратору было нечем. В регистрации проверка остаётся: там адрес
                обязателен, на него уходит ссылка подтверждения.
              */}
              <input
                className="field mt-1"
                type={mode === 'login' ? 'text' : 'email'}
                maxLength={255}
                autoComplete={mode === 'login' ? 'username' : 'email'}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>

            {mode === 'register' ? (
              <label className="mb-3 block text-xs text-ink-soft">
                {t('auth.name')} <span className="text-ink-faint">{t('auth.name.hint')}</span>
                <input
                  className="field mt-1"
                  maxLength={64}
                  autoComplete="nickname"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </label>
            ) : null}

            <label className="mb-4 block text-xs text-ink-soft">
              {t('auth.password')}{' '}
              {mode === 'register' ? (
                <span className="text-ink-faint">{t('auth.password.hint')}</span>
              ) : null}
              <input
                className="field mt-1"
                type="password"
                maxLength={100}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>

            {/*
              Задачка «докажите, что вы человек» — п. 3.1. Стоит последней в форме, перед
              самой кнопкой: это не про игрока, а про заявку, и отвечать на неё логичнее,
              когда всё остальное заполнено. Нет вопроса — задачка выключена на сервере, и
              поля тогда нет вовсе.
            */}
            {mode === 'register' && challenge ? (
              <label className="mb-4 block text-xs text-ink-soft">
                {challenge.question}{' '}
                <span className="text-ink-faint">{challenge.hint}</span>
                <input
                  className="field mt-1"
                  maxLength={32}
                  autoComplete="off"
                  value={challengeAnswer}
                  onChange={(event) => setChallengeAnswer(event.target.value)}
                />
              </label>
            ) : null}

            <div className="flex items-baseline justify-between">
              {/*
                Пока висит замок от подбора, кнопка называет остаток: иначе игрок видит
                «Войти», которое ничего не делает, и жмёт его снова и снова — а каждая
                попытка замок продлевает.
              */}
              <button type="submit" className="link text-accent" disabled={busy || waitSeconds > 0}>
                {waitSeconds > 0
                  ? // Минуты вместо секунд, когда ждать долго: «3506 с» читается как
                    // ошибка, а предел регистраций с адреса запирает форму на час.
                    waitSeconds > 90
                    ? t('auth.wait.minutes', { n: Math.ceil(waitSeconds / 60) })
                    : t('auth.wait.seconds', { n: waitSeconds })
                  : busy
                    ? '…'
                    : mode === 'login'
                      ? t('auth.submit.login')
                      : t('auth.submit.register')}
              </button>
              <button
                type="button"
                className="link text-xs"
                disabled={busy}
                onClick={() => {
                  setMode(mode === 'login' ? 'register' : 'login');
                  setError(null);
                  setNotice(null);
                }}
              >
                {mode === 'login' ? t('auth.switch.register') : t('auth.switch.login')}
              </button>
            </div>
          </form>

          {/*
            Повторная отправка стоит на форме входа, а не регистрации: сюда приходит тот,
            у кого запись уже есть, а письма нет. Логин с паролем он вводит здесь же —
            их и спрашивает сервер.
          */}
          {mode === 'login' ? (
            <p className="mt-3 border-t border-space-700 pt-3 text-11 text-ink-faint">
              {t('auth.resend.question')}{' '}
              <button
                type="button"
                className="link"
                disabled={busy || waitSeconds > 0}
                onClick={resend}
              >
                {t('auth.resend.action')}
              </button>
            </p>
          ) : null}
        </section>

        <p className="mt-4 text-11 leading-relaxed text-ink-faint">{t('auth.footer')}</p>
        </div>
      </div>
    </div>
  );
}
