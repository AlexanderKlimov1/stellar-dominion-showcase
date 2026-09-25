/**
 * Настройки клиента, которые переживают перезагрузку страницы.
 *
 * Это выбор игрока, а не состояние партии: партия живёт на сервере, а настройка
 * относится к этому браузеру и действует на любую игру, открытую в нём.
 */

const REVEAL_GALAXY_KEY = 'moo3.settings.revealGalaxy';

/**
 * «Показать галактику» — карта целиком вместо одних своих систем. По умолчанию выключено:
 * остальные системы откроет механика разведки, которой пока нет.
 */
export const readRevealGalaxy = (): boolean =>
  window.localStorage.getItem(REVEAL_GALAXY_KEY) === 'on';

export const writeRevealGalaxy = (enabled: boolean): void =>
  window.localStorage.setItem(REVEAL_GALAXY_KEY, enabled ? 'on' : 'off');
