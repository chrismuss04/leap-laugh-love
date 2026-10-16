# Monthly recap mockup

The approved design for the monthly trading recap, built from the Figma frames one folder up. Those
frames are the original Spotify Wrapped reference; this folder is what Leapfolio's recap should
look like.

- `recap-mockup.html` - the interactive mockup (published version 9). Opens in any browser; every
  image is embedded. Figures are sample data, not real client numbers.
- `desktop/card-1.png` ... `card-6.png` - each card at 1920x1080, the desktop 16:9 layout. The
  Figma backgrounds are rotated 90 degrees clockwise to fill it.
- `narrow/card-1.png` ... `card-6.png` - each card at 1080x1920, the narrow 9:16 layout used below
  640px, the same size as the Figma frames.

Cards: 1 Intro, 2 Money Moved, 3 Top Tickers, 4 Top Gainer, 5 Me vs the S&P 500, 6 The Tops.

The card palette uses the app accent, teal `#2dd4bf`, in place of the reference's green. Fonts are
Dela Gothic One and Inter.

Open items:
- The teal background art has a thin dark blue fringe left over from the recolor. Re-export it when
  the backgrounds are converted to WebP for the app.
- Card 4's "Top 4% of Leapfolio traders" ranks the client's overall trading return, not the top
  gainer, so its wording gets clarified when the cards are built.
- Card animations come in a later pass.
