package com.moo3.server.web;

import com.moo3.server.dto.PlanetDto;
import com.moo3.server.dto.QueueProjectsRequest;
import com.moo3.server.service.PlanetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Колонии империи разом — п. 10, окно Colonies MOO II.
 * <p>
 * Стоит отдельно от {@link ColonyController} потому, что тот привязан к одной планете
 * адресом ({@code /planets/{planetId}}), а здесь планет столько, сколько игрок выделил в
 * списке. Одно действие на всех — не то же самое, что несколько запросов подряд: приказ
 * либо исполняется целиком, либо не исполняется вовсе.
 */
@RestController
@RequestMapping("/api/games/{gameId}/colonies")
public class ColoniesController {

    private final PlanetService planetService;

    public ColoniesController(PlanetService planetService) {
        this.planetService = planetService;
    }

    /**
     * Поставить один проект в очередь выделенным колониям — п. 10.
     * <p>
     * Проект должен быть доступен каждой из них: отказ приходит с именем той колонии,
     * которой он не по силам, и очереди остальных при этом не меняются.
     */
    @PostMapping("/queue")
    public List<PlanetDto> enqueueAll(@PathVariable UUID gameId,
                                      @Valid @RequestBody QueueProjectsRequest request) {
        return planetService.enqueueAll(gameId, request);
    }
}
