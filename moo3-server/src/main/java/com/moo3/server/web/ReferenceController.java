package com.moo3.server.web;

import com.moo3.server.dto.BattleDto;
import com.moo3.server.dto.BuildingDto;
import com.moo3.server.dto.DemoBattleDto;
import com.moo3.server.dto.GalaxySizeDto;
import com.moo3.server.dto.MineralRichnessDto;
import com.moo3.server.dto.PlanetClimateDto;
import com.moo3.server.dto.PlanetSizeDto;
import com.moo3.server.dto.RaceDesignDto;
import com.moo3.server.dto.RaceDto;
import com.moo3.server.dto.ResearchTreeDto;
import com.moo3.server.dto.SaveRaceTraitCostsRequest;
import com.moo3.server.service.AccountService;
import com.moo3.server.service.CatalogService;
import com.moo3.server.service.DemoBattleService;
import com.moo3.server.service.ReferenceService;
import com.moo3.server.service.RaceDesignService;
import com.moo3.server.service.ResearchCatalog;
import com.moo3.server.service.TechnologySections;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Справочники и каталоги для клиента: правила галактики и планет (п. 4),
 * дерево технологий (п. 9 / 11.1) и заглушки по п. 5, 6, 8, 10.
 */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final ReferenceService referenceService;
    private final CatalogService catalogService;
    private final ResearchCatalog researchCatalog;
    private final RaceDesignService raceDesignService;
    private final DemoBattleService demoBattleService;
    private final TechnologySections technologySections;
    private final AccountService accountService;

    public ReferenceController(ReferenceService referenceService,
                               CatalogService catalogService,
                               ResearchCatalog researchCatalog,
                               RaceDesignService raceDesignService,
                               DemoBattleService demoBattleService,
                               TechnologySections technologySections,
                               AccountService accountService) {
        this.referenceService = referenceService;
        this.catalogService = catalogService;
        this.researchCatalog = researchCatalog;
        this.raceDesignService = raceDesignService;
        this.demoBattleService = demoBattleService;
        this.technologySections = technologySections;
        this.accountService = accountService;
    }

    @GetMapping("/galaxy-sizes")
    public List<GalaxySizeDto> galaxySizes() {
        return referenceService.galaxySizes();
    }

    @GetMapping("/planet-sizes")
    public List<PlanetSizeDto> planetSizes() {
        return referenceService.planetSizes();
    }

    @GetMapping("/planet-climates")
    public List<PlanetClimateDto> planetClimates() {
        return referenceService.planetClimates();
    }

    @GetMapping("/minerals")
    public List<MineralRichnessDto> minerals() {
        return referenceService.minerals();
    }

    @GetMapping("/races")
    public List<RaceDto> races() {
        return catalogService.races();
    }

    @GetMapping("/buildings")
    public List<BuildingDto> buildings() {
        return catalogService.buildings();
    }

    /**
     * Конструктор расы — п. 7: бюджет очков и группы особенностей.
     * <p>
     * Отдаётся из файла конструктора: цены и набор особенностей правятся в нём и
     * подхватываются на лету.
     */
    @GetMapping("/race-traits")
    public RaceDesignDto raceTraits() {
        return raceDesignService.design();
    }

    /**
     * Правка цен особенностей расы — п. 7: экран «Стоимость особенностей рас» из главного
     * меню. Правка ложится в сам справочник {@code race-traits.json}, поэтому переживает
     * перезапуск сервера и видна всем, кто сядет собирать расу следующим.
     * <p>
     * <b>Только администратору.</b> Это запись в справочник на диске, который читается на
     * лету всеми партиями сервера: пока игра жила в своей сети, пропуска здесь не было, а
     * сервер вышел наружу — и любой пользователь из интернета мог переписать цены сторон
     * расы одним запросом. Чтение справочников по-прежнему открыто: экран выбора расы
     * стоит до входа в партию.
     */
    @PutMapping("/race-traits")
    public RaceDesignDto updateRaceTraits(@Valid @RequestBody SaveRaceTraitCostsRequest request,
                                          @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return raceDesignService.updateCosts(request);
    }

    /**
     * Заводит демонстрационный бой — п. 8: две эскадры из разных кораблей сходятся сами.
     * <p>
     * Пропуска игрока здесь нет, как и у остальных справочников: сцену смотрят из главного
     * меню, до входа в партию. Ходить этим эндпоинтом можно только в бою демонстрации —
     * настоящие бои он не пускает.
     */
    @PostMapping("/demo-battle")
    public DemoBattleDto startDemoBattle() {
        return demoBattleService.startDto();
    }

    /** Состояние демонстрационного боя — п. 8. */
    @GetMapping("/demo-battle/{battleId}")
    public BattleDto demoBattle(@PathVariable UUID battleId) {
        return demoBattleService.view(battleId);
    }

    /**
     * Шаг демонстрации — п. 8: ходит корабль, чья очередь. Обе стороны ведёт сервер,
     * поэтому экран просто просит следующий шаг и показывает, что случилось.
     */
    @PostMapping("/demo-battle/{battleId}/step")
    public BattleDto stepDemoBattle(@PathVariable UUID battleId) {
        return demoBattleService.step(battleId);
    }

    /**
     * Дерево технологий для экрана выбора исследования — п. 9.
     * <p>
     * Отдаётся из файла описания дерева, а не из таблицы {@code technology}: разделы,
     * уровни и выбор одной технологии на уровне лежат в нём.
     */
    @GetMapping("/research")
    public ResearchTreeDto research() {
        // У каждой технологии здесь проставлен раздел окна «Инфо» — п. 11.1: список
        // изученного делится там на четыре части, как в окне Tech Review MOO II, и
        // делится он не по разделам науки, а по тому, что технология открывает.
        return technologySections.withSections(researchCatalog.tree());
    }
}
