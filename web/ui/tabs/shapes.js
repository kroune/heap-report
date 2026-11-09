/* Shapes tab — one exact structural-collapse graph for the complete heap.
   The renderer is shared with web/viz/shapes, but this tab (not the class-viz
   popup) owns dump changes, analysis jobs and interactive refetches. */
import {esc, fmtB, fmtN, catOf} from '../../data/http.js';
import {pollJobs} from '../../data/dumprepo.js';
import * as ddr from '../../data/dumpdatarepo.js';
import {getDump, onDumpChange} from '../../app/state.js';
import {catColor, shortClass, openViz} from '../../viz/common.js';
import * as shapes from '../../viz/shapes/index.js';
import {dumpNotReady} from './classes.js';

export function mount(container, repo, opts = {}) {
  const R = repo || ddr;
  const st = {dump: null, seq: 0, params: {}};
  const card = document.createElement('div');
  card.className = 'card shapes-card';
  const body = document.createElement('div');
  body.className = 'shapes-body';
  card.appendChild(body);
  const msg = document.createElement('div');
  msg.className = 'tabmsg';
  container.append(card, msg);

  let rendererCleanup = null;
  let stopPoll = null;

  function disposeRenderer() {
    const cleanup = rendererCleanup;
    rendererCleanup = null;
    if (cleanup) cleanup();
    else body.textContent = '';
  }

  function showMsg(text, isErr = false) {
    disposeRenderer();
    msg.textContent = text;
    msg.className = 'tabmsg on' + (isErr ? ' err' : '');
    card.classList.add('hidden');
  }

  function stopWatching() {
    if (stopPoll) stopPoll();
    stopPoll = null;
  }

  function analyze(onStatus, samples, analysisOptions = {}) {
    const dumpId = st.dump;
    const nextParams = {...st.params, ...analysisOptions};
    Promise.resolve(R.shapesAnalyze(dumpId, analysisOptions)).then(r => {
      if (st.dump !== dumpId) return;
      if (!r.ok) {
        onStatus(`analyze failed: ${r.error || 'unknown error'}`, true);
        return;
      }
      const job = r.data;
      const reopen = () => {
        stopWatching();
        if (st.dump !== dumpId) return;
        st.params = nextParams;
        R.invalidate(dumpId);
        load();
      };
      if (!job || job.id == null || job.state === 'done') {
        reopen();
        return;
      }
      if (job.state === 'failed' || job.state === 'cancelled') {
        onStatus(`analysis ${job.state}: ${job.error || 'see the jobs panel'}`, true);
        return;
      }
      stopWatching();
      onStatus(`analysis ${job.state} — waiting for the job to finish…`);
      stopPoll = pollJobs(jobs => {
        const current = (jobs || []).find(x => x.id === job.id);
        if (!current) return;
        if (current.state === 'done') reopen();
        else if (current.state === 'failed' || current.state === 'cancelled') {
          stopWatching();
          onStatus(`analysis ${current.state}: ${current.error || 'see the jobs panel'}`, true);
        } else onStatus(`analysis ${current.state}…`);
      });
    });
  }

  async function load(extraParams = {}) {
    st.params = {...st.params, ...extraParams};
    const my = ++st.seq;
    const id = getDump();
    st.dump = id;
    if (!id) {
      showMsg('no dump selected — pick a dump in the selector above.');
      return;
    }
    showMsg('loading heap-wide shapes…');
    const notReady = opts.inline ? null : await dumpNotReady(id);
    if (my !== st.seq) return;
    if (notReady) {
      showMsg(notReady.msg, notReady.err);
      return;
    }

    let vm;
    try {
      vm = await shapes.prepare(R, id, st.params);
    } catch (e) {
      if (my === st.seq) showMsg((e && e.message) || String(e), true);
      return;
    }
    if (my !== st.seq) return;
    msg.className = 'tabmsg';
    card.classList.remove('hidden');
    disposeRenderer();
    try {
      rendererCleanup = shapes.render(body, vm, {
        esc, fmtB, fmtN, catColor, catOf, shortClass,
        onOpenViz: openViz,
        refetch: extra => load(extra),
        analyze: opts.inline ? null : analyze,
      }) || null;
    } catch (e) {
      showMsg((e && e.message) || String(e), true);
    }
  }

  onDumpChange(() => {
    stopWatching();
    load();
  });
  load();
}
