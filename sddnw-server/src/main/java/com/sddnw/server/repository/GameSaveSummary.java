package com.sddnw.server.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Заголовок сохранения для списка загрузки — проекция без самого слепка.
 * <p>
 * Слепок партии лежит в той же строке одним JSON-документом на сотню килобайт. Список
 * же показывает только название, ход и время, поэтому читать состояние ради него нельзя:
 * на сотне сохранений это сотня документов, поднятых в память на один клик.
 */
public interface GameSaveSummary {

    UUID getId();

    UUID getGameId();

    String getName();

    String getGalaxySize();

    Integer getWidthParsecs();

    Integer getHeightParsecs();

    Integer getStarCount();

    Integer getTurn();

    Integer getHumanPlayers();

    Integer getTotalPlayers();

    OffsetDateTime getSavedAt();

    /** Снято игрой в конце хода, а не человеком — п. 3. */
    Boolean getAuto();

    /** Учётные записи людей той партии через запятую; пусто — слепок до миграции 077. */
    String getHumanAccounts();
}
