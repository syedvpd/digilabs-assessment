'use client';

import { ReactNode, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(ScrollTrigger, useGSAP);

export function Reveal({ children, className = '', delay = 0, y = 28 }: { children: ReactNode; className?: string; delay?: number; y?: number }) {
  const ref = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    if (!ref.current) return;
    gsap.fromTo(ref.current, { autoAlpha: 0, y }, {
      autoAlpha: 1,
      y: 0,
      duration: 0.9,
      delay,
      ease: 'power3.out',
      scrollTrigger: { trigger: ref.current, start: 'top 86%', once: true },
    });
  }, { scope: ref });

  return <div ref={ref} className={className}>{children}</div>;
}
