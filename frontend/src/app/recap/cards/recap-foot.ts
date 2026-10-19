import { Component } from '@angular/core';

/** The Leapfolio mark and address along the bottom of a recap card; the card positions it. */
@Component({
    selector: 'app-recap-foot',
    template: `
    <span class="mark" aria-hidden="true">
      <svg viewBox="0 0 16 16">
        <rect x="1" y="9" width="3" height="6" rx=".5" fill="#262626" />
        <rect x="6.5" y="5" width="3" height="10" rx=".5" fill="#262626" />
        <rect x="12" y="1" width="3" height="14" rx=".5" fill="#262626" />
      </svg>
    </span>
    <span class="url">LEAPFOLIO.COM/RECAP</span>`,
    styles: `
    :host { display: flex; align-items: center; }
    .mark { width: 9.8cqw; height: 9.8cqw; border-radius: 50%; background: var(--recap-paper); display: grid;
      place-items: center; flex: none; }
    .mark svg { width: 46%; height: 46%; }
    .url { font: 600 3.9cqw/1 var(--recap-font-card); letter-spacing: .01em; white-space: nowrap; }
    @container viewer (min-width: 640px) {
      .mark { width: 4.2cqw; height: 4.2cqw; }
      .url { font-size: 1.7cqw; }
    }`
})
export class RecapFootComponent {}
