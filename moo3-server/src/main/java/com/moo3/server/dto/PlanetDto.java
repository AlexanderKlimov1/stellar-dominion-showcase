package com.moo3.server.dto;

import java.util.UUID;

/**
 * Планета звёздной системы — п. 4.1.
 *
 * @param foodPerFarmer      еда с одного фермера на этом климате — п. 4.1.2
 * @param productionPerWorker продукция с одного рабочего при этом богатстве минералами — п. 4.1.3
 * @param colony             колония на планете; у незаселённой планеты её нет
 * @param gravity            тяжесть мира — п. 4.1: непривычная расе роняет производство
 * @param colonyBaseReady    колония достроила колониальную базу и ждёт, какую планету
 *                           системы заселить — п. 4.1
 * @param find               находка на планете — п. 4.1: золотые жилы, самоцветы, туземцы
 *                           или наследие ушедшей цивилизации; пусто — планета обыкновенная
 * @param findLabel          имя находки на языке запроса
 * @param findDescription    что находка даёт и при каком условии — теми же словами, какими
 *                           это объясняет оригинал
 */
public record PlanetDto(
        UUID id,
        Integer orbit,
        String name,
        String size,
        String sizeLabel,
        String climate,
        String climateLabel,
        Integer populationMultiplierPercent,
        Boolean colonizable,
        Integer foodPerFarmer,
        String gravity,
        String gravityLabel,
        /**
         * Чего стоит эта тяжесть колонии, в процентах производства — п. 4.1; ноль у
         * привычной.
         * <p>
         * Считается для расы ОБЫЧНОЙ тяжести, как и в списке планет оригинала
         * (`docs/moo2/planets.png`: под «Low G» стоит «-25% prod»): список читают, выбирая,
         * куда слать колониальный корабль, и цена там названа общая, без поправки на расу
         * смотрящего.
         */
        Integer gravityPenaltyPercent,
        String minerals,
        String mineralsLabel,
        Integer productionPerWorker,
        Integer maxPopulation,
        Integer population,
        UUID ownerPlayerId,
        Boolean homeworld,
        ColonyDto colony,
        Boolean colonyBaseReady,
        String find,
        String findLabel,
        String findDescription
) {
}
