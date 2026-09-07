'use client';

import { useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(ScrollTrigger, useGSAP);

export function PerformanceVisual() {
  const ref = useRef<HTMLDivElement>(null);
  useGSAP(() => {
    if (!ref.current || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    gsap.to('.pulse-dot', { scale: 1.7, opacity: 0.2, repeat: -1, yoyo: true, duration: 1.5, ease: 'sine.inOut', stagger: 0.2 });
    gsap.to('.chart-line', { strokeDashoffset: 0, duration: 2.4, ease: 'power3.out', scrollTrigger: { trigger: ref.current, start: 'top 75%', once: true } });
  }, { scope: ref });


  return (
    <div ref={ref} className="performance-visual">
      <div className="pv-header"><span>ACQUISITION MODEL</span><span className="pv-live"><i /> LIVE</span></div>
      <div className="pv-main">
        <div className="pv-number"><span>qualified demand</span><strong>+37.8%</strong><small>illustrative model</small></div>
        <svg viewBox="0 0 720 310" className="pv-chart" role="img" aria-label="Illustrative rising performance chart">
          <defs><linearGradient id="area" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="rgba(151,246,214,.25)" /><stop offset="1" stopColor="rgba(151,246,214,0)" /></linearGradient></defs>
          <path d="M20 270 C95 240 115 255 170 220 S260 180 305 195 S390 140 435 155 S510 90 565 112 S650 60 700 32 L700 300 L20 300Z" fill="url(#area)" />
          <path className="chart-line" pathLength="1" d="M20 270 C95 240 115 255 170 220 S260 180 305 195 S390 140 435 155 S510 90 565 112 S650 60 700 32" fill="none" stroke="#97f6d6" strokeWidth="4" strokeLinecap="round" />
          {[170,305,435,565,700].map((x, i) => <circle key={x} className="pulse-dot" cx={x} cy={[220,195,155,112,32][i]} r="5" fill="#97f6d6" />)}
        </svg>
      </div>
      <div className="pv-footer"><span>attention</span><span>intent</span><span>conversion</span><span>learning</span></div>
    </div>
  );
}
