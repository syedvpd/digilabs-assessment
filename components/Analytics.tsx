'use client';

import { useEffect } from 'react';

export function Analytics() {
  useEffect(() => {
    const w = window as typeof window & { dataLayer?: Record<string, unknown>[]; gtag?: (...args: unknown[]) => void; fbq?: (...args: unknown[]) => void };
    w.dataLayer = w.dataLayer || [];
    w.dataLayer.push({ 'gtm.start': Date.now(), event: 'gtm.js' });

    const gtmId = process.env.NEXT_PUBLIC_GTM_ID;
    if (gtmId && !document.querySelector(`script[data-gtm="${gtmId}"]`)) {
      const script = document.createElement('script');
      script.async = true;
      script.src = `https://www.googletagmanager.com/gtm.js?id=${encodeURIComponent(gtmId)}`;
      script.dataset.gtm = gtmId;
      document.head.appendChild(script);
    }

    const ga4Id = process.env.NEXT_PUBLIC_GA4_ID;
    if (ga4Id && !document.querySelector(`script[data-ga4="${ga4Id}"]`)) {
      const script = document.createElement('script');
      script.async = true;
      script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(ga4Id)}`;
      script.dataset.ga4 = ga4Id;
      document.head.appendChild(script);
      w.gtag = (...args: unknown[]) => w.dataLayer?.push({ event: 'gtag', args });
      w.gtag('js', new Date());
      w.gtag('config', ga4Id, { anonymize_ip: true });
    }

    const metaId = process.env.NEXT_PUBLIC_META_PIXEL_ID;
    if (metaId && !document.querySelector(`script[data-meta-pixel="${metaId}"]`)) {
      const script = document.createElement('script');
      script.async = true;
      script.dataset.metaPixel = metaId;
      script.text = `window.fbq=window.fbq||function(){(window.fbq.q=window.fbq.q||[]).push(arguments)};window.fbq('init','${metaId}');window.fbq('track','PageView');`;
      document.head.appendChild(script);
    }
  }, []);

  return null;
}

declare global {
  interface Window {
    dataLayer: Record<string, unknown>[];
    gtag?: (...args: unknown[]) => void;
    fbq?: (...args: unknown[]) => void;
  }
}

export function trackLead(data: { budget: string }) {
  if (typeof window === 'undefined') return;
  window.dataLayer = window.dataLayer || [];
  const payload = { event: 'lead_submit', lead_budget: data.budget, event_id: `lead_${Date.now()}` };
  window.dataLayer.push(payload);

  // Mock GA4 event: always fires into the local dataLayer so the screening demo is verifiable.
  window.dataLayer.push({ event: 'ga4_generate_lead', value: 1 });
  window.gtag?.('event', 'generate_lead', { lead_budget: data.budget });

  // Mock/optional Meta Pixel event. If a real pixel is configured, this becomes a real event.
  window.fbq?.('track', 'Lead');
  window.dispatchEvent(new CustomEvent('digilabss:lead', { detail: data }));
  localStorage.setItem('digilabss_last_tracking_event', JSON.stringify({ ...payload, trackedAt: new Date().toISOString() }));
  console.info('[Digilabss analytics] lead_submit', payload);
}
