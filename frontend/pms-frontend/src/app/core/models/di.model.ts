export type SectionDi = 'HONORAIRES' | 'FRAIS' | 'AUTRES_FRAIS';

export const SECTION_DI_LABELS: Record<SectionDi, string> = {
  HONORAIRES: 'Honoraires',
  FRAIS: 'Frais',
  AUTRES_FRAIS: 'Autres frais (taxes, provisions)'
};

export interface LigneDiRequest {
  section: SectionDi;
  ordre?: number;
  profilContractuel?: string;
  ressourceProposee?: string;
  ressourceRetenue?: string;
  unite?: string;
  chargeVendueJh?: number;
  prixVenteUnitaire?: number;
  quantiteInterneJh?: number;
  coutUnitaireTcc?: number;
  fraisDivers?: number;
  fraisGeneraux?: number;
  coutImpots?: number;
  tauxPourcentage?: number;
}

export interface LigneDiResponse extends LigneDiRequest {
  id: number;
  // calculés côté serveur (jamais stockés)
  montantDevise?: number;
  montantTnd?: number;
  prixRevient?: number;
  coutFinal?: number;
  margeNette?: number;
  margePct?: number;
}

export interface DevisInterneResponse {
  projectId: number;
  projectCode: string;
  currency: string;
  exchangeRateToTnd: number;
  lignes: LigneDiResponse[];
  totalVenduDevise: number;
  totalVenduTnd: number;
  totalChargeVendueJh: number;
  totalQuantiteInterneJh: number;
  totalCoutFinal: number;
  margeNette: number;
  margePct?: number;
}
