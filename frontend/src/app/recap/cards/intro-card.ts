import { Component, computed, input } from '@angular/core';
import { monthName, monthShort } from '../recap-format';

/** Card 1: which month this is, over the dotted "year" art. */
@Component({
    selector: 'app-recap-intro-card',
    template: `
    <div class="abs copy">
      <div class="kicker" data-a style="--i:0">Leapfolio Recap · {{ short() }}</div>
      <h2 class="display" data-a style="--i:1"><span>Your</span><span class="lime">{{ name() }},</span><span>recapped.</span></h2>
      <p class="sub" data-a style="--i:3">{{ sub() }}</p>
    </div>
    <div class="abs tap" data-a style="--i:5">Click to start →</div>`,
    styleUrls: ['./card.css', './intro-card.css']
})
export class RecapIntroCardComponent {
  /** yyyy-MM */
  readonly month = input.required<string>();
  readonly quiet = input(false);

  readonly name = computed(() => monthName(this.month()));
  readonly short = computed(() => monthShort(this.month()));
  readonly sub = computed(() => this.quiet()
    ? 'No trades this month, so here’s how what you held did, and how it stacked up against the market.'
    : 'What you traded, what paid off, and how you did against the market.');
}
