package com.moo3.server.web;

import com.moo3.server.dto.BalanceCombinationDto;
import com.moo3.server.dto.BalanceRunDto;
import com.moo3.server.dto.BalanceRunRequest;
import com.moo3.server.service.BalanceLoad;
import com.moo3.server.service.AccountService;
import com.moo3.server.service.BalanceRunService;

import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Пульт балансировки — этап 2 плана (`balance-metrics-works.txt`).
 * <p>
 * Хозяйство сервера, а не своя партия, поэтому каждый вызов спрашивает роль
 * ({@code requireAdmin}), как это делает удаление сохранений. Позже, когда появится
 * ролевая модель, этот же вызов станет проверкой нужной роли — менять придётся одно место.
 */
@RestController
@RequestMapping("/api/balance")
@Validated
public class BalanceController {

    /** Нагрузка прогона по времени суток — п. 2 этапа 2. */
    private final BalanceLoad balanceLoad;

    private final BalanceRunService balanceRunService;
    private final AccountService accountService;

    public BalanceController(BalanceRunService balanceRunService, AccountService accountService,
                             BalanceLoad balanceLoad) {
        this.balanceLoad = balanceLoad;
        this.balanceRunService = balanceRunService;
        this.accountService = accountService;
    }

    /** Заказать прогон: галактика, сколько империй и чем играют, сколько партий и ходов. */
    @PostMapping("/runs")
    public BalanceRunDto start(@Valid @RequestBody BalanceRunRequest request,
                               @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.start(request);
    }

    /**
     * Поставить прогон на паузу — п. 2 этапа 2.
     * <p>
     * Отвечает сразу, а в силу вступает, когда доиграются начатые партии: бросать партию
     * посередине нельзя, её замер пропал бы. Паузу переживают только замеры этого запуска
     * сервера — перезапуск приостановленный прогон закрывает.
     */
    @PostMapping("/runs/{runId}/pause")
    public BalanceRunDto pause(@PathVariable UUID runId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        balanceRunService.pause(runId);
        return balanceRunService.run(runId);
    }

    /** Продолжить приостановленный прогон — п. 2 этапа 2. */
    @PostMapping("/runs/{runId}/resume")
    public BalanceRunDto resume(@PathVariable UUID runId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        balanceRunService.resume(runId);
        return balanceRunService.run(runId);
    }

    /**
     * Сколько партий прогон считает разом — п. 2 этапа 2.
     * <p>
     * Ночью больше, днём меньше: прогон занимает столько ядер, сколько ему дали, и на
     * двенадцати потоках за машиной уже не поработать. Настройка действует НА ХОДУ —
     * предел спрашивается перед каждой партией, поэтому и смена часа, и правка отсюда
     * подхватываются без перезапуска и без остановки идущего прогона.
     */
    @GetMapping("/load")
    public Map<String, Object> load(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return Map.of(
                "nightFrom", balanceLoad.nightFrom(),
                "nightUntil", balanceLoad.nightUntil(),
                "nightWorkers", balanceLoad.nightWorkers(),
                "dayWorkers", balanceLoad.dayWorkers(),
                "night", balanceLoad.night(),
                "workers", balanceLoad.workers(),
                "running", balanceLoad.running());
    }

    /** Меняет её на ходу; пропущенное поле оставляет прежним. */
    @PutMapping("/load")
    public Map<String, Object> setLoad(@RequestBody Map<String, Integer> request,
                                       @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        balanceLoad.update(request.get("nightFrom"), request.get("nightUntil"),
                request.get("nightWorkers"), request.get("dayWorkers"));
        return load(accountToken);
    }

    /** Прогоны свежими сверху: идущий виден по числу сыгранных партий. */
    @GetMapping("/runs")
    public List<BalanceRunDto> runs(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.runs();
    }

    /**
     * Пересудить прогон нынешними правилами, не переигрывая его.
     * <p>
     * Замеры у прогона свои, цены — из его же снимка; меняются только правила чтения.
     */
    @PostMapping("/runs/{runId}/reassess")
    public BalanceRunDto reassess(@PathVariable UUID runId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.reassess(runId);
    }

    /**
     * Связки, которые стоит проверить, и что о них известно.
     * <p>
     * Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл
     * имеют считанные — те, о которых есть догадка. Здесь они и копятся вместе с ответами
     * прогонов.
     */
    @GetMapping("/combinations")
    public List<BalanceCombinationDto> combinations(@AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.combinations();
    }

    /** Запомнить связку: что проверяем и зачем. */
    @PostMapping("/combinations")
    public BalanceCombinationDto remember(@Valid @RequestBody BalanceCombinationDto.Request request,
                                          @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.remember(request);
    }

    /** Забыть связку — догадка не оправдалась или оказалась той же, что соседняя. */
    @DeleteMapping("/combinations/{id}")
    public void forget(@PathVariable UUID id, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        balanceRunService.forget(id);
    }

    /** Один прогон целиком — со снимком цен и приговорами. */
    @GetMapping("/runs/{runId}")
    public BalanceRunDto run(@PathVariable UUID runId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return balanceRunService.run(runId);
    }
}
