# Strategy Note

The page treats the service as a single acquisition story rather than a collection of agency sections. I cut the conventional long-form explanations, service grids and decorative statistics and replaced them with a sequence of visual decisions: earn attention, create intent, make conversion measurable, then feed learning back into the next cycle.

Typography carries the hierarchy. A single modern sans-serif is used across the experience, while responsive `clamp()` sizing, tight tracking and compressed display line-height create the oversized editorial feel. Large type is reserved for the proposition and major story moments; supporting copy stays short and quiet so the page can breathe.

The heaviest concepts are visualized rather than explained. The acquisition system is presented as a pinned scroll scene, performance is represented by a lightweight SVG chart, and the creative/media/landing/learning layers become a horizontal story on desktop and a native swipe carousel on smaller screens. This gives each scroll a purpose: the visitor is progressing through a model, not simply passing cards.

Motion is intentionally restrained. Lenis provides smooth interpolation without hijacking native scrolling, while GSAP and ScrollTrigger drive the few moments that benefit from scroll-linked choreography. Transforms and opacity are preferred over layout properties, and reduced-motion users receive a simplified experience.

Performance remains part of the design. Critical typography is optimized by Next, hero media has a poster and metadata preload, non-critical visuals are lazy where appropriate, and the page avoids large particle libraries or unnecessary DOM animation. The goal is not maximum motion; it is a page that feels expensive because every movement, word and transition has a reason.
