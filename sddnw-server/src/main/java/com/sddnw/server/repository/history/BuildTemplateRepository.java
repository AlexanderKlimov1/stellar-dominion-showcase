package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.BuildTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Шаблоны стройки одного игрока — п. 10: по стадии развития, потом по названию. */
public interface BuildTemplateRepository extends JpaRepository<BuildTemplateEntity, UUID> {

    List<BuildTemplateEntity> findAllByAccountIdOrderByTierAscNameAsc(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
