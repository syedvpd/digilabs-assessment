'use client';

import { useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { ArrowDown } from 'lucide-react';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(ScrollTrigger, useGSAP);

export function Hero() {
  const root = useRef<HTMLElement>(null);
  const media = useRef<HTMLDivElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const atmosphere = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    if (!root.current || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const mm = gsap.matchMedia();
    mm.add('(min-width: 768px)', () => {
      const intro = gsap.timeline({ defaults: { ease: 'power3.out' } });
      intro
        .fromTo('.hero-eyebrow', { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.8 })
        .fromTo(title.current, { opacity: 0, y: 28, filter: 'blur(8px)' }, { opacity: 1, y: 0, filter: 'blur(0px)', duration: 1.0, clearProps: 'filter' }, '-=0.4')
        .fromTo('.hero-bottom', { opacity: 0, y: 18 }, { opacity: 1, y: 0, duration: 0.8 }, '-=0.5')
        .fromTo('.hero-foot', { opacity: 0 }, { opacity: 1, duration: 0.7 }, '-=0.4');

      gsap.to(media.current, {
        yPercent: 7,
        scale: 1.03,
        immediateRender: false,
        scrollTrigger: { trigger: root.current, start: 'top top', end: 'bottom top', scrub: 1.25 }
      });
      gsap.to(title.current, {
        yPercent: -12,
        immediateRender: false,
        scrollTrigger: { trigger: root.current, start: 'top top', end: 'bottom top', scrub: 1.1 }
      });
      gsap.to(atmosphere.current, {
        yPercent: -10,
        xPercent: 3,
        scale: 1.05,
        immediateRender: false,
        scrollTrigger: { trigger: root.current, start: 'top top', end: 'bottom top', scrub: 1.5 }
      });
      gsap.to('.hero-foot', {
        opacity: 0,
        y: 14,
        immediateRender: false,
        scrollTrigger: { trigger: root.current, start: 'top top', end: '45% top', scrub: true }
      });
    });
    return () => mm.revert();
  }, { scope: root });

  return (
    <section ref={root} className="hero" aria-labelledby="hero-title">
      <div ref={atmosphere} className="hero-atmosphere" />
      <div className="hero-grid" />
      <div className="hero-orb orb-a" />
      <div className="hero-orb orb-b" />
      <div ref={media} className="hero-media">
        <video autoPlay muted loop playsInline preload="metadata" poster="/hero-poster.svg" aria-hidden="true">
          <source src="/hero-loop.mp4" type="video/mp4" />
        </video>
        <div className="hero-media-shade" />
        <div className="hero-media-ui"><span>signal / 01</span><span>creative → demand</span></div>
      </div>
      <div className="hero-content page-width">
        <p className="eyebrow mint hero-eyebrow">Performance marketing · Tier 1 markets</p>
        <h1 ref={title} id="hero-title">Demand,<br /><span>designed to</span><br />compound.</h1>
        <div className="hero-bottom">
          <p>Paid acquisition without the agency clutter. One connected system for message, media, experience and learning.</p>
          <a href="#system" className="scroll-cue"><span>Explore the system</span><i><ArrowDown size={17} /></i></a>
        </div>
      </div>
      <div className="hero-foot page-width"><span>US / UK / EU</span><span>Creative + media</span><span>Full funnel</span><span>Weekly learning</span></div>
    </section>
  );
}
