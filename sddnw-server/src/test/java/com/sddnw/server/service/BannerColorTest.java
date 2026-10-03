package com.sddnw.server.service;

import com.sddnw.server.domain.enums.BannerColor;
import com.sddnw.server.web.error.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Знамя империи — п. 3.2: выбирается перед партией, как в MOO II, и двух одинаковых в партии
 * не бывает.
 */
class BannerColorTest {

    /** Правило знамени справочник рас не спрашивает — ему хватает цветов. */
    private final PlayerRoster roster = new PlayerRoster(null);

    @Test
    @DisplayName("Выбранное знамя берётся, если свободно")
    void chosenBannerIsTaken() {
        assertThat(roster.bannerColor(BannerColor.BROWN, "#4fa3ff", Set.of("#ff5f5f")))
                .isEqualTo(BannerColor.BROWN.hex());
    }

    @Test
    @DisplayName("Занятое знамя не взять — отказ, а не тихая подмена")
    void takenBannerIsRefused() {
        assertThatThrownBy(() -> roster.bannerColor(BannerColor.RED, "#4fa3ff", Set.of("#FF5F5F")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("banner.taken");
    }

    @Test
    @DisplayName("Без выбора — знамя своей расы, а занято оно — первое свободное")
    void withoutChoiceRaceBannerThenFirstFree() {
        assertThat(roster.bannerColor(null, BannerColor.BLUE.hex(), Set.of()))
                .isEqualTo(BannerColor.BLUE.hex());
        assertThat(roster.bannerColor(null, BannerColor.BLUE.hex(), Set.of(BannerColor.BLUE.hex())))
                .isEqualTo(BannerColor.RED.hex());
        // У расы без своего знамени (оттенок не из восьми) — тоже первое свободное.
        assertThat(roster.bannerColor(null, "#47e3d5", Set.of()))
                .isEqualTo(BannerColor.RED.hex());
    }

    @Test
    @DisplayName("Восемь империй разбирают восемь разных знамён")
    void eightEmpiresGetEightBanners() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            taken.add(roster.bannerColor(null, BannerColor.GREEN.hex(), taken));
        }
        assertThat(taken).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(BannerColor.values()).map(BannerColor::hex).toList());
    }
}
