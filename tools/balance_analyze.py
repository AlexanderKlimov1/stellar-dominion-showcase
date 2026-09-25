"""
Разбор телеметрии балансировки — этап 1 плана (balance-metrics-works.txt).

Отвечает на четыре вопроса:

  1. Годится ли мощь суррогатом исхода: предсказывает ли лидер по мощи на ходу T того,
     кто в итоге победил, и насколько мощь на ходу T согласуется с итоговым порядком.
  2. Каков курс «очко → сила»: сколько силы приносит очко расы (по прогону --curve).
  3. Что из механик игра вообще трогала: пока счётчик пуст, цена стороны расы, живущей
     этой механикой, — не измерение, а ноль по недосмотру (п. 6 плана).
  4. Различимы ли отдельные стороны расы между собой — диагностика перед этапом 2:
     она отделяет «цены не совпадают с ценностью» от «ИИ сторонами не пользуется».

    python tools\\balance_analyze.py                        — разбирает tools\\balance\\telemetry.jsonl
    python tools\\balance_analyze.py --file другой.jsonl

Своих зависимостей не тянет: считает на голом Python, как и всё в tools.
"""
import argparse
import collections
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FILE = os.path.join(ROOT, 'tools', 'balance', 'telemetry.jsonl')
REPORT = os.path.join(ROOT, 'tools', 'balance', 'balance-analysis.txt')

report = []


def say(line=''):
    print(line)
    report.append(line)


def load(path):
    if not os.path.exists(path):
        raise SystemExit('нет файла телеметрии %s — сперва прогон tools\\balance_run.py' % path)
    return [json.loads(line) for line in io.open(path, encoding='utf-8')]


def by_game(rows):
    """Строки, разложенные по партиям и империям: {партия: {место: [строки по ходам]}}."""
    games = collections.defaultdict(lambda: collections.defaultdict(list))
    for row in rows:
        games[row['game']][row['slot']].append(row)
    for game in games.values():
        for history in game.values():
            history.sort(key=lambda r: r['turn'])
    return games


def at_turn(history, turn):
    """Замер на этом ходу или последний до него; пусто — империя туда не дожила."""
    seen = [row for row in history if row['turn'] <= turn]
    return seen[-1] if seen else None


def average(values):
    return sum(values) / len(values) if values else 0.0


def error(values):
    """
    Стандартная ошибка среднего — без неё таблица врёт молчанием.

    Партии разнятся щедростью карты и удачей соседства сильнее, чем расы — сторонами:
    средняя доля мощи по бюджету бывает обманчиво ровной, и отличить измеренный курс от
    разброса можно только числом рядом со средним.
    """
    if len(values) < 2:
        return 0.0
    mean = average(values)
    variance = sum((value - mean) ** 2 for value in values) / (len(values) - 1)
    return (variance / len(values)) ** 0.5


def spearman(first, second):
    """Ранговая согласованность двух рядов — считаем сами, без сторонних библиотек."""
    if len(first) < 2:
        return None

    def ranks(values):
        order = sorted(range(len(values)), key=lambda i: values[i])
        result = [0.0] * len(values)
        index = 0
        while index < len(order):
            same = index
            while same + 1 < len(order) and values[order[same + 1]] == values[order[index]]:
                same += 1
            average = (index + same) / 2.0 + 1
            for position in range(index, same + 1):
                result[order[position]] = average
            index = same + 1
        return result

    left, right = ranks(first), ranks(second)
    n = len(left)
    mean_left, mean_right = sum(left) / n, sum(right) / n
    top = sum((left[i] - mean_left) * (right[i] - mean_right) for i in range(n))
    bottom = (sum((x - mean_left) ** 2 for x in left)
              * sum((x - mean_right) ** 2 for x in right)) ** 0.5
    return None if bottom == 0 else round(top / bottom, 3)


