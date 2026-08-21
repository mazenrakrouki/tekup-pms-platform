package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Ligne du Devis Interne (F-AFF-13 §3) — STRUCTURE UNIQUEMENT.
 *
 * Décision 2026-07-05 (BUSINESS_ANALYSIS.md §16) : le modèle est livré vide ;
 * les valeurs réelles de la société ne sont jamais seedées. Les montants
 * (devise, TND) et marges sont calculés à la lecture par DevisInterneService —
 * jamais stockés (« ne jamais stocker devise et TND indépendamment »).
 */
@Entity
@Table(name = "lignes_di")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneDi extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SectionDi section;

    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 0;

    @Column(name = "profil_contractuel", length = 120)
    private String profilContractuel;

    @Column(name = "ressource_proposee", length = 120)
    private String ressourceProposee;

    @Column(name = "ressource_retenue", length = 120)
    private String ressourceRetenue;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String unite = "H-Jour";

    @Column(name = "charge_vendue_jh", precision = 10, scale = 2)
    private BigDecimal chargeVendueJh;

    @Column(name = "prix_vente_unitaire", precision = 15, scale = 2)
    private BigDecimal prixVenteUnitaire;

    @Column(name = "quantite_interne_jh", precision = 10, scale = 2)
    private BigDecimal quantiteInterneJh;

    @Column(name = "cout_unitaire_tcc", precision = 10, scale = 2)
    private BigDecimal coutUnitaireTcc;

    @Column(name = "frais_divers", precision = 15, scale = 2)
    private BigDecimal fraisDivers;

    @Column(name = "frais_generaux", precision = 15, scale = 2)
    private BigDecimal fraisGeneraux;

    @Column(name = "cout_impots", precision = 15, scale = 2)
    private BigDecimal coutImpots;

    /** Lignes taxes/provisions : coût = taux × total vendu TND (ex. 0.05 = 5 %). */
    @Column(name = "taux_pourcentage", precision = 7, scale = 4)
    private BigDecimal tauxPourcentage;
}
