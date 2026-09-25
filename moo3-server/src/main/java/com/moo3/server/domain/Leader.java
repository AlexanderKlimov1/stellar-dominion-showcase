package com.moo3.server.domain;

import com.moo3.server.domain.enums.LeaderKind;

import java.util.List;
import java.util.Map;

/**
 * Лидер из справочника — п. 6.
 * <p>
 * Это описание человека, а не его службы: кто он, что умеет и с каким опытом приходит.
 * Служба у конкретного игрока (нанят, где служит, сколько опыта набрал) живёт в базе —
 * {@code player_leader}, — а сюда ведёт только код.
 *
 * @param id              номер лидера в оригинале: по нему сверяют справочник с MOO II
 * @param code            код в описании и в базе
 * @param name            имя, как в оригинале
 * @param title           прозвище: «Легендарный учёный», «Пиратский капитан»
 * @param kind            колониальный или корабельный
 * @param raceCode        раса, если имя её называет; иначе пусто
 * @param startExperience опыт, с которым лидер впервые предлагает службу
 * @param skills          способности с силой и приростом за уровень
 * @param techs           технологии, которые лидер приносит при найме
 * @param techsRandomOne  из списка технологий даётся одна случайная, а не все
 * @param randomSkills    сколько способностей набирается жребием при появлении (Brainac)
 */
public record Leader(
        Integer id,
        String code,
        LocalizedText names,
        LocalizedText titles,
        LeaderKind kind,
        String raceCode,
        Integer startExperience,
        List<LeaderSkill> skills,
        List<String> techs,
        Boolean techsRandomOne,
        Integer randomSkills
) {

    /**
     * Имя лидера на языке читателя — п. 3.5 и п. 6.
     * <p>
     * Имя собственное, и по-русски оно ТРАНСЛИТЕРИРУЕТСЯ, а не переводится: Quorrin —
     * Куоррин. Прежде имя лежало одной строкой, и по-русски выходила латиница посреди
     * русской фразы («Quorrin, Непредсказуемый»), хотя у рас транслитерация есть.
     * <p>
     * В базе имени нет вовсе — лидер хранится кодом, — поэтому языку тут ничто не мешает:
     * это не хранимое имя, а подпись, собираемая на запрос.
     */
    public String name() {
        return names == null ? null : names.text();
    }

    /** Имя по-английски: для журнала сервера, у которого языка читателя нет. */
    public String logName() {
        return names == null ? null : names.en();
    }

    /** Звание на языке читателя — п. 3.5. */
    public String title() {
        return titles == null ? null : titles.text();
    }

    /** Способность и её сила у этого лидера. */
    public record LeaderSkill(String ability, Double value, Double perLevel) {
    }

    /** Есть ли у лидера такая способность. */
    public Boolean has(String ability) {
        return skills.stream().anyMatch(skill -> skill.ability().equals(ability));
    }

    /** Способности по коду — так их удобнее считать при подсчёте прибавок. */
    public Map<String, LeaderSkill> skillsByAbility() {
        return skills.stream().collect(java.util.stream.Collectors.toMap(
                LeaderSkill::ability, skill -> skill, (first, second) -> first,
                java.util.LinkedHashMap::new));
    }
}
