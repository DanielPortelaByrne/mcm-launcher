/**
 * MCM Home usage log: receives batches of events from the parents' Fire TV and writes them to this Sheet.
 *
 * Setup (once): in this Sheet, Extensions -> Apps Script, paste this file, set KEY below to the same secret
 * the TV is given, then Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone ->
 * Deploy, and copy the web app URL. Run `setup` once from the editor to create the tabs.
 *
 * Tabs: "Events" (one row per thing that happened, newest at the bottom), "Days" (per day: how long Home was
 * up, how many things were opened, which sections were reached) and "Opened" (what was opened, most first).
 */
const KEY = 'CHANGE-ME';            // the same value as "logKey" on the TV
const TZ = 'Europe/Dublin';
const HEADERS = ['Time', 'Event', 'Where', 'What', 'App', 'Seconds', 'Detail', 'Day'];

function doPost(e) {
  let body;
  try { body = JSON.parse(e.postData.contents); } catch (err) { return reply({ ok: false, error: 'bad json' }); }
  if (body.key !== KEY) return reply({ ok: false, error: 'bad key' });
  const sheet = events_();
  const rows = (body.events || []).map((ev) => {
    const when = new Date(ev.t);
    return [
      Utilities.formatDate(when, TZ, 'yyyy-MM-dd HH:mm:ss'), ev.type || '', ev.where || '', ev.what || '',
      ev.app || '', ev.seconds == null ? '' : ev.seconds, ev.detail || '', Utilities.formatDate(when, TZ, 'yyyy-MM-dd'),
    ];
  });
  if (rows.length) {
    const lock = LockService.getScriptLock(); lock.waitLock(20000);
    try { sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADERS.length).setValues(rows); } finally { lock.releaseLock(); }
  }
  return reply({ ok: true, received: rows.length });
}

/** A quick check from a browser: the web app URL shows the number of rows logged so far. */
function doGet() { return reply({ ok: true, rows: Math.max(0, events_().getLastRow() - 1) }); }

function setup() {
  events_();
  const ss = SpreadsheetApp.getActive();
  const days = ss.getSheetByName('Days') || ss.insertSheet('Days');
  days.clear();
  days.getRange('A1').setFormula(
    '=QUERY(Events!A:H, "select H, count(A), sum(F) where B=\'home_shown\' or B=\'home_left\' group by H order by H desc label H \'Day\', count(A) \'Home visits\', sum(F) \'Seconds on Home\'", 1)');
  days.getRange('E1').setFormula(
    '=QUERY(Events!A:H, "select H, count(A) where B=\'open\' group by H order by H desc label H \'Day\', count(A) \'Things opened\'", 1)');
  days.getRange('H1').setFormula(
    '=QUERY(Events!A:H, "select H, C, count(A) where B=\'section_seen\' group by H, C order by H desc label H \'Day\', C \'Section reached\', count(A) \'Times\'", 1)');
  const opened = ss.getSheetByName('Opened') || ss.insertSheet('Opened');
  opened.clear();
  opened.getRange('A1').setFormula(
    '=QUERY(Events!A:H, "select C, D, count(A), max(A) where B=\'open\' group by C, D order by count(A) desc label C \'Where\', D \'What\', count(A) \'Times\', max(A) \'Last\'", 1)');
  opened.getRange('F1').setFormula(
    '=QUERY(Events!A:H, "select E, D, count(A), sum(F) where B=\'playing\' group by E, D order by sum(F) desc label E \'App\', D \'Playing\', count(A) \'Times\', sum(F) \'Seconds\'", 1)');
}

function events_() {
  const ss = SpreadsheetApp.getActive();
  let sheet = ss.getSheetByName('Events');
  if (!sheet) {
    sheet = ss.insertSheet('Events', 0);
    sheet.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]).setFontWeight('bold');
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function reply(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
