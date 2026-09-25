package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlanetBuildingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlanetBuildingRepository extends JpaRepository<PlanetBuildingEntity, UUID> {

    List<PlanetBuildingEntity> findAllByPlanetId(UUID planetId);

    List<PlanetBuildingEntity> findAllByPlanetIdIn(Collection<UUID> planetIds);
}
