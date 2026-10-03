package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.FleetShipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Состав флотов по проектам кораблей — п. 8. */
public interface FleetShipRepository extends JpaRepository<FleetShipEntity, UUID> {

    /**
     * Состав флота одной выборкой. {@code ORDER BY id} здесь — лишь устойчивость выборки, а
     * НЕ порядок правил игры: идентификатор строки это случайный UUID, выданный Hibernate
     * при записи, и во втором прогоне той же партии он другой.
     * <p>
     * Поэтому всякий, кто выбирает из этих строк ОДНУ (слабейший корабль, очередной под
     * списание потерь), обязан отсортировать их сам — {@link com.sddnw.server.service.GameOrder#fleetShips},
     * по порядку самих проектов. На этом уже погорели: партия расходилась сама с собой на
     * 111-м ходу, потому что из двух линкоров одного корпуса амёба съедала то один, то
     * другой (журнал, 25.09.2026).
     */
    List<FleetShipEntity> findAllByFleetIdOrderByIdAsc(UUID fleetId);

    /** Состав сразу нескольких флотов: экран флота и фаза встреч читают их пачкой. */
    List<FleetShipEntity> findAllByFleetIdIn(Collection<UUID> fleetIds);

    Optional<FleetShipEntity> findByFleetIdAndDesignId(UUID fleetId, UUID designId);

    void deleteAllByFleetId(UUID fleetId);
}
