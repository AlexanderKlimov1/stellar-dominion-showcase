package com.sddnw.server.web;

import com.sddnw.server.dto.BuildTemplateDto;
import com.sddnw.server.dto.SaveBuildTemplateRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.BuildTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Шаблоны стройки — п. 10: порядок развития, который игрок закладывает колониям одним
 * движением.
 * <p>
 * Адрес вне партии нарочно: шаблон принадлежит УЧЁТНОЙ ЗАПИСИ и нужен в главном меню, где
 * никакой партии ещё нет. Оттого и пропуск здесь только один — записи (п. 3.1).
 */
@RestController
@RequestMapping("/api/build-templates")
public class BuildTemplateController {

    private final BuildTemplateService templates;
    private final AccountService accountService;

    public BuildTemplateController(BuildTemplateService templates, AccountService accountService) {
        this.templates = templates;
        this.accountService = accountService;
    }

    @GetMapping
    public List<BuildTemplateDto> list(@AccountToken String accountToken) {
        return templates.list(accountService.require(accountToken).getId());
    }

    /** Новый шаблон или правка своего: пустой `id` — новый. */
    @PostMapping
    public BuildTemplateDto save(@Valid @RequestBody SaveBuildTemplateRequest request,
                                 @AccountToken String accountToken) {
        return templates.save(accountService.require(accountToken).getId(), request);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId, @AccountToken String accountToken) {
        templates.delete(accountService.require(accountToken).getId(), templateId);
    }
}
