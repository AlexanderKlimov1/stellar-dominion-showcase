package com.moo3.server.dto;

/**
 * Демонстрационный бой — п. 8: поле и то, чем стороны в него вошли.
 * <p>
 * Силы сторон отдаются отдельно от {@link BattleDto}: там они текущие и тают по ходу боя,
 * а здесь — те, с которыми флоты сошлись. По ним и видно обещанный перевес.
 *
 * @param advantagePercent на сколько процентов вторая сторона сильнее первой
 */
public record DemoBattleDto(
        BattleDto battle,
        String leftName,
        String rightName,
        Integer leftPower,
        Integer rightPower,
        Integer advantagePercent
) {
}
