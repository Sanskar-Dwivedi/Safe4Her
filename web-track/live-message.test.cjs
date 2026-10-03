const { test } = require('node:test');
const assert = require('node:assert/strict');
const parse = require('./live-message.js');
const now = Date.parse('2026-10-03T12:00:00Z');
const legacy = { event: 'message', time: now / 1000, click: 'https://maps.google.com/?q=28.6,77.2', message: 'accurate to 15 m' };
test('Android Google links and Apple encoded ll links share coordinates', () => {
  assert.deepEqual(parse(legacy, now).point, [28.6, 77.2]);
  assert.deepEqual(parse({ ...legacy, click: 'https://maps.apple.com/?ll=28.6%2C77.2' }, now).point, [28.6, 77.2]);
});
test('iPhone uses measurement time even when relay publication is new', () => {
  const fixAt = '2026-10-03T11:55:00Z';
  const result = parse({ ...legacy, message: JSON.stringify({ version: 1, state: 'active', lat: 28.6, lng: 77.2, accuracyM: 8, fixAt }) }, now);
  assert.equal(result.at, Date.parse(fixAt)); assert.equal(result.accuracy, 8);
});
test('ended shares need no coordinates, and malformed/future fixes are rejected', () => {
  assert.deepEqual(parse({ event: 'message', message: '{"version":1,"state":"ended"}' }, now), { ended: true });
  assert.deepEqual(parse({ ...legacy, tags: ['white_check_mark'] }, now), { ended: true });
  assert.equal(parse({ ...legacy, click: 'https://maps.google.com/?q=190,77' }, now), null);
  assert.equal(parse({ ...legacy, time: now / 1000 + 60 }, now), null);
  assert.equal(parse({ ...legacy, message: '{"version":1,"state":"active","lat":"28","lng":77,"fixAt":"invalid"}' }, now), null);
});
