'use client';

import { useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(ScrollTrigger, useGSAP);

const states = [
  { kicker: '01 / Attention', title: 'Earn the first second.', copy: 'Creative earns attention before a visitor ever sees the offer.', stat: 'ATTENTION', accent: 'Signal enters' },
  { kicker: '02 / Intent', title: 'Turn attention into intent.', copy: 'Message and landing experience align around one useful next action.', stat: 'INTENT', accent: 'Signal focuses' },
  { kicker: '03 / Conversion', title: 'Make belief measurable.', copy: 'Interactions become evidence for the next creative and media decision.', stat: 'CONVERSION', accent: 'Signal converts' },
  { kicker: '04 / Learning', title: 'Let the system compound.', copy: 'Every result feeds the next decision, making the loop sharper over time.', stat: 'LEARNING', accent: 'Signal returns' },
];

function SystemGraphic({ active }: { active: number }) {
  return (
    <div className="system-graphic" aria-hidden="true">
      <div className="system-glow" />
      <div className="system-orbit orbit-1" />
      <div className="system-orbit orbit-2" />
      <div className="system-orbit orbit-3" />
      <div className="system-core"><span>{String(active + 1).padStart(2, '0')}</span><b>DG</b></div>
      {Array.from({ length: 12 }).map((_, i) => <i key={i} className={`system-node node-${i + 1} ${i % 4 === active ? 'node-active' : ''}`} />)}
      <div className="system-ring-label"><span>ACQUISITION SYSTEM</span><strong>{states[active].stat}</strong></div>
      <div className="system-scan" />
    </div>
  );
}

export function StoryScene() {
  const root = useRef<HTMLElement>(null);
  const panels = useRef<HTMLDivElement[]>([]);
  const copies = useRef<HTMLDivElement[]>([]);
  const progress = useRef<HTMLDivElement>(null);
  const systemLabel = useRef<HTMLParagraphElement>(null);

  useGSAP(() => {
    if (!root.current) return;
    const mm = gsap.matchMedia();

    mm.add('(min-width: 768px) and (prefers-reduced-motion: no-preference)', () => {
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: root.current,
          start: 'top top',
          end: '+=3600',
          pin: true,
          scrub: 1.35,
          anticipatePin: 1,
          invalidateOnRefresh: true,
        },
      });

      panels.current.forEach((panel, i) => gsap.set(panel, { autoAlpha: i === 0 ? 1 : 0, scale: i === 0 ? 1 : 0.93, y: i === 0 ? 0 : 30 }));
      copies.current.forEach((copy, i) => gsap.set(copy, { autoAlpha: i === 0 ? 1 : 0, y: i === 0 ? 0 : 42, filter: i === 0 ? 'blur(0px)' : 'blur(8px)' }));
      gsap.set(progress.current, { scaleX: 0 });
      gsap.set(systemLabel.current, { autoAlpha: 1, y: 0, filter: 'blur(0px)' });

      states.slice(1).forEach((_, index) => {
        const from = index;
        const to = index + 1;
        tl.to(progress.current, { scaleX: to / (states.length - 1), duration: 0.7, ease: 'none' })
          .to(systemLabel.current, { autoAlpha: 0, y: -4, filter: 'blur(3px)', duration: 0.22, ease: 'power2.inOut' }, '<')
          .to(panels.current[from], { autoAlpha: 0, scale: 1.07, y: -18, duration: 0.42, ease: 'power2.inOut' }, '<')
          .to(copies.current[from], { autoAlpha: 0, y: -30, filter: 'blur(8px)', duration: 0.34, ease: 'power2.inOut' }, '<')
          .to(panels.current[to], { autoAlpha: 1, scale: 1, y: 0, duration: 0.62, ease: 'power3.out' }, '-=0.1')
          .to(copies.current[to], { autoAlpha: 1, y: 0, filter: 'blur(0px)', duration: 0.58, ease: 'power3.out' }, '<0.08')
          .to(systemLabel.current, { autoAlpha: 1, y: 0, filter: 'blur(0px)', duration: 0.36, ease: 'power2.out' }, '-=0.25');
      });
    });

    mm.add('(max-width: 767px) and (prefers-reduced-motion: no-preference)', () => {
      panels.current.forEach((panel, i) => gsap.set(panel, { autoAlpha: i === 0 ? 1 : 0 }));
      copies.current.forEach((copy, i) => gsap.set(copy, { autoAlpha: i === 0 ? 1 : 0 }));
      states.forEach((_, i) => {
        if (i === 0) return;
        gsap.to(panels.current[i], { autoAlpha: 1, scrollTrigger: { trigger: panels.current[i], start: 'top 72%', end: 'top 35%', scrub: true } });
        gsap.to(copies.current[i], { autoAlpha: 1, y: 0, scrollTrigger: { trigger: copies.current[i], start: 'top 80%', end: 'top 48%', scrub: true } });
      });
    });

    return () => mm.revert();
  }, { scope: root });

  return (
    <section ref={root} id="system" className="story-scene" aria-labelledby="system-title">
      <div className="story-noise" />
      <div className="story-shell page-width">
        <div className="story-copy">
          <p ref={systemLabel} className="eyebrow mint story-system-label">One connected system</p>
          <h2 id="system-title" className="sr-only">The acquisition system</h2>
          {states.map((state, i) => (
            <div key={state.kicker} ref={el => { if (el) copies.current[i] = el; }} className="story-copy-block">
              <p className="story-kicker">{state.kicker}</p>
              <h3>{state.title}</h3>
              <p className="story-description">{state.copy}</p>
              <div className="story-mini"><span>{state.accent}</span><span>{state.stat}</span></div>
            </div>
          ))}
        </div>
        <div className="story-visual">
          {states.map((state, i) => (
            <div key={state.kicker} ref={el => { if (el) panels.current[i] = el; }} className="story-panel">
              <SystemGraphic active={i} />
              <span className="panel-index">{state.kicker}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="story-progress"><span>Scroll to move the system</span><i ref={progress} /></div>
    </section>
  );
}
