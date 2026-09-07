'use client';

import { ArrowUpRight } from 'lucide-react';

export function Navigation() {
  return (
    <header className="site-nav-wrap">
      <nav className="site-nav" aria-label="Primary navigation">
        <a href="#top" className="brand" aria-label="Digilabss home"><span className="brand-mark">D</span><span>digilabss</span><em>/</em><small>growth</small></a>
        <div className="nav-links">
          <a href="#system">System</a>
          <a href="#work">Work</a>
          <a href="#proof">Proof</a>
          <a href="#contact">Contact</a>
        </div>
        <a className="nav-cta" href="#contact">Book a Call <ArrowUpRight size={14} /></a>
      </nav>
    </header>
  );
}