def surrogate(games):
    """Проверка суррогата: предсказывает ли мощь на ходу T исход партии."""
    say('1. МОЩЬ КАК СУРРОГАТ ИСХОДА')
    finished = {code: game for code, game in games.items()
                if any(row.get('status') == 'FINISHED'
                       for history in game.values() for row in history)}
    say('   партий всего: %d, из них доигранных: %d' % (len(games), len(finished)))
    if not finished:
        say('   предсказывать нечего: ни одна партия не кончилась победой.')
        say('   Этап 1 требует доигранных партий — их дают галактики на две-три империи.')
        say()
        return

    turns = [25, 50, 75, 100, 150, 200]
    say('   %-8s %-12s %-14s %s' % ('ход', 'партий', 'угадано', 'ранговая связь'))
    for turn in turns:
        hits, total, correlations = 0, 0, []
        for game in finished.values():
            winner = next((row['winner_slot'] for history in game.values() for row in history
                           if row.get('winner_slot') is not None), None)
            snapshot = {slot: at_turn(history, turn) for slot, history in game.items()}
            snapshot = {slot: row for slot, row in snapshot.items() if row}
            if winner is None or len(snapshot) < 2:
                continue
            # Партия, кончившаяся раньше этого хода, ничего не проверяет: «предсказание»
            # задним числом — не предсказание.
            if max(row['turn'] for row in snapshot.values()) < turn:
                continue

            total += 1
            leader = max(snapshot.items(), key=lambda item: item[1]['might'])[0]
            if leader == winner:
                hits += 1

            finals = {slot: history[-1]['might'] for slot, history in game.items()}
            slots = sorted(snapshot)
            correlation = spearman([snapshot[s]['might'] for s in slots],
                                   [finals.get(s, 0) for s in slots])
            if correlation is not None:
                correlations.append(correlation)

        if total == 0:
            continue
        mean = sum(correlations) / len(correlations) if correlations else float('nan')
        say('   %-8s %-12s %-14s %.2f'
            % (turn, total, '%d (%.0f %%)' % (hits, 100.0 * hits / total), mean))
    say()
    say('   «Угадано» — лидер по мощи на этом ходу и есть будущий победитель.')
    say('   «Ранговая связь» — согласие порядка империй по мощи тогда и в конце партии.')
    say()


#: Сколько империй должно прийтись на бюджет, чтобы точка что-то значила. Обычный прогон
#: (не --curve) играет готовыми расами, и все они стоят ровно десять очков; одинокая точка
#: другого бюджета там появляется от случайности одной партии, а прямая по ней и по
#: семидесяти «десяткам» показывала курс с обратным знаком — измерением это не было.
CURVE_MIN_EMPIRES = 4


