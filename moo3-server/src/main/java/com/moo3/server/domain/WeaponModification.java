package com.moo3.server.domain;

import com.moo3.server.domain.enums.WeaponKind;

/**
 * Модификация оружия — п. 8, «Modifications» окна дизайна MOO II.
 * <p>
 * В оригинале ствол ставят на корабль не как есть: его утяжеляют, облегчают до ближней
 * обороны, учат стрелять чаще, пробивать броню или охватывать корабль целиком. Меняются
 * при этом урон и меткость — и цена с местом, которые за всё это платят.
 * <p>
 * Числа оригинала (StrategyWiki, Warship design и обсуждения модификаций): тяжёлое орудие
 * бьёт на половину больнее и стоит вдвое дороже и крупнее; ближняя оборона — половина
 * урона, цены и места при четверти лишней меткости; скорострельное добавляет ту же
 * четверть меткости за половину цены и места; обволакивающее стоит вдвое. Цена
 * бронебойного — реконструкция: оригинал её не публикует, и взята она по образцу
 * скорострельного.
 *
 * <b>У ракет свой набор</b>, и это не прихоть: в оригинале переделывают не установку
 * ствола, а саму ракету — броня корпуса ракеты, скорость, заряд, помехозащита,
 * разделяющиеся боеголовки и наведение по излучению. {@code appliesTo} говорит, какому
 * оружию модификация подходит, и ставить её на другое сервер не даёт.
 *
 * @param appliesTo      виды оружия, которым модификация подходит
 * @param damagePercent  насколько меняется урон ствола, в процентах
 * @param attackPercent  насколько меняется меткость этого ствола, в процентных пунктах
 * @param shotsPercent   насколько меняется число выстрелов (AF — втрое, MIRV — вчетверо)
 * @param rangePercent   насколько меняется дальность ствола (HV — вдвое дальше, PD — вдвое ближе)
 * @param envelops       удар охватывает цель со всех четырёх сторон разом — ENV
 * @param noRangePenalty расстояние не ослабляет удар — NR
 * @param costPercent    насколько меняется цена ствола, в процентах
 * @param spacePercent   насколько меняется место ствола, в процентах
 * @param piercesArmour  выстрел проходит броню насквозь и бьёт прямо в корпус
 * @param piercesShield  щит такого выстрела не держит вовсе
 * @param halvesEvasion  вдвое срезает уклонение цели от ракет — помехозащита ECCM
 * @param missileArmourPercent насколько крепче сама ракета: настолько же труднее её сбить
 * @param missileSpeed   насколько ракета быстрее — её тоже труднее перехватить
 * @param hitsEngine     пробившись сквозь щит, ракета выбивает двигатель цели
 * @param requiredTechCode технология, без которой модификации нет; {@code null} — с начала
 */
public record WeaponModification(
        String code,
        LocalizedText names,
        LocalizedText descriptions,
        java.util.Set<WeaponKind> appliesTo,
        Integer damagePercent,
        Integer attackPercent,
        Integer shotsPercent,
        Integer rangePercent,
        Integer costPercent,
        Integer spacePercent,
        Boolean piercesArmour,
        Boolean piercesShield,
        Boolean envelops,
        Boolean noRangePenalty,
        Boolean halvesEvasion,
        Integer missileArmourPercent,
        Integer missileSpeed,
        Boolean hitsEngine,
        String requiredTechCode
) {

    /** Название на языке читателя — п. 3.5. */
    public String name() {
        return names.text();
    }

    /** Описание на языке читателя — п. 3.5. */
    public String description() {
        return descriptions == null ? null : descriptions.text();
    }

    /** Подходит ли модификация такому оружию — п. 8. */
    public Boolean fits(WeaponKind kind) {
        return kind != null && appliesTo.contains(kind);
    }
}
