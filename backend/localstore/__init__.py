"""backend.localstore — the writable LocalDumpStore package.

  files.py     on-disk artifact inspection + drop helpers (no state)
  compact.py   zstd compaction of MAT indexes (mtime convention; CI uses it)
  transfer.py  download + assembly machinery (parts, pipes, staged untar)
  stages.py    one executor per component acquisition (dump/data/indexes)
  store.py     FsDumpStore: meta, tags, machine persistence, reconcile loop

On-disk contract: backend/LAYOUT.md. State machine: backend/machine/.
"""
from .files import (MARKER, MARKER_TEXT, UNTAR, drop_index_set,
                    drop_untrusted_raws, has_compacted, has_data,
                    has_data_csvs, parse_debris, raws_zsts)
from .compact import compact_dir
from .transfer import (ASSEMBLE_TIMEOUT, AssemblyError, DL_CONN, DL_RETRIES,
                       DlProgress, PartPipe, Transfer)
from .store import FsDumpStore, COMPACT_HOLD_MAX

__all__ = [
    "MARKER", "MARKER_TEXT", "UNTAR", "drop_index_set", "drop_untrusted_raws",
    "has_compacted", "has_data", "has_data_csvs", "parse_debris", "raws_zsts",
    "compact_dir",
    "ASSEMBLE_TIMEOUT", "AssemblyError", "DL_CONN", "DL_RETRIES",
    "DlProgress", "PartPipe", "Transfer", "FsDumpStore", "COMPACT_HOLD_MAX",
]
