import { useEffect, useRef, useState } from 'react';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/** Что игрок вводит перед сборкой расы: как зовут его самого, расу и родную звезду. */
export interface RaceNames {
  raceName: string;
  playerName: string;
  homeStarName: string;
}

/**
 * Имена перед сборкой своей расы — п. 7.
 *
 * В MOO II выбор «своя раса» спрашивает имена отдельным окошком и только потом открывает
 * окно race picks: название расы стоит в его заголовке, и вводить его посреди столбцов
 * со сторонами было бы негде. Здесь тем же окошком спрашивается и имя игрока с названием
 * родной звезды — это настройки участника, и обоим место до старта партии: галактика
 * генерируется при старте, и переименовать звезду потом уже некому.
 *
 * **У своей расы название обязательно.** Своей расу делает именно оно: сервер по нему
 * отличает собранную расу от справочной, и раса без имени получает имя готовой — игрок
 * собирал стороны, а в лобби видел «Земляне» и не мог понять, взялась его сборка или нет.
 * Сам сервер остаётся терпимым (стороны он применяет и без имени) — и правильно: без
 * имени к нему приходят сборки балансового прогона, у которых имени и не бывает. А вот
 * здесь, на дороге игрока, пустое имя — это потеря собранной расы, и дальше с ним не
 * пускают.
 *
 * Название звезды необязательно: без него родная система возьмёт имя из справочника
 * звёзд, как остальные.
 */
export function RaceNameDialog({
  names,
  readyRaceName,
  confirmLabel,
  onSubmit,
  onBack,
}: {
  names: RaceNames;
  /**
   * Название выбранной готовой расы; пусто — игрок собирает свою. У готовой расы имя
   * своё и правке не подлежит: название расы это и есть признак «собрал сам», по нему
   * сервер отличает собранную расу от справочной.
   */
  readyRaceName?: string;
  /** Подпись кнопки: у готовой расы окошко заканчивает вход, у своей — ведёт дальше. */
  confirmLabel: string;
  onSubmit: (names: RaceNames) => void;
  onBack: () => void;
}) {
  const [raceName, setRaceName] = useState(names.raceName);
  const [playerName, setPlayerName] = useState(names.playerName);
  const [homeStarName, setHomeStarName] = useState(names.homeStarName);
  const firstField = useRef<HTMLInputElement>(null);

  useModalEscape(true, onBack);

  // Окошко открывается с курсором в названии расы: за ним игрок сюда и пришёл.
  useEffect(() => firstField.current?.select(), []);
  const { t } = useT();

  // Своя раса — та, у которой есть имя: см. javadoc. У готовой расы имя своё, и поле
  // названия здесь вообще не показывается.
  const custom = !readyRaceName;
  const ready = Boolean(playerName.trim()) && (!custom || Boolean(raceName.trim()));

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-space-950/90 p-8">
      <form
        role="dialog"
        aria-modal="true"
        aria-label={t('race.custom')}
        className="flex w-full max-w-md flex-col border border-space-600 bg-space-900 text-xs shadow-lg shadow-black/60"
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) {
            onSubmit({
              raceName: raceName.trim(),
              playerName: playerName.trim(),
              homeStarName: homeStarName.trim(),
            });
          }
        }}
      >
        <div className="border-b border-space-700 px-4 py-2 uppercase tracking-[0.2em] text-ink-bright">
          {readyRaceName ?? t('race.custom')}
        </div>

        <div className="flex flex-col gap-3 px-4 py-3">
          {readyRaceName ? (
            <p className="text-ink-soft">
              <span className="uppercase tracking-[0.2em] text-ink-dim">{t('race.names.race')}</span>
              <span className="mt-1 block border border-space-700 bg-space-950 px-2 py-1 text-ink-bright">
                {readyRaceName}
              </span>
            </p>
          ) : (
            <label className="block text-ink-soft">
              <span className="uppercase tracking-[0.2em] text-ink-dim">
                {t('race.names.raceName')}
                {raceName.trim() ? null : (
                  <span className="ml-2 normal-case tracking-normal text-warn">
                    {t('race.names.required')}
                  </span>
                )}
              </span>
              <input
                ref={firstField}
                className="field mt-1"
                maxLength={128}
                value={raceName}
                placeholder={t('race.names.raceName.placeholder')}
                onChange={(event) => setRaceName(event.target.value)}
              />
            </label>
          )}

          <label className="block text-ink-soft">
            <span className="uppercase tracking-[0.2em] text-ink-dim">{t('race.names.player')}</span>
            <input
              ref={readyRaceName ? firstField : undefined}
              className="field mt-1"
              maxLength={128}
              value={playerName}
              onChange={(event) => setPlayerName(event.target.value)}
            />
          </label>

          <label className="block text-ink-soft">
            <span className="uppercase tracking-[0.2em] text-ink-dim">{t('race.names.homeStar')}</span>
            <input
              className="field mt-1"
              maxLength={64}
              value={homeStarName}
              placeholder={t('race.names.homeStar.placeholder')}
              onChange={(event) => setHomeStarName(event.target.value)}
            />
          </label>

          <p className="text-11 leading-relaxed text-ink-dim">
            {t('race.names.homeStar.hint')}
          </p>
        </div>

        <div className="flex items-center justify-end gap-4 border-t border-space-700 px-4 py-2 uppercase tracking-[0.2em] text-ink">
          <button type="button" className="hover:text-accent" onClick={onBack}>
            {t('race.names.back')}
          </button>
          <button
            type="submit"
            className={ready ? 'text-accent' : 'pointer-events-none text-ink-faint'}
          >
            {confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
}
