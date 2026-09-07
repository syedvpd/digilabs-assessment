'use client';

import { useEffect } from 'react';
import Lenis from 'lenis';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

gsap.registerPlugin(ScrollTrigger);

export function SmoothScroll() {
  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) return;

    const lenis = new Lenis({
      duration: 1.35,
      lerp: 0.075,
      smoothWheel: true,
      wheelMultiplier: 0.92,
      touchMultiplier: 1.05,
      syncTouch: true,
      autoRaf: false,
    });

    const onScroll = () => ScrollTrigger.update();
    lenis.on('scroll', onScroll);

    const raf = (time: number) => lenis.raf(time * 1000);
    gsap.ticker.add(raf);
    gsap.ticker.lagSmoothing(0);

    const onAnchor = (event: MouseEvent) => {
      const anchor = (event.target as HTMLElement).closest<HTMLAnchorElement>('a[href^="#"]');
      if (!anchor) return;
      const href = anchor.getAttribute('href');
      if (!href || href === '#') return;
      const target = document.querySelector<HTMLElement>(href);
      if (!target) return;
      event.preventDefault();
      lenis.scrollTo(target, { offset: -28, duration: 1.4 });
    };

    document.addEventListener('click', onAnchor);
    requestAnimationFrame(() => ScrollTrigger.refresh());

    return () => {
      document.removeEventListener('click', onAnchor);
      lenis.off('scroll', onScroll);
      gsap.ticker.remove(raf);
      lenis.destroy();
      ScrollTrigger.refresh();
    };
  }, []);

  return null;
}
