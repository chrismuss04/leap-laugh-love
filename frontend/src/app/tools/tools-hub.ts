import { Component, EnvironmentInjector, afterNextRender, inject } from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { ToolPreviewComponent } from './previews/tool-preview';
import { ToolsContext } from './tools-context';
import { TOOLS, ToolInfo, isModifiedClick } from './tools-catalog';

/** Shared by the opened tile and the tool page, so the browser morphs one into the other. */
export const EXPAND_TRANSITION = 'tool-expand';

type TransitionDocument = Document & { startViewTransition?: (update: () => Promise<void>) => unknown };

/** Trading Tools: one tile per tool, each previewing what it would do with the selected account. */
@Component({
    selector: 'app-tools-hub',
    imports: [CommonModule, ToolPreviewComponent],
    template: `
    <header class="hub-header">
      <h1>Trading Tools</h1>
      <p>Each tool works on {{ context.account()?.name ?? 'your account' }}.</p>
    </header>

    <ul class="tiles" role="list">
      <li *ngFor="let tool of tools">
        <a #tile class="tile" [attr.href]="context.urlFor(tool.id)" [attr.data-tool]="tool.id"
           [attr.aria-label]="tool.name + ': ' + tool.blurb"
           (click)="open($event, tool, tile)" (keydown.space)="open($event, tool, tile)">
          <app-tool-preview [tool]="tool.id"></app-tool-preview>
          <span class="tile-name">{{ tool.name }}</span>
          <span class="tile-blurb">{{ tool.blurb }}</span>
        </a>
      </li>
    </ul>
  `,
    styles: [`
    :host { display: block; }
    .hub-header h1 { font-size: 28px; font-weight: 600; color: var(--foreground); }
    .hub-header p { margin-top: 6px; font-size: 14px; color: var(--muted-foreground); }
    .tiles {
      list-style: none;
      margin-top: 24px;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 250px), 1fr));
      gap: 16px;
    }
    .tile {
      display: flex;
      flex-direction: column;
      gap: 6px;
      height: 100%;
      padding: 16px 18px 20px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: var(--surface);
      color: inherit;
      text-decoration: none;
      transition: border-color 0.15s ease, background 0.15s ease, transform 0.15s ease;
    }
    .tile:hover { background: var(--surface-hover); border-color: color-mix(in srgb, var(--foreground) 18%, var(--border)); }
    .tile:focus-visible { outline: 2px solid var(--ring); outline-offset: 2px; }
    .tile:active { transform: scale(0.99); }
    .tile-name { margin-top: 8px; font-size: 16px; font-weight: 600; color: var(--foreground); }
    .tile-blurb { font-size: 13px; line-height: 1.45; color: var(--muted-foreground); }
    @media (prefers-reduced-motion: reduce) {
      .tile { transition: none; }
      .tile:active { transform: none; }
    }
  `]
})
export class ToolsHubComponent {
  readonly tools = TOOLS;
  readonly context = inject(ToolsContext);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT) as TransitionDocument;
  // The hub is gone by the time the tool renders, so wait on an injector that outlives it.
  private readonly injector = inject(EnvironmentInjector);

  /**
   * Opens a tool, growing it out of its tile where the browser can. A modified click (new tab,
   * new window) is left to the browser, which follows the tile's link.
   */
  open(event: Event, tool: ToolInfo, tile: HTMLElement): void {
    if (event instanceof MouseEvent && isModifiedClick(event)) {
      return;
    }
    event.preventDefault();
    const url = this.context.urlFor(tool.id);
    const reduceMotion = this.document.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? true;
    if (!this.document.startViewTransition || reduceMotion) {
      this.router.navigateByUrl(url);
      return;
    }
    tile.style.setProperty('view-transition-name', EXPAND_TRANSITION);
    this.document.startViewTransition(async () => {
      await this.router.navigateByUrl(url);
      await new Promise<void>(resolve => afterNextRender(() => resolve(), { injector: this.injector }));
    });
  }
}
