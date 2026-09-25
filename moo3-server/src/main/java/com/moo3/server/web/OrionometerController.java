package com.moo3.server.web;

import com.moo3.server.dto.OrionometerDto;
import com.moo3.server.service.AccountService;
import com.moo3.server.service.OrionometerService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Пульт Орионометра — записи игры человека в ОРИГИНАЛ.
 *
 * <p>Хозяйство машины, а не своя партия: запись поднимает чужой процесс и водит файлы на
 * диске. Поэтому каждый вызов спрашивает роль ({@code requireAdmin}) — то же правило, что у
 * пульта балансировки и удаления сохранений.
 */
@RestController
@RequestMapping("/api/orionometer")
public class OrionometerController {

    private final OrionometerService orionometerService;
    private final AccountService accountService;

    public OrionometerController(OrionometerService orionometerService,
                                 AccountService accountService) {
        this.orionometerService = orionometerService;
        this.accountService = accountService;
    }

    /** Что сейчас с записью: идёт ли, открыта ли игра, сколько нажатий записано. */
    @GetMapping
    public OrionometerDto state(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return orionometerService.state();
    }

    /** Начать запись. Игра должна быть уже открыта — иначе записывать нечего. */
    @PostMapping("/start")
    public OrionometerDto start(@RequestParam(required = false) Integer minutes,
                                @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return orionometerService.start(minutes);
    }

    /** Остановить запись: протокол дописывается и остаётся на диске. */
    @PostMapping("/stop")
    public OrionometerDto stop(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return orionometerService.stop();
    }

    /**
     * Пустить нейросеть играть в оригинал через Орионометр.
     *
     * <p>Моста пока нет, и пульт об этом говорит прямо — см. {@code playWithBrain}.
     */
    @PostMapping("/brain")
    public OrionometerDto brain(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return orionometerService.playWithBrain();
    }
}
