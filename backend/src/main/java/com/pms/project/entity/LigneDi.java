package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One row of lignes_di: a line of the Devis Interne (DI, the internal quote comparing what a
 * project sold against what it really costs). Structure only — amounts/margins are derived at
 * read time by DevisInterneService, never stored (F-AFF-13 §3), so a corrected exchange rate
 * never leaves stale figures behind. Reachable only via MANAGE_DI plus the ADR-021 project-scope check.
 */
@Entity
@Table(name = "lignes_di")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneDi extends BaseEntity {

    /** The project this line belongs to. Lazy: a quote holds dozens of lines, all pointing at the same project — eager loading would trigger N+1 queries. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Which of the three quote blocks this line sits in — compute() gives AUTRES_FRAIS lines a different cost formula (see SectionDi). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SectionDi section;

    /** Display rank inside the section, so the quote keeps its order after a line is added or corrected later. */
    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 0;

    /** The profile sold in the offer, e.g. "PC-1 Chef de mission". Free text, not a Resource link, since the client bought a profile, not a named person. */
    @Column(name = "profil_contractuel", length = 120)
    private String profilContractuel;

    /** Person named in the offer sent to the client; compared with ressourceRetenue below during review. */
    @Column(name = "ressource_proposee", length = 120)
    private String ressourceProposee;

    /** Person actually staffed — free text, since it must also be able to name a subcontractor with no PMS account. */
    @Column(name = "ressource_retenue", length = 120)
    private String ressourceRetenue;

    /** Unit the quantities are counted in ("H-Jour" = man-day); a label only, compute() never reads it. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String unite = "H-Jour";

    /** Workload SOLD on this line, in man-days (JH) — the promise to the client, not what the company plans to spend. Nullable; nz() treats a gap as zero. */
    @Column(name = "charge_vendue_jh", precision = 10, scale = 2)
    private BigDecimal chargeVendueJh;

    /** Selling price of one unit, in the PROJECT currency (not TND) — conversion happens once, in compute(). */
    @Column(name = "prix_vente_unitaire", precision = 15, scale = 2)
    private BigDecimal prixVenteUnitaire;

    /** Workload the company really expects to spend, in man-days — the gap with chargeVendueJh above is where the margin comes from. */
    @Column(name = "quantite_interne_jh", precision = 10, scale = 2)
    private BigDecimal quantiteInterneJh;

    /**
     * What one internal man-day really costs, in TND (TCC = "taux de coût chargé", loaded cost
     * rate). Copied onto the line rather than read from the resource table, since a DI is a
     * forecast often written for a profile or subcontractor with no Resource row, and must not
     * change retroactively when a rate is renegotiated.
     */
    @Column(name = "cout_unitaire_tcc", precision = 10, scale = 2)
    private BigDecimal coutUnitaireTcc;

    /** Misc costs on this line as a flat TND amount (FD on the Excel sheet), kept separate so a reviewer can see why a line costs more than days x rate. */
    @Column(name = "frais_divers", precision = 15, scale = 2)
    private BigDecimal fraisDivers;

    /** General overhead charged to this line, flat TND (FG-P&ST) — a structural charge, distinct from fraisDivers' project-level cost. */
    @Column(name = "frais_generaux", precision = 15, scale = 2)
    private BigDecimal fraisGeneraux;

    /** Direct taxes on this line as a flat TND amount (RS/IS, IRPP/CNSS, ENR, REDEV); some taxes are a fixed sum, others use tauxPourcentage below instead. */
    @Column(name = "cout_impots", precision = 15, scale = 2)
    private BigDecimal coutImpots;

    /**
     * Rate for tax/provision lines: cost = this rate x total sold of the whole quote, in TND
     * (e.g. 0.05 for 5%). Stored as a coefficient, not "5", since compute() multiplies it
     * directly. This is why the calculation needs two passes: such a line can't be priced
     * until every other line is summed.
     */
    @Column(name = "taux_pourcentage", precision = 7, scale = 4)
    private BigDecimal tauxPourcentage;
}
