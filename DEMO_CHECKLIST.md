# Digilabss Hiring Assignment — Mandatory Compliance Checklist

This checklist maps the implementation directly to every requirement stated in the supplied 4-day screening brief. Do not mark a performance/deployment claim as completed unless it has been actually measured or deployed.

## Source brief — assignment status

| Requirement | What is implemented | Verification |
|---|---|---|
| **1. Design Direction — visual first** | Every major scene leads with typography + visual system/animation rather than long paragraphs. | Scroll through hero, system, proof, media and work scenes. |
| **1. Large confident typography + whitespace** | Responsive `clamp()` display type, tight tracking/leading, generous section spacing. | Resize desktop/mobile and inspect hierarchy. |
| **1. Animated/video hero** | Local poster-backed looping MP4 hero with layered atmosphere. | Open homepage with network offline after assets are cached / inspect `public/hero-loop.mp4`. |
| **1. Scroll-triggered reveals** | Reusable GSAP `Reveal` component with viewport-triggered opacity/y/blur treatment. | Scroll down from top; sections enter progressively. |
| **1. Subtle animated background** | CSS gradient atmosphere, blurred orbs, grid, noise and restrained motion. | Hero/system backgrounds animate without particle-library overhead. |
| **1. One continuous service story** | Thesis → acquisition system → proof → visual layers → work → operating loop → contact. | Follow page from top to bottom. |
| **1. Persistent Book a Call** | Floating CTA plus navigation CTA. | CTA remains available while scrolling. |
| **2. React/Next + Tailwind** | Next.js + React + Tailwind CSS. | `package.json`, `tailwind.config.ts`. |
| **2. GSAP scroll animation** | GSAP + ScrollTrigger is the primary scroll animation system. | `StoryScene.tsx`, `Carousel.tsx`, `Hero.tsx`. |
| **2. At least 3 motion moments** | Hero scroll choreography; pinned acquisition story; horizontal work carousel; viewport reveals; hover/background motion. | Test each scene. |
| **3. Book a Call / lead capture** | Name, email, company, monthly budget. | Submit form. |
| **3. Client-side validation** | Required fields + work-email format + budget validation + inline error states. | Submit empty/invalid form. |
| **3. Dummy CRM endpoint / Google Sheet** | `POST /api/leads` validates and stores demo leads in server memory, logs them to terminal, and exposes `GET /api/leads` for verification. Optional `CRM_WEBHOOK_URL` can forward the same JSON to Google Apps Script/Make/Zapier/CRM. | Submit form, then open `/api/leads`; optionally configure webhook. |
| **3. Basic tracking** | GTM dataLayer; `lead_submit`; mock GA4 `generate_lead`; optional real GA4 event; optional Meta Pixel `Lead`; localStorage tracking record. | Submit form, inspect browser console/localStorage/dataLayer. |
| **4. Fast/smooth despite visuals** | Local poster/video, lightweight SVG visual, CSS atmosphere, transform/opacity animation, lazy non-critical media, limited DOM motion. | Production build + Lighthouse/PageSpeed measurement. |
| **4. Buttery scrolling desktop/tablet/mobile** | Lenis smooth scrolling synchronized to GSAP ticker + ScrollTrigger; mobile motion is simplified. | Test wheel, trackpad and touch. |
| **4. No jank/layout shift** | Fixed media aspect ratios, reserved layout dimensions, transform-based animation, no layout property animation for scroll scenes. | Chrome Performance/Lighthouse run. |
| **4. PageSpeed 85+ mobile** | Performance architecture targets the brief. | **Must be measured on the deployed URL before claiming 85+.** |
| **4. Core Web Vitals LCP/CLS/INP good** | Architecture is designed around these metrics. | **Must be measured on the deployed URL before claiming good.** |
| **4. Lazy loading** | Non-critical SVG/media are lazy-loadable; critical hero uses poster + metadata preload rather than aggressive full preload. | Inspect network waterfall. |
| **4. Compressed/optimized media** | Local compact MP4 + SVG assets; no massive image library. | Inspect `public/` file sizes and production network. |
| **4. Animation never at cost of speed** | Transform/opacity, limited pinned scenes, no particle framework, reduced-motion fallback. | Performance profile. |
| **5. 200–300 word Strategy Note** | `STRATEGY_NOTE.md` is included and is within the requested range. | Read file. |
| **6. Deployment** | Vercel/Netlify-ready project configuration and deployment instructions. | Deploy and record final URL. |
| **Evaluation — visual taste/restraint** | Unified palette, editorial type hierarchy, restrained effects. | Creative review. |
| **Evaluation — natural motion** | Scroll-linked scene transitions rather than animation on every element. | Slow and normal-speed scroll test. |
| **Evaluation — responsive consistency** | Dedicated desktop/tablet/mobile layouts and motion behavior. | Test 360/390/430/768/1024/1280/1440/1920 widths. |
| **Evaluation — real-world performance scores** | Measurement instructions included; no fabricated score. | Run PageSpeed/Lighthouse after deployment. |
| **Evaluation — premium, not busy** | Visual hierarchy deliberately limits copy, cards and effects. | Creative/hiring-manager review. |

## Exact functionality demo — CRM

1. Run `npm run dev`.
2. Submit a valid lead in the form.
3. Confirm the terminal prints `[Digilabss DEMO CRM] lead received`.
4. Open `http://localhost:3000/api/leads`.
5. Confirm the submitted lead appears in the JSON response.
6. Inspect `localStorage` for `digilabss_last_lead`.
7. Inspect `localStorage` for `digilabss_last_tracking_event`.
8. Inspect `window.dataLayer` in DevTools.

For a Google Sheet-style handoff, create a Google Apps Script web app that accepts POST JSON and set:

```text
CRM_WEBHOOK_URL=https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec
```

The API forwards the lead server-side without exposing the webhook URL to the browser.

## Exact tracking demo

Without any analytics IDs, the screening demo still fires:

```text
lead_submit
└── lead_budget
└── event_id

ga4_generate_lead

digilabss:lead CustomEvent
```

Optional production IDs:

```text
NEXT_PUBLIC_GTM_ID
NEXT_PUBLIC_GA4_ID
NEXT_PUBLIC_META_PIXEL_ID
```

## Performance acceptance gate

The brief asks for **85+ mobile PageSpeed** and good **LCP / CLS / INP**. These are environment/deployment measurements, not code claims.

Before sending the live URL to the interviewer:

- Deploy production build.
- Run Google PageSpeed Insights on the public URL.
- Record mobile score.
- Check LCP.
- Check CLS.
- Check INP.
- Fix regressions before claiming compliance.

## Final presentation checklist

- [ ] `npm install`
- [ ] `npm run lint`
- [ ] `npm run build`
- [ ] `npm start`
- [ ] Desktop hero tested
- [ ] Desktop pinned story tested
- [ ] Desktop horizontal story tested
- [ ] Mobile tested
- [ ] Tablet tested
- [ ] Keyboard navigation tested
- [ ] Reduced motion tested
- [ ] Form validation tested
- [ ] CRM endpoint verified
- [ ] GTM/dataLayer event verified
- [ ] Mock/real GA4 event verified
- [ ] Optional Meta Lead event verified if configured
- [ ] No missing media
- [ ] No console errors
- [ ] No hydration errors
- [ ] No horizontal overflow
- [ ] Production PageSpeed measured
- [ ] Core Web Vitals checked
- [ ] Live URL obtained
- [ ] GitHub repository pushed
- [ ] Strategy note attached
