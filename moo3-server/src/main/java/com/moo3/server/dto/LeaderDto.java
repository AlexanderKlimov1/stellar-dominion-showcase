package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Лидер на службе или предлагающий её — п. 6.
 * <p>
 * Собран из двух половин: справочник даёт имя, прозвище и способности, база — состояние,
 * опыт и назначение. Сила способностей приходит уже пересчитанной под нынешнее звание:
 * экран не должен повторять формулу роста, иначе она разъедется с сервером.
 *
 * @param id           строка службы, а не номер лидера: по ней и нанимают, и увольняют
 * @param code         код лидера в справочнике
 * @param name         имя, как в оригинале
 * @param title        прозвище
 * @param kind         род: колониальный или корабельный
 * @param kindLabel    род по-русски
 * @param raceName     раса, если имя её называет
 * @param state        предлагает службу, служит или отказано
 * @param stateLabel   состояние по-русски
 * @param rank         звание по накопленному опыту
 * @param experience   накопленный опыт
 * @param hireCost     разовая плата за наём
 * @param salary       жалованье за ход; 0 у «Богача» — он сам приносит казне
 * @param skills       способности с пересчитанной силой
 * @param techs        технологии, которые лидер приносит при найме
 * @param offeredTurn  ход, когда предложил службу
 * @param expiresTurn  ход, когда предложение сгорит
 * @param systemId     где служит колониальный лидер
 * @param systemName   название той системы
 * @param fleetId      где служит корабельный лидер
 * @param arrivesTurn  ход, с которого назначение действует; пусто — лидер в резерве
 */
public record LeaderDto(
        UUID id,
        String code,
        String name,
        String title,
        String kind,
        String kindLabel,
        String raceName,
        String state,
        String stateLabel,
        String rank,
        Integer experience,
        Integer hireCost,
        Integer salary,
        List<LeaderSkillDto> skills,
        List<String> techs,
        Integer offeredTurn,
        Integer expiresTurn,
        UUID systemId,
        String systemName,
        UUID fleetId,
        Integer arrivesTurn
) {
}
