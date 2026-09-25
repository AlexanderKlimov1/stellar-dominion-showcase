package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.LeaderKind;
import com.moo3.server.domain.enums.LeaderState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Служба лидера у игрока — п. 6.
 * <p>
 * Сам лидер живёт в справочнике: имя, способности, их сила и прирост за звание. Здесь
 * только то, чего в файле быть не может, — кого этот игрок нанял, сколько лидер набрал
 * опыта и куда назначен. Цена найма и жалованье записываются при появлении и больше не
 * меняются: иначе нанятый лидер дорожал бы у игрока на руках.
 */
@Entity
@Table(name = "player_leader")
public class PlayerLeaderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Код лидера в справочнике — ссылка в JSON, а не копия его характеристик. */
    @Column(name = "leader_code", nullable = false)
    private String leaderCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private LeaderKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private LeaderState state = LeaderState.OFFERED;

    @Column(name = "experience", nullable = false)
    private Integer experience = 0;

    @Column(name = "offered_turn", nullable = false)
    private Integer offeredTurn;

    @Column(name = "hired_turn")
    private Integer hiredTurn;

    @Column(name = "hire_cost", nullable = false)
    private Integer hireCost = 0;

    @Column(name = "salary", nullable = false)
    private Integer salary = 0;

    /** Система, где служит колониальный лидер; {@code null} — сидит в резерве. */
    @Column(name = "star_system_id")
    private UUID starSystemId;

    /** Флот, где служит корабельный лидер; {@code null} — сидит в резерве. */
    @Column(name = "fleet_id")
    private UUID fleetId;

    /** Ход, с которого назначение действует: до места лидер добирается пять ходов. */
    @Column(name = "arrives_turn")
    private Integer arrivesTurn;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getLeaderCode() {
        return leaderCode;
    }

    public void setLeaderCode(String leaderCode) {
        this.leaderCode = leaderCode;
    }

    public LeaderKind getKind() {
        return kind;
    }

    public void setKind(LeaderKind kind) {
        this.kind = kind;
    }

    public LeaderState getState() {
        return state;
    }

    public void setState(LeaderState state) {
        this.state = state;
    }

    public Integer getExperience() {
        return experience;
    }

    public void setExperience(Integer experience) {
        this.experience = experience;
    }

    public Integer getOfferedTurn() {
        return offeredTurn;
    }

    public void setOfferedTurn(Integer offeredTurn) {
        this.offeredTurn = offeredTurn;
    }

    public Integer getHiredTurn() {
        return hiredTurn;
    }

    public void setHiredTurn(Integer hiredTurn) {
        this.hiredTurn = hiredTurn;
    }

    public Integer getHireCost() {
        return hireCost;
    }

    public void setHireCost(Integer hireCost) {
        this.hireCost = hireCost;
    }

    public Integer getSalary() {
        return salary;
    }

    public void setSalary(Integer salary) {
        this.salary = salary;
    }

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public UUID getFleetId() {
        return fleetId;
    }

    public void setFleetId(UUID fleetId) {
        this.fleetId = fleetId;
    }

    public Integer getArrivesTurn() {
        return arrivesTurn;
    }

    public void setArrivesTurn(Integer arrivesTurn) {
        this.arrivesTurn = arrivesTurn;
    }
}
