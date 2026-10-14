import { parseSseFrame } from './sse-frame';

describe('parseSseFrame', () => {
  it('extracts a named event and payload', () => {
    expect(parseSseFrame('event: price\ndata: {"price":100}')).toEqual({
      event: 'price', data: '{"price":100}'
    });
  });
  it('supports CRLF and multiline payloads', () => {
    expect(parseSseFrame('event: order\r\ndata: {\r\ndata: "status":"FILLED"}')).toEqual({
      event: 'order', data: '{\n"status":"FILLED"}'
    });
  });
  it('defaults to message for unnamed events', () => {
    expect(parseSseFrame('data: hello')).toEqual({ event: 'message', data: 'hello' });
  });
  it('ignores comments and unused fields around a payload', () => {
    expect(parseSseFrame(': heartbeat\nid: 42\nretry: 1000\nevent: order\ndata: {}')).toEqual({
      event: 'order', data: '{}'
    });
  });
  it('ignores frames without data', () => {
    expect(parseSseFrame(': heartbeat')).toBeNull();
    expect(parseSseFrame('event: price')).toBeNull();
    expect(parseSseFrame('')).toBeNull();
  });
  it('preserves an explicitly empty data field', () => {
    expect(parseSseFrame('event: order\ndata:')).toEqual({ event: 'order', data: '' });
  });
});
