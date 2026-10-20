export type RecognitionStatus =
  | 'NONE'
  | 'PENDING'
  | 'PROCESSING'
  | 'NEEDS_REVIEW'
  | 'EXTRACTING'
  | 'READY'
  | 'FAILED';

export type PageSource = 'TEXT' | 'IMAGE';
export type PageStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';

export interface PaperPage {
  pageNo: number;
  source: PageSource;
  status: PageStatus;
  text: string | null;
  hasFigure: boolean;
  notes: string | null;
  error: string | null;
  imageUrl: string;
}

export interface PaperRecognition {
  paperId: number;
  recognitionStatus: RecognitionStatus;
  recognitionError: string | null;
  pageCount: number;
  pages: PaperPage[];
  questionNos: number[];
  warnings: string[];
  assets: PaperAsset[];
}

export interface PaperAsset {
  key: string;
  pageNo: number | null;
  kind: string;
  role: string | null;
  anchorText: string | null;
  optionCount: number | null;
  layout: string | null;
  width: number | null;
  height: number | null;
}

export interface StructureNode {
  type: string;
  title: string | null;
  text: string | null;
  questionType: string | null;
  fullScore: number | null;
  pageFrom: number | null;
  pageTo: number | null;
  assetIds: string[];
  children: StructureNode[];
}

export interface StructurePreview {
  paperId: number;
  subjectId: string;
  totalScore: number;
  questionCount: number;
  warnings: string[];
  nodes: StructureNode[];
}

export interface PaperUploadResult {
  paperId: number;
  pageCount: number;
  recognitionStatus: RecognitionStatus;
  duplicate: boolean;
  message: string;
}
