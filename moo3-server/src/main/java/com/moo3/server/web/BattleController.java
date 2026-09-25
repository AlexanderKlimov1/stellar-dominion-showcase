package com.moo3.server.web;

import com.moo3.server.dto.BattleActionRequest;
import com.moo3.server.dto.BattleDto;
import com.moo3.server.service.BattleService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Тактический бой — п. 8, сцена боя MOO II.
 * <p>
 * Ходят корабли, а не игроки: очередь строится по инициативе и идёт сквозь обе стороны.
 * Экран спрашивает состояние поля и шлёт ход своего корабля; корабли ИИ сервер проводит
 * сам и отдаёт их ходы событиями в том же ответе.
 */
@RestController
@RequestMapping("/api/games/{gameId}/battles")
@Validated
public class BattleController {

    private final BattleService battleService;

    public BattleController(BattleService battleService) {
        this.battleService = battleService;
    }

    /** Бои партии, которые ещё идут: по ним клиент открывает сцену. */
    @GetMapping
    public List<BattleDto> active(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return battleService.active(gameId, accessToken);
    }

    /** Состояние поля: корабли, очередь хода и чей ход сейчас. */
    @GetMapping("/{battleId}")
    public BattleDto battle(@PathVariable UUID gameId,
                            @PathVariable UUID battleId,
                            @AccessToken String accessToken) {
        return battleService.battle(gameId, battleId, accessToken);
    }

    /**
     * Ход корабля: перелёт, залп, пропуск или отступление — п. 8.
     * <p>
     * В ответе приходит поле после хода и события, случившиеся за это время: свой залп и
     * ходы кораблей ИИ, которые успели сходить следом. Экран проигрывает их подряд.
     */
    @PostMapping("/{battleId}/action")
    public BattleDto act(@PathVariable UUID gameId,
                         @PathVariable UUID battleId,
                         @AccessToken String accessToken,
                         @Valid @RequestBody BattleActionRequest request) {
        return battleService.act(gameId, battleId, accessToken, request);
    }

}
