package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlanetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlanetRepository extends JpaRepository<PlanetEntity, UUID> {

    /**
     * Все колонии этих игроков — п. 4.1.1: еду империя развозит грузовым флотом, и
     * посчитать подвоз можно только зная все её колонии, а не одну.
     */
    /**
     * Порядок задан явно: ход читает эти строки и принимает по ним решения «первый
     * подходящий», а база отдаёт строки в том порядке, в каком они лежат, — и порядок
     * этот меняется от правок. Та же партия с тем же зерном обязана повторяться
     * (balance-metrics-works.txt, этап 0).
     * <p>
     * <b>По НАЗВАНИЮ, а не по идентификатору, и это не косметика.</b> Идентификатор
     * планеты — случайный UUID ({@code GenerationType.UUID}), который выдаёт Hibernate при
     * записи строки. Порядок по нему устойчив ВНУТРИ прогона и РАЗНЫЙ между двумя прогонами
     * одной и той же партии: идентификаторы во второй раз выпадут другие. Сортировка по
     * такому ключу выглядит как порядок, но повторимости не даёт — а весь этап 0
     * балансировки стоит на том, что партия с тем же зерном повторяется до последнего числа.
     * <p>
     * Измерено: {@code balance_run.py --check-determinism} расходился на 78-м ходу — у
     * ведущей империи 23251 жителя против 23261 при равных мощи, колониях, науке и флоте.
     * Причина ровно здесь: подвоз еды ({@code ColonyService.freightDeliveries}) кормит
     * голодающие колонии по возрастанию нехватки, а при РАВНОЙ нехватке — в том порядке, в
     * каком колонии пришли из этой выборки. Последний кусок еды доставался разным колониям,
     * и население расходилось на единицу, а дальше росло само.
     * <p>
     * Название планеты — ключ игровой: оно собрано из имени звезды и номера орбиты
     * ({@code StarSystemEntity.planetName}), выводится из зерна партии и уникально.
     * <p>
     * <b>Порядок задаётся НЕ ЗДЕСЬ.</b> Выборка отдаёт строки как удобно базе, а
     * расставляет их {@link com.moo3.server.service.GameOrder} — в Java и сравнением по
     * кодовым единицам. Сортировка строковой колонки в {@code ORDER BY} зависит от правил
     * сравнения СУБД, и та же партия на другой базе пошла бы иначе.
     */
    List<PlanetEntity> findAllByOwnerPlayerIdIn(Collection<UUID> ownerPlayerIds);

    List<PlanetEntity> findAllByStarSystemIdOrderByOrbitAsc(UUID starSystemId);

    List<PlanetEntity> findAllByOwnerPlayerId(UUID ownerPlayerId);

    /** Сколько у империи колоний — п. 8: по ним считается запас командных очков. */
    Integer countByOwnerPlayerId(UUID ownerPlayerId);
}
