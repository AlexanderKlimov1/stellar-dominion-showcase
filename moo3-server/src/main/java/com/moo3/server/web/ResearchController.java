package com.moo3.server.web;

import com.moo3.server.dto.ChooseResearchRequest;
import com.moo3.server.dto.PlayerResearchDto;
import com.moo3.server.service.ResearchService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Исследования игрока — п. 9: текущая цель, вложенные в неё очки и изученное. */
@RestController
@RequestMapping("/api/games/{gameId}/research")
@Validated
public class ResearchController {

    private final ResearchService researchService;

    public ResearchController(ResearchService researchService) {
        this.researchService = researchService;
    }

    @GetMapping
    public PlayerResearchDto getResearch(@PathVariable UUID gameId,
                                         @AccessToken String accessToken) {
        return researchService.state(gameId, accessToken);
    }

    /** Выбор технологии для исследования — п. 9. */
    @PostMapping
    public PlayerResearchDto chooseResearch(@PathVariable UUID gameId,
                                            @Valid @RequestBody ChooseResearchRequest request) {
        return researchService.choose(gameId, request);
    }
}
