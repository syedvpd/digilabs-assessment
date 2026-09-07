'use client';

import { useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { ArrowUpRight } from 'lucide-react';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(ScrollTrigger, useGSAP);

const cards = [
  { number: '01', title: 'Creative', subtitle: 'Find the message that earns attention.', stat: '4.8×', label: 'illustrative hook lift', tone: 'cream' },
  { number: '02', title: 'Experience', subtitle: 'Turn curiosity into a confident next step.', stat: '−31%', label: 'illustrative friction', tone: 'mint' },
  { number: '03', title: 'Media', subtitle: 'Put budget behind signals that keep improving.', stat: '+42%', label: 'illustrative efficiency', tone: 'blue' },
  { number: '04', title: 'Learning', subtitle: 'Feed every result back into the next decision.', stat: '7d', label: 'learning cadence', tone: 'dark' },
];

export function CampaignCarousel() {
  const section = useRef<HTMLElement>(null);
  const track = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    if (!section.current || !track.current) return;
    const mm = gsap.matchMedia();
    mm.add('(min-width: 1000px) and (prefers-reduced-motion: no-preference)', () => {
      const getScrollAmount = () => {
        if (!track.current) return 0;
        const trackWidth = track.current.scrollWidth;
        const windowWidth = window.innerWidth;
        const leftPadding = Math.max(24, (windowWidth - 1240) / 2);
        return Math.max(0, trackWidth - windowWidth + leftPadding + 48);
      };

      gsap.to(track.current, {
        x: () => -getScrollAmount(),
        ease: 'none',
        scrollTrigger: {
          trigger: section.current,
          start: 'top top',
          end: () => `+=${Math.max(2400, getScrollAmount() * 1.7)}`,
          pin: true,
          scrub: 1.2,
          anticipatePin: 1,
          invalidateOnRefresh: true,
        },
      });
    });
    return () => mm.revert();
  }, { scope: section });

  return (
    <section ref={section} className="carousel-section" id="work" aria-labelledby="stack-title">
      <div className="carousel-shell">
        <div className="carousel-intro page-width">
          <div>
            <p className="eyebrow">The acquisition stack</p>
            <h2 id="stack-title">One story.<br /><span>Many signals.</span></h2>
          </div>
          <p>Scroll through the layers. Each has one job—and each result becomes input for the next decision.</p>
        </div>
        <div className="carousel-viewport">
          <div ref={track} className="carousel-track">
            {cards.map(card => (
              <article key={card.number} className={`campaign-card ${card.tone}`} tabIndex={0}>
                <div className="card-top"><span>{card.number}</span><ArrowUpRight size={17} /></div>
                <div className="campaign-art" aria-hidden="true">
                  <div className="art-ring ring-one" />
                  <div className="art-ring ring-two" />
                  <div className="art-dot" />
                  <div className="art-wave" />
                  <div className="art-axis" />
                </div>
                <div className="card-bottom">
                  <p className="eyebrow">{card.title}</p>
                  <h3>{card.subtitle}</h3>
                  <div className="card-stat">
                    <strong>{card.stat}</strong>
                    <span>{card.label}</span>
                  </div>
                </div>
              </article>
            ))}
          </div>
        </div>
        <div className="carousel-mobile-hint page-width">
          <span>Swipe the layers</span><span aria-hidden="true">→</span>
        </div>
      </div>
    </section>
  );
}
