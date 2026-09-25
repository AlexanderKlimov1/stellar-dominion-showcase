package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Фаза прибытия жителей в конце хода — п. 4.1.1.
 * <p>
 * Идёт сразу после роста населения и до производства: сошедшие на планету жители
 * работают уже в этом ходу, как и родившиеся. Отправленные в этом же ходу здесь и
 * сходят — рейс занимает остаток хода, и еду его грузовики в этот ход уже не возили:
 * подвоз посчитан раньше, при сборке контекста.
 */
@Service
public class TransferPhase implements TurnPhase {

    private final PopulationTransferService transferService;

    public TransferPhase(PopulationTransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public Integer order() {
        return 6;
    }

    @Override
    public String name() {
        return "Прибытие жителей";
    }

    @Override
    public void apply(TurnContext context) {
        transferService.arrive(context);
    }
}
