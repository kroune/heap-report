import { useCallback, useEffect, useState } from 'react';

function readHash(): string | null {
  const match = /^#dump=(.*)$/.exec(window.location.hash);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return null; }
}

export function useCurrentDump(inlineDump?: string) {
  const [currentDump, setCurrentDump] = useState<string | null>(() => inlineDump || readHash());

  useEffect(() => {
    if (inlineDump) setCurrentDump(inlineDump);
  }, [inlineDump]);

  const selectDump = useCallback((id: string | null) => {
    setCurrentDump(id);
    if (id == null) window.history.replaceState(null, '', window.location.pathname + window.location.search);
    else window.history.replaceState(null, '', `#dump=${encodeURIComponent(id)}`);
  }, []);

  useEffect(() => {
    const onHashChange = () => setCurrentDump(readHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  return { currentDump, selectDump };
}
