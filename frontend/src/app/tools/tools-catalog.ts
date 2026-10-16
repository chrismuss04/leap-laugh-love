/** Trading Tools: the tools the hub lists, in the order it shows them. */
export type ToolId = 'rebalance' | 'raise-cash' | 'basket' | 'draw';

export interface ToolInfo {
  id: ToolId;
  name: string;
  /** One line under the name on the tile and the tool's header. */
  blurb: string;
  /** The short tag its orders carry in the trade plan tray. */
  planLabel: string;
}

export const TOOLS: readonly ToolInfo[] = [
  { id: 'rebalance', name: 'Rebalance Donut', blurb: 'Reshape your allocation and see the trades it takes.', planLabel: 'Rebalance' },
  { id: 'raise-cash', name: 'Raise Cash', blurb: 'Pick an amount and get the sells that free it up.', planLabel: 'Raise Cash' },
  { id: 'basket', name: 'Basket Builder', blurb: 'Group several orders and place them together.', planLabel: 'Basket' },
  { id: 'draw', name: 'Draw Your Trade', blurb: 'Sketch where a price is going and turn it into orders.', planLabel: 'Draw' }
];

export function findTool(id: string | null | undefined): ToolInfo | null {
  return TOOLS.find(tool => tool.id === id) ?? null;
}

/** The query parameter naming the account every tool acts on. */
export const ACCOUNT_PARAM = 'account';
