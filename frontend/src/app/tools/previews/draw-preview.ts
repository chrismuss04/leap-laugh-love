import { Component, computed, input } from '@angular/core';
import { PRESETS } from '../draw/presets';
import { smooth, splinePath } from '../draw/stroke';

/** The rally preset's control points, reused so the tile shows the tool's own first shape. */
const SHAPE = PRESETS[0].points;

/**
 * Draw Your Trade's tile: the account's value today drawn as history up to a live dot, then a pen
 * that draws where it goes next, over and over. Without a moving chart (reduced motion) the drawn
 * line simply sits there.
 */
@Component({
  selector: 'app-draw-preview',
  template: `
    <svg [attr.width]="width()" [attr.height]="height()" [attr.viewBox]="'0 0 ' + width() + ' ' + height()" aria-hidden="true">
      <rect class="future" [attr.x]="split()" y="0" [attr.width]="width() - split()" [attr.height]="height()" rx="6" />
      <path class="history" [attr.d]="paths().history" />
      <path class="drawn" pathLength="1" [attr.d]="paths().future" />
      <circle class="pen" r="3.5">
        <animateMotion dur="3.4s" repeatCount="indefinite" [attr.path]="paths().future" calcMode="spline"
                       keyPoints="0;1;1" keyTimes="0;0.55;1" keySplines="0.45 0 0.25 1;0 0 1 1" />
        <animate attributeName="opacity" dur="3.4s" repeatCount="indefinite" values="1;1;0;0" keyTimes="0;0.55;0.65;1" />
      </circle>
      <circle class="now" [attr.cx]="split()" [attr.cy]="paths().nowY" r="3.5" />
    </svg>
  `,
  styles: [`
    :host { display: inline-flex; }
    svg { overflow: visible; }
    .future { fill: var(--primary); opacity: 0.06; }
    .history { fill: none; stroke: color-mix(in srgb, var(--foreground) 60%, transparent); stroke-width: 1.5; stroke-linejoin: round; }
    .drawn {
      fill: none;
      stroke: var(--gain);
      stroke-width: 2.25;
      stroke-linecap: round;
      stroke-dasharray: 1;
      filter: drop-shadow(0 0 4px color-mix(in srgb, var(--gain) 60%, transparent));
      animation: pen-line 3.4s cubic-bezier(0.45, 0, 0.25, 1) infinite;
    }
    .pen { fill: #fff; filter: drop-shadow(0 0 4px var(--gain)); }
    .now { fill: var(--primary); stroke: var(--background); stroke-width: 1.5; }
    @keyframes pen-line {
      0% { stroke-dashoffset: 1; opacity: 1; }
      55% { stroke-dashoffset: 0; opacity: 1; }
      85% { stroke-dashoffset: 0; opacity: 1; }
      100% { stroke-dashoffset: 0; opacity: 0; }
    }
    @media (prefers-reduced-motion: reduce) {
      .drawn { animation: none; stroke-dasharray: none; }
      .pen { display: none; }
    }
  `]
})
export class DrawPreviewComponent {
  /** The account's value through today, oldest first. */
  readonly values = input<readonly number[]>([]);
  readonly width = input(150);
  readonly height = input(56);

  /** Where now sits across the tile: history to its left, the drawn future to its right. */
  readonly split = computed(() => Math.round(this.width() * 0.52));

  readonly paths = computed(() => {
    const width = this.width();
    const height = this.height();
    const split = this.split();
    const values = this.values().length >= 2 ? this.values() : [1, 1];
    const low = Math.min(...values);
    const span = Math.max(...values) - low;
    const pad = 6;
    // History fills the middle band, leaving room above it for the line to climb.
    const y = (v: number) => (span > 0 ? height * 0.78 - ((v - low) / span) * height * 0.32 : height * 0.62);
    const history = values.map((v, i) => ({ x: pad + (i / (values.length - 1)) * (split - pad), y: y(v) }));
    const nowY = history[history.length - 1].y;
    const rise = Math.max(nowY - pad, 8);
    const future = smooth(SHAPE.map(([t, m]) => ({ x: split + t * (width - split - pad), y: nowY - m * rise })), 2);
    const futurePath = splinePath(future, 0.5);
    return {
      history: history.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(''),
      future: futurePath,
      nowY
    };
  });
}
