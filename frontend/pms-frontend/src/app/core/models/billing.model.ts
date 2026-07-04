export type JalonStatut = 'PREVU' | 'FACTURE' | 'PAYE';

export interface JalonFacturation {
  id: number;
  projectId: number;
  projectCode: string;
  label: string;
  pourcentage: number;
  montant: number;
  datePrevue?: string;
  dateFacture?: string;
  statut: JalonStatut;
}

export interface Paiement {
  id: number;
  jalonId: number;
  montantRecu: number;
  datePaiement: string;
}

export interface Avenant {
  id: number;
  projectId: number;
  projectCode: string;
  numero: string;
  objet: string;
  montant: number;
  workloadDays?: number;
  dateAvenant: string;
}
