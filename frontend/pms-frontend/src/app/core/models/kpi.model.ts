export interface KpiResponse {
  snapshotId?: number;
  projectId: number;
  projectCode: string;
  snapshotDate?: string;
  budgetPlanifie: number;
  budgetConsome: number;
  eac: number;
  marge: number;
  tauxConsommation: number;
  // ── Indicateurs EVM (F-AFF-13 §5) ──
  evPct?: number;            // Earned Value 0-100 (saisie CdP au snapshot)
  deliveryPct?: number;      // livrés / planifiés × 100
  consommeJh?: number;       // Σ imputations validées (JH)
  rafJh?: number;            // reste à faire (JH)
  deriveJh?: number;         // workload vendu − consommé − RAF
  caProduction?: number;     // budget TND × EV %
  totalFacture?: number;     // Σ jalons facturés/réglés (TND)
  fae?: number;              // CA production − total facturé
  margeActuelle?: number;    // CA production − coût actuel (TND)
  margeActuellePct?: number; // marge actuelle / CA production
  margeVenduePct?: number;   // baseline (DI ou fiche identification)
  dateFinEstimee?: string;
  faitsMarquants?: string;
  warnings?: string[];
}

/** Saisie mensuelle du CdP lors de la revue projet. */
export interface SnapshotRequest {
  evPct?: number;
  dateFinEstimee?: string;
  faitsMarquants?: string;
}
