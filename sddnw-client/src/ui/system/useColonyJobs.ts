import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import type { Jobs } from './colonyRules';

/**
 * Распределение жителей колонии — п. 4.1: черновик и его отправка на сервер.
 *
 * Живёт выше обоих сегментов экрана колонии: фигурки жителей и выработка нарисованы
 * в разных панелях, как в MOO II, но считаются от одного распределения. Иначе после
 * переноса жителя одна панель обгоняла бы другую на один ответ сервера.
 *
 * Раскладка меняется на месте, не дожидаясь сервера: перетаскивание должно отзываться
 * мгновенно. Ответ заменяет планету целиком, ошибка возвращает прежнюю раскладку.
 */
export function useColonyJobs(planet: Planet) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setResearch = useGameStore((state) => state.setResearch);
  const colony = planet.colony;

  const [jobs, setJobs] = useState<Jobs>({ farmers: 0, workers: 0, scientists: 0 });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setJobs({
      farmers: colony?.farmers ?? 0,
      workers: colony?.workers ?? 0,
      scientists: colony?.scientists ?? 0,
    });
    setError(null);
  }, [planet.id, colony?.farmers, colony?.workers, colony?.scientists]);

  const own = planet.ownerPlayerId === credentials?.playerId;

  /** Перевести `count` жителей с одной работы на другую — группа уходит одним запросом. */
  const move = (from: keyof Jobs, to: keyof Jobs, count = 1) => {
    const moving = Math.min(count, jobs[from]);
    if (!own || from === to || moving <= 0 || !game || !credentials) {
      return;
    }
    const next = { ...jobs, [from]: jobs[from] - moving, [to]: jobs[to] + moving };
    const previous = jobs;
    setJobs(next);
    setError(null);

    gameApi
      .setPopulation(game.id, planet.id, credentials.accessToken,
        next.farmers, next.workers, next.scientists)
      .then(async (updated) => {
        updatePlanet(updated);
        // Учёные колонии — часть дохода очков всей империи (п. 9), поэтому вместе
        // с планетой обновляется и состояние исследований.
        setResearch(await gameApi.getResearch(game.id, credentials.accessToken));
      })
      .catch((failure: Error) => {
        setJobs(previous);
        setError(failure.message);
      });
  };

  return { jobs, move, error, own };
}
