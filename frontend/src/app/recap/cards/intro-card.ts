import { Component, computed, input } from '@angular/core';
import { RecapPayload } from '../../services/recap';

/** Card 1: the month's title over the dotted art. */
@Component({
  selector: 'app-intro-card',
  template: `
    <div class="abs copy">
      <div class="kicker" data-a style="--i:0">Leapfolio Recap · {{ shortMonth() }}</div>
      <h2 class="display" data-a style="--i:1">
        <!-- The trailing spaces keep it one sentence for screen readers; Angular drops spaces between tags. -->
        <span>Your </span><span class="accent">{{ monthName() }}, </span><span>recapped.</span>
      </h2>
      <p class="sub" data-a style="--i:3">
        @if (recap().quiet) {
          A quiet month for trading. Here's how your holdings did, and how the market moved.
        } @else {
          What you traded, what paid off, and how you did against the market.
        }
      </p>
    </div>
    <div class="abs tap" data-a style="--i:5" aria-hidden="true">
      <span class="pointer">Click to start →</span><span class="touch">Tap to start →</span>
    </div>`,
  styleUrl: './intro-card.css'
})
export class IntroCardComponent {
  readonly recap = input.required<RecapPayload>();
  readonly monthName = input.required<string>();

  /** "Sep 2026" */
  readonly shortMonth = computed(() => `${this.monthName().slice(0, 3)} ${this.recap().month.slice(0, 4)}`);
}