def curve(games):
    """
    Курс «очко → сила»: сколько силы приносит очко расы.

    Мер две, и это не роскошь. Мощь — суррогат исхода, но в неё входят флот и изученное,
    а они у ИИ гуляют от случая к случаю сильнее, чем от расы: на 240 партиях разрыв
    крайних бюджетов по мощи тонет в собственной ошибке. Выработка колоний тише — стороны
    расы бьют в неё прямо, — и на той же выборке тот же разрыв виден уверенно. Поэтому
    печатаются обе: одна отвечает «кто победит», вторая — «на сколько сильнее».
    """
    say('2. КУРС «ОЧКО → СИЛА»')
    shares = collections.defaultdict(list)
    made = collections.defaultdict(list)
    extras = {key: collections.defaultdict(list)
              for key in ('production', 'population_k', 'colonies', 'technologies')}
    for game in games.values():
        last = {slot: history[-1] for slot, history in game.items() if history}
        if len(last) < 2 or any(row.get('budget') is None for row in last.values()):
            continue
        total = sum(row['might'] for row in last.values())
        made_total = sum(row['production'] for row in last.values())
        if total <= 0 or made_total <= 0:
            continue
        for row in last.values():
            # Доля в своей партии, а не сама величина: галактики разной щедрости иначе
            # смешались бы в одну кучу (п. 3 плана — парные семена и ковариаты).
            shares[row['budget']].append(100.0 * row['might'] / total)
            made[row['budget']].append(100.0 * row['production'] / made_total)
            for key, sink in extras.items():
                sink[row['budget']].append(row[key])

    thin = {budget: len(values) for budget, values in shares.items()
            if len(values) < CURVE_MIN_EMPIRES}
    shares = {budget: values for budget, values in shares.items()
              if len(values) >= CURVE_MIN_EMPIRES}
    if len(shares) < 2:
        say('   бюджетов с выборкой от %d империй меньше двух — нужен прогон --curve.'
            % CURVE_MIN_EMPIRES)
        if thin:
            say('   отброшены одиночки: %s'
                % ', '.join('бюджет %s (%d)' % pair for pair in sorted(thin.items())))
        say()
        return

    made = {budget: values for budget, values in made.items() if budget in shares}

    say('   %-9s %-8s %-16s %-16s %-9s %-8s'
        % ('бюджет', 'империй', 'доля мощи, %', 'доля выраб., %', 'жителей', 'колоний'))
    might_points, made_points = [], []
    for budget in sorted(shares):
        might_points.append((budget, average(shares[budget]), error(shares[budget])))
        made_points.append((budget, average(made[budget]), error(made[budget])))
        say('   %-9s %-8s %-16s %-16s %-9.0f %-8.1f'
            % (budget, len(shares[budget]),
               '%.2f ± %.2f' % (average(shares[budget]), error(shares[budget])),
               '%.2f ± %.2f' % (average(made[budget]), error(made[budget])),
               average(extras['population_k'][budget]) / 1000.0,
               average(extras['colonies'][budget])))
    say()

    measured = report_measure('Мощь', might_points)
    made_measured = report_measure('Выработка', made_points)
    say()
    if made_measured and not measured:
        say('   Мерить силу расы сегодня нужно ВЫРАБОТКОЙ: в мощь входят флот и изученное,')
        say('   а они у ИИ гуляют от случая сильнее, чем от расы. Исход партии по-прежнему')
        say('   предсказывает мощь (раздел 1) — это разные вопросы и разные меры.')
    elif not measured and not made_measured:
        say('   Ни одна мера не различает крайние бюджеты: выборка мала для любого курса.')
    say()
    gap_by_turn(games, might_points[0][0], might_points[-1][0])


def report_measure(name, points):
    """
    Наклон и различимость одной меры. Возвращает, измерен ли курс по ней.

    Без строки о различимости таблица выглядит измерением, даже когда весь её разброс —
    шум: на 36 партиях десятиочковая раса обходила безликую на 1,6 п.п. при ошибке
    разности 1,3, и это читалось как курс.
    """
    n = len(points)
    mean_x = sum(x for x, _, _ in points) / n
    mean_y = sum(y for _, y, _ in points) / n
    bottom = sum((x - mean_x) ** 2 for x, _, _ in points)
    slope = None if bottom == 0 else (
        sum((x - mean_x) * (y - mean_y) for x, y, _ in points) / bottom)

    low, high = points[0], points[-1]
    gap = high[1] - low[1]
    gap_error = (low[2] ** 2 + high[2] ** 2) ** 0.5
    errors = abs(gap) / gap_error if gap_error else float('inf')
    say('   %-10s очко = %+.3f п.п. доли; бюджеты %d и %d врозь на %+.2f ± %.2f — %.1f ошибки%s'
        % (name + ':', slope if slope is not None else 0.0, low[0], high[0], gap, gap_error,
           errors, '' if errors >= 2 else '  ← НЕ ИЗМЕРЕНО'))
    return errors >= 2


