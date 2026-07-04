import { NiveauRisque } from './governance.model';

export interface PartiePrenante {
  id: number;
  projectId: number;
  projectCode: string;
  nom: string;
  fonction?: string;
  email?: string;
  telephone?: string;
  influence: NiveauRisque;
  interet: NiveauRisque;
}
