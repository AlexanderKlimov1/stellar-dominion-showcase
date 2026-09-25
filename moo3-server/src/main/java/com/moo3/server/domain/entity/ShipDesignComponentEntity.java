package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Компонент в проекте корабля — п. 8.
 * <p>
 * Одинаковых пушек в проекте бывает несколько, поэтому строка хранит количество, а не
 * повторяется. Порядок нужен, чтобы окно дизайна показывало состав так же, как игрок
 * его собирал.
 */
/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "ship_design_component",
    indexes = {
        @Index(name = "ix_ship_design_component_design", columnList = "design_id")
    })
public class ShipDesignComponentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "design_id", nullable = false)
    private UUID designId;

    /** Код компонента — ссылка в {@code ship-components.json}. */
    @Column(name = "component_code", nullable = false)
    private String componentCode;

    @Column(name = "count", nullable = false)
    private Integer count;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /**
     * Модификации ствола — п. 8: коды через запятую, пусто — ствол как есть.
     * <p>
     * Строкой, а не своей таблицей: модификаций у ствола одна-две, а состав проекта
     * читается на каждый корабль в бою — таблица дала бы выборку на каждый ствол.
     */
    @Column(name = "modifications")
    private String modifications;

    public String getModifications() {
        return modifications;
    }

    public void setModifications(String modifications) {
        this.modifications = modifications;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDesignId() {
        return designId;
    }

    public void setDesignId(UUID designId) {
        this.designId = designId;
    }

    public String getComponentCode() {
        return componentCode;
    }

    public void setComponentCode(String componentCode) {
        this.componentCode = componentCode;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