def gap_by_turn(games, low_budget, high_budget):
    """
    Когда сборка расы начинает быть видна — на каком ходу снимать замер.

    Ожидание было такое: стороны расы работают процентами, значит копятся, и разойтись
    сборки успевают лишь к концу партии. Замер показал обратное — разрыв не растёт, а
    ошибка растёт, и уверенней всего сборка видна РАНО. Поэтому таблица и печатается: она
    называет ход, на котором курс ещё различим.
    """
    turns = [25, 50, 100, 150, 200, 300, 400]
    rows = []
    for turn in turns:
        pools = {'might': ([], []), 'production': ([], [])}
        for game in games.values():
            snapshot = {slot: at_turn(history, turn) for slot, history in game.items()}
            snapshot = {slot: row for slot, row in snapshot.items() if row}
            if len(snapshot) < 2 or max(row['turn'] for row in snapshot.values()) < turn:
                continue
            for key, (low, high) in pools.items():
                total = sum(row[key] for row in snapshot.values())
                if total <= 0:
                    continue
                for row in snapshot.values():
                    if row.get('budget') == low_budget:
                        low.append(100.0 * row[key] / total)
                    elif row.get('budget') == high_budget:
                        high.append(100.0 * row[key] / total)
        if len(pools['might'][0]) < CURVE_MIN_EMPIRES:
            continue
        line = [turn, len(pools['might'][0])]
        for key in ('might', 'production'):
            low, high = pools[key]
            gap = average(high) - average(low)
            spread = (error(low) ** 2 + error(high) ** 2) ** 0.5
            line += [gap, spread]
        rows.append(line)

    if not rows:
        return
    say('   Когда сборка становится видна (бюджет %d против %d):' % (high_budget, low_budget))
    say('   %-7s %-9s %-15s %-8s %-15s %s'
        % ('ход', 'партий', 'мощь, п.п.', 'ошибок', 'выработка, п.п.', 'ошибок'))
    for turn, count, gap, spread, made_gap, made_spread in rows:
        say('   %-7s %-9s %-15s %-8.1f %-15s %.1f'
            % (turn, count, '%+.2f ± %.2f' % (gap, spread),
               abs(gap) / spread if spread else float('inf'),
               '%+.2f ± %.2f' % (made_gap, made_spread),
               abs(made_gap) / made_spread if made_spread else float('inf')))
    say()


def usage(rows):
    """Что из механик игра вообще трогала — п. 6 плана."""
    say('3. ЧТО ИГРА ТРОГАЛА')
    empires = {}
    for row in rows:
        empires[(row['game'], row['slot'])] = row.get('used') or {}
    total = len(empires)
    counters = collections.Counter()
    times = collections.Counter()
    for used in empires.values():
        for code, count in used.items():
            counters[code] += 1
            times[code] += count

    known = ['COLONIZED', 'OUTPOST', 'FLIGHT', 'WAR', 'BATTLE', 'INVASION', 'CAPTURE',
             'MIND_CONTROL', 'ESPIONAGE', 'TREATY', 'LEADER']
    say('   империй в выборке: %d' % total)
    say('   %-14s %-16s %s' % ('механика', 'у скольких', 'раз всего'))
    for code in known:
        share = '%d (%.0f %%)' % (counters[code], 100.0 * counters[code] / total) if total else '0'
        say('   %-14s %-16s %d' % (code, share, times[code]))

    silent = [code for code in known if counters[code] == 0]
    say()
    if silent:
        say('   НЕ СРАБОТАЛО НИ РАЗУ: %s' % ', '.join(silent))
        say('   Стороны расы, живущие этими механиками, измерять прогонами нельзя: их цена')
        say('   останется вилкой «оценка ИИ … оценка скриптового зонда» (п. 6 плана).')
    else:
        say('   Все механики хоть раз сработали — измеримы все стороны расы.')
    say()


#: Сколько империй должно взять сторону, чтобы её замер что-то значил.
TRAIT_MIN_TAKERS = 12


