package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.PlanetBuildingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlanetBuildingRepository extends JpaRepository<PlanetBuildingEntity, UUID> {

    List<PlanetBuildingEntity> findAllByPlanetId(UUID planetId);

    List<PlanetBuildingEntity> findAllByPlanetIdIn(Collection<UUID> planetIds);
}
