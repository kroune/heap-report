"""backend/mat — the MAT-backed core.QueryEngine + local bootstrap (package).

  parsing.py   raw extract parsing helpers (pure: CSV rows, name/category)
  db.py        the per-dump analysis store (data/analysis.db): CSV landing
               ingest + markers, readers, derived reach/split tables
  reach.py     the reachability pass (inclusive retained, shared wedges,
               holder-set split copies) -> derived db tables
  payloads.py  structures -> JSON-serializable payloads (pure)
  extract.py   MAT subprocess gateway (MatRunner) + query-shape helpers
  engine.py    MatQueryEngine: caches, queries, analyze/bootstrap orchestration

Dependency direction: engine -> payloads -> parsing, engine -> extract,
engine -> db -> parsing, engine -> reach -> parsing. Nothing outside this
package reaches into the submodules; MatQueryEngine is the whole public
surface.
"""
from .engine import MatQueryEngine

__all__ = ["MatQueryEngine"]
