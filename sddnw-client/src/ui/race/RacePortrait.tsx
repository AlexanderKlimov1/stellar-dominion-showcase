import { useEffect, useState, type ReactNode } from 'react';

/**
 * Портрет расы — то, что в MOO II стоит в клетке выбора расы и в окне посла
 * (`docs/moo2/race-select.png`; окно посла снимком не лежит, оно описано в
 * `docs/moo2/README.md`, раздел `diplomacy-talks.png`).
 *
 * Портреты растровые и рисуются не кодом: их делает хозяин проекта по списку
 * `docs/race-portraits.md` (что изображено, размер, имя файла). Лежат они в
 * `sddnw-client/public/races/<код расы строчными>.webp` и берутся ПО АДРЕСУ, а не импортом
 * в сборку — как запись боя на обложке: импорт сделал бы файл условием сборки, и клиент без
 * портретов не собрался бы вовсе.
 *
 * <b>Нет файла — стоит прежний знак</b> (`fallback`, обычно `RaceEmblem`): портреты
 * появляются по одному, и раса без портрета не должна оставаться пустой клеткой. Файл,
 * которого нет, запоминается на всё время работы страницы: без этого каждая перерисовка
 * экрана заново спрашивала бы сервер о тринадцати картинках.
 */
const missing = new Set<string>();

export const racePortraitUrl = (code: string) => `/races/${code.toLowerCase()}.webp`;

export function RacePortrait({ code, alt, fallback }: {
  /** Код справочной расы (`HUMANS`); пусто — портрета нет вовсе, стоит знак. */
  code?: string;
  alt: string;
  fallback: ReactNode;
}) {
  const [failed, setFailed] = useState(() => !code || missing.has(code));
  useEffect(() => {
    setFailed(!code || missing.has(code));
  }, [code]);

  if (failed || !code) {
    return <>{fallback}</>;
  }
  return (
    <img
      src={racePortraitUrl(code)}
      alt={alt}
      className="h-full w-full object-cover"
      onError={() => {
        missing.add(code);
        setFailed(true);
      }}
    />
  );
}
