'use client';

import { ArrowUpRight, CheckCircle2, ChevronRight, Play } from 'lucide-react';
import { SmoothScroll } from './SmoothScroll';
import { Hero } from './Hero';
import { Navigation } from './Navigation';
import { StoryScene } from './StoryScene';
import { CampaignCarousel } from './Carousel';
import { PerformanceVisual } from './PerformanceVisual';
import { LeadForm } from './LeadForm';
import { Reveal } from './Reveal';

const principles = [
  ['Creative', 'Earn attention', 'A sharper message gives every media dollar somewhere useful to land.'],
  ['Experience', 'Build belief', 'The page turns curiosity into a confident next step.'],
  ['Measurement', 'Create evidence', 'Every signal becomes fuel for the next decision.'],
];

const metrics = [
  ['3.4×', 'blended ROAS', 'illustrative'],
  ['−28%', 'cost / lead', 'illustrative'],
  ['61%', 'qualified pipeline', 'illustrative'],
];

export default function Page() {
  return (
    <>
      <SmoothScroll />
      <Navigation />
      <main className="site-main" id="top">
        <Hero />

        <section className="manifesto section-light" aria-labelledby="thesis-title">
          <div className="page-width manifesto-grid">
            <Reveal><p className="eyebrow">The thesis</p></Reveal>
            <Reveal delay={0.08}><h2 id="thesis-title">Good growth should feel <span>obvious.</span></h2></Reveal>
            <Reveal delay={0.12}><p className="manifesto-copy">The complexity stays underneath. What the customer sees is clarity: one proposition, one experience, one reason to keep moving.</p></Reveal>
          </div>
        </section>

        <StoryScene />

        <section className="performance section-dark" id="proof" aria-labelledby="proof-title">
          <div className="page-width">
            <div className="section-heading split-heading">
              <div><Reveal><p className="eyebrow mint">Proof without the theatre</p></Reveal><Reveal delay={0.06}><h2 id="proof-title">Make the invisible <span>measurable.</span></h2></Reveal></div>
              <Reveal delay={0.1}><p className="section-note">Illustrative screening model only. Replace every number with verified client outcomes before production use.</p></Reveal>
            </div>
            <Reveal delay={0.1}><PerformanceVisual /></Reveal>
            <div className="metric-row">
              {metrics.map(([value, label, tag], i) => <Reveal key={label} delay={0.08 + i * 0.05}><div className="metric"><strong>{value}</strong><span>{label}</span><small>{tag}</small></div></Reveal>)}
            </div>
          </div>
        </section>

        <section className="principles section-paper" aria-labelledby="discipline-title">
          <div className="page-width">
            <div className="section-heading"><Reveal><p className="eyebrow">The discipline</p><h2 id="discipline-title">Three things.<br /><span>Nothing extra.</span></h2></Reveal></div>
            <div className="principle-grid">
              {principles.map(([eyebrow, title, copy], i) => <Reveal key={title} delay={i * 0.07}><article className="principle"><span className="principle-number">0{i + 1}</span><p className="eyebrow">{eyebrow}</p><h3>{title}</h3><p>{copy}</p><CheckCircle2 size={17} aria-hidden="true" /></article></Reveal>)}
            </div>
          </div>
        </section>

        <section className="media-story section-white" aria-labelledby="media-title">
          <div className="page-width media-grid">
            <Reveal><div className="media-copy"><p className="eyebrow">Motion, not decoration</p><h2 id="media-title">Show the system.<br /><span>Don't explain it.</span></h2><p>A living visual communicates more in one second than a wall of agency copy. The heavy media is local, poster-backed and kept outside the critical path where possible.</p><a href="#work" className="inline-link">See the layers <ArrowUpRight size={16} /></a></div></Reveal>
            <Reveal delay={0.08}><div className="media-frame"><video autoPlay muted loop playsInline preload="metadata" poster="/hero-poster.svg" aria-label="Abstract acquisition system animation"><source src="/hero-loop.mp4" type="video/mp4" /></video><div className="media-overlay"><div><p className="eyebrow mint">Live signal</p><strong>Attention → belief → action</strong></div><span><Play size={14} fill="currentColor" aria-hidden="true" /></span></div></div></Reveal>
          </div>
        </section>

        <CampaignCarousel />

        <section className="process section-light" id="process" aria-labelledby="process-title">
          <div className="page-width process-layout"><Reveal><div className="process-statement"><p className="eyebrow">The operating loop</p><h2 id="process-title">Built to get<br /><span>smarter every week.</span></h2></div></Reveal><div className="process-list">{['Find the signal', 'Build the system', 'Compound the win'].map((x, i) => <Reveal key={x} delay={i * 0.06}><article><span>0{i + 1}</span><div><h3>{x}</h3><p>{['Audience, offer and economics reduced to the variables that actually move revenue.', 'Creative, landing experience and measurement designed as one acquisition loop.', 'Weekly learning turns spend into a repeatable growth engine—not another dashboard.'][i]}</p></div><ChevronRight size={18} aria-hidden="true" /></article></Reveal>)}</div></div>
        </section>

        <section className="contact" id="contact" aria-labelledby="contact-title">
          <div className="contact-glow" />
          <div className="page-width contact-grid"><Reveal><div className="contact-copy"><p className="eyebrow">Start here</p><h2 id="contact-title">Good growth<br />starts with<br /><span>a good brief.</span></h2><p>Tell us what you're trying to grow. We'll bring the questions, the point of view and the first useful next step.</p><div className="session-line"><CheckCircle2 size={17} aria-hidden="true" /> 30-minute working session</div></div></Reveal><Reveal delay={0.08}><LeadForm /></Reveal></div>
        </section>

        <footer className="footer"><div className="page-width footer-inner"><div className="brand footer-brand"><span className="brand-mark">D</span><span>digilabss</span><em>/</em><small>growth</small></div><div><span>© 2026 · Apple-inspired screening concept</span><span>Demo content · replace before production</span></div></div></footer>
      </main>
      <a href="#contact" className="floating-cta">Book a Call <ArrowUpRight size={15} aria-hidden="true" /></a>
    </>
  );
}
