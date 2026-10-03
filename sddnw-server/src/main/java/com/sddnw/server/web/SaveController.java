package com.sddnw.server.web;

import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.GameSaveDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.GameSaveService;
import com.sddnw.server.service.GameService;
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

    /**
     * Список сохранений для диалога загрузки: свежие сверху.
     * <p>
     * Пропуск записи спрашивается ради одного поля — можно ли поднять эту строку: правило
     * «автосохранение поднимают только те же люди» (п. 3) должно быть видно ДО нажатия, а
     * не приходить отказом после него.
     */
    @GetMapping
    public List<GameSaveDto> listSaves(@AccountToken String accountToken) {
        return gameSaveService.list(accountService.require(accountToken).getId());
    }

    /**
     * Загрузка сохранения: из слепка поднимается новая партия.
     * <p>
     * Это тоже вход в партию, поэтому и здесь спрашивается пропуск учётной записи — п. 3.1.
     * А у АВТОСОХРАНЕНИЯ запись решает и больше: поднять его могут только те же люди,
     * которые в той партии играли (п. 3), и поднявший садится за свою же империю.
     */
    @PostMapping("/{saveId}/load")
    public CreateGameResponse loadSave(@PathVariable UUID saveId,
                                       @AccountToken String accountToken) {
        // Гость сохранений не поднимает (backlog-promo, пункт 1): сохранения лежат общей
        // кучей, и загрузка увела бы его из гостевой партии в любую — огромную, на восьмерых.
        // В свою партию он возвращается списком «Ваши партии», ей сохранение не нужно.
        return gameService.loadSave(saveId, accountService.requireRegistered(accountToken).getId());
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
