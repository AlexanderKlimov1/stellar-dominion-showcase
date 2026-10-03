package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.BuildTemplateEntity;
import com.sddnw.server.dto.BuildTemplateDto;
import com.sddnw.server.dto.SaveBuildTemplateRequest;
import com.sddnw.server.repository.history.BuildTemplateRepository;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Шаблоны стройки — п. 10, миграция 079.
 * <p>
 * Шаблон принадлежит УЧЁТНОЙ ЗАПИСИ, а не партии: порядок развития — привычка игрока,
 * он переживает партию и нужен в главном меню, где партии ещё нет. Отсюда и всё
 * остальное: чужой шаблон не правится и не удаляется, а список приходит только свой.
 * <p>
 * <b>Коды проектов сервер не проверяет</b>, и это нарочно. Шаблон пишут ДО партии, где
 * ни колонии, ни изученного ещё нет, — проверять его против списка стройки не с чем.
 * Несбыточное отсеется при закладке: {@code ColonyService.enqueueTemplate} берёт из
 * шаблона то, что колония МОЖЕТ построить сейчас, и молча пропускает остальное.
 * Транзакция своя: записи живут во втором источнике данных
 * ({@code sddnw.history.datasource}), и у него свой менеджер транзакций.
 */
@Service
public class BuildTemplateService {

    private static final Logger log = LoggerFactory.getLogger(BuildTemplateService.class);

    private final BuildTemplateRepository repository;

    public BuildTemplateService(BuildTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(transactionManager = "historyTransactionManager", readOnly = true)
    public List<BuildTemplateDto> list(UUID accountId) {
        return repository.findAllByAccountIdOrderByTierAscNameAsc(accountId).stream()
                .map(this::toDto)
                .toList();
    }

    /** Свой шаблон по опознавателю: чужой не отдаётся — им и закладывать нечего. */
    @Transactional(transactionManager = "historyTransactionManager", readOnly = true)
    public BuildTemplateDto require(UUID accountId, UUID templateId) {
        return toDto(own(accountId, templateId));
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public BuildTemplateDto save(UUID accountId, SaveBuildTemplateRequest request) {
        BuildTemplateEntity template = request.id() == null
                ? new BuildTemplateEntity()
                : own(accountId, request.id());
        template.setAccountId(accountId);
        template.setName(request.name().trim());
        template.setTier(request.tier());
        template.setProjectCodes(request.projects() == null ? List.of() : request.projects());
        template.setUpdatedAt(OffsetDateTime.now());
        BuildTemplateEntity saved = repository.saveAndFlush(template);
        log.info("Шаблон стройки «{}» (стадия {}) сохранён: {} проектов",
                saved.getName(), saved.getTier(), saved.getProjectCodes().size());
        return toDto(saved);
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public void delete(UUID accountId, UUID templateId) {
        repository.delete(own(accountId, templateId));
        log.info("Шаблон стройки {} удалён", templateId);
    }

    /** Шаблоны удалённой записи: без хозяина они никому не нужны. */
    @Transactional(transactionManager = "historyTransactionManager")
    public void deleteByAccount(UUID accountId) {
        repository.deleteByAccountId(accountId);
    }

    private BuildTemplateEntity own(UUID accountId, UUID templateId) {
        BuildTemplateEntity template = repository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("template.notFound", templateId));
        if (!template.getAccountId().equals(accountId)) {
            throw new ForbiddenException("template.notYours");
        }
        return template;
    }

    private BuildTemplateDto toDto(BuildTemplateEntity template) {
        return new BuildTemplateDto(template.getId(), template.getName(), template.getTier(),
                template.getProjectCodes());
    }
}
