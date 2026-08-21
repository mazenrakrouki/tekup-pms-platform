export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;   // current page, 0-indexed
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
}
