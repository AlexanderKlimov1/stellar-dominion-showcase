package com.moo3.server.domain.save;

import com.moo3.server.domain.enums.SpaceMonster;
import com.moo3.server.domain.enums.StarColor;

import java.util.List;

/**
 * Звёздная система в слепке партии — координаты в парсеках, как в игре (п. 4.2).
 *
 * @param monster        чудище системы — п. 11.1; пусто — система чиста. В слепке его не
 *                       было вовсе, и поднятая партия теряла ВСЕХ чудищ разом, включая
 *                       Стража особой звезды: клад оказывался открыт без боя
 * @param monsterStrength здоровье чудища: раненый сторож должен оставаться раненым
 * @param specialClaimed клад особой звезды уже взят — п. 4.2.1. У слепков прошлых версий
 *                       поля нет, и приходит {@code null}: читается как «не взят»
 */
public record StarSystemSnapshot(
        String name,
        Double xParsec,
        Double yParsec,
        StarColor starColor,
        Boolean special,
        SpaceMonster monster,
        Integer monsterStrength,
        Boolean specialClaimed,
        List<PlanetSnapshot> planets
) {
}
