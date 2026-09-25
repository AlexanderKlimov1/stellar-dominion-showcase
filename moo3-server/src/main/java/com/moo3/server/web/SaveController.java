package com.moo3.server.web;

import com.moo3.server.dto.CreateGameResponse;
import com.moo3.server.dto.GameSaveDto;
import com.moo3.server.service.AccountService;
import com.moo3.server.service.GameSaveService;
import com.moo3.server.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Сохранённые партии: список для диалога загрузки и сама загрузка. */
@RestController
@RequestMapping("/api/saves")
public class SaveController {

    private final GameSaveService gameSaveService;
    private final GameService gameService;
    private final AccountService accountService;

    public SaveController(GameSaveService gameSaveService, GameService gameService,
                          AccountService accountService) {
        this.gameSaveService = gameSaveService;
        this.gameService = gameService;
        this.accountService = accountService;
    }

    /** Список сохранений для диалога загрузки: свежие сверху. */
    @GetMapping
    public List<GameSaveDto> listSaves() {
        return gameSaveService.list();
    }

    /**
     * Загрузка сохранения: из слепка поднимается новая партия.
     * <p>
     * Это тоже вход в партию, поэтому и здесь спрашивается пропуск учётной записи — п. 3.1.
     */
    @PostMapping("/{saveId}/load")
    public CreateGameResponse loadSave(@PathVariable UUID saveId,
                                       @AccountToken String accountToken) {
        accountService.require(accountToken);
        return gameService.loadSave(saveId);
    }

    /**
     * Удаление сохранения — только администратором (п. 3.1).
     * <p>
     * Сохранения лежат на сервере общей кучей и хозяина не имеют: в списке загрузки их
     * видят все, и загрузить чужое может любой. Значит, и убирать их — дело того, кто
     * отвечает за сервер, а не первого встречного: раньше эндпоинт не спрашивал ничего,
     * и снести чужую партию мог кто угодно.
     */
    @DeleteMapping("/{saveId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSave(@PathVariable UUID saveId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        gameSaveService.delete(saveId);
    }
}
