/// <reference types="vite/client" />

import type { SnapshotPayload } from './data/types';

declare global {
  interface Window {
    __INLINE__?: SnapshotPayload;
  }
}

export {};
