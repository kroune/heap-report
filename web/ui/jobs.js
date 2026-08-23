/* ui/jobs.js — job status component (owner: jobs).
 *
 * mountJobs(container): self-contained. Subscribes via pollJobs() from
 * data/dumprepo.js and renders every job kind uniformly: kind badge, dump id,
 * detail, state, progress bar (progress.done/total bytes via fmtB; download
 * jobs also get stage, speed/eta via fmtDur, unpacked-bytes and a per-part
 * line), log tail (last ~10 lines; full log in a <details>), error in red
 * (.job-err).
 *
 * The DOM is rebuilt at most once per poll tick: each render builds a fresh
 * tree off-DOM and swaps it in with a single replaceChildren(). Rebuilds are
 * invisible to the user:
 *  - expanded full-log <details> survive (open state in a per-mount Set),
 *  - scrollTop of every log <pre> survives (saved before the swap, restored
 *    after the new tree is ATTACHED — scrollTop does not stick on detached
 *    elements, keyed by job id + tail/full),
 *  - jobs already done/failed on the FIRST poll are pre-dismissed: they
 *    finished before this page loaded, re-showing them on every reload is
 *    noise (jobs that finish while the page is open still appear),
 *  - each card has a close button; dismissed job ids are kept in a per-mount
 *    Set and skipped (UI-only — the job itself keeps running).
 *
 * All styling lives in the shell css (app.css, owner: shell) — classes used:
 *   jobs-empty, job-card, job-done, job-failed, job-head, job-kind, job-title,
 *   job-state, job-state-<state>, job-close, job-prog, job-prog-track,
 *   job-prog-bar, job-prog-label, job-log, job-log-full, job-err
 * The only inline style is the progress bar width (a dynamic layout value).
 */
import {fmtB, fmtDur} from "../data/http.js";
import {pollJobs} from "../data/dumprepo.js";

const LOG_TAIL = 10;

function logPre(jobId, which, lines) {
  const pre = document.createElement("pre");
  pre.className = "job-log";
  pre.dataset.jlog = jobId + ":" + which;   // scroll-restoration key
  pre.textContent = lines;
  return pre;
}

