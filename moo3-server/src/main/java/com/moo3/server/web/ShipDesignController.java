package com.moo3.server.web;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.dto.SaveShipDesignRequest;
import com.moo3.server.dto.ShipCatalogDto;
import com.moo3.server.dto.ShipDesignDto;
import com.moo3.server.service.GameAccess;
import com.moo3.server.service.ShipDesignService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Проекты кораблей — п. 8, окно Ship Design MOO II.
 * <p>
 * Справочник корпусов и компонентов отдаётся с признаком доступности: чего игрок не
 * изучил, то видно, но поставить нельзя — как затемнённые строки оригинала.
 */
@RestController
@RequestMapping("/api/games/{gameId}/ship-designs")
@Validated
public class ShipDesignController {

    private final GameAccess gameAccess;
    private final ShipDesignService shipDesignService;

    public ShipDesignController(GameAccess gameAccess, ShipDesignService shipDesignService) {
        this.gameAccess = gameAccess;
        this.shipDesignService = shipDesignService;
    }

    /** Корпуса и компоненты, доступные империи — п. 8. */
    @GetMapping("/catalog")
    public ShipCatalogDto catalog(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return shipDesignService.catalog(gameAccess.requirePlayerOfRunningGame(gameId, accessToken));
    }

    /** Действующие проекты игрока — по ним строят колонии. */
    @GetMapping
    public List<ShipDesignDto> designs(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return shipDesignService.designs(gameAccess.requirePlayerOfRunningGame(gameId, accessToken));
    }

    /**
     * Сохраняет проект в ячейку — п. 8. Занятая ячейка переписывается, а корабли,
     * построенные по прежнему проекту, остаются прежними.
     */
    @PostMapping
    public ShipDesignDto save(@PathVariable UUID gameId,
                              @AccessToken String accessToken,
                              @Valid @RequestBody SaveShipDesignRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        return shipDesignService.save(game, player, request);
    }

    /** Убирает проект из ячейки — построенные по нему корабли остаются в строю. */
    @DeleteMapping("/{designId}")
    public List<ShipDesignDto> delete(@PathVariable UUID gameId,
                                      @PathVariable UUID designId,
                                      @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        shipDesignService.delete(player, designId);
        return shipDesignService.designs(player);
    }
}
