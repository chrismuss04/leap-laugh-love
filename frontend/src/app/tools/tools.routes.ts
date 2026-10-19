import { CanMatchFn, Routes } from '@angular/router';
import { findTool } from './tools-catalog';

/** Matches /tools/:tool only for a tool the hub lists. */
const knownTool: CanMatchFn = (_route, segments) => findTool(segments[0]?.path) !== null;

/** Trading Tools: the hub, then each tool; anything else under /tools goes back to the hub. */
export const TOOL_ROUTES: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./tools-hub').then(m => m.ToolsHubComponent) },
  { path: ':tool', canMatch: [knownTool], loadComponent: () => import('./tool-page').then(m => m.ToolPageComponent) },
  { path: '**', redirectTo: '' }
];
