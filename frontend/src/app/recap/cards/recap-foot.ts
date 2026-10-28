import { Component } from '@angular/core';

/**
 * The Leapfolio brand along the bottom of a recap card, drawn as the header's (shell.html): the
 * teal tile with its three bars, then the name. Sized in cqw so it scales with the card; the card
 * positions it.
 */
@Component({
    selector: 'app-recap-foot',
    template: `
    <span class="mark" aria-hidden="true">
      <svg viewBox="0 0 16 16">
        <rect x="1" y="9" width="3" height="6" rx=".5" />
        <rect x="6.5" y="5" width="3" height="10" rx=".5" />
        <rect x="12" y="1" width="3" height="14" rx=".5" />
      </svg>
    </span>
    <span class="name">Leapfolio</span>`,
    styles: `
    :host { display: flex; align-items: center; }
    /* The header's 26px tile, 7px corners and 16px bars, in proportion. */
    .mark { width: 6.4cqw; height: 6.4cqw; border-radius: 27%; background: var(--primary); display: grid;
      place-items: center; flex: none; }
    .mark svg { width: 61.5%; height: 61.5%; fill: var(--primary-foreground); }
    .name { font: 600 3.9cqw/1 var(--recap-font-card); color: var(--recap-paper); white-space: nowrap; }
    @container viewer (min-width: 640px) {
      .mark { width: 2.8cqw; height: 2.8cqw; }
      .name { font-size: 1.7cqw; }
    }`
})
export class RecapFootComponent {}
