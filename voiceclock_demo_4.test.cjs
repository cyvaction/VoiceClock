// Run: node voiceclock_demo_4.test.cjs
// DOM and audio are test doubles; this checks behavior, not browser rendering or sound output.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, 'voiceclock_demo_4.html'), 'utf8');
const source = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const assetText = html.match(/<script[^>]*id="audioAssets"[^>]*>([\s\S]*?)<\/script>/)[1];
const assets = JSON.parse(assetText);
const ids = new Map(), elements = [], stored = new Map();
let focused, played;

class Element {
  constructor(tag, attrs = {}) {
    this.tag = tag; this.attrs = attrs; this.children = []; this.events = {};
    this.dataset = new Proxy({}, { set: (data, key, value) => { data[key] = String(value); this.attrs['data-' + key] = String(value); return true; } });
    this._text = ''; this._value = attrs.value || ''; this.hidden = 'hidden' in attrs;
    this.checked = 'checked' in attrs; this.disabled = 'disabled' in attrs;
    this.classList = { add() {}, remove() {}, toggle() {} };
    for (const [key, value] of Object.entries(attrs)) if (key.startsWith('data-')) this.dataset[key.slice(5)] = value;
    if (attrs.id) { assert(!ids.has(attrs.id), `Duplicate ID: ${attrs.id}`); ids.set(attrs.id, this); }
    elements.push(this);
  }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set value(value) { this._value = String(value); }
  get value() { return this._value; }
  append(...children) {
    for (const child of children) {
      child.parent = this; this.children.push(child);
      if (this.tag === 'select' && (!this.value || 'selected' in child.attrs)) this.value = child.value;
    }
  }
  replaceChildren(...children) { this._text = ''; this.children = []; this.append(...children); }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  getAttribute(name) { return this.attrs[name]; }
  removeAttribute(name) { delete this.attrs[name]; }
  addEventListener(name, action) { this.events[name] = action; }
  closest(tag) { for (let node = this; node; node = node.parent) if (node.tag === tag) return node; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  focus() { focused = this; }
}
const stack = [];
const markup = html.replace(/<script\b[\s\S]*?<\/script>/g, '').replace(/<style>[\s\S]*?<\/style>/g, '');
for (const match of markup.matchAll(/<(\/?)([\w-]+)\b([^>]*)>/g)) {
  const [, closing, tag, raw] = match;
  if (closing) { if (stack.at(-1)?.tag === tag) stack.pop(); continue; }
  const attrs = {};
  for (const attr of raw.matchAll(/([\w-]+)(?:="([^"]*)")?/g)) attrs[attr[1]] = attr[2] || '';
  const node = new Element(tag, attrs); stack.at(-1)?.append(node);
  if (!['meta', 'input', 'br', 'hr', 'link'].includes(tag) && !raw.endsWith('/')) stack.push(node);
}
ids.set('audioAssets', { textContent: assetText });
function $(id) { assert(ids.has(id), `Missing element: ${id}`); return ids.get(id); }
function query(selector) {
  const data = selector.match(/^\[(data-[\w-]+)\]$/);
  return elements.filter(el => data ? data[1] in el.attrs : selector === 'details.more' ? el.tag === 'details' && el.attrs.class === 'more' : el.tag === selector);
}
class AudioDouble {
  constructor() { played = this; }
  play() { return Promise.resolve(); }
  pause() { this.paused = true; }
  load() {}
  removeAttribute(key) { delete this[key]; }
}
const context = vm.createContext({
  document: { getElementById: $, querySelectorAll: query, createElement: tag => new Element(tag), createTextNode: text => ({ textContent: text, attrs: {} }) },
  Option: function(text, value) { const option = new Element('option', { value }); option.textContent = text; return option; },
  Audio: AudioDouble,
  localStorage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value) },
  setTimeout() { return 1; }, clearTimeout() {}, setInterval() { return 1; }, console,
});
const run = code => vm.runInContext(code, context);
vm.runInContext(source, context);
const button = (attribute, value) => elements.find(el => el.attrs[attribute] === value);
const click = (attribute, value) => { const el = button(attribute, value); assert(el); el.onclick({ target: el }); };
const change = (id, value, event = 'change') => { const el = $(id); el.value = value; (el.events[event] || el['on' + event])({ target: el }); };
const submit = id => $(id).onsubmit({ preventDefault() {} });
let checks = 0;
function check(name, action) { action(); checks++; console.log('PASS ' + name); }

