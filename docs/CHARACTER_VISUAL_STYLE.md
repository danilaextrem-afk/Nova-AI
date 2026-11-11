# NOVA AI — Photorealistic Character Art Direction

## Main visual language
The entire default character library uses photorealistic **adult fictional people** (all characters are 21+), not anime, cartoons, plastic CGI, overly filtered faces or copied portraits.

### Women
- Expressive original faces and distinct hairstyles (red hair, blonde, brunette, black hair), realistic skin texture, varied facial features.
- Naturally proportioned adult bodies and different builds; use attractive, polished, respectful portraits without uniform appearance requirements.
- Contemporary clothing (smart casual, elegant dresses, outdoor jackets, coats, uniforms) appropriate to the character's story.
- Art direction reference from the user: warm indoor portrait, long red hair, teal clothing, natural smile, soft realistic lighting. Reference defines a *photography style*, **not** permission to copy the person's identity or face.

### Men
- Photorealistic adult faces, distinct grooming, realistic hair and skin, natural varied body types.
- Clothing appropriate to roles: investigator, pilot, scientist, explorer, creative professional, fantasy guardian.
- Avoid cartoon/anime rendering, overprocessed skin or celebrity likeness.

## Portrait specifications
- Prefer original or appropriately licensed AI-generated adult fictional photos; do not reproduce an actual person's identity.
- Master: 1024×1280 or higher, portrait 4:5, sRGB; crop shoulder/torso with face clear and centered.
- Avatar: 512×512 WebP, cover crop with consistent focal position, 150–350 KB budget.
- Character cover: 768×1024 WebP, original cinematic scene, at least 720 px tall.
- Platform-safe content: fully clothed; no nudity, underwear-focused or sexual imagery.
- Android uses local bundled starter thumbnails before backend connects; remote URL may replace them once uploaded to a properly configured backend/CDN.
- Each character gets unique facial features and scene, not one generic reused avatar.
- Image data must be shipped with the app or served by a stable licensed endpoint; **do not present concept sheets as integrated Android assets**.

## Art QA
1. Face and hands anatomically plausible, no duplicated fingers or distorted eyes.
2. Adult visual presentation, natural proportions and lighting.
3. Does not reproduce the user reference person's exact face.
4. Stable facial identity across future generated images of same character.
5. Thumbnail clear on a 360dp Android screen at 1.5× and 3× screen density.
6. Image has clear content provenance and appropriate usage rights.

## Launch UX
App installation and entering the free story/character catalog should not require purchase.
Free chat quota should be available without payments when an AI provider is configured. Costs and quota must be stated clearly.
No fake chatbot replies when the real provider is absent or down.
