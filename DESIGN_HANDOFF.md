# Design Handoff — What Changed

## The redesign in one sentence

The page was rebuilt as a scroll-led product story rather than a stack of marketing sections.

## Motion architecture

`Lenis` handles smooth scroll interpolation without taking ownership away from native scrolling. `GSAP` owns animation timelines. `ScrollTrigger` maps scroll position to pinned scenes and progress. CSS owns responsive layout and lightweight ambient animation. React owns component composition and application state.

## Three hero/story moments

1. **Hero depth:** local video, atmospheric layers, grid and layered typography establish the opening scene.
2. **Pinned system story:** one visual stays in place while the story progresses through Attention → Intent → Conversion → Learning.
3. **Horizontal acquisition stack:** desktop scrolling becomes a horizontal campaign story; mobile falls back to a native swipe carousel.

## Why the page feels less like a template

- One shared palette instead of unrelated section colors.
- Display typography changes scale by hierarchy, not randomly.
- Sections blend into each other instead of looking like cards placed on a page.
- Visuals carry explanations that would otherwise require long copy.
- Motion is used to reveal relationships between ideas.
- Large empty areas are intentional staging, not missing content.

## Performance rules

- Prefer transforms and opacity for motion.
- Hero video uses a poster and metadata preload.
- SVG is used for the performance visual.
- Mobile reduces pinned complexity.
- Reduced-motion users get a simplified experience.
- No heavy particle library is used.
- ScrollTrigger instances are reverted during component cleanup.

## Interview language

If asked why the redesign uses GSAP + ScrollTrigger + Lenis:

> “I separated scroll physics from animation choreography. Lenis makes the scroll feel fluid, while ScrollTrigger maps the user's real scroll progress into a few carefully designed scenes. That lets the page tell a story without hijacking the user's input or animating every element.”
