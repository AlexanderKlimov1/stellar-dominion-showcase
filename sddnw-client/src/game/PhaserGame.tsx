import { useEffect, useRef, useState } from 'react';
import Phaser from 'phaser';
import { useT } from '../i18n';
import { GalaxyScene } from './scenes/GalaxyScene';
import type { GalaxyMap } from '../api/types';
import { gameEvents } from '../events/gameEvents';
import { selectSelf, useGameStore } from '../state/gameStore';
import { readView } from '../state/session';
import { fleetReach } from '../ui/fleet/fleetShips';

/**
 * Обёртка движка Phaser 3 для React.
 *
 * React только монтирует канвас и отдаёт сцене карту через шину событий;
 * всё остальное (панорамирование, зум, выделение) живёт внутри сцены.
 */
export function PhaserGame() {
  const containerRef = useRef<HTMLDivElement>(null);
  const gameRef = useRef<Phaser.Game | null>(null);

  const map = useGameStore((state) => state.map);
  const players = useGameStore((state) => state.players);
  const fleet = useGameStore((state) => state.fleet);
  const self = useGameStore(selectSelf);
  const setTargetingFleet = useGameStore((state) => state.setTargetingFleet);
  const setFlightFleet = useGameStore((state) => state.setFlightFleet);
  const setFleetOrder = useGameStore((state) => state.setFleetOrder);
  const targetingFleetId = useGameStore((state) => state.targetingFleetId);
  // Номер хода, а не сама партия: он нужен сцене, чтобы поставить летящий флот на его
  // место в пути, — и он же не спорит с именем движка Phaser ниже.
  const turn = useGameStore((state) => state.game?.turn ?? 1);
  const setSelectedSystem = useGameStore((state) => state.setSelectedSystem);
  const setHoveredSystem = useGameStore((state) => state.setHoveredSystem);
  const setOpenedSystem = useGameStore((state) => state.setOpenedSystem);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const setOverlay = useGameStore((state) => state.setOverlay);
  const setShowLabels = useGameStore((state) => state.setShowLabels);
  const setZoom = useGameStore((state) => state.setZoom);

  /*
    Графика потеряна: браузер отнял у страницы контекст WebGL (сон видеокарты, смена
    драйвера, слишком много вкладок с 3D). Phaser 3.90 пробует вернуть его сам и сообщает
    об этом событием, а пока он молчит, карта просто чёрная — и игрок, у которого вся
    игра при этом работает, видит «игра сломалась». Поэтому потерю называем словами и
    предлагаем перезагрузку, а вернулся контекст — надпись уходит сама (трек техдолга,
    пункт 13).
  */
  const [graphicsLost, setGraphicsLost] = useState(false);
  const { t } = useT();

  // Создание движка: один раз на монтирование компонента.
  useEffect(() => {
    const container = containerRef.current;
    if (!container || gameRef.current) {
      return;
    }

    /*
      ДВИЖОК ПОДНИМАЕТСЯ ТОЛЬКО НА ХОЛСТЕ С РАЗМЕРОМ. Родившись на 0×0 — вкладка открылась
      скрытой, панель ещё не развернулась, — Phaser падает на первом же кадре с
      «Framebuffer status: Incomplete Attachment» и больше не рисует НИЧЕГО: галактика
      чёрная навсегда, хотя игра идёт. Поэтому ждём, пока у контейнера появится ширина и
      высота, и только тогда заводим движок; дальше размер ведёт сам Phaser (RESIZE).

      Размер спрашивается и СРАЗУ, а не только наблюдателем: наблюдатель отвечает на
      кадре отрисовки, а у скрытой вкладки кадров нет вовсе — движок с готовым размером
      ждал бы, пока вкладку покажут. Наблюдатель нужен ровно для нулевого холста.
    */
    let game: Phaser.Game | null = null;
    const sized = () => container.clientWidth > 0 && container.clientHeight > 0;
    const observer = new ResizeObserver(() => {
      if (game || !sized()) {
        return;
      }
      observer.disconnect();
      game = start(container);
    });
    if (sized()) {
      game = start(container);
    } else {
      observer.observe(container);
    }

    return () => {
      observer.disconnect();
      if (!game) {
        return;
      }
      // Снимаем подписки сцены явно: на лайфцикл Phaser здесь положиться нельзя.
      const scene = game.scene?.getScene(GalaxyScene.KEY) as GalaxyScene | null;
      scene?.detachUiListeners();

      game.destroy(true);
      gameRef.current = null;

      // Phaser откладывает destroy до следующего шага игрового цикла. Если движок
      // ещё загружался (так делает StrictMode в dev: создать — уничтожить — создать),
      // шага не будет, и мёртвый канвас останется в DOM первым ребёнком контейнера,
      // перекрывая канвас живой игры. Подчищаем контейнер сами.
      container.replaceChildren();
    };
  }, []);

  /** Заводит движок в контейнере, у которого уже есть размер. */
  const start = (container: HTMLDivElement): Phaser.Game => {
    const game = new Phaser.Game({
      type: Phaser.AUTO,
      parent: container,
      backgroundColor: '#05070f',
      scale: {
        mode: Phaser.Scale.RESIZE,
        width: '100%',
        height: '100%',
      },
      render: { antialias: true },
      scene: [GalaxyScene],
    });
    gameRef.current = game;

    // Рендерер появляется при загрузке движка, а не в конструкторе: подписка — после неё.
    // У холста без WebGL (Phaser.AUTO откатился на Canvas) терять нечего.
    game.events.once(Phaser.Core.Events.READY, () => {
      if (game.renderer instanceof Phaser.Renderer.WebGL.WebGLRenderer) {
        game.renderer.on(Phaser.Renderer.Events.LOSE_WEBGL, () => setGraphicsLost(true));
        game.renderer.on(Phaser.Renderer.Events.RESTORE_WEBGL, () => setGraphicsLost(false));
      }
    });

    // Отладочный доступ к движку из консоли браузера; в сборку не попадает.
    if (import.meta.env.DEV) {
      (window as unknown as { sddnw?: unknown }).sddnw = { game, events: gameEvents };
    }
    return game;
  };

  // События Phaser → React.
  useEffect(() => {
    const offs = [
      gameEvents.on('system:selected', ({ system }) => setSelectedSystem(system)),
      gameEvents.on('system:hovered', ({ system }) => setHoveredSystem(system)),
      gameEvents.on('system:opened', ({ system }) => setOpenedSystem(system)),
      gameEvents.on('camera:changed', ({ zoom }) => setZoom(zoom)),
      /*
        Нажатие на значок СТОЯЩЕГО флота включает прицел, а не открывает состав: в MOO II
        сперва выбирают корабли, потом систему назначения — п. 8. Состав спросят следом,
        когда цель уже выбрана.

        Нажатие на ЛЕТЯЩИЙ флот открывает его окно — сцену «флот в пути»
        (`FleetInFlightWindow`): «2 turns to Draconis» оригинала. Прицел ему ни к чему —
        приказ летящему флоту отдаётся только с Hyperspace Communications, а сменить курс
        можно из самого окна. Прежде нажатие по летящему флоту вело в прицел отправки, а
        оттуда — в диалог отбора кораблей, которых в пути не отобрать.

        Флот ищется в хранилище в миг нажатия, а не замыкается в подписке: подписка ставится
        один раз, а состав флотов меняется каждый ход.
      */
      gameEvents.on('fleet:selected', ({ fleetId, course }) => {
        const group = useGameStore.getState().fleet?.fleets.find((one) => one.id === fleetId);
        // Окно флота одно на стоянку и полёт, как в оригинале (`docs/moo2/fleet-dialog.png`):
        // у стоящего оно открывается сразу с прицелом — следующая звезда станет целью, — а
        // полосы-подсказки поверх карты больше нет.
        setFlightFleet({ fleetId, course });
        if (!group?.targetSystemId) {
          setTargetingFleet(fleetId);
        }
      }),
      gameEvents.on('fleet:target-picked', ({ fleetId, systemId }) =>
        setFleetOrder({ fleetId, targetSystemId: systemId })),
    ];
    return () => offs.forEach((off) => off());
  }, [
    setSelectedSystem,
    setHoveredSystem,
    setOpenedSystem,
    setZoom,
    setTargetingFleet,
    setFlightFleet,
    setFleetOrder,
  ]);

  /*
    Прицел — п. 8: пока он включён, нажатие по звезде задаёт цель полёта. Список
    достижимых систем уходит вместе с ним: дальность меряется от колоний империи и на
    время выбора не меняется, а линию сцена красит по нему на каждом движении курсора.

    Списка два, и берётся тот, что по КОРАБЛЯМ этого флота: дополнительные баки — модуль
    корабля (п. 8), и флот с ними долетает дальше. Дальностей всего две, поэтому и списков
    два, а не по одному на каждый флот.
  */
  useEffect(() => {
    const group = fleet?.fleets.find((one) => one.id === targetingFleetId);
    gameEvents.emit('fleet:targeting', {
      fleetId: targetingFleetId,
      reachableSystemIds: fleetReach(fleet, group).reachableSystemIds,
    });
  }, [targetingFleetId, fleet?.fleets, fleet?.rangeParsecs, fleet?.reachableSystemIds,
    fleet?.reachableFarSystemIds]);

  /*
    Флоты игрока на карте — п. 8. Отдельной подпиской от карты: состав флотов меняется
    чаще самой карты (построили корабль, флот улетел, флот погиб в бою), и перерисовывать
    из-за этого всю галактику незачем.

    Отправляется и на каждую готовность сцены: сцена в StrictMode пересоздаётся, а свои
    значки она рисует из последнего присланного списка.
  */
  useEffect(() => {
    const send = () => {
      gameEvents.emit('map:fleets', {
        fleets: fleet?.fleets ?? [],
        color: self?.color ?? '#7fd4ff',
        turn,
      });
    };
    const off = gameEvents.on('scene:ready', send);
    send();
    return off;
  }, [fleet, self?.color, turn]);

  // React → Phaser: как только карта загружена, отдаём её сцене.
  useEffect(() => {
    if (!map) {
      return;
    }
    const send = () => {
      gameEvents.emit('map:load', { map, players });
      restoreView(map);
    };

    /**
     * Возвращает экраны, с которыми игрок ушёл со страницы: выделенную звезду,
     * экран системы, открытую панель и состояние подписей. Экраны живут в состоянии
     * клиента, поэтому достаточно вернуть его — разметка перерисуется сама; карте
     * дополнительно нужны команды: она рисуется не React'ом.
     */
    const restoreView = (loaded: GalaxyMap) => {
      const view = readView(loaded.gameId);
      if (!view) {
        return;
      }
      const systemById = (id: string | null) =>
        id ? (loaded.systems.find((system) => system.id === id) ?? null) : null;

      const selected = systemById(view.selectedSystemId);
      if (selected) {
        setSelectedSystem(selected);
        gameEvents.emit('map:select-system', { systemId: selected.id });
      }

      // Экран системы и панель п. 11.1 занимают одно место, поэтому возвращается
      // то, что было открыто последним; в хранилище они и не бывают вместе.
      const opened = systemById(view.openedSystemId);
      if (opened) {
        setOpenedSystem(opened);
        // Экран колонии лежит поверх системного, поэтому возвращается вместе с ней.
        if (view.openedColonyId && opened.planets.some((planet) => planet.id === view.openedColonyId)) {
          setOpenedColony(view.openedColonyId);
        }
      } else if (view.overlay !== 'none') {
        setOverlay(view.overlay);
        // Колонию открывают и со списка колоний империи — тогда она лежит поверх
        // панели, а не поверх экрана системы, и возвращается вместе с ней.
        if (view.openedColonyId
            && loaded.systems.some((system) =>
              system.planets.some((planet) => planet.id === view.openedColonyId))) {
          setOpenedColony(view.openedColonyId);
        }
      }

      if (!view.showLabels) {
        setShowLabels(false);
        gameEvents.emit('map:toggle-labels', { visible: false });
      }
    };

    // Сцена Phaser создаётся асинхронно и в StrictMode пересоздаётся, поэтому
    // подписка постоянная: карта уходит и сразу, и на каждую готовность сцены.
    // Повторная отправка безопасна — renderMap полностью перерисовывает карту.
    const off = gameEvents.on('scene:ready', send);
    send();
    return off;
  }, [map, players, setSelectedSystem, setOpenedSystem, setOpenedColony, setOverlay, setShowLabels]);

  return (
    <>
      <div ref={containerRef} className="absolute inset-0" />
      {graphicsLost ? (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <div className="panel pointer-events-auto flex max-w-md flex-col gap-3 px-5 py-4 text-13">
            <div className="text-ink-bright">{t('map.graphicsLost.title')}</div>
            <div className="text-ink-soft">{t('map.graphicsLost.body')}</div>
            <button type="button" className="link self-start" onClick={() => window.location.reload()}>
              {t('map.graphicsLost.reload')}
            </button>
          </div>
        </div>
      ) : null}
    </>
  );
}
