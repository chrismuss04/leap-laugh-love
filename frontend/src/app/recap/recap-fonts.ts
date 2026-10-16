// The recap cards' typefaces, from the Figma reference. Loaded when the recap page first opens,
// so no other page downloads them; until they arrive the cards fall back to the stacks in
// story-viewer.css.
const FONTS_URL = 'https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Inter:wght@400;600;800&display=swap';
const LINK_ID = 'recap-fonts';

/** Adds the recap fonts' stylesheet to the page, once. */
export function loadRecapFonts(doc: Document = document): void {
  if (doc.getElementById(LINK_ID)) {
    return;
  }
  const link = doc.createElement('link');
  link.id = LINK_ID;
  link.rel = 'stylesheet';
  link.href = FONTS_URL;
  doc.head.appendChild(link);
}