def traits(games):
    """
    Сила отдельных сторон расы — диагностика перед этапом 2.

    Отвечает на вопрос, который курс сам по себе не решает: курс мал (десять очков дают
    восьмую часть выработки) — это ИИ не умеет пользоваться сторонами расы или стороны
    и правда слабы? Ответ виден по РАЗБРОСУ: если стороны различаются между собой сильно
    сильнее, чем общий курс, значит прибор работает, а цены не совпадают с ценностью —
    и этап 2 имеет смысл. Если же все стороны дают одинаково около нуля, мерить нечего:
    сперва надо учить ИИ.

    Считается честно, но просто: сторона сравнивается у тех, кто её взял, с теми, кто не
    взял, среди империй СОПОСТАВИМОГО бюджета — иначе дорогая сторона мерила бы не себя,
    а богатство сборки, в которую она только и помещается.
    """
    say('4. СИЛА ОТДЕЛЬНЫХ СТОРОН (диагностика перед этапом 2)')
    empires = []
    for game in games.values():
        last = {slot: history[-1] for slot, history in game.items() if history}
        made_total = sum(row['production'] for row in last.values())
        if len(last) < 2 or made_total <= 0:
            continue
        for row in last.values():
            empires.append({'budget': row.get('budget'),
                            'traits': set(row.get('traits') or []),
                            'share': 100.0 * row['production'] / made_total})
    if not empires:
        say('   нечего считать: в телеметрии нет выработки.')
        say()
        return

    #: Цена стороны неизвестна анализу, поэтому «сопоставимый бюджет» берётся так:
    #: сравниваются только те империи, чей бюджет не меньше самого дешёвого бюджета,
    #: при котором сторону вообще брали.
    cheapest = {}
    for empire in empires:
        for code in empire['traits']:
            if code not in cheapest or empire['budget'] < cheapest[code]:
                cheapest[code] = empire['budget']

    measured = []
    for code, floor in sorted(cheapest.items()):
        took = [e['share'] for e in empires if code in e['traits'] and e['budget'] >= floor]
        without = [e['share'] for e in empires if code not in e['traits'] and e['budget'] >= floor]
        if len(took) < TRAIT_MIN_TAKERS or len(without) < TRAIT_MIN_TAKERS:
            continue
        gap = average(took) - average(without)
        spread = (error(took) ** 2 + error(without) ** 2) ** 0.5
        measured.append((gap, spread, code, len(took)))

    if not measured:
        say('   ни одна сторона не набрала %d носителей — нужна выборка побольше.'
            % TRAIT_MIN_TAKERS)
        say()
        return

    measured.sort(reverse=True)
    strong = [row for row in measured if row[1] and abs(row[0]) / row[1] >= 2]
    say('   сторон в замере: %d, из них различимы (два стандартных отклонения): %d'
        % (len(measured), len(strong)))
    say()
    say('   %-22s %-8s %-18s %s' % ('сторона', 'взяли', 'выработка, п.п.', 'ошибок'))
    for gap, spread, code, takers in measured[:8] + [(None, None, None, None)] + measured[-8:]:
        if code is None:
            say('   %-22s %-8s %-18s %s' % ('...', '', '', ''))
            continue
        say('   %-22s %-8d %-18s %.1f'
            % (code, takers, '%+.2f ± %.2f' % (gap, spread),
               abs(gap) / spread if spread else float('inf')))
    say()

    best = measured[0][0]
    worst = measured[-1][0]
    say('   Разброс между сильнейшей и слабейшей стороной: %.2f п.п.' % (best - worst))
    say('   Для сравнения: весь бюджет расы стоит примерно столько же (раздел 2).')
    if strong:
        say('   Различимые стороны есть — прибор мерит, и цены с ценностью не совпадают.')
        say('   Этап 2 имеет смысл: регрессия разложит это по сторонам аккуратнее.')
    else:
        say('   Ни одна сторона не различима: мерить нечего, и дело не в ценах. Сперва')
        say('   надо учить ИИ пользоваться сторонами расы (п. 6 плана).')
    say()


def main():
    parser = argparse.ArgumentParser(description='Разбор телеметрии балансировки (этап 1)')
    parser.add_argument('--file', default=DEFAULT_FILE, help='файл телеметрии JSONL')
    options = parser.parse_args()

    rows = load(options.file)
    games = by_game(rows)
    say('Телеметрия: %d замеров, %d партий, %s'
        % (len(rows), len(games), os.path.relpath(options.file, ROOT)))
    say()

    surrogate(games)
    curve(games)
    usage(rows)
    traits(games)

    io.open(REPORT, 'w', encoding='utf-8').write(chr(10).join(report) + chr(10))
    say('Отчёт: %s' % os.path.relpath(REPORT, ROOT))


if __name__ == '__main__':
    main()
