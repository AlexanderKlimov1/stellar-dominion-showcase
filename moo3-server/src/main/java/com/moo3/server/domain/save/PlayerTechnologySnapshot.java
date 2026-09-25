package com.moo3.server.domain.save;

/**
 * Изученная технология в слепке партии — п. 9.
 * <p>
 * Технология записана кодами раздела, уровня и самой технологии: дерево живёт в файле
 * описания и в слепок не копируется, иначе сохранение ломалось бы от правок дерева.
 */
public record PlayerTechnologySnapshot(
        String categoryCode,
        Integer levelOrder,
        String optionCode,
        Integer acquiredTurn
) {
}
