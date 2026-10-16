/**
 * An SVG path drawing a series as a line across a box: the first value at the left edge, the last
 * at the right, the lowest at the bottom and the highest at the top. A flat series runs along the
 * middle; a single value draws a flat line; no values draw nothing.
 * @param values the series, oldest first
 * @param x0 the box's left edge
 * @param y0 the box's top edge
 * @param width the box's width
 * @param height the box's height
 * @returns the path's d attribute, or '' with no values
 */
export function linePath(values: readonly number[], x0: number, y0: number, width: number, height: number): string {
  if (values.length === 0) {
    return '';
  }
  const series = values.length === 1 ? [values[0], values[0]] : values;
  const low = Math.min(...series);
  const high = Math.max(...series);
  const span = high - low;
  const step = width / (series.length - 1);
  return series.map((value, i) => {
    const x = x0 + i * step;
    const y = span === 0 ? y0 + height / 2 : y0 + height - ((value - low) / span) * height;
    return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
  }).join('');
}

/**
 * The same line closed down to a baseline, for a filled area under it.
 * @param line a path from linePath
 * @param x0 the box's left edge
 * @param width the box's width
 * @param baseline the y the area closes along
 * @returns the area's d attribute, or '' for an empty line
 */
export function areaPath(line: string, x0: number, width: number, baseline: number): string {
  return line ? `${line}L${(x0 + width).toFixed(1)},${baseline}L${x0.toFixed(1)},${baseline}Z` : '';
}
