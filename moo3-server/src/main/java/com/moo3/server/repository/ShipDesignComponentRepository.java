package com.moo3.server.repository;

import com.moo3.server.domain.entity.ShipDesignComponentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Состав проектов кораблей — п. 8. */
public interface ShipDesignComponentRepository extends JpaRepository<ShipDesignComponentEntity, UUID> {

    List<ShipDesignComponentEntity> findAllByDesignIdOrderBySortOrderAsc(UUID designId);

    /** Состав сразу нескольких проектов: экран дизайна и флот читают их пачкой. */
    List<ShipDesignComponentEntity> findAllByDesignIdInOrderBySortOrderAsc(Collection<UUID> designIds);

    void deleteAllByDesignId(UUID designId);
}
