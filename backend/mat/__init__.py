"""backend/mat — the MAT-backed core.QueryEngine + local bootstrap (package).

  parsing.py   raw extract files (CSV + sidecars) -> python structures
  payloads.py  structures -> JSON-serializable payloads (pure)
  extract.py   MAT subprocess gateway (MatRunner) + query-shape helpers
  engine.py    MatQueryEngine: caches, queries, analyze/bootstrap orchestration

Dependency direction: engine -> payloads -> parsing, engine -> extract.
Nothing outside this package reaches into the submodules; MatQueryEngine is
the whole public surface.
"""
from .engine import MatQueryEngine

__all__ = ["MatQueryEngine"]
