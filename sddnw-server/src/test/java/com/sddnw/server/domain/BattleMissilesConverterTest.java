package com.sddnw.server.domain;

import com.sddnw.server.domain.enums.BattleSide;
import com.sddnw.server.domain.enums.WeaponKind;
import com.sddnw.server.service.BattleRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ракеты в полёте переживают запись в колонку боя — backlog-promo, пункт 30: залп, пущенный
 * одним запросом, долетает другим, и всё, что решает попадание, обязано вернуться целым.
 */
class BattleMissilesConverterTest {

    private final BattleMissilesConverter converter = new BattleMissilesConverter();

    @Test
    @DisplayName("Залп в полёте читается из колонки таким же, каким записан")
    void roundTrip() {
        BattleRules.Shot shot = new BattleRules.Shot(WeaponKind.MISSILE, 14, 5,
                Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, 100, 4, Boolean.TRUE, 24,
                Boolean.FALSE, Boolean.FALSE, 16);
        BattleMissile missile = new BattleMissile(3, BattleSide.DEFENDER, UUID.randomUUID(),
                UUID.randomUUID(), 12, 7, 16, 1, 40, 10, List.of(shot, shot));

        String column = converter.convertToDatabaseColumn(List.of(missile));
        List<BattleMissile> back = converter.convertToEntityAttribute(column);

        assertThat(back).containsExactly(missile);
    }

    @Test
    @DisplayName("Пустое небо — пустая колонка, и обратно — пустой изменяемый список")
    void emptySky() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        List<BattleMissile> back = converter.convertToEntityAttribute(null);
        assertThat(back).isEmpty();
        back.add(null);
    }
}
