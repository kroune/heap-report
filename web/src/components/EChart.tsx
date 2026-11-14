import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import type { EChartsOption } from 'echarts';

export function EChart({ option, onClick, height = 560 }: {
  option: EChartsOption;
  onClick?: (params: unknown) => void;
  height?: number | string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<echarts.ECharts | null>(null);
  const clickHandler = useRef(onClick);
  clickHandler.current = onClick;

  useEffect(() => {
    if (!host.current) return;
    const instance = echarts.init(host.current, 'dark', { renderer: 'canvas' });
    chart.current = instance;
    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(host.current);
    const click = (params: unknown) => clickHandler.current?.(params);
    instance.on('click', click);
    return () => {
      observer.disconnect();
      instance.off('click', click);
      instance.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    chart.current?.setOption(option, { notMerge: true, lazyUpdate: false });
  }, [option]);

  return <div ref={host} style={{ width: '100%', height, minHeight: 320 }} aria-label="Interactive heap treemap" />;
}
