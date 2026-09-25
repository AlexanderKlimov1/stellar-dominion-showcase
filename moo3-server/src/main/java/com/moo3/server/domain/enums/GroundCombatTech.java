package com.moo3.server.domain.enums;

import java.util.Arrays;
import java.util.Set;

/**
 * Технологии наземного боя — п. 12: чем империя вооружает и прикрывает своих бойцов.
 * <p>
 * <b>Числа — из формулы оригинала.</b> MOO II считает силу пехотинца как
 * {@code 0,5 + винтовка + корабельная броня + снаряжение + раса}, а бронеединицы — как
 * {@code 1 + винтовка + корабельная броня} (раса и снаряжение технике не помогают).
 * Слагаемые оригинала: винтовки 0,05 / 0,1 / 0,2 / 0,3, корабельная броня 0,1 … 0,25,
 * силовой доспех 0,1, личный щит 0,1.
 * <p>
 * <b>Перевод в проценты прямой, а не подобранный.</b> Наш бой считает силу как
 * {@code бойцы * 10 * (100 + процентов) / 100}, то есть сотня процентов — это та самая
 * «база 0,5» оригинала, а расовая подготовка уже записана в справочнике рас теми самыми
 * числами MOO II (плохие бойцы −10, хорошие +10, великие +20). Значит доля оригинала,
 * умноженная на сто, и есть наш процент: винтовка-лазер 0,05 → +5 %, и хорошие бойцы
 * +0,1 → +10 %, ровно как в справочнике. Ничего подгонять не пришлось — две шкалы
 * совпали сами, потому что обе считают от одной базы.
 * <p>
 * <b>Что здесь реконструкция.</b> Три вещи.
 * <ol>
 *   <li>Порядок брони. Источник называет нейтронную броню 0,15, а зортриевую 0,2 —
 *       но в дереве зортриевая изучается РАНЬШЕ (Chemistry 5 против 6), и с такими
 *       числами следующая ступень лестницы не давала бы ничего. Лестница обязана идти
 *       по возрастанию, поэтому числа расставлены по порядку изучения: 10, 15, 20, 25.</li>
 *   <li>Батлоиды. В оригинале это отдельный род войск: бронеединица стоит 200 очков при
 *       нападении и 150 при обороне против 50 у пехотинца (+100 и +50 к сотне за броню).
 *       Наше войско считается жителями, а не единицами, и разделить его на пехоту и
 *       технику нечем, поэтому взята ПОЛОВИНА полного перевеса брони: +100 % при
 *       нападении и +50 % при обороне — как если бы бронетехникой была половина отряда.
 *       Асимметрия оригинала при этом сохранена целиком: танк лучше атакует, чем
 *       обороняется.</li>
 *   <li>Титановая броня в списке не значится: в оригинале она даёт ровно ноль, а ноль
 *       ступенью лестницы не бывает.</li>
 * </ol>
 * <p>
 * Коды совпадают с кодами дерева ({@code resources/Technologies/tech.json}): их строит
 * {@code ResearchCatalog} из названия — «Laser Rifle» → {@code laser-rifle}.
 * <p>
 * Правятся эти числа только здесь.
 */
public enum GroundCombatTech {

    /** Physics, уровень 1: винтовка-лазер, 0,05 в формуле оригинала. */
    LASER_RIFLE("laser-rifle", "Laser Rifle", Kind.WEAPON, 5, 5),

    /** Physics, уровень 2: та самая вторая ступень винтовок, 0,1. */
    FUSION_RIFLE("fusion-rifle", "Fusion Rifle", Kind.WEAPON, 10, 10),

    /** Physics, уровень 7: 0,2. */
    /*
      Коды здесь остались от ПРЕЖНИХ названий («zortrium-armor» у корриевой брони):
      код — опознаватель, он лежит в базе у изученного и в справочниках, а имя переименовано
      (docs/renaming.md). Расхождение намеренное: менять код значило бы потерять изученное
      во всех старых партиях.
     */
    PHASOR_RIFLE("phasor-rifle", "Resonator Rifle", Kind.WEAPON, 20, 20),

