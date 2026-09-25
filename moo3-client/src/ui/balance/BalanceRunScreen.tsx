import { useCallback, useEffect, useMemo, useState } from 'react';
import { readBalanceRun, writeBalanceRun } from '../../state/session';
import { gameApi } from '../../api/client';
import type {
  BalanceCombination,
  BalanceRun,
  BalanceRunPayload,
  BalanceSynergy,
  Race,
  RaceDesign,
} from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Пульт балансировки — этап 2 плана (`balance-metrics-works.txt`).
 *
 * Хозяин игры описывает прогон (галактика, сколько империй и чем играют, сколько партий и
 * по сколько ходов), сервер играет их в фоне, а пульт показывает не сырые замеры, а
 * приговор каждой цене: оценена адекватно, слишком дорого или слишком дёшево.
 *
 * Экран намеренно не ждёт конца прогона: тот идёт десятки минут. Страницу можно закрыть и
 * вернуться — прогон живёт записью в базе, а пульт спрашивает его раз в несколько секунд.
 */
export function BalanceRunScreen({ onClose }: { onClose: () => void }) {
  const [runs, setRuns] = useState<BalanceRun[]>([]);
  /*
    ВЫБРАННЫЙ ПРОГОН ПОМНИТСЯ, как и сам экран (state/session.ts). Вернуть пульт мало: без
    выбранного прогона хозяин видит список вместо полосы хода, за которой следил, — а
    следят за ней часами.
  */
  const [openId, setOpenIdState] = useState<string | null>(() => readBalanceRun());
  const setOpenId = (next: string | null) => {
    setOpenIdState(next);
    writeBalanceRun(next);
  };
  const [design, setDesign] = useState<RaceDesign | null>(null);
  const [races, setRaces] = useState<Race[]>([]);
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [empires, setEmpires] = useState(6);
  const [games, setGames] = useState(12);
  const [turns, setTurns] = useState(150);
  const [galaxySize, setGalaxySize] = useState('SMALL');
  const [slots, setSlots] = useState<string[]>([]);
  // Мест под связку три: две стороны — пара, три — тройка. Пустое место не считается.
  const [combination, setCombination] = useState<[string, string, string]>(['', '', '']);
  const [known, setKnown] = useState<BalanceCombination[]>([]);
  // Род прогона: приговоры ценам (этап 2) или поиск сильнейших сборок (этап 3).
  const [kind, setKind] = useState<'MEASURE' | 'ORACLE'>('MEASURE');
  const [population, setPopulation] = useState(24);
  const [note, setNote] = useState('');

  useModalEscape(true, onClose);
  useT();

  const refresh = useCallback(async () => {
    try {
      setRuns(await gameApi.listBalanceRuns());
    } catch (error) {
      setFailure((error as { message?: string }).message ?? t('balance.listFailed'));
    }
  }, []);

  useEffect(() => {
    void refresh();
    void gameApi.reference.raceTraits().then(setDesign).catch(() => undefined);
    void gameApi.reference.races().then(setRaces).catch(() => undefined);
    void gameApi.listBalanceCombinations().then(setKnown).catch(() => undefined);
  }, [refresh]);

  // Прогон идёт десятки минут, и всё это время пульт должен показывать, сколько партий
  // сыграно. Пока прогон идёт, спрашиваем часто; пока не идёт — редко, но СПРАШИВАЕМ.
  //
  // Раньше опрос при затишье выключался вовсе («висеть на сервере без нужды незачем»), и
  // выходило, что пульт не мог заметить прогон, заведённый НЕ ИЗ НЕГО. А круг балансировки
  // так и заводит: замер, потом оракула — из скрипта, и между ними есть промежуток, где не
  // идёт ничего. Страница, обновившаяся в этот промежуток, замирала навсегда: оракул шёл, а
  // экран показывал пусто, и отличить это от «оракул не запустился» было нечем.
  const running = runs.some((run) => run.status === 'RUNNING' || run.status === 'PAUSED');
  useEffect(() => {
    const timer = window.setInterval(() => void refresh(), running ? 4000 : 20000);
    return () => window.clearInterval(timer);
  }, [running, refresh]);

  const budget = design?.picks ?? 15;
  const open = useMemo(() => runs.find((run) => run.id === openId) ?? null, [runs, openId]);

  // Мест ровно столько, сколько империй: список подрезается и добивается при смене числа.
  useEffect(() => {
    setSlots((current) => {
      const next = current.slice(0, empires);
      while (next.length < empires) {
        next.push('');
      }
      return next;
    });
  }, [empires]);

  const chosen = combination.filter((code) => code !== '');

  async function start() {
    setBusy(true);
    setFailure(null);
    try {
      const payload: BalanceRunPayload = {
        galaxySize,
        empires,
        games,
        turns,
        // Пустое место — случайная законная сборка на полный бюджет: именно из них
        // регрессия и вынимает ценности сторон.
        // Пустое место уходит без бюджета: это значит «свой на каждую партию».
        races: slots.map((code) => (code ? { raceCode: code } : {})),
        // Связка уходит только целиком: одна сторона без второй ничего не проверяет.
        combination: kind === 'MEASURE' && chosen.length >= 1 ? chosen : undefined,
        kind,
        population: kind === 'ORACLE' ? population : undefined,
      };
      const started = await gameApi.startBalanceRun(payload);
      setOpenId(started.id);
      await refresh();
    } catch (error) {
      setFailure((error as { message?: string }).message ?? t('balance.startFailed'));
    } finally {
      setBusy(false);
    }
  }

  /** Запомнить догадку: замер её не восстановит, а через месяц её не вспомнить. */
  async function remember() {
    setFailure(null);
    try {
      setKnown(await gameApi.rememberBalanceCombination(chosen, note || undefined).then(
        () => gameApi.listBalanceCombinations(),
      ));
      setNote('');
    } catch (error) {
      setFailure((error as { message?: string }).message ?? t('balance.rememberFailed'));
    }
  }

  async function forget(id: string) {
    try {
      await gameApi.forgetBalanceCombination(id);
      setKnown(await gameApi.listBalanceCombinations());
    } catch (error) {
      setFailure((error as { message?: string }).message ?? t('balance.forgetFailed'));
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header className="flex flex-none items-center justify-between border-b border-space-700 px-4 py-2">
        <span className="uppercase tracking-[0.2em] text-ink-dim">{t('balance.title')}</span>
        <button type="button" className="link" onClick={onClose}>
          {t('balance.toMenu')}
        </button>
      </header>

      <div className="flex min-h-0 flex-1 gap-4 overflow-hidden p-4">
        {/* Слева — заказ прогона и список прогонов. */}
        <aside className="flex w-80 flex-none flex-col gap-3 overflow-y-auto">
          <section className="panel">
            <div className="panel-title">{t('balance.newRun')}</div>

            <div className="mt-2 flex gap-1">
              {(['MEASURE', 'ORACLE'] as const).map((one) => (
                <button
                  key={one}
                  type="button"
                  className={
                    'flex-1 border px-2 py-1 text-11 ' +
                    (kind === one
                      ? 'border-accent text-accent'
                      : 'border-space-700 text-ink-soft hover:border-space-600')
                  }
                  onClick={() => setKind(one)}
                >
                  {t(one === 'MEASURE' ? 'balance.kind.MEASURE' : 'balance.kind.ORACLE')}
                </button>
              ))}
            </div>
            <p className="mt-1 text-10 leading-snug text-ink-faint">
              {kind === 'MEASURE'
                ? t('balance.kind.MEASURE.hint')
                : t('balance.kind.ORACLE.hint')}
            </p>

            <label className="mt-2 block text-11 text-ink-soft">
              {t('balance.galaxySize')}
              <select
                className="field mt-1 w-full"
                value={galaxySize}
                onChange={(event) => setGalaxySize(event.target.value)}
              >
                <option value="SMALL">Small</option>
                <option value="MEDIUM">Medium</option>
                <option value="LARGE">Large</option>
                <option value="HUGE">Huge</option>
              </select>
            </label>

            <div className="mt-2 grid grid-cols-3 gap-2">
              <NumberField label={t('balance.empires')} value={empires} min={2} max={8} onChange={setEmpires} />
              <NumberField label={t('balance.games')} value={games} min={1} max={2000} onChange={setGames} />
              <NumberField label={t('balance.turns')} value={turns} min={10} max={1000} onChange={setTurns} />
            </div>

            {kind === 'ORACLE' ? (
              <div className="mt-2">
                <NumberField
                  label={t('balance.population')}
                  value={population}
                  min={4}
                  max={200}
                  onChange={setPopulation}
                />
                <p className="mt-1 text-10 leading-snug text-ink-faint">
                  {t('balance.population.hint')}
                </p>
              </div>
            ) : null}

            <div className={kind === 'ORACLE' ? 'hidden' : ''}>
            <div className="mt-3 text-11 text-ink-soft">{t('balance.whatEmpiresPlay')}</div>
            <p className="mt-1 text-10 leading-snug text-ink-faint">
              {t('balance.whatEmpiresPlay.hint', { budget })}
            </p>
            {slots.map((code, index) => (
              <select
                // Место в партии — и есть его тождество: список подрезается по числу империй.
                key={`slot-${index}`}
                className="field mt-1 w-full"
                value={code}
                onChange={(event) =>
                  setSlots((current) =>
                    current.map((one, at) => (at === index ? event.target.value : one)),
                  )
                }
              >
                <option value="">{t('balance.slot.random', { n: index + 1 })}</option>
                {races.map((race) => (
                  <option key={race.code} value={race.code}>
                    {t('balance.slot.race', { n: index + 1, race: race.name })}
                  </option>
                ))}
              </select>
            ))}

            <div className="mt-3 text-11 text-ink-soft">{t('balance.plant')}</div>
            <p className="mt-1 text-10 leading-snug text-ink-faint">
              {t('balance.plant.hint')}
            </p>
            {[0, 1, 2].map((part) => (
              <select
                key={`combination-${part}`}
                className="field mt-1 w-full"
                value={combination[part]}
                onChange={(event) =>
                  setCombination((current) => {
                    const next: [string, string, string] = [current[0], current[1], current[2]];
                    next[part] = event.target.value;
                    return next;
                  })
                }
              >
                <option value="">
                  {t(part === 2 ? 'balance.plant.third' : 'balance.plant.none')}
                </option>
                {(design?.groups ?? []).flatMap((group) =>
                  group.options.map((trait) => (
                    <option key={trait.code} value={trait.code}>
                      {trait.name} ({trait.picks > 0 ? `+${trait.picks}` : trait.picks})
                    </option>
                  )),
                )}
              </select>
            ))}
            {chosen.length >= 2 ? (
              <div className="mt-1 flex gap-2">
                <input
                  className="field w-full"
                  placeholder={t('balance.note.placeholder')}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                />
                <button type="button" className="link whitespace-nowrap" onClick={() => void remember()}>
                  {t('balance.remember')}
                </button>
              </div>
            ) : null}

            </div>

            <button
              type="button"
              className="mt-3 w-full border border-space-600 px-3 py-1 uppercase tracking-[0.2em] text-accent hover:bg-space-700 disabled:pointer-events-none disabled:text-ink-faint"
              disabled={busy || running}
              onClick={() => void start()}
            >
              {running ? t('balance.alreadyRunning') : busy ? t('balance.starting') : t('balance.start')}
            </button>
            {failure ? <p className="mt-2 text-11 text-danger">{failure}</p> : null}
          </section>

          <section className="panel">
            <div className="panel-title">{t('balance.runs')}</div>
            {runs.length === 0 ? (
              <p className="mt-2 text-ink-faint">{t('balance.runs.none')}</p>
            ) : (
              runs.map((run) => (
                <button
                  key={run.id}
                  type="button"
                  className={
                    'mt-1 block w-full border px-2 py-1 text-left ' +
                    (run.id === openId
                      ? 'border-accent text-accent'
                      : 'border-space-700 hover:border-space-600')
                  }
                  onClick={() => setOpenId(run.id)}
                >
                  <span className="block">
                    {new Date(run.createdAt).toLocaleString()} · {run.games} × {run.turns}
                    {run.kind === 'ORACLE' ? t('balance.oracleMark') : ''}
                  </span>
                  <span className="block text-10 text-ink-dim">
                    {run.status === 'RUNNING'
                      ? t('balance.status.running', { played: run.played, games: run.games })
                      : run.status === 'PAUSED'
                        ? t('balance.status.paused', { played: run.played, games: run.games })
                        : run.status === 'FAILED'
                          ? t('balance.status.failed', { reason: run.failure ?? '' })
                          : t('balance.status.finished', { n: run.measured ?? 0 })}
                  </span>
                  {run.status === 'RUNNING' || run.status === 'PAUSED' ? (
                    <ProgressBar run={run} thin />
                  ) : null}
                </button>
              ))
            )}
          </section>
          <section className="panel">
            <div className="panel-title">{t('balance.known')}</div>
            <p className="mt-1 text-10 leading-snug text-ink-faint">
              {t('balance.known.hint')}
            </p>
            {known.length === 0 ? (
              <p className="mt-2 text-ink-faint">{t('balance.known.none')}</p>
            ) : (
              known.map((one) => (
                <div key={one.id} className="mt-2 border-t border-space-800 pt-1">
                  <button
                    type="button"
                    className="block w-full text-left text-accent"
                    onClick={() =>
                      setCombination([one.traits[0] ?? '', one.traits[1] ?? '', one.traits[2] ?? ''])
                    }
                  >
                    ▸ {one.names.join(' + ')}
                  </button>
                  {one.note ? <p className="text-10 text-ink-dim">{one.note}</p> : null}
                  <p className="text-10 text-ink-faint">
                    {one.checkedAt
                      ? `${one.verdictLabel}${
                          one.extra === undefined || one.extra === null
                            ? ''
                            : t('balance.known.measured', { extra: one.extra.toFixed(2), error: one.error?.toFixed(2) ?? '—', carriers: one.carriers ?? 0 })
                        }`
                      : t('balance.known.unchecked')}
                    <button
                      type="button"
                      className="link ml-2 text-10"
                      onClick={() => void forget(one.id)}
                    >
                      {t('balance.forget')}
                    </button>
                  </p>
                </div>
              ))
            )}
          </section>
        </aside>

        {/* Справа — приговоры выбранного прогона. */}
        <main className="min-h-0 flex-1 overflow-y-auto">
          {open ? (
            <RunDetails
              run={open}
              onReassess={async () => {
                await gameApi.reassessBalanceRun(open.id);
                await refresh();
              }}
              onPauseToggle={async () => {
                if (open.status === 'PAUSED') {
                  await gameApi.resumeBalanceRun(open.id);
                } else {
                  await gameApi.pauseBalanceRun(open.id);
                }
                await refresh();
              }}
            />
          ) : (
            <Hint />
          )}
        </main>
      </div>
    </div>
  );
}

/** Что показывает пульт, пока прогон не выбран: чем он вообще меряет. */
function Hint() {
  return (
    <section className="panel">
      <div className="panel-title">{t('balance.how')}</div>
      <p className="mt-2 leading-relaxed text-ink-soft">
        {t('balance.how.1')}
      </p>
      <p className="mt-2 leading-relaxed text-ink-soft">
        {t('balance.how.2.a')} <b>{t('balance.how.2.b')}</b> {t('balance.how.2.c')}
      </p>
      <p className="mt-2 leading-relaxed text-ink-soft">
        {t('balance.how.3')}
      </p>
      <p className="mt-2 leading-relaxed text-ink-soft">
        {t('balance.how.4')}
      </p>
      <p className="mt-2 leading-relaxed text-ink-soft">
        {t('balance.how.5.a')} <b>{t('balance.how.5.b')}</b> {t('balance.how.5.c')}
      </p>
    </section>
  );
}

/**
 * С какого числа партий приговоры перестают быть наводкой.
 *
 * Взято из замеров этапа 1: на 240 партиях ошибка доли выработки около 0,4 п.п., на
 * восьми — единицы п.п., и курс очка на такой выборке неотличим от нуля. Сотня — та
 * граница, за которой разница между сильной и слабой стороной уже видна.
 */
const SOLID_GAMES = 100;

function RunDetails({
  run,
  onReassess,
  onPauseToggle,
}: {
  run: BalanceRun;
  onReassess: () => Promise<void>;
  onPauseToggle: () => Promise<void>;
}) {
  const judged = run.judgements.filter((one) => one.verdict !== 'NOT_MEASURED');
  const silent = run.judgements.filter((one) => one.verdict === 'NOT_MEASURED');
  // Связок у прогонов, посчитанных до того, как их научились искать, нет вовсе.
  const synergies = run.synergies ?? [];
  const planted = run.combination ?? [];
  // Подсаженная связка идёт первой строкой независимо от того, устояла она или нет: её
  // проверяли нарочно, и ответ «не устояла» — такой же ответ, как и находка.
  const isPlanted = (one: BalanceSynergy) =>
    planted.length >= 2
    && planted.length === one.members.length
    && one.members.every((code) => planted.includes(code));
  const found = synergies.filter((one) => one.verdict !== 'PLAIN' || isPlanted(one));
  // Связок не нашлось — показываем самые крупные из взвешенных: пустой раздел читался бы
  // как «пары не считали», а это разные вещи.
  const shown = (found.length > 0 ? found : synergies.slice(0, 8))
    .slice()
    .sort((a, b) => Number(isPlanted(b)) - Number(isPlanted(a)));

  return (
    <div className="flex flex-col gap-3">
      <section className="panel">
        <div className="panel-title">{t('balance.run')}</div>
        <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1 text-ink-soft">
          <Line name={t('balance.run.createdAt')} value={new Date(run.createdAt).toLocaleString()} />
          <Line name={t('balance.run.galaxy')} value={t('balance.run.galaxy.value', { size: run.galaxySize, empires: run.empires })} />
          <Line name={t('balance.run.games')} value={t('balance.run.games.value', { played: run.played, games: run.games, turns: run.turns })} />
          <Line name={t('balance.run.seed')} value={String(run.seed)} />
          {run.pointValue !== undefined && run.pointValue !== null ? (
            <Line name={t('balance.run.pointValue')} value={t('balance.run.pointValue.value', { n: run.pointValue.toFixed(3) })} />
          ) : null}
          {run.residual !== undefined && run.residual !== null ? (
            <Line name={t('balance.run.residual')} value={t('balance.run.residual.value', { n: run.residual.toFixed(1) })} />
          ) : null}
          <Line name={t('balance.run.measured')} value={String(run.measured ?? 0)} />
          {run.combination?.length >= 2 ? (
            <Line
              name={t('balance.run.combination')}
              value={run.combination
                .map((code) => run.judgements.find((one) => one.code === code)?.name ?? code)
                .join(' + ')}
            />
          ) : null}
        </div>
        <p className="mt-2 text-10 leading-snug text-ink-faint">
          {t('balance.run.snapshot')}
        </p>
        {run.residual !== undefined && run.residual !== null ? (
          <p className="mt-1 text-10 leading-snug text-ink-faint">
            <b>{t('balance.run.residual.note.a')}</b>{t('balance.run.residual.note.b')}
          </p>
        ) : null}
        {run.status === 'FINISHED' ? (
          <button
            type="button"
            className="link mt-2 text-11"
            onClick={() => void onReassess()}
          >
            {t('balance.reassess')}
          </button>
        ) : null}
        {run.games < SOLID_GAMES ? (
          <p className="mt-1 text-10 leading-snug text-warn/80">
            {t('balance.fewGames', { n: SOLID_GAMES })}
          </p>
        ) : null}
      </section>

      {run.status === 'RUNNING' || run.status === 'PAUSED' ? (
        <section className="panel">
          <div className="panel-title">{t(run.status === 'PAUSED' ? 'balance.paused' : 'balance.going')}</div>
          <ProgressBar run={run} />
          <p className="mt-2 text-ink-soft">
            {t('balance.played', { played: run.played, games: run.games })}
          </p>
          {/*
            Пауза МЯГКАЯ: новых партий прогон не начинает, а начатые доигрывает — бросать
            партию посередине нельзя, её замер пропал бы. Поэтому между нажатием и тишиной
            проходит до нескольких минут, и сыгранных всё это время прибавляется.
            И пауза не переживает перезапуск сервера: партии прогона живут в памяти.
          */}
          <p className="mt-2 text-11 leading-snug text-ink-dim">
            {run.status === 'PAUSED'
              ? t('balance.paused.note')
              : t('balance.going.note')}
          </p>
          <button type="button" className="btn mt-3" onClick={() => void onPauseToggle()}>
            {t(run.status === 'PAUSED' ? 'balance.resume' : 'balance.pause')}
          </button>
        </section>
      ) : null}

      {run.oracle ? (
        <section className="panel">
          <div className="panel-title">{t('balance.oracle')}</div>
          <p className="mt-1 text-10 leading-snug text-ink-faint">
            {t('balance.oracle.hint', { generations: run.oracle.generations, ladder: run.oracle.ladder.length })}
          </p>
          <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1 text-ink-soft">
            <Line name={t('balance.oracle.best')} value={t('balance.oracle.pp', { n: run.oracle.best?.toFixed(2) ?? '—' })} />
            <Line name={t('balance.oracle.median')} value={t('balance.oracle.pp', { n: run.oracle.median?.toFixed(2) ?? '—' })} />
            <Line name={t('balance.oracle.gap')} value={t('balance.oracle.pp', { n: run.oracle.gap?.toFixed(2) ?? '—' })} />
          </div>
          <p
            className={
              'mt-2 leading-snug ' +
              (run.oracle.dominated ? 'text-warn' : 'text-good')
            }
          >
            {run.oracle.dominated
              ? t('balance.oracle.dominated')
              : t('balance.oracle.even')}
          </p>

          <table className="mt-2 w-full">
            <thead className="text-10 uppercase tracking-wider text-ink-faint">
              <tr>
                <th className="py-1 text-left">{t('balance.oracle.col.build')}</th>
                <th className="py-1 text-right">{t('balance.oracle.col.points')}</th>
                <th className="py-1 text-right">{t('balance.oracle.col.games')}</th>
                <th className="py-1 text-right">{t('balance.oracle.col.strength')}</th>
              </tr>
            </thead>
            <tbody>
              {run.oracle.ladder.slice(0, 20).map((one, at) => (
                <tr key={one.traits.join('+') + at} className="border-t border-space-800">
                  <td className="py-1 leading-snug">{one.names.join(', ')}</td>
                  <td className="py-1 text-right text-ink-soft">{one.budget}</td>
                  <td className="py-1 text-right text-ink-faint">{one.games}</td>
                  <td className="py-1 text-right">
                    {one.strength?.toFixed(2)}
                    {one.error === undefined || one.error === null ? null : (
                      <span className="text-ink-faint"> ± {one.error.toFixed(2)}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {run.oracle.unseenNames?.length ? (
            <>
              <p className="mt-3 text-11 text-ink-soft">
                {t('balance.oracle.unseen', { n: run.oracle.unseenNames.length })}
              </p>
              <p className="mt-1 text-10 leading-snug text-ink-faint">
                {t('balance.oracle.unseen.hint')}
              </p>
              <p className="mt-1 leading-relaxed text-ink-dim">
                {run.oracle.unseenNames.join(', ')}
              </p>
            </>
          ) : null}

          {run.oracle.deadNames.length > 0 ? (
            <>
              <p className="mt-3 text-11 text-ink-soft">
                {t('balance.oracle.dead', { n: run.oracle.deadNames.length })}
              </p>
              <p className="mt-1 text-10 leading-snug text-ink-faint">
                {t('balance.oracle.dead.hint')}
              </p>
              <p className="mt-1 leading-relaxed text-ink-dim">
                {run.oracle.deadNames.join(', ')}
              </p>
            </>
          ) : (
            <p className="mt-3 text-11 text-good">
              {t('balance.oracle.noDead')}
            </p>
          )}
        </section>
      ) : null}

      {judged.length > 0 ? (
        <section className="panel">
          <div className="panel-title">{t('balance.verdicts')}</div>
          <p className="mt-1 text-10 leading-snug text-ink-faint">
            {t('balance.verdicts.hint')}
          </p>
          <table className="mt-2 w-full">
            <thead className="text-10 uppercase tracking-wider text-ink-faint">
              <tr>
                <th className="py-1 text-left">{t('balance.col.trait')}</th>
                <th className="py-1 text-right">{t('balance.col.price')}</th>
                <th className="py-1 text-right">{t('balance.col.takers')}</th>
                <th className="py-1 text-right">{t('balance.oracle.col.strength')}</th>
                <th className="py-1 text-right">{t('balance.col.output')}</th>
                <th className="py-1 text-right">{t('balance.col.science')}</th>
                <th className="py-1 text-right">{t('balance.col.espionage')}</th>
                <th className="py-1 text-right">{t('balance.col.money')}</th>
                <th className="py-1 text-right">{t('balance.col.fleet')}</th>
                <th className="py-1 text-right">{t('balance.col.ground')}</th>
                <th className="py-1 text-right">{t('balance.col.technologies')}</th>
                <th className="py-1 text-right">{t('balance.col.measured')}</th>
                <th className="py-1 text-right">{t('balance.col.step')}</th>
                <th className="py-1 text-left">{t('balance.col.verdict')}</th>
              </tr>
            </thead>
            <tbody>
              {judged.map((one) => (
                <tr key={one.code} className="border-t border-space-800">
                  <td className="py-1">{one.name}</td>
                  <td className="py-1 text-right text-ink-soft">{one.price}</td>
                  <td className="py-1 text-right text-ink-faint">{one.takers}</td>
                  <td className="py-1 text-right">
                    {one.strength?.toFixed(2)}
                    <span className="text-ink-faint"> ± {one.error?.toFixed(2)}</span>
                  </td>
                  <Measured value={one.production} />
                  <Measured value={one.research} />
                  <Measured value={one.espionage} />
                  <Measured value={one.money} />
                  <Measured value={one.military} />
                  <Measured value={one.ground} />
                  <Measured value={one.technology} />
                  <td className="py-1 text-right text-ink-soft">
                    {one.fairPrice === undefined || one.fairPrice === null
                      ? '—'
                      : one.fairPrice.toFixed(1)}
                  </td>
                  <td className="py-1 text-right">
                    {one.recommendedPrice === undefined || one.recommendedPrice === null ? (
                      <span className="text-ink-faint">—</span>
                    ) : (
                      <span className="text-accent">
                        {one.price} → {one.recommendedPrice}
                      </span>
                    )}
                  </td>
                  <td className={'py-1 pl-2 ' + verdictColour(one.verdict)}>{one.verdictLabel}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ) : null}

      {synergies.length > 0 ? (
        <section className="panel">
          <div className="panel-title">{t('balance.synergies')}</div>
          <p className="mt-1 text-10 leading-snug text-ink-faint">
            {t('balance.synergies.hint')}
          </p>
          <table className="mt-2 w-full">
            <thead className="text-10 uppercase tracking-wider text-ink-faint">
              <tr>
                <th className="py-1 text-left">{t('balance.synergies.col.combination')}</th>
                <th className="py-1 text-right">{t('balance.col.price')}</th>
                <th className="py-1 text-right">{t('balance.synergies.col.both')}</th>
                <th className="py-1 text-right">{t('balance.synergies.col.extra')}</th>
                <th className="py-1 text-right">{t('balance.synergies.col.together')}</th>
                <th className="py-1 text-left">{t('balance.col.verdict')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((one) => (
                <tr key={one.members.join('+')} className="border-t border-space-800">
                  <td className="py-1">
                    {isPlanted(one) ? <span className="text-accent">▸ </span> : null}
                    {one.names.join(' + ')}
                  </td>
                  <td className="py-1 text-right text-ink-soft">{one.price}</td>
                  <td className="py-1 text-right text-ink-faint">{one.pairs}</td>
                  <td className="py-1 text-right">
                    {one.extra?.toFixed(2)}
                    <span className="text-ink-faint"> ± {one.error?.toFixed(2)}</span>
                  </td>
                  <td className="py-1 text-right text-ink-soft">{one.together?.toFixed(2)}</td>
                  <td className={'py-1 pl-2 ' + pairingColour(one.verdict)}>{one.verdictLabel}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {found.length > 0 ? (
            <p className="mt-2 text-10 leading-snug text-ink-faint">
              {t('balance.synergies.rest', { n: synergies.length })}
            </p>
          ) : (
            <p className="mt-2 text-10 leading-snug text-ink-faint">
              {t('balance.synergies.none.a', { n: synergies.length })}
              {planted.length >= 2 ? t('balance.synergies.none.planted') : ''}
              {t('balance.synergies.none.b')}
            </p>
          )}
        </section>
      ) : null}

      {silent.length > 0 ? (
        <section className="panel">
          <div className="panel-title">{t('balance.silent')}</div>
          <p className="mt-1 text-10 leading-snug text-ink-faint">
            {t('balance.silent.hint')}
          </p>
          <p className="mt-2 leading-relaxed text-ink-dim">
            {silent.map((one) => one.name).join(', ')}
          </p>
        </section>
      ) : null}
    </div>
  );
}

/** Сила по одному мерилу: пусто значит «мерило в этом прогоне не сработало». */
function Measured({ value }: { value?: number }) {
  return (
    <td className="py-1 text-right text-ink-dim">
      {value === undefined || value === null ? '—' : value.toFixed(2)}
    </td>
  );
}

/** Связка, усиливающая стороны, — находка; мешающая — тоже, но другого рода. */
function pairingColour(verdict: string): string {
  return verdict === 'SYNERGY' ? 'text-good' : 'text-warn';
}

function verdictColour(verdict: string): string {
  if (verdict === 'FAIR') {
    return 'text-good';
  }
  return verdict === 'TOO_EXPENSIVE' ? 'text-warn' : 'text-accent';
}

/**
 * Сколько прогона позади — п. 2 этапа 2.
 *
 * Прогон идёт часами (500 партий по 500 ходов — это больше двух часов), и «сыграно 137 из
 * 500» словами не отвечает на единственный вопрос, который у хозяина прогона есть:
 * успеет ли он к сроку. Поэтому рядом с долей стоит ОСТАТОК ВРЕМЕНИ, а считается он по
 * самому прогону — сколько партий он отыграл с момента заказа, столько же будет играть и
 * дальше. Своего состояния экрану для этого не нужно: он и так перечитывает прогон каждые
 * четыре секунды.
 */
/** Сколько секунд назад мы ещё помним скорость прогона. */
const RATE_WINDOW_MS = 12 * 60 * 1000;

function ProgressBar({ run, thin }: { run: BalanceRun; thin?: boolean }) {
  const played = run.played ?? 0;
  const total = run.games || 1;
  const share = Math.max(0, Math.min(1, played / total));

  // СКОРОСТЬ СЧИТАЕТСЯ ПО ПОСЛЕДНИМ ПАРТИЯМ, А НЕ ПО ВСЕМУ ПРОГОНУ.
  //
  // Было среднее с начала: остаток = (сколько осталось) * (время с начала) / (сыграно).
  // В первые минуты это среднее раздуто разогревом — поднимается сервер, прогревается JIT,
  // генерируются галактики, — и пульт показывал вдвое больший срок, чем выходит на самом
  // деле. На круге 5 он объявил восемь часов там, где установившаяся скорость давала шесть с
  // половиной: 46,6 секунды на партию против 55 по первым восьми. Оценка, которая пугает
  // вдвое, хуже отсутствия оценки — по ней принимают решения.
  const [trail, setTrail] = useState<{ at: number; played: number }[]>([]);
  useEffect(() => {
    setTrail([]);
  }, [run.id]);
  useEffect(() => {
    setTrail((old) => {
      const last = old[old.length - 1];
      if (last && last.played === played) {
        return old;
      }
      const now = Date.now();
      const next = [...old, { at: now, played }];
      // Держим окно, но последние две точки не выбрасываем никогда: по одной точке скорости
      // не бывает, а по нулю тем более.
      return next.filter((one, index) => index >= next.length - 2
        || now - one.at <= RATE_WINDOW_MS);
    });
  }, [played]);

  const now = Date.now();
  const first = trail[0];
  const windowed = first && played > first.played && now > first.at
    ? (now - first.at) / 1000 / (played - first.played)
    : null;
  const elapsed = (now - new Date(run.createdAt).getTime()) / 1000;
  const average = played > 0 && elapsed > 0 ? elapsed / played : null;
  const perGame = windowed ?? average;
  // Пока не сыграно ни одной партии, скорости нет и остаток неизвестен: врать «осталось
  // ноль» хуже, чем молчать.
  const left = perGame === null ? null : (total - played) * perGame;
  return (
    <div className={thin ? 'mt-1' : 'mt-2'}>
      <div className={'w-full bg-space-700 ' + (thin ? 'h-1' : 'h-2')}>
        <div
          className={'bg-accent ' + (thin ? 'h-1' : 'h-2')}
          style={{ width: `${(share * 100).toFixed(1)}%` }}
        />
      </div>
      {thin ? null : (
        <div className="mt-1 flex justify-between text-10 text-ink-dim">
          <span>{(share * 100).toFixed(1)} %</span>
          <span>{left === null ? t('balance.rateUnknown') : t('balance.left', { time: howLong(left) })}</span>
        </div>
      )}
    </div>
  );
}

/** Секунды словами: «2 ч 14 мин», «7 мин», «40 с». */
function howLong(seconds: number): string {
  if (seconds < 90) {
    return t('balance.seconds', { n: Math.round(seconds) });
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 90) {
    return t('balance.minutes', { n: minutes });
  }
  return t('balance.hours', { h: Math.floor(minutes / 60), m: minutes % 60 });
}

function Line({ name, value }: { name: string; value: string }) {
  return (
    <>
      <span className="text-ink-faint">{name}</span>
      <span>{value}</span>
    </>
  );
}

function NumberField({
  label,
  value,
  min,
  max,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="block text-11 text-ink-soft">
      {label}
      <input
        type="number"
        className="field mt-1 w-full"
        value={value}
        min={min}
        max={max}
        onChange={(event) => {
          const next = Number(event.target.value);
          onChange(Number.isFinite(next) ? Math.min(max, Math.max(min, next)) : min);
        }}
      />
    </label>
  );
}
