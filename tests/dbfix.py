"""Test fixture helper: plant an ingested data bundle (data/analysis.db).

Fixtures used to drop the CSV extracts into data/ directly; extracts now land
as CSV and are ingested into analysis.db (backend/mat/db.py), so fixtures
build the db through the same ingest path the landing hook uses.
"""
import os

from backend.mat import db as dbmod


def make_data_db(d, hist, dom):
    """Write hist/dom CSV text into <d>/data/ and ingest them (deletes the
    CSVs, leaves analysis.db)."""
    data = os.path.join(d, "data")
    os.makedirs(data, exist_ok=True)
    with open(os.path.join(data, "histogram.csv"), "w") as f:
        f.write(hist)
    with open(os.path.join(data, "dominator_by_class.csv"), "w") as f:
        f.write(dom)
    dbmod.ingest_data_dir(data)


def wire_ingest_hook(store):
    """The engine's on_data_files hook without the engine (download-pipeline
    tests never run MAT): ingest landed data-bundle CSVs into analysis.db."""
    store.on_data_files = lambda dump_id: dbmod.ingest_data_dir(
        os.path.join(store.dir_of(dump_id), "data"))
