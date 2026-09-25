import { useState } from 'react';
import type { Race } from '../../api/types';
import { RaceNameDialog, type RaceNames } from './RaceNameDialog';
import { RacePicksScreen } from './RacePicksScreen';
import { RaceSelectScreen } from './RaceSelectScreen';
import { useT } from '../../i18n';

export interface PlayerSetup {
  playerName: string;
  /** Название родной звезды; пусто — имя выберет генератор. */
  homeStarName: string;
  /** Название расы, собранной игроком; пусто — готовая раса MOO II — п. 7. */
  raceName: string;
  /** Коды выбранных особенностей расы — п. 7. */
  raceTraits: string[];
  /**
   * Код выбранной готовой расы — п. 5; пусто — раса достанется по разнарядке.
   * Свои стороны она получит на сервере: у готовой расы MOO II они те же очки, что
   * покупает игрок, и присылать их обратно с клиента незачем.
   */
  raceCode?: string;
}

/**
 * Вход в партию: выбор расы, имена, сборка расы — тремя экранами, как в MOO II.
 *
 * Порядок оригинала: сперва выбор расы из готовых (`RaceSelectScreen`), затем окошко с
 * именами (`RaceNameDialog`), и — только если выбрана своя раса — окно сборки
 * (`RacePicksScreen`). Готовая раса окна сборки не открывает: её десять очков уже
 * потрачены за игрока, и менять в них нечего — в оригинале так же. Имена спрашиваются
 * между шагами не от хорошей жизни: название расы стоит в заголовке окна сборки, и
 * вводить его среди столбцов со сторонами негде.
 *
 * Каждый экран возвращает на предыдущий — и кнопкой, и по Esc, — а с первого Esc уводит
 * в меню: игрок должен уметь передумать на любом шаге, ничего не создав.
 *
 * Собранное отдаётся наружу одним куском (`PlayerSetup`): партию создаёт меню, а этот
 * набор — то, чем игрок в неё входит.
 */
export function RaceSetupFlow({
  title,
  confirmLabel,
  playerName,
  homeStarName,
  raceName,
  raceTraits,
  onConfirm,
  onClose,
}: {
  /** Заголовок: экраны открываются и на создание партии, и на вход в чужую. */
  title: string;
  /** Подпись кнопки, заканчивающей сборку. */
  confirmLabel: string;
  /** Значения, с которыми экраны открываются: последние введённые в этом сеансе. */
  playerName: string;
  homeStarName: string;
  raceName: string;
  raceTraits: string[];
  onConfirm: (setup: PlayerSetup) => void;
  onClose: () => void;
}) {
  const { t } = useT();
  const [step, setStep] = useState<'select' | 'names' | 'picks'>('select');
  const [names, setNames] = useState<RaceNames>({ raceName, playerName, homeStarName });
  // Выбранная готовая раса; null — игрок пошёл собирать свою.
  const [ready, setReady] = useState<Race | null>(null);

  // Окошко с именами открывается поверх выбора расы, а не вместо него: в оригинале
  // выбор расы под ним и остаётся, и возврат «назад» виден заранее.
  if (step === 'select' || step === 'names') {
    return (
      <>
        <RaceSelectScreen
          title={title}
          escapeActive={step === 'select'}
          onPick={(race) => {
            setReady(race);
            setStep('names');
          }}
          onCustom={() => {
            setReady(null);
            setStep('names');
          }}
          onClose={onClose}
        />
        {step === 'names' ? (
          <RaceNameDialog
            names={names}
            // У готовой расы название своё, и менять его нельзя: имя расы — это и есть
            // признак «собрал сам», по нему сервер отличает свою расу от готовой.
            readyRaceName={ready?.name}
            confirmLabel={ready === null ? t('race.names.toPicks') : confirmLabel}
            onBack={() => setStep('select')}
            onSubmit={(entered) => {
              setNames(entered);
              if (ready === null) {
                setStep('picks');
                return;
              }
              // Готовая раса собрана за игрока: стороны ей выдаст сервер по коду.
              onConfirm({
                playerName: entered.playerName,
                homeStarName: entered.homeStarName,
                raceName: '',
                raceTraits: [],
                raceCode: ready.code,
              });
            }}
          />
        ) : null}
      </>
    );
  }

  return (
    <RacePicksScreen
      raceName={names.raceName}
      traits={raceTraits}
      confirmLabel={confirmLabel}
      onBack={() => setStep('names')}
      onAccept={(traits) =>
        onConfirm({
          playerName: names.playerName,
          homeStarName: names.homeStarName,
          raceName: names.raceName,
          raceTraits: traits,
        })
      }
    />
  );
}
