package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Фаза шпионажа в конце хода — п. 13: империи набирают очки разведки, а отправленные
 * к соперникам агенты продвигают свои операции и делают их, когда набрали.
 * <p>
 * Идёт последней, после исследований: украденная технология достаётся империи уже
 * изученной, а сорванная стройка теряет ровно то, что вложила в этот ход.
 */
@Service
public class EspionagePhase implements TurnPhase {

    private final EspionageService espionageService;

    public EspionagePhase(EspionageService espionageService) {
        this.espionageService = espionageService;
    }

    @Override
    public Integer order() {
        return 12;
    }

    @Override
    public String name() {
        return "Шпионаж";
    }

    @Override
    public void apply(TurnContext context) {
        espionageService.advance(context);
    }
}
