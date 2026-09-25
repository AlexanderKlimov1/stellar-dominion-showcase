package com.moo3.server.galaxy;

import com.moo3.server.config.GameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Каталог реальных названий звёзд из текстового справочника — п. 11.3.
 * <p>
 * Путь к файлу задаёт {@code moo3.game.star-names-file}. Формат: одно название в строке,
 * пустые строки и строки с {@code #} игнорируются. Файл перечитывается при каждой генерации
 * галактики, поэтому правки справочника подхватываются без перезапуска сервера.
 * <p>
 * Имя специальной звезды (п. 4.2.1) в каталог не входит и выдаётся генератором отдельно.
 */
@Component
public class StarNameCatalog {

    /**
     * Имя специальной звезды — п. 4.2.1.
     * <p>
     * Wardenhold («Стражевая»), а не Orion: сама звезда астрономическая, но история о
     * страже, который её сторожит, — лор Master of Orion, и имя тянуло его за собой
     * (`docs/renaming.md`). Имя латинское на обоих языках, как и все прочие звёзды: оно
     * ложится в базу строкой, а у хранимого имени языка быть не может.
     */
    public static final String SPECIAL_STAR = "Wardenhold";

    private static final Logger log = LoggerFactory.getLogger(StarNameCatalog.class);
    private static final String COMMENT_PREFIX = "#";
    /** Windows-редакторы сохраняют UTF-8 с BOM, он приезжает в начало первой строки. */
    private static final String BOM = "\uFEFF";

    private final Path file;

    public StarNameCatalog(GameProperties gameProperties) {
        this.file = Path.of(gameProperties.starNamesFile()).toAbsolutePath();
    }

    /**
     * Раздатчик уникальных в пределах одной галактики имён.
     * Порядок зависит только от переданного {@link Random}, поэтому генерация
     * с одинаковым seed воспроизводима.
     */
    public NamePicker picker(Random random) {
        List<String> names = new ArrayList<>(read());
        Collections.shuffle(names, random);
        return new NamePicker(names);
    }

    private Set<String> read() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать справочник названий звёзд: " + file, e);
        }

        // LinkedHashSet: повтор в файле не должен занимать два места в каталоге.
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String name = line.replace(BOM, "").trim();
            if (name.isEmpty() || name.startsWith(COMMENT_PREFIX) || name.equalsIgnoreCase(SPECIAL_STAR)) {
                continue;
            }
            names.add(name);
        }

        if (names.isEmpty()) {
            throw new IllegalStateException("Справочник названий звёзд пуст: " + file);
        }
        log.debug("Справочник названий звёзд: {} имён из {}", names.size(), file);
        return names;
    }

    public static final class NamePicker {

        private final Deque<String> available;
        private Integer overflow = 0;

        private NamePicker(List<String> names) {
            this.available = new ArrayDeque<>(names);
        }

        /**
         * Следующее свободное имя. Если каталог исчерпан — к именам добавляется
         * порядковый суффикс, так что уникальность в галактике сохраняется.
         */
        public String next() {
            String name = available.poll();
            if (name != null) {
                return name;
            }
            overflow = overflow + 1;
            return "Nameless " + overflow;
        }

        public Integer remaining() {
            return available.size();
        }
    }
}
