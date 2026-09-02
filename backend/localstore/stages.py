"""backend.localstore.stages — one executor per component acquisition.

A stage is the body of one in-progress component state: it re-validates
whatever a previous attempt left (in-progress states are UNTRUSTED — parts
are size-checked, partials resumed, staging dirs rebuilt, extracted sets
re-validated against the manifest), then does the work and returns the DONE
Comp. Failures raise — the store maps them to the component's ERROR state;
core.Aborted propagates untouched (cancel/preempt already owns the state).

Retries live HERE, at the work site: a stage attempts the whole
fetch+assemble up to STAGE_ATTEMPTS times (per-part network retries are
transfer's, one level below). Only an exhausted last attempt is a real
ERROR — anything less is a retry, not a state.
"""
import glob
import json
import os
import shutil

from .. import core
from ..machine import Comp, DONE
from . import files
from .transfer import AssemblyError, DlProgress, PartPipe

STAGE_ATTEMPTS = int(os.environ.get("HEAP_REPORT_STAGE_ATTEMPTS", "3"))
STAGE_BACKOFF = float(os.environ.get("HEAP_REPORT_STAGE_BACKOFF", "5"))


def _attempts(rt, log, body, on_reject=None):
    """Run body() with stage-level retries. AssemblyError (size-complete but
    bad bytes) drops the rejected parts via on_reject so the next attempt
    re-downloads them; everything else is retried with the parts kept
    (resume). Returns body's result; raises the last failure."""
    for attempt in range(1, STAGE_ATTEMPTS + 1):
        if rt.abort.is_set():
            raise core.Aborted("cancelled")
        try:
            return body()
        except core.Aborted:
            raise
        except AssemblyError as e:
            if on_reject is not None:
                on_reject(e)
            log("  assembly rejected size-complete parts — dropped them so "
                "the next attempt re-downloads")
            if attempt == STAGE_ATTEMPTS:
                raise
        except Exception as e:   # noqa: BLE001 - retried / surfaced by the store
            if attempt == STAGE_ATTEMPTS:
                raise
            log(f"  attempt {attempt}/{STAGE_ATTEMPTS} failed: {e}")
        if rt.abort.wait(STAGE_BACKOFF * attempt):
            raise core.Aborted("cancelled")


def _clean_tmp(tmp, parts, transfer):
    """Success only: the fetched parts are redundant once assembled/committed
    (failures keep them for resume). Per-FILE drops only — .dl is shared by
    the concurrently running stages of the other components, so no stage may
    remove the dir itself (the store's quiescent sweep does)."""
    transfer.drop_parts(tmp, parts)


def dump_stage(store, dump_id, view, rt):
    """ACQUIRE_DUMP: hprof parts -> streaming gunzip -> daemon.hprof.
    Nothing lands at the final path directly (gzip verifies the stream CRC
    before the rename)."""
    plan, source = view.plan, view.source

    def fn(job):
        log = lambda m: store.jobs.log(job, m)
        d = store.dir_of(dump_id)
        hprof = os.path.join(d, "daemon.hprof")
        tmp = os.path.join(d, ".dl")
        os.makedirs(tmp, exist_ok=True)
        parts = list(plan.hprof_parts)
        if not parts and not os.path.exists(hprof):
            raise RuntimeError(f"download plan for {dump_id} has no daemon.hprof parts")
        store.set_resume_tokens(dump_id, "dump", parts)

        def body():
            if os.path.exists(hprof):
                return   # a previous attempt finished the assembly
            out = hprof + ".assembling"
            for stale in [out] + glob.glob(hprof + ".part*"):
                if os.path.exists(stale):
                    os.remove(stale)   # staging of a killed attempt
            prog = DlProgress(job)
            pipe = PartPipe(store.jobs, job, parts, tmp, ["gzip", "-dc"], out,
                            "gunzip", on_fed=prog.fed_bytes, on_part=prog.flush)
            try:
                store.transfer.fetch_all(parts, source, tmp, job, log, prog,
                                         abort=rt.abort)
                prog.set_stage("assemble")
                pipe.finish()   # downloads done — the feeder drains the tail
            except Exception:
                pipe.abort()
                if os.path.exists(out):
                    os.remove(out)
                raise
            finally:
                job.progress = None
            os.replace(out, hprof)
            log(f"  dump: {os.path.getsize(hprof) / 1e9:.1f} GB -> {hprof}")

        _attempts(rt, log, body,
                  on_reject=lambda e: store.transfer.drop_parts(tmp, e.parts))
        _clean_tmp(tmp, parts, store.transfer)
        return Comp(DONE)

    return fn


