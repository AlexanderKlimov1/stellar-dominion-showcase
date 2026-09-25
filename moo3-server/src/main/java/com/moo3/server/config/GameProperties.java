package com.moo3.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки игровых правил — п. 3.2 и п. 4.2.
 *
 * @param totalPlayers      суммарное количество игроков в галактике
 * @param maxHumanPlayers   максимум игроков-людей (создатель + присоединившиеся)
 * @param minStarDistanceParsecs минимальная дистанция между звёздами в парсеках
 * @param starNamesFile      путь к текстовому справочнику названий звёзд (п. 11.3)
 * @param techFile           путь к описанию дерева технологий в JSON (п. 9)
 * @param buildingsFile      путь к описанию зданий колоний в JSON (п. 10)
 * @param raceTraitsFile     путь к описанию особенностей рас в JSON (п. 7)
 * @param shipsFile          путь к описанию корпусов и компонентов кораблей в JSON (п. 8)
 * @param leadersFile        путь к описанию лидеров в JSON (п. 6)
 */
@ConfigurationProperties(prefix = "moo3.game")
public record GameProperties(
        Integer totalPlayers,
        Integer maxHumanPlayers,
        Double minStarDistanceParsecs,
        String starNamesFile,
        String techFile,
        String buildingsFile,
        String raceTraitsFile,
        String shipsFile,
        String leadersFile
) {
}
