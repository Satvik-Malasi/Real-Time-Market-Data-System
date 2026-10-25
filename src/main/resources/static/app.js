// Live dashboard. Two ways data reaches this page:
//  1. Pushed: trades arrive over a STOMP WebSocket the moment the server gets them from Finnhub.
//  2. Pulled: summaries and history come from the REST API (/api/...) with fetch().

const LIVE_WINDOW_MS = 5 * 60 * 1000;   // the live chart shows the last 5 minutes
const CHART_REDRAW_MS = 250;            // redraw at most 4 times a second, however fast trades arrive
const REPORT_REFRESH_MS = 10_000;
const STATUS_REFRESH_MS = 5_000;

const $ = (id) => document.getElementById(id);
const priceFormat = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const volumeFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 });
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const dayTimeFormat = new Intl.DateTimeFormat(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit' });

const fmtPrice = (v) => (v == null ? '–' : priceFormat.format(v));
const fmtPct = (v, digits = 3) => (v == null ? '–' : `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`);

let symbol = null;
let livePoints = [];          // {x: timestamp, y: price}
let chartDirty = false;
let lastPrice = null;

// ---------- charts ----------

function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function lineChart(canvas, tickFormat) {
    return new Chart(canvas, {
        type: 'line',
        data: { datasets: [{ data: [], borderColor: cssVar('--accent'), borderWidth: 1.5, pointRadius: 0, tension: 0 }] },
        options: {
            animation: false,
            parsing: false,
            normalized: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: {
                    callbacks: {
                        title: (items) => tickFormat.format(items[0].parsed.x),
                        label: (item) => fmtPrice(item.parsed.y),
                    },
                },
            },
            scales: {
                x: { type: 'linear', ticks: { color: cssVar('--muted'), maxTicksLimit: 8, callback: (v) => tickFormat.format(v) },
                     grid: { color: cssVar('--border') } },
                y: { ticks: { color: cssVar('--muted'), callback: (v) => fmtPrice(v) }, grid: { color: cssVar('--border') } },
            },
        },
    });
}

const liveChart = lineChart($('live-chart'), timeFormat);
const historyChart = lineChart($('history-chart'), dayTimeFormat);

function redrawLiveChart() {
    if (!chartDirty) return;
    chartDirty = false;
    const now = Date.now();
    const cutoff = now - LIVE_WINDOW_MS;
    livePoints = livePoints.filter((p) => p.x >= cutoff);
    liveChart.data.datasets[0].data = livePoints;
    // Fixed 5-minute window that slides with the clock, instead of an axis that jumps to fit the data.
    liveChart.options.scales.x.min = cutoff;
    liveChart.options.scales.x.max = now;
    liveChart.update('none');

    const tenSecondsAgo = Date.now() - 10_000;
    const recent = livePoints.filter((p) => p.x >= tenSecondsAgo).length;
    $('rate').textContent = (recent / 10).toFixed(1);
}

// ---------- live trades over WebSocket (STOMP) ----------

function connectLiveFeed() {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new StompJs.Client({
        brokerURL: `${protocol}://${location.host}/ws`,
        reconnectDelay: 3000, // the browser side reconnects too, if the server restarts
        onConnect: () => {
            client.subscribe(`/topic/trades/${symbol}`, (message) => onTrades(JSON.parse(message.body)));
        },
        onWebSocketClose: () => setConnection(false, 'page disconnected'),
    });
    client.activate();
}

function onTrades(trades) {
    for (const t of trades) {
        livePoints.push({ x: t.timestamp, y: t.price });
    }
    const latest = trades[trades.length - 1];
    if (latest) {
        const el = $('last-price');
        el.textContent = fmtPrice(latest.price);
        el.className = 'big ' + (lastPrice == null || latest.price === lastPrice ? '' : latest.price > lastPrice ? 'up' : 'down');
        lastPrice = latest.price;
    }
    chartDirty = true;
}

// ---------- REST calls ----------

async function getJson(path) {
    const response = await fetch(path);
    const body = await response.json();
    if (!response.ok) throw new Error(body.detail || response.statusText);
    return body;
}

function setConnection(connected, text) {
    const pill = $('connection');
    pill.textContent = text;
    pill.className = 'pill ' + (connected ? 'pill-on' : 'pill-off');
}

