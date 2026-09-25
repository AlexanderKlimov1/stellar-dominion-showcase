import { useEffect, useState } from 'react';

import { gameApi, GameApiError } from '../../api/client';
import type { AccountSession, RegistrationChallenge } from '../../api/types';
import { useT } from '../../i18n';
import { writeAccount } from '../../state/session';
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
    <div className="flex h-full w-full justify-center overflow-y-auto bg-space-950 p-10">
      <div className="my-auto w-full max-w-md">
        <header className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl tracking-[0.3em] text-accent">{t('app.name')}</h1>
            <p className="mt-1 text-xs uppercase tracking-[0.35em] text-ink-dim">
              {t('app.tagline')}
            </p>
          </div>
          <div className="flex flex-col items-end gap-1">
            <LocaleSwitch />
            <TextScaleSwitch />
          </div>
        </header>

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
              <span className="text-ink-faint">
                {mode === 'login' ? t('auth.email.login.hint') : t('auth.email.register.hint')}
              </span>
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
  );
}
