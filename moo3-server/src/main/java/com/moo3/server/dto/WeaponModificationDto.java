package com.moo3.server.dto;

/**
 * Модификация ствола для окна дизайна — п. 8.
 * <p>
 * Числа приходят готовыми: экран показывает, во что модификация обойдётся и что даст, а
 * считает всё равно сервер.
 *
 * @param damagePercent  насколько меняется урон ствола, в процентах
 * @param attackPercent  насколько меняется меткость этого ствола (BA формулы попадания)
 * @param shotsPercent   насколько меняется число выстрелов: AF — втрое, MIRV — вчетверо
 * @param rangePercent   насколько меняется дальность: HV — вдвое дальше, PD — вдвое ближе
 * @param costPercent    насколько меняется цена ствола, в процентах
 * @param spacePercent   насколько меняется место ствола, в процентах
 * @param piercesArmour  выстрел проходит броню насквозь
 * @param piercesShield  щит такого выстрела не держит
 * @param envelops       удар охватывает цель со всех четырёх сторон разом (ENV)
 * @param noRangePenalty расстояние не ослабляет удар (NR)
 * @param halvesEvasion  вдвое срезает уклонение цели от ракет (ECCM)
 * @param missileArmourPercent насколько крепче сама ракета: настолько труднее её сбить
 * @param missileSpeed   насколько ракета быстрее — её тоже труднее перехватить
 * @param hitsEngine     пробившись сквозь щит, ракета выбивает двигатель цели (EMG)
 * @param requiredTechCode технология, без которой модификации нет
 * @param available      изучена ли эта технология
 */
public record WeaponModificationDto(
        String code,
        String name,
        String description,
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
        String requiredTechCode,
        Boolean available
) {
}