    /** Physics, уровень 8: 0,3 — лучшее оружие пехоты игры. */
    PLASMA_RIFLE("plasma-rifle", "Plasma Rifle", Kind.WEAPON, 30, 30),

    /** Chemistry, уровень 2: 0,1. Корабельная броня идёт бойцу нагрудником. */
    TRITANIUM_ARMOR("tritanium-armor", "Duranite Armor", Kind.ARMOUR, 10, 10),

    /** Chemistry, уровень 5. */
    ZORTRIUM_ARMOR("zortrium-armor", "Korrium Armor", Kind.ARMOUR, 15, 15),

    /** Chemistry, уровень 6. */
    NEUTRONIUM_ARMOR("neutronium-armor", "Neutronium Armor", Kind.ARMOUR, 20, 20),

    /** Chemistry, уровень 7: 0,25 — лучшая броня дерева. */
    ADAMANTIUM_ARMOR("adamantium-armor", "Adamant Armor", Kind.ARMOUR, 25, 25),

    /** Engineering, уровень 5: силовой доспех, 0,1. */
    POWERED_ARMOR("powered-armor", "Powered Armor", Kind.GEAR, 10, 10),

    /** Force Fields, уровень 4: личный щит, 0,1. */
    PERSONAL_SHIELD("personal-shield", "Personal Shield", Kind.GEAR, 10, 10),

    /** Engineering, уровень 7: бронетехника — см. «что здесь реконструкция» в javadoc. */
    BATTLEOIDS("battleoids", "Battle Walkers", Kind.GEAR, 100, 50);

    /**
     * Чем технология помогает бойцу — и как складывается с себе подобными.
     * <p>
     * Винтовка у бойца одна и броня одна: из них берётся ЛУЧШАЯ изученная, как и в
     * оригинале, где новая ступень заменяет прежнюю, а не добавляется к ней. Снаряжение —
     * дело другое: доспех, щит и бронетехника надеваются разом, поэтому складываются.
     */
    public enum Kind {
        WEAPON,
        ARMOUR,
        GEAR
    }

    private final String code;
    private final String name;
    private final Kind kind;
    private final int attackPercent;
    private final int defencePercent;

    GroundCombatTech(String code, String name, Kind kind, int attackPercent, int defencePercent) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.attackPercent = attackPercent;
        this.defencePercent = defencePercent;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getAttackPercent() {
        return attackPercent;
    }

    public Integer getDefencePercent() {
        return defencePercent;
    }

    /** Надбавка к силе высаженного десанта — п. 12. */
    public static Integer attackPercent(Set<String> technologies) {
        return percent(technologies, GroundCombatTech::getAttackPercent);
    }

    /** Надбавка к силе защитников колонии — п. 12. */
    public static Integer defencePercent(Set<String> technologies) {
        return percent(technologies, GroundCombatTech::getDefencePercent);
    }

    /**
     * Сумма надбавок: по лучшей винтовке, по лучшей броне и по всему снаряжению разом.
     * <p>
     * Обе стороны боя считаются одним и тем же способом: в MOO II изученное работает и в
     * нападении, и в обороне — своего оружия для обороны нет.
     */
    private static Integer percent(Set<String> technologies,
                                   java.util.function.ToIntFunction<GroundCombatTech> value) {
        int best = 0;
        int armour = 0;
        int gear = 0;
        for (GroundCombatTech tech : values()) {
            if (!technologies.contains(tech.getCode())) {
                continue;
            }
            switch (tech.getKind()) {
                case WEAPON -> best = Math.max(best, value.applyAsInt(tech));
                case ARMOUR -> armour = Math.max(armour, value.applyAsInt(tech));
                case GEAR -> gear += value.applyAsInt(tech);
            }
        }
        return best + armour + gear;
    }
}