async function refreshStatus() {
    try {
        const s = await getJson('api/status');
        $('received').textContent = s.tradesReceived.toLocaleString();
        if (!s.enabled) setConnection(false, 'stream disabled');
        else if (s.connected) setConnection(true, 'live');
        else setConnection(false, `reconnecting (attempt ${s.reconnectAttempts})`);
        return s;
    } catch (e) {
        setConnection(false, 'server unreachable');
        return null;
    }
}

async function refreshReport() {
    try {
        const r = await getJson(`api/report?symbol=${encodeURIComponent(symbol)}&minutes=15`);
        $('r-count').textContent = r.tradeCount.toLocaleString();
        $('r-volume').textContent = volumeFormat.format(r.totalVolume);
        const change = $('r-change');
        change.textContent = fmtPct(r.changePercent);
        change.className = r.changePercent == null ? '' : r.changePercent >= 0 ? 'up' : 'down';
        $('r-range').textContent = r.highPrice == null ? '–' : `${fmtPrice(r.highPrice)} / ${fmtPrice(r.lowPrice)}`;
        $('r-vwap').textContent = fmtPrice(r.vwap);
        $('r-sma').textContent = fmtPrice(r.movingAverage5m);
        $('r-vol').textContent = r.oneMinuteVolatilityPercent == null ? '–' : `${r.oneMinuteVolatilityPercent.toFixed(4)}%`;
    } catch (e) {
        console.warn('report failed', e);
    }
}

async function loadHistory() {
    try {
        const candles = await getJson(`api/history?symbol=${encodeURIComponent(symbol)}&interval=15m&limit=96`);
        const points = candles.map((c) => ({ x: c.openTime, y: c.close }));
        historyChart.data.datasets[0].data = points;
        if (points.length) {
            historyChart.options.scales.x.min = points[0].x;
            historyChart.options.scales.x.max = points[points.length - 1].x;
        }
        historyChart.update('none');
    } catch (e) {
        $('history-chart').replaceWith(Object.assign(document.createElement('p'), {
            className: 'muted', textContent: `Historical data unavailable: ${e.message}`,
        }));
    }
}

async function checkDataQuality() {
    const button = $('dq-button');
    button.disabled = true;
    try {
        const q = await getJson(`api/data-quality?symbol=${encodeURIComponent(symbol)}&minutes=15`);
        const cells = [
            ['Minutes compared', q.comparedMinutes],
            ['Mean close difference', q.meanAbsCloseDiffPercent == null ? '–' : `${q.meanAbsCloseDiffPercent.toFixed(4)}%`],
            ['Worst close difference', q.maxAbsCloseDiffPercent == null ? '–' : `${q.maxAbsCloseDiffPercent.toFixed(4)}%`],
            ['Volume coverage', q.volumeCoveragePercent == null ? '–' : `${q.volumeCoveragePercent.toFixed(1)}%`],
        ];
        $('dq-result').replaceChildren(...cells.map(([label, value]) => {
            const box = document.createElement('div');
            const l = Object.assign(document.createElement('div'), { className: 'label', textContent: label });
            const v = Object.assign(document.createElement('div'), { textContent: value });
            box.append(l, v);
            return box;
        }));
        $('dq-note').textContent = q.note;
    } catch (e) {
        $('dq-note').textContent = `Check failed: ${e.message}`;
    } finally {
        button.disabled = false;
    }
}

// ---------- start ----------

async function start() {
    const status = await refreshStatus();
    symbol = status?.symbols?.[0] ?? 'BINANCE:BTCUSDT';
    $('symbol').textContent = symbol;
    $('csv-link').href = `api/candles.csv?symbol=${encodeURIComponent(symbol)}&minutes=60`;
    $('dq-button').addEventListener('click', checkDataQuality);

    connectLiveFeed();
    refreshReport();
    loadHistory();

    setInterval(redrawLiveChart, CHART_REDRAW_MS);
    setInterval(() => { chartDirty = true; }, 1000); // keeps the window sliding when no trades arrive
    setInterval(refreshReport, REPORT_REFRESH_MS);
    setInterval(refreshStatus, STATUS_REFRESH_MS);
}

start();
