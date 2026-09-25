package com.moo3.server.web;

import com.moo3.server.dto.DiplomacyActionRequest;
import com.moo3.server.dto.DiplomacyRelationDto;
import com.moo3.server.dto.TechTradeDto;
import com.moo3.server.service.EmpireService;
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

/** Дипломатия — п. 15: с кем игрок знаком и что он им предлагает. */
@RestController
@RequestMapping("/api/games/{gameId}/diplomacy")
@Validated
public class DiplomacyController {

    private final EmpireService empireService;

    public DiplomacyController(EmpireService empireService) {
        this.empireService = empireService;
    }

    /** Знакомые империи и отношения с ними — п. 15. */
    @GetMapping
    public List<DiplomacyRelationDto> relations(@PathVariable UUID gameId,
                                                @AccessToken String accessToken) {
        return empireService.relations(gameId, accessToken);
    }

    /** Предложить мир или объявить войну — п. 15. */
    @PostMapping
    public DiplomacyRelationDto act(@PathVariable UUID gameId,
                                    @Valid @RequestBody DiplomacyActionRequest request) {
        return empireService.diplomacy(gameId, request);
    }

    /**
     * Что можно обменять с этой империей — п. 15: чем поделиться и что попросить.
     * <p>
     * Обмен в MOO II называет обе технологии сразу, поэтому окну нужны оба списка. Знать
     * состав чужого дерева знакомому не запрещено: в оригинале контакт как раз и даёт
     * сведения о сопернике.
     */
    @GetMapping("/{otherPlayerId}/technologies")
    public TechTradeDto tradeable(@PathVariable UUID gameId,
                                  @PathVariable UUID otherPlayerId,
                                  @AccessToken String accessToken) {
        return empireService.tradeable(gameId, accessToken, otherPlayerId);
    }
}
