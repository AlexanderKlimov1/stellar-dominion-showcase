package com.moo3.server.dto;

import com.moo3.server.domain.enums.BattleSide;

import java.util.List;
import java.util.UUID;

/**
 * Корабль на поле боя — п. 8, для сцены боя.
 * <p>
 * Экран рисует корабль прямоугольником, а размер берёт из корпуса: фрегат мал, Leviathan
 * занимает пол-экрана. Настоящие спрайты встанут на место прямоугольников и возьмут те же
 * координаты.
 *
 * @param hullSize   размер корпуса, 1..6: им и меряется прямоугольник корабля
 * @param structure  прочность сейчас; ноль — корабль уничтожен
 * @param initiative очередь хода: чем больше, тем раньше корабль ходит в круге
 * @param speed      сколько клеток корабль проходит за свой ход
 * @param moveLeft   сколько ему осталось пройти в этом ходу
 * @param weaponRange насколько далеко бьёт самый дальнобойный его ствол: с модификациями
 *                    (HV, PD) дальность у каждого корабля своя
 * @param yours      корабль этого игрока: только им он и может ходить
 * @param platform   корпус — орбитальная платформа (п. 8, п. 11): звёздная база и её
 *                   старшие сёстры стоят на поле сооружением, а не кораблём
 * @param defense    защита от лучей: та же величина, что в окне дизайна
 * @param missileEvasion уклонение от ракет в процентах — его даёт постановщик помех
 * @param weapons    чем корабль вооружён — п. 8: список для нижней полосы и окна осмотра
 * @param specials   особые модули корабля названиями
 * @param engineName двигатель, {@code null} — его нет вовсе (платформы неподвижны)
 * @param armourName броня корабля
 * @param shieldName щит; {@code null} — щита нет
 * @param computerName прибор наведения; {@code null} — его нет
 */
public record BattleShipDto(
        UUID id,
        UUID ownerPlayerId,
        String ownerName,
        BattleSide side,
        String designName,
        String hullName,
        Integer hullSize,
        Integer ordinal,
        Integer x,
        Integer y,
        Integer structure,
        Integer maxStructure,
        Integer armour,
        Integer maxArmour,
        Integer shield,
        Integer attack,
        Integer initiative,
        Integer speed,
        Integer moveLeft,
        Integer weaponRange,
        Boolean fired,
        Boolean destroyed,
        Boolean yours,
        Boolean platform,
        /**
         * На поле не корабль, а КОСМИЧЕСКОЕ ЧУДИЩЕ — п. 11.1: сцена рисует его существом,
         * а не прямоугольником корпуса. Признак приходит с сервера, а не угадывается по
         * названию проекта: имя хранится в базе и переименуется.
         */
        Boolean monster,
        Integer defense,
        Integer missileEvasion,
        List<BattleWeaponDto> weapons,
        List<String> specials,
        /** Двигатель разбит — п. 8: корабль неподвижен и с поля боя уже не уйдёт. */
        Boolean engineWrecked,
        /** Щит разбит: между кругами ему восстанавливаться нечем — п. 8. */
        Boolean shieldWrecked,
        /** Прицельный компьютер сожжён: корабль целится голым прицелом — п. 8. */
        Boolean computerWrecked,
        String engineName,
        String armourName,
        String shieldName,
        String computerName
) {
}
