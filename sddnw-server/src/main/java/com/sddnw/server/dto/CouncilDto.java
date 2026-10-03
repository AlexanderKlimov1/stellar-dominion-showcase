package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Выборы Высшего совета — п. 3: всё, что нужно сцене совета.
 * <p>
 * Сцена показывает голоса ПО ОДНОМУ, поэтому они приходят списком в том порядке, в каком
 * их объявляют: сперва тяжёлые. Числа заголовка (всего голосов и сколько нужно для
 * избрания) посчитаны на ходу голосования и хранятся вместе с ним — пересчитывать их
 * позже нечем, население с тех пор изменилось.
 *
 * @param turn          ход, на котором собрался совет
 * @param totalVotes    голосов у галактики всего
 * @param requiredVotes сколько нужно кандидату: две трети
 * @param electedPlayerId кто избран; {@code null} — совет разошёлся ни с чем
 * @param candidates    двое кандидатов — их имена стоят в заголовке
 * @param voters        все голосующие империи в порядке объявления
 * @param refused       избрания уже не признали: партия идёт дальше, второго отказа не будет
 * @param canRefuse     этот игрок вправе не подчиниться — п. 3
 * @param open          совет созван и ждёт голосов людей: итог подведут в конце хода, а
 *                      чужие голоса до тех пор не объявлены
 * @param ballot        этого игрока спрашивают: он голосует сам из сцены совета
 */
public record CouncilDto(
        Integer turn,
        Integer totalVotes,
        Integer requiredVotes,
        UUID electedPlayerId,
        String electedName,
        List<CouncilVoterDto> candidates,
        List<CouncilVoterDto> voters,
        Boolean refused,
        Boolean canRefuse,
        Boolean open,
        Boolean ballot
) {
}
