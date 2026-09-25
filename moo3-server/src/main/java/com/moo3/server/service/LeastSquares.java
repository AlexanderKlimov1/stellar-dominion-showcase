package com.moo3.server.service;

/**
 * Метод наименьших квадратов с гребневой поправкой — счётная машинка этапа 2
 * (`balance-metrics-works.txt`).
 * <p>
 * Игровых правил здесь нет вовсе: на вход матрица наблюдений и столбец ответов, на выходе
 * веса и их ошибки. Правила чтения этих чисел живут в {@link BalanceVerdictRules}.
 * <p>
 * <b>Почему гребень, а не чистый МНК.</b> Столбцы сборки линейно зависимы по устройству
 * конструктора: из группы-переключателя берут ровно одну сторону, поэтому её столбцы в
 * сумме дают единицу — ту же, что и свободный член. Чистые нормальные уравнения на такой
 * матрице вырождаются, и обращение либо падает, либо даёт числа из воздуха. Гребень
 * (малая добавка к диагонали) делает систему разрешимой всегда, ценой лёгкого притягивания
 * весов к нулю — а для наших целей это скорее плюс: цена не должна прыгать от шума
 * (п. 5 плана).
 * <p>
 * Библиотеки ради этого не заводим: матрица здесь в полсотни столбцов, и обращение
 * методом Гаусса — Жордана считается мгновенно. Зависимости сервера ставятся вручную, и
 * тащить ради одного обращения линейную алгебру целиком незачем.
 */
public final class LeastSquares {

    private LeastSquares() {
    }

    /**
     * Веса, их ошибки и остаточный разброс.
     *
     * @param weights веса столбцов в том же порядке, в каком они шли в матрице
     * @param errors  стандартная ошибка каждого веса
     * @param sigma   корень из остаточной дисперсии: насколько ответы не легли на модель
     */
    public record Fit(double[] weights, double[] errors, double sigma) {
    }

    /**
     * Решает {@code y = X * w} по наименьшим квадратам с гребневой поправкой.
     *
     * @param x      матрица наблюдений: строка — наблюдение, столбец — признак
     * @param y      ответы, по одному на строку
     * @param ridge  гребневая добавка к диагонали; ноль — чистый МНК
     */
    public static Fit solve(double[][] x, double[] y, double ridge) {
        int rows = x.length;
        if (rows == 0) {
            return new Fit(new double[0], new double[0], 0);
        }
        int columns = x[0].length;

        // Нормальные уравнения: (X'X + ridge*I) w = X'y. Матрица маленькая, и считать её
        // построчно дешевле, чем собирать транспонированную копию.
        double[][] normal = new double[columns][columns];
        double[] right = new double[columns];
        for (double[] row : x) {
            for (int i = 0; i < columns; i++) {
                if (row[i] == 0) {
                    continue;
                }
                for (int j = 0; j < columns; j++) {
                    normal[i][j] += row[i] * row[j];
                }
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int i = 0; i < columns; i++) {
                right[i] += x[row][i] * y[row];
            }
        }
        for (int i = 0; i < columns; i++) {
            normal[i][i] += ridge;
        }

        double[][] inverse = invert(normal);
        double[] weights = new double[columns];
        for (int i = 0; i < columns; i++) {
            double sum = 0;
            for (int j = 0; j < columns; j++) {
                sum += inverse[i][j] * right[j];
            }
            weights[i] = sum;
        }

        // Остаточный разброс: на сколько ответы не легли на модель. Степени свободы —
        // наблюдения минус столбцы; при их нехватке ошибки считать нечем.
        double residual = 0;
        for (int row = 0; row < rows; row++) {
            double predicted = 0;
            for (int i = 0; i < columns; i++) {
                predicted += x[row][i] * weights[i];
            }
            residual += Math.pow(y[row] - predicted, 2);
        }
        int freedom = rows - columns;
        double variance = freedom > 0 ? residual / freedom : 0;
        double sigma = Math.sqrt(variance);

        double[] errors = new double[columns];
        for (int i = 0; i < columns; i++) {
            errors[i] = variance <= 0 ? 0 : Math.sqrt(Math.max(0, variance * inverse[i][i]));
        }
        return new Fit(weights, errors, sigma);
    }

    /** Обращение методом Гаусса — Жордана с выбором главного элемента по столбцу. */
    private static double[][] invert(double[][] source) {
        int size = source.length;
        double[][] work = new double[size][2 * size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(source[i], 0, work[i], 0, size);
            work[i][size + i] = 1;
        }

        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            double[] swap = work[column];
            work[column] = work[pivot];
            work[pivot] = swap;

            double head = work[column][column];
            if (Math.abs(head) < 1e-12) {
                // Столбец выродился даже с гребнем: считаем его весом нуль, а не бросаем
                // всё. Такое бывает со стороной, которую не взяли ни разу.
                continue;
            }
            for (int i = column; i < 2 * size; i++) {
                work[column][i] /= head;
            }
            for (int row = 0; row < size; row++) {
                if (row == column || work[row][column] == 0) {
                    continue;
                }
                double factor = work[row][column];
                for (int i = column; i < 2 * size; i++) {
                    work[row][i] -= factor * work[column][i];
                }
            }
        }

        double[][] inverse = new double[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(work[i], size, inverse[i], 0, size);
        }
        return inverse;
    }
}