check('All controls and accessibility references resolve; audio remains self-contained', () => {
  for (const match of source.matchAll(/(?<!\$)\$\('([\w-]+)'\)/g)) $(match[1]);
  for (const el of elements) for (const key of ['for', 'aria-labelledby', 'aria-describedby', 'aria-controls'])
    for (const id of (el.attrs[key] || '').split(' ').filter(Boolean)) $(id);
  assert.equal(Object.keys(assets).length, 50);
  assert(!/<(?:script|link|img)\b[^>]*(?:src|href)="https?:/i.test(html));
  const v3Path = path.join(__dirname, 'voiceclock_demo_3.html');
  if (fs.existsSync(v3Path)) {
    const original = fs.readFileSync(v3Path, 'utf8');
    assert.equal(assetText, original.match(/<script[^>]*id="audioAssets"[^>]*>([\s\S]*?)<\/script>/)[1]);
  }
});
check('Independent seasonal schedules, 48 toggles, presets, and persistence', () => {
  assert.equal(elements.filter(el => el.dataset.slot !== undefined).length, 48);
  click('data-season', 'summer'); run('state.schedules.summer.delete(1);render()');
  const winter = run('serialize(state)');
  elements.find(el => el.dataset.slot === '1').onclick();
  assert(run('state.schedules.summer.has(1)'));
  assert.equal(button('data-slot', '1').getAttribute('aria-pressed'), 'true');
  assert.deepEqual(JSON.parse(run('serialize(state)')).schedules.winter, JSON.parse(winter).schedules.winter);
  assert(JSON.parse(stored.get('voiceclock-demo-v4')).schedules.summer.includes(1));
  click('data-preset', 'hour'); assert.equal(run('state.schedules.summer.size'), 24);
  click('data-preset', 'half'); assert.equal(run('state.schedules.summer.size'), 29);
  click('data-preset', 'clear'); click('data-season', 'winter'); click('data-preset', 'clear');
  assert.equal($('annSwitch').disabled, true); assert.equal($('listenNext').disabled, true);
  assert.equal(run('nextAnnouncement(new Date(),state.starts,state.schedules)'), null);
  click('data-preset', 'common'); assert.equal($('annSwitch').disabled, false);
  run('state=defaultState();syncSleep();render()');
});
check('Configuration uses all 48 unique 24-hour times while speech stays 12-hour', () => {
  const times = Array.from({ length: 48 }, (_, slot) => String(Math.floor(slot / 2)).padStart(2, '0') + (slot % 2 ? ':30' : ':00'));
  assert.deepEqual($('morningSlots').children.map(el => el.textContent), times.slice(0, 24));
  assert.deepEqual($('afternoonSlots').children.map(el => el.textContent), times.slice(24));
  for (const id of ['sleepStart', 'sleepEnd']) {
    assert.deepEqual($(id).children.map(el => el.textContent), times);
    assert.deepEqual($(id).children.map(el => el.value), times);
  }
  assert.equal(button('data-slot', '0').getAttribute('aria-label'), '上午 00:00');
  assert.equal(button('data-slot', '24').getAttribute('aria-label'), '下午 12:00');
  button('data-slot', '35').onclick();
  assert.equal($('previewSpeech').textContent, '现在是下午五点半');
  assert.equal(run('reportSpeech(0)'), '现在是上午十二点整');
  assert.equal(run('reportSpeech(24)'), '现在是下午十二点整');
  run('state=defaultState();syncSleep();render()');
});
check('Tabs, keyboard navigation, period selection, and summary shortcuts', () => {
  click('data-panel', 'battery'); assert.equal($('panel-battery').hidden, false); assert.equal($('panel-schedule').hidden, true);
  assert.equal($('tab-battery').getAttribute('aria-selected'), 'true');
  button('data-panel', 'battery').onkeydown({ key: 'ArrowRight', preventDefault() {} });
  assert.equal($('panel-sleep').hidden, false); assert.equal(focused, $('tab-sleep'));
  click('data-go', 'battery'); assert.equal(focused, $('tab-battery'));
  click('data-panel', 'schedule'); click('data-period', '1');
  assert.equal($('afternoonSlots').hidden, false); assert.equal($('morningSlots').hidden, true);
});
check('Month/day selectors constrain valid dates and validate equal starts', () => {
  run("openDialog('seasonDialog')");
  assert.equal($('summerMonth').value, '06'); assert.equal($('winterMonth').value, '12');
  change('summerMonth', '01'); $('summerDay').value = '31'; change('summerMonth', '02');
  assert.equal($('summerDay').children.length, 28); assert.equal($('summerDay').value, '28');
  change('winterMonth', '02'); $('winterDay').value = '28'; submit('seasonForm');
  assert($('seasonError').textContent); assert.equal($('seasonDialog').open, true);
  change('winterMonth', '12'); $('winterDay').value = '01'; submit('seasonForm');
  assert.equal(run('state.starts.summer'), '02-28'); assert.equal($('seasonDialog').open, false);
  assert.equal(run("seasonFor('12-01',state.starts)"), 'winter');
});
check('Battery threshold, repeat settings, sleep validation, and disabled state', () => {
  change('threshold', '30', 'input'); assert.equal(run('state.threshold'), 30);
  change('maxReminders', '5'); change('intervalMinutes', '30');
  assert.equal(run('state.maxReminders'), 5); assert.equal(run('state.intervalMinutes'), 30);
  change('sleepStart', '07:00'); assert($('sleepError').textContent);
  assert.equal(run('state.sleepStart'), '22:00');
  change('sleepEnd', '08:00'); assert.equal(run('state.sleepStart'), '07:00');
  assert.equal($('sleepError').textContent, '');
  $('sleepSwitch').checked = false; $('sleepSwitch').onchange({ target: $('sleepSwitch') });
  assert.equal($('sleepFields').disabled, true); assert.equal($('sleepLimit').textContent, '按次数设置');
});
check('Required reminder rules: strict threshold, interval, midnight sleep, charging', () => {
  assert.equal(run("reportSpeech(14)"), '现在是上午七点整');
  assert.equal(run("reportSpeech(35)"), '现在是下午五点半');
  run('state=defaultState();round={count:0,last:null,sleepKeys:new Set()}');
  assert.equal(run("batteryDecision(new Date('2026-09-21T12:00:00+08:00'),25,false,state,round).remind"), false);
  assert.equal(run("batteryDecision(new Date('2026-09-21T12:00:00+08:00'),24,false,state,round).remind"), true);
  run("round.count=1;round.last=new Date('2026-09-21T12:00:00+08:00').getTime()");
  assert.equal(run("batteryDecision(new Date('2026-09-21T12:14:00+08:00'),24,false,state,round).remind"), false);
  assert.equal(run("batteryDecision(new Date('2026-09-21T12:15:00+08:00'),24,false,state,round).remind"), true);
  run("round.sleepKeys.add(sleepWindow(new Date('2026-09-21T23:00:00+08:00'),state))");
  assert.equal(run("batteryDecision(new Date('2026-09-22T01:00:00+08:00'),24,false,state,round).remind"), false);
  assert.equal(run("batteryDecision(new Date('2026-09-22T01:00:00+08:00'),24,true,state,round).reset"), true);
});
check('Scenario controls, reset confirmation, and audio sequencing', () => {
  click('data-open', 'simDialog'); assert.equal($('simDialog').open, true);
  run('resetSimulation()');
  $('simDate').value = '2026-09-21'; $('simTime').value = '12:00'; $('simBattery').value = '24';
  $('simCheck').onclick(); assert.equal(run('round.count'), 1);
  $('simAdvance').onclick(); assert.equal(run('round.count'), 2); assert.equal($('simTime').value, '12:15');
  run('playSlot(35)');
  assert.equal(played.src, 'data:' + assets.chime.type + ';base64,' + assets.chime.data);
  assert.equal(played.playbackRate, 1); played.onended();
  assert.equal(played.src, 'data:' + assets.announcement_1730.type + ';base64,' + assets.announcement_1730.data);
  assert.equal(played.playbackRate, .82); assert.equal(played.preservesPitch, true);
  played.onended(); assert.equal($('audioNow').hidden, true);
  $('confirmReset').onclick(); assert.equal(run('state.threshold'), 25); assert.equal(run('state.schedules.summer.size'), 12);
});
console.log(`${checks} checks passed. Browser layout and actual audio output require a browser preview.`);
