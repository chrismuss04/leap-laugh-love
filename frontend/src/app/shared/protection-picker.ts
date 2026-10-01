import { Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { MAX_PROTECTION, PROTECTION_PRESETS, formatProtection, parseProtection } from './price-protection';

/**
 * One row of pill buttons for choosing a price protection: optionally Off, the presets, and
 * optionally Custom with a typed percent. Emits the chosen percent, null for Off, or NaN while a
 * custom entry isn't valid - so the parent can block saving without tracking the text itself.
 */
@Component({
  selector: 'app-protection-picker',
  template: `
    <div class="picker" role="radiogroup" [attr.aria-label]="label()">
      @if (allowOff()) {
        <button type="button" role="radio" class="chip" [class.on]="selected() === 'off'"
                [attr.aria-checked]="selected() === 'off'" (click)="choose(null)">Off</button>
      }
      @for (option of options(); track option) {
        <button type="button" role="radio" class="chip num" [class.on]="selected() === option"
                [attr.aria-checked]="selected() === option" (click)="choose(option)">{{ format(option) }}</button>
      }
      @if (allowCustom()) {
        <button type="button" role="radio" class="chip" [class.on]="selected() === 'custom'"
                [attr.aria-checked]="selected() === 'custom'" (click)="chooseCustom()">Custom</button>
      }
    </div>
    @if (selected() === 'custom') {
      <div class="custom" [class.invalid]="customInvalid()">
        <input #customInput class="custom-input num" inputmode="decimal" autocomplete="off" placeholder="0.75"
               [attr.aria-label]="label() + ', custom percent'" [attr.aria-invalid]="customInvalid()"
               [value]="customText()" (input)="typeCustom($any($event.target).value)" />
        <span class="suffix" aria-hidden="true">%</span>
      </div>
      @if (customInvalid()) {
        <span class="error-message" role="alert">Enter a percent from 0 to {{ max }}, up to two decimals</span>
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 8px; }
    .picker { display: flex; flex-wrap: wrap; gap: 4px; padding: 4px; background: var(--muted);
      border-radius: 999px; align-self: flex-start; max-width: 100%; }
    .chip { padding: 7px 14px; border-radius: 999px; background: transparent; color: var(--muted-foreground);
      font-size: 13px; font-weight: 600; transition: background 0.15s ease, color 0.15s ease; }
    .chip:hover:not(.on) { color: var(--foreground); }
    .chip.on { background: color-mix(in srgb, var(--primary) 16%, transparent); color: var(--primary); }
    .chip:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
    .custom { display: inline-flex; align-items: center; align-self: flex-start; gap: 6px; padding: 0 14px;
      border: 1px solid var(--border); border-radius: 999px; background: var(--muted); }
    .custom:focus-within { border-color: var(--ring);
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 25%, transparent); }
    .custom.invalid { border-color: var(--danger); }
    .custom-input { width: 64px; padding: 8px 0; background: transparent; border: 0; outline: none;
      color: var(--foreground); font-size: 14px; font-weight: 600; text-align: right; }
    .custom-input::placeholder { color: var(--muted-foreground); font-weight: 500; }
    .suffix { color: var(--muted-foreground); font-size: 14px; }
    .error-message { margin-top: 0; }
  `
})
export class ProtectionPickerComponent {
  /** The chosen percent; null is Off, NaN an invalid custom entry. */
  readonly value = input<number | null>(null);
  readonly allowOff = input(true);
  readonly allowCustom = input(false);
  readonly label = input('Price protection');
  readonly valueChange = output<number | null>();

  readonly max = MAX_PROTECTION;
  private readonly customInput = viewChild<ElementRef<HTMLInputElement>>('customInput');
  format = formatProtection;

  /** Set once the client picks Custom, so it stays chosen while they type a preset's value. */
  private readonly customChosen = signal(false);
  private readonly typedText = signal<string | null>(null);

  /** The presets, plus a saved non-preset value when it can't be shown as Custom. */
  readonly options = computed(() => {
    const value = this.value();
    return !this.allowCustom() && value != null && !Number.isNaN(value) && !PROTECTION_PRESETS.includes(value)
      ? [...PROTECTION_PRESETS, value].sort((a, b) => a - b)
      : PROTECTION_PRESETS;
  });

  readonly selected = computed<number | 'off' | 'custom'>(() => {
    const value = this.value();
    if (this.customChosen() || (value != null && !this.options().includes(value))) {
      return 'custom';
    }
    return value == null ? 'off' : value;
  });

  readonly customText = computed(() => {
    const value = this.value();
    return this.typedText() ?? (value == null || Number.isNaN(value) ? '' : String(value));
  });

  /** Only once something is typed: a freshly opened, empty Custom isn't an error yet. */
  readonly customInvalid = computed(() => Number.isNaN(this.value()) && this.customText().trim() !== '');

  choose(value: number | null): void {
    this.customChosen.set(false);
    this.typedText.set(null);
    this.valueChange.emit(value);
  }

  chooseCustom(): void {
    this.customChosen.set(true);
    this.typedText.set(this.customText());
    this.valueChange.emit(parseProtection(this.customText()) ?? NaN);
    // The field only exists once Custom renders; put the caret in it so typing just works.
    setTimeout(() => this.customInput()?.nativeElement.focus());
  }

  typeCustom(text: string): void {
    this.typedText.set(text);
    // Custom needs a figure: an empty entry isn't Off.
    this.valueChange.emit(parseProtection(text) ?? NaN);
  }
}
