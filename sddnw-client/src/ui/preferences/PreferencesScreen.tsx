import { useState } from 'react';
import type { Key } from '../../i18n';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { HotkeysEditor } from './HotkeysEditor';
import { t, useT } from '../../i18n';

/**
 * Настройки игрока — п. 11.1: экран, на котором живут настройки САМОГО ИГРОКА, а не партии.
 *
 * Открывается и до партии (главное меню), и внутри неё (меню «Игра»): настройки принадлежат
 * учётной записи и партии не касаются вовсе — заведи игрок новую, они останутся теми же.
 *
 * <b>Разделы стоят списком слева</b>, как отчёты в окне «Инфо», хотя раздел пока один:
 * горячие клавиши. Так у следующей настройки есть место, куда встать, и встанет она строкой
 * в список, а не переделкой экрана. Язык и размер текста сюда НЕ переехали намеренно: их
 * меняют одним нажатием прямо там, где заметили (экран входа, главное меню, меню «Игра»), и
 * путь в настройки для этого был бы длиннее самого действия.
 *
 * <b>Экран модальный</b> — и это не только про Esc: пока он открыт, горячие клавиши карты
 * молчат (см. {@link GalaxyHotkeys}), а иначе назначаемая F4 успевала бы открыть родной мир
 * прямо во время назначения.
 */
export function PreferencesScreen({ onClose }: { onClose: () => void }) {
  const [section, setSection] = useState<Section>('hotkeys');
  /**
   * Пока строка ждёт нажатия, Esc принадлежит ей — он отменяет назначение, а не закрывает
   * экран. Очередь модальных экранов тут не поможет: редактор и экран рождаются в одной
   * отрисовке, и порядок оказался бы обратным ожидаемому — те же грабли, что на экране
   * исследований.
   */
  const [capturing, setCapturing] = useState(false);
  useModalEscape(!capturing, onClose);
  useT();

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 uppercase tracking-[0.3em] text-accent">
          {t('preferences.title')}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        <nav className="w-56 flex-none border-r border-space-700 py-3">
          {SECTIONS.map((one) => (
            <button
              key={one.code}
              type="button"
              className={
                'block w-full px-4 py-1 text-left '
                + (section === one.code ? 'text-accent' : 'link text-ink-soft')
              }
              onClick={() => setSection(one.code)}
            >
              {t(one.label)}
            </button>
          ))}
        </nav>

        <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
          {section === 'hotkeys' ? <HotkeysEditor onCapturing={setCapturing} /> : null}
        </div>
      </div>

      <footer className="flex flex-none items-center justify-end border-t border-space-700 px-4 py-2">
        <button type="button" className="link" onClick={onClose}>
          {t('common.close')}
        </button>
      </footer>
    </div>
  );
}

/** Разделы настроек. Новая настройка — строка здесь и свой блок справа. */
type Section = 'hotkeys';

const SECTIONS: { code: Section; label: Key }[] = [
  { code: 'hotkeys', label: 'preferences.section.hotkeys' },
];
