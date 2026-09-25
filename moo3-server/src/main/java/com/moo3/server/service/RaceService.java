package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.repository.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Раса игрока в игре — п. 7: что она ему даёт.
 * <p>
 * Одна точка, через которую подсистемы спрашивают про расу: колонии (п. 10), наземный бой
 * (п. 12), шпионаж (п. 13) и корабли (п. 8). Сама раса хранится кодами особенностей на
 * игроке, а её цена и действие живут в файле конструктора, поэтому здесь только разбор
 * кодов через {@link RaceTraitCatalog}.
 */
@Service
public class RaceService {

    private final PlayerRepository playerRepository;
    private final RaceTraitCatalog catalog;

    public RaceService(PlayerRepository playerRepository, RaceTraitCatalog catalog) {
        this.playerRepository = playerRepository;
        this.catalog = catalog;
    }

    /** Что даёт раса этого игрока. */
    public RaceEffects effects(PlayerEntity player) {
        return catalog.effects(player.getRaceTraitCodes());
    }

    /**
     * Расы сразу нескольких игроков — для расчётов, которые идут по всей галактике:
     * карта колоний, фазы конца хода. Одним запросом вместо запроса на игрока.
     */
    public Map<UUID, RaceEffects> effectsByPlayer(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        return playerRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(PlayerEntity::getId, this::effects));
    }

    /** Название расы для клиента: своя, собранная игроком, либо раса из справочника. */
    public String raceName(PlayerEntity player, Function<String, String> catalogNames) {
        return player.getRaceName() == null ? catalogNames.apply(player.getRaceCode()) : player.getRaceName();
    }
}