def data_stage(store, dump_id, view, rt):
    """ACQUIRE_DATA: the tiny data bundle — staged untar, moved into place
    only after tar succeeded, then ingested into data/analysis.db via the
    store's on_data_files hook (an interrupted attempt never leaves truncated
    CSVs that would pass has_data()). The bundle's data/meta.json is NOT
    moved over the store-owned live meta (single-writer rule); its non-state
    fields (modules, dump, …) are merged via update_meta."""
    plan, source = view.plan, view.source
    part = plan.data_bundle

    def fn(job):
        log = lambda m: store.jobs.log(job, m)
        d = store.dir_of(dump_id)
        if files.has_data(d):
            return Comp(DONE)
        if store.on_data_files is not None and files.has_data_csvs(d):
            # a previous attempt crashed between the move and the ingest:
            # the CSVs are complete (staging-validated) — ingest, don't
            # re-download
            store.on_data_files(dump_id)
            if files.has_data(d):
                return Comp(DONE)
        tmp = os.path.join(d, ".dl")
        os.makedirs(tmp, exist_ok=True)

        def body():
            prog = DlProgress(job)
            store.transfer.fetch_all([part], source, tmp, job, log, prog,
                                     abort=rt.abort)
            bp = os.path.join(tmp, part.name)
            staging = store.transfer.stage_prepare(d, "data")
            try:
                store.transfer.assemble([bp], ["tar", "-xz", "-C", staging],
                                        None, job, "data")
            except AssemblyError as e:
                e.parts = [part]   # size-complete but tar rejects it — bad bytes
                raise
            finally:
                job.progress = None
            # old bundles ship the CSV pair (ingested after the move); new
            # ones ship analysis.db directly — either is a complete bundle
            if not (files.has_data(staging) or files.has_data_csvs(staging)):
                shutil.rmtree(staging, ignore_errors=True)
                raise AssemblyError(
                    "data bundle unpacked but histogram/dominator extracts missing",
                    [part])
            os.remove(bp)
            sdata = os.path.join(staging, "data")
            data = os.path.join(d, "data")
            os.makedirs(data, exist_ok=True)
            for name in os.listdir(sdata):
                if name != "meta.json":
                    os.replace(os.path.join(sdata, name), os.path.join(data, name))
            bundle_meta = {}
            try:
                with open(os.path.join(sdata, "meta.json")) as f:
                    bundle_meta = json.load(f)
            except Exception:
                pass   # older bundles may not carry one — fields just stay local
            shutil.rmtree(staging, ignore_errors=True)
            if isinstance(bundle_meta, dict):
                fields = {k: v for k, v in bundle_meta.items()
                          if k not in ("state", "error", "machine")}
                if fields:
                    store.update_meta(dump_id, lambda m: m.update(fields))
            if store.on_data_files is not None:
                store.on_data_files(dump_id)   # ingest CSVs -> analysis.db
            log("  data: overview ready (classes/treemap/compare)")

        _attempts(rt, log, body,
                  on_reject=lambda e: store.transfer.drop_parts(tmp, e.parts))
        return Comp(DONE)

    return fn


def indexes_stage(store, dump_id, view, rt):
    """ACQUIRE_INDEXES: the prebuilt compacted index tar — staged untar,
    manifest validation, atomic commit. Trust is EARNED, never presumed: a
    raw *.index set is usable directly (local parse, or a restore of valid
    zsts); a compacted set must pass manifest validation first —
    size-mismatched files can never become valid, so they are deleted (they
    would otherwise masquerade as a complete set and break every later
    restore identically)."""
    plan, source = view.plan, view.source

    def fn(job):
        log = lambda m: store.jobs.log(job, m)
        d = store.dir_of(dump_id)
        tmp = os.path.join(d, ".dl")
        os.makedirs(tmp, exist_ok=True)
        files.drop_untrusted_raws(d, log)
        raws, zsts = files.raws_zsts(d)
        if raws:
            store.transfer.drop_parts(tmp, plan.index_parts)   # redundant
            log("  indexes: a raw set is already present — nothing to fetch")
            return Comp(DONE)
        if zsts:
            ok, why = store.transfer.indexes_complete(d, plan.manifest)
            if ok:
                store.transfer.drop_parts(tmp, plan.index_parts)   # redundant
                log(f"  indexes: already present and validated ({why})")
                return Comp(DONE, compacted=True)
            dropped = store.transfer.drop_invalid(d, plan.manifest, log)
            log(f"  index set failed validation ({why}) — re-fetching"
                + (f" ({dropped} corrupt file(s) dropped)" if dropped else ""))
        parts = list(plan.index_parts)
        if not parts:
            raise RuntimeError("index acquisition requested but the plan has "
                               "no index parts")

        def body():
            prog = DlProgress(job)
            staging = store.transfer.stage_prepare(d, "indexes")
            # the S3 lane ships the tar zstd-compressed (indexes.tar.zst) and
            # GNU tar does not auto-detect compression on stdin — pipe through
            # zstd first. A zstd failure truncates tar's input -> non-zero rc
            # -> AssemblyError, same as any other corrupt part.
            argv = ["tar", "-x", "-C", staging]
            if parts[0].name.endswith(".zst"):
                argv = ["sh", "-c", 'zstd -dc | tar -x -C "$1"', "sh", staging]
            pipe = PartPipe(store.jobs, job, parts, tmp,
                            argv, None, "untar",
                            on_fed=prog.fed_bytes, on_part=prog.flush)
            try:
                store.transfer.fetch_all(parts, source, tmp, job, log, prog,
                                         abort=rt.abort)
                prog.set_stage("assemble")
                pipe.finish()
            except Exception:
                pipe.abort()
                raise
            finally:
                job.progress = None
            why = store.transfer.commit_untar(d, staging, plan.manifest, parts)
            log(f"  indexes: unpacked and verified ({why})")

        _attempts(rt, log, body,
                  on_reject=lambda e: store.transfer.drop_parts(tmp, e.parts))
        _clean_tmp(tmp, parts, store.transfer)

        def record(m):
            m["indexes"] = "remote"
            files_ = plan.manifest.get("files") if isinstance(plan.manifest, dict) else None
            if isinstance(files_, dict) and files_:
                m["idx_manifest"] = files_   # persisted trust basis: later
                # observations validate the set against THIS, not presence

        store.update_meta(dump_id, record)
        return Comp(DONE, compacted=True)

    return fn
