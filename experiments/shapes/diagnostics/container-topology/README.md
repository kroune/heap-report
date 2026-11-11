# Container topology diagnostics

`audit_immutable_residual.py` runs tuple-based counterfactuals on a shapes API
JSON payload. It measures how selected map-entry and entry-array
normalizations change the structural partition without reading or modifying a
heap dump or production artifact.

```sh
python3 diagnostics/container-topology/audit_immutable_residual.py payload.json [report.json]
```

SCCs remain distinct opaque atoms in this analysis. The normalization modes
are sensitivity controls, not proposals to silently discard raw reference
statistics or apply broader production map semantics.
