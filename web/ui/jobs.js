/* ui/jobs.js — job status component (owner: jobs).
 *
 * mountJobs(container): self-contained. Subscribes via pollJobs() from
 * data/dumprepo.js and renders every job kind uniformly: kind badge, dump id,
 * detail, state, progress bar (progress.done/total bytes via fmtB), log tail
 * (last ~10 lines; full log in a <details>), error in red (.job-err).
 *
 * The DOM is rebuilt at most once per poll tick: each render builds a fresh
 * tree off-DOM and swaps it in with a single replaceChildren(). Expanded
 * full-log <details> survive rebuilds (their open state is kept in a per-mount
 * Set, not in the DOM).
 *
 * All styling lives in the shell css (app.css, owner: shell) — classes used:
 *   jobs, jobs-empty, job-card, job-head, job-kind, job-kind-<kind>,
 *   job-title, job-state, job-state-<state>, job-prog, job-prog-track,
 *   job-prog-bar, job-prog-label, job-log, job-log-full, job-err
 * The only inline style is the progress bar width (a dynamic layout value).
 */
import {fmtB} from "../data/http.js";
import {pollJobs} from "../data/dumprepo.js";

const LOG_TAIL = 10;

function jobCard(job, openLogs, onToggleLog) {
  const card = document.createElement("div");
  card.className = "job-card";

  const head = document.createElement("div");
  head.className = "job-head";

  const kind = document.createElement("span");
  kind.className = "job-kind job-kind-" + job.kind;
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
    label.textContent = `${fmtB(p.done)} / ${fmtB(p.total)} (${pct.toFixed(0)}%)`;
    wrap.appendChild(label);
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
    const tail = document.createElement("pre");
    tail.className = "job-log";
    tail.textContent = log.slice(-LOG_TAIL).join("\n");
    card.appendChild(tail);
    if (log.length > LOG_TAIL) {
      const det = document.createElement("details");
      det.className = "job-log-full";
      if (openLogs.has(job.id)) {
        det.open = true;
      }
      det.addEventListener("toggle", () => onToggleLog(job.id, det.open));
      const sum = document.createElement("summary");
      sum.textContent = `full log (${log.length} lines)`;
      det.appendChild(sum);
      const full = document.createElement("pre");
      full.className = "job-log";
      full.textContent = log.join("\n");
      det.appendChild(full);
      card.appendChild(det);
    }
  }
  return card;
}

export function mountJobs(container) {
  const openLogs = new Set();   // job ids whose full-log <details> is expanded

  function render(jobs) {
    const root = document.createElement("div");
    root.className = "jobs";
    if (!jobs || !jobs.length) {
      const empty = document.createElement("div");
      empty.className = "jobs-empty";
      empty.textContent = "no jobs";
      root.appendChild(empty);
    } else {
      for (const job of jobs) {
        root.appendChild(jobCard(job, openLogs, (id, open) => {
          if (open) {
            openLogs.add(id);
          } else {
            openLogs.delete(id);
          }
        }));
      }
    }
    container.replaceChildren(root);   // the single DOM mutation per poll tick
  }

  return pollJobs(render);
}