function jobCard(job, ui) {
  const card = document.createElement("div");
  card.className = "job-card job-" + job.state;

  const head = document.createElement("div");
  head.className = "job-head";

  const kind = document.createElement("span");
  kind.className = "job-kind";
  kind.textContent = job.kind;
  head.appendChild(kind);

  const title = document.createElement("span");
  title.className = "job-title";
  title.textContent = [job.dump, job.detail].filter(Boolean).join(" — ")
    || `job #${job.id}`;
  head.appendChild(title);

  const state = document.createElement("span");
  state.className = "job-state job-state-" + job.state;
  state.textContent = job.state;
  head.appendChild(state);
  card.appendChild(head);

  const close = document.createElement("button");
  close.className = "job-close";
  close.textContent = "×";
  close.title = "dismiss (the job keeps running)";
  close.addEventListener("click", () => ui.dismiss(job.id));
  card.appendChild(close);

  const p = job.progress;
  if (p && p.total > 0) {
    const pct = Math.min(100, 100 * p.done / p.total);
    const wrap = document.createElement("div");
    wrap.className = "job-prog";
    const track = document.createElement("div");
    track.className = "job-prog-track";
    const bar = document.createElement("div");
    bar.className = "job-prog-bar";
    bar.style.width = pct.toFixed(1) + "%";   // dynamic layout value
    track.appendChild(bar);
    wrap.appendChild(track);
    const label = document.createElement("span");
    label.className = "job-prog-label";
    let text = p.stage === "assemble"
      ? `assembling — ${fmtB(p.done)} / ${fmtB(p.total)} unpacked (${pct.toFixed(0)}%)`
      : `${fmtB(p.done)} / ${fmtB(p.total)} (${pct.toFixed(0)}%)`;
    if (p.speed) text += ` · ${fmtB(Math.round(p.speed))}/s`;
    if (p.eta != null) text += ` · eta ${fmtDur(p.eta)}`;
    if (p.stage === "download" && p.asm && p.asm.total > 0)
      text += ` · ${fmtB(p.asm.done)} unpacked`;
    label.textContent = text;
    wrap.appendChild(label);
    if (Array.isArray(p.parts) && p.parts.length) {
      const isDone = x => x.done || (x.size != null && x.have >= x.size);
      const done = p.parts.filter(isDone).length;
      const act = p.parts.filter(x => !isDone(x) && x.have > 0)
        .map(x => `${x.n} ${x.size ? Math.floor(100 * x.have / x.size) + "%" : fmtB(x.have)}`);
      const sub = document.createElement("span");
      sub.className = "job-prog-label";
      sub.textContent = `parts ${done}/${p.parts.length}` +
        (act.length ? ` · active: ${act.join(", ")}` : "");
      wrap.appendChild(sub);
    }
    card.appendChild(wrap);
  }

  if (job.error) {
    const err = document.createElement("div");
    err.className = "job-err";
    err.textContent = job.error;
    card.appendChild(err);
  }

  const log = Array.isArray(job.log) ? job.log : [];
  if (log.length) {
    card.appendChild(logPre(job.id, "tail", log.slice(-LOG_TAIL).join("\n")));
    if (log.length > LOG_TAIL) {
      const det = document.createElement("details");
      det.className = "job-log-full";
      if (ui.openLogs.has(job.id)) {
        det.open = true;
      }
      det.addEventListener("toggle", () => {
        if (det.open) {
          ui.openLogs.add(job.id);
        } else {
          ui.openLogs.delete(job.id);
        }
      });
      const sum = document.createElement("summary");
      sum.textContent = `full log (${log.length} lines)`;
      det.appendChild(sum);
      det.appendChild(logPre(job.id, "full", log.join("\n")));
      card.appendChild(det);
    }
  }
  return card;
}

export function mountJobs(container) {
  let lastJobs = [];
  let firstPoll = true;
  const ui = {
    openLogs: new Set(),   // job ids whose full-log <details> is expanded
    dismissed: new Set(),  // job ids hidden via the close button
    scrolls: new Map(),    // "id:tail"|"id:full" -> scrollTop, survives rebuilds
    dismiss(id) { this.dismissed.add(id); render(lastJobs); },
  };

  function render(jobs) {
    lastJobs = jobs;
    if (firstPoll) {
      firstPoll = false;
      // jobs that already finished before this page loaded: pre-dismiss —
      // they are history, not news (running/queued ones still show)
      for (const j of jobs || []) {
        if (j.state === "done" || j.state === "failed") ui.dismissed.add(j.id);
      }
    }
    // save log scroll positions before the swap
    for (const pre of container.querySelectorAll(".job-log[data-jlog]")) {
      if (pre.scrollTop) ui.scrolls.set(pre.dataset.jlog, pre.scrollTop);
      else ui.scrolls.delete(pre.dataset.jlog);
    }
    const visible = (jobs || []).filter(j => !ui.dismissed.has(j.id));
    const root = document.createElement("div");
    if (!visible.length) {
      const empty = document.createElement("div");
      empty.className = "jobs-empty";
      empty.textContent = "no jobs";
      root.appendChild(empty);
    } else {
      for (const job of visible) root.appendChild(jobCard(job, ui));
    }
    container.replaceChildren(root);   // the single DOM mutation per poll tick
    // restore log scroll positions AFTER attach (scrollTop doesn't stick on
    // detached elements — setting it pre-attach clamps to 0)
    for (const pre of container.querySelectorAll(".job-log[data-jlog]")) {
      const st = ui.scrolls.get(pre.dataset.jlog);
      if (st) pre.scrollTop = st;
    }
  }

  return pollJobs(render);
}
