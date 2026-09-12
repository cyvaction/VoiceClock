// Run: node voiceclock_demo.test.cjs (no dependencies or browser required).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, 'voiceclock_demo.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script); // Compile the complete script without executing DOM bindings.
const pure = script.split('// UI bindings')[0];
const api = vm.runInNewContext(pure + '\n({TIME_SLOTS,reportSpeech,batterySpeech,validStarts,seasonFor,rangeText,validSleep,nextAnnouncement})');

assert.equal(api.TIME_SLOTS.length, 48);
assert.equal(new Set(api.TIME_SLOTS).size, 48);
assert.equal(api.TIME_SLOTS[0], '00:00');
assert.equal(api.TIME_SLOTS[47], '23:30');
assert.equal(new Set(api.TIME_SLOTS.map((_, i) => api.reportSpeech(i))).size, 48);
for (const [slot, text] of [[0, '现在是上午十二点整'], [1, '现在是上午十二点半'], [14, '现在是上午七点整'], [24, '现在是下午十二点整'], [35, '现在是下午五点半'], [47, '现在是下午十一点半']]) {
  assert.equal(api.reportSpeech(slot), text);
}
assert.equal(api.batterySpeech(), '手机没电，请及时充电');

const starts = {summer:'06-01', winter:'12-01'};
assert.ok(api.validStarts(starts));
for (const invalid of ['02-29', '04-31', '00-01', '13-01', '01-00', '6-01', '', '12-01']) {
  assert.equal(api.validStarts({...starts, summer:invalid}), false, invalid);
}
for (const year of [2027, 2028]) {
  for (let day = new Date(Date.UTC(year, 0, 1)); day.getUTCFullYear() === year; day.setUTCDate(day.getUTCDate() + 1)) {
    const md = day.toISOString().slice(5, 10);
    const expected = day.getUTCMonth() >= 5 && day.getUTCMonth() < 11 ? 'summer' : 'winter';
    assert.equal(api.seasonFor(md, starts), expected, md);
  }
}
const reversed = {summer:'12-01', winter:'06-01'};
assert.equal(api.seasonFor('01-01', reversed), 'summer');
assert.equal(api.seasonFor('06-01', reversed), 'winter');
assert.equal(api.rangeText('summer', starts), '06月01日 – 11月30日');
assert.equal(api.rangeText('winter', starts), '12月01日 – 次年05月31日');
assert.equal(api.rangeText('winter', {summer:'03-01', winter:'12-01'}), '12月01日 – 次年02月末');

const schedules = {summer:new Set([0, 35]), winter:new Set([0])};
for (const [now, expected, season] of [
  ['2026-05-31T23:59:00+08:00', '2026-06-01T00:00:00+08:00', 'summer'],
  ['2026-06-01T00:00:00+08:00', '2026-06-01T17:30:00+08:00', 'summer'],
  ['2026-11-30T23:59:00+08:00', '2026-12-01T00:00:00+08:00', 'winter'],
  ['2026-12-31T23:59:00+08:00', '2027-01-01T00:00:00+08:00', 'winter'],
  ['2028-02-28T23:59:00+08:00', '2028-02-29T00:00:00+08:00', 'winter']
]) {
  const result = api.nextAnnouncement(new Date(now), starts, schedules);
  assert.equal(result.time, new Date(expected).getTime());
  assert.equal(result.season, season);
}
assert.equal(api.nextAnnouncement(new Date(), starts, {summer:new Set(), winter:new Set()}), null);
assert.equal(api.nextAnnouncement(new Date('2026-06-01T00:00:00+08:00'), starts, {summer:new Set(), winter:new Set([0])}).time, new Date('2026-12-01T00:00:00+08:00').getTime());
assert.ok(api.validSleep('22:00', '07:00'));
assert.ok(api.validSleep('13:30', '15:00'));
for (const [start, end] of [['22:00', '22:00'], ['22:15', '07:00'], ['', '07:00'], ['24:00', '07:00'], ['22:00', '07:01']]) assert.equal(api.validSleep(start, end), false);

const interval = html.match(/<select id="intervalMinutes"[^>]*>([\s\S]*?)<\/select>/)[1];
assert.deepEqual([...interval.matchAll(/value="(\d+)"/g)].map(match => Number(match[1])), [5, 15, 30, 60]);
assert.match(interval, /value="15" selected/);
assert.match(html, /id="threshold"[^>]*min="5" max="99" step="1" value="25"/);
assert.match(html, /<option value="10">10 次<\/option>/);
for (const name of ['primary', 'primary2']) {
  const hex = html.match(new RegExp('--' + name + ':#([0-9a-f]{6})'))[1];
  const rgb = hex.match(/../g).map(value => parseInt(value, 16) / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  const contrast = 1.05 / (rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722 + 0.05);
  assert.ok(contrast >= 4.5, name + ': ' + contrast);
}
console.log('PASS: script syntax, 48 slots, fixed battery phrase, seasons/leap dates, next announcement, sleep validation, interval defaults and primary contrast.');
