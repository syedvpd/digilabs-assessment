# Digilabss — Apple-Inspired Meta Ads Landing Page

A premium one-page screening implementation for the supplied Digilabss hiring assignment. The experience is intentionally built as a continuous acquisition story rather than a conventional agency page.

## Mandatory brief coverage

The implementation covers the brief's six requirement groups:

1. **Design Direction** — visual-first storytelling, large typography, animated/video hero, scroll reveals, atmospheric background, one continuous story and persistent Book a Call.
2. **Tech Stack** — Next.js/React + Tailwind CSS + GSAP/ScrollTrigger. Lenis is used for smooth-scroll interpolation.
3. **Functionality** — validated lead form, demo CRM endpoint with verifiable GET endpoint, optional Google Sheet/CRM webhook, GTM dataLayer, mock GA4 event and optional real GA4/Meta Pixel events.
4. **Performance & Responsiveness** — responsive layouts, local poster-backed media, transform/opacity animation, lazy non-critical media, reduced-motion mode and performance-oriented scene architecture.
5. **Strategy Note** — see `STRATEGY_NOTE.md` (200–300 words).
6. **Deployment** — Vercel/Netlify-ready.

See `DEMO_CHECKLIST.md` for a requirement-by-requirement audit and the exact verification procedure.

## Run locally

```bash
npm install
npm run dev
```

Open `http://localhost:3000`.

## Production verification

```bash
npm run lint
npm run build
npm start
```

`npm run lint` intentionally performs a strict TypeScript check so the screening submission has a simple no-build-tooling lint gate.

## Architecture

- `components/Hero.tsx` — cinematic hero and scroll-linked parallax.
- `components/SmoothScroll.tsx` — Lenis + GSAP ticker synchronization.
- `components/StoryScene.tsx` — pinned, scroll-driven acquisition story.
- `components/PerformanceVisual.tsx` — lightweight SVG performance visualization.
- `components/Carousel.tsx` — desktop horizontal storytelling + mobile native swipe fallback.
- `components/Reveal.tsx` — reusable viewport reveal primitive.
- `components/LeadForm.tsx` — client validation, lead submission and success UI.
- `components/Analytics.tsx` — GTM/dataLayer, mock GA4 and optional GA4/Meta events.
- `app/api/leads/route.ts` — demo CRM endpoint, verifiable GET endpoint and optional server-side webhook forwarding.

## CRM / Google Sheet integration

The assignment says the form submission must log or store data somewhere verifiable. The default demo implementation does both:

- logs the lead in the server terminal;
- stores recent leads in memory;
- exposes them at `GET /api/leads`;
- stores a client-side copy in localStorage.

For a real Google Sheet/CRM handoff, set `CRM_WEBHOOK_URL` to a secure server-side webhook such as a Google Apps Script web app, Make, Zapier or CRM endpoint. The browser never receives this secret URL.

## Tracking

The lead form fires:

- `lead_submit` to `window.dataLayer`;
- mock `ga4_generate_lead` to `window.dataLayer`;
- optional real GA4 `generate_lead` when `NEXT_PUBLIC_GA4_ID` is set;
- optional Meta Pixel `Lead` when `NEXT_PUBLIC_META_PIXEL_ID` is set;
- a localStorage record for demo verification.

Set:

```text
NEXT_PUBLIC_GTM_ID=GTM-XXXXXXX
NEXT_PUBLIC_GA4_ID=G-XXXXXXXXXX
NEXT_PUBLIC_META_PIXEL_ID=123456789012345
CRM_WEBHOOK_URL=
```

## Motion philosophy

Premium scrolling is not created by aggressively slowing the wheel. Lenis interpolates scrolling while preserving normal input semantics; GSAP and ScrollTrigger map scroll progress to carefully selected cinematic scenes. The page deliberately avoids animating every element.

Major motion moments include:

1. Hero media/text/atmosphere choreography.
2. Pinned four-stage acquisition system.
3. Scroll-driven horizontal acquisition-stack story.
4. Viewport reveals.
5. Subtle background and hover motion.

Desktop receives the richest choreography. Mobile uses simpler, touch-friendly motion and native horizontal swipe for the carousel.

## Performance

The code is designed for the brief's performance target but does **not** fabricate a PageSpeed score. The required **85+ mobile PageSpeed** and good **LCP / CLS / INP** must be measured against the final deployed URL.

Performance choices:

- local compact MP4 and SVG assets;
- hero poster and metadata preload;
- lazy non-critical visuals;
- fixed aspect ratios to reduce layout shift;
- transform/opacity-based scroll animation;
- no particle library;
- limited pinned scenes;
- reduced-motion fallback;
- responsive motion complexity;
- no aggressive wheel hijacking.

## Deployment

### Vercel

Import the repository into Vercel and set the environment variables in the project dashboard.

### Netlify

Build command:

```text
npm run build
```

Publish the Next.js output using Netlify's Next.js runtime/plugin.

Before submitting the assignment, deploy a production build and record the real public URL, PageSpeed result and GitHub repository URL.

## Content integrity

The supplied brief states that the brand, service and content are placeholders for a screening exercise. Therefore all performance numbers on the page are explicitly labelled illustrative/demo content and must be replaced with verified outcomes before production use.
