"use strict";

const app = document.querySelector("#app");
const dialog = document.querySelector("#confirm");
const roles = {passenger:"Жолаушы", operator:"Оператор", admin:"Әкімші"};
const flightStates = ["Уақытылы","Кешігуде","Отырғызу","Тоқтатылды"];
const ticketStates = ["Жаңа","Қаралып жатыр","Шешілді"];
const ratingLabels = ["", "Өте нашар", "Нашар", "Орташа", "Жақсы", "Керемет"];
const tasks = ["Ұшу құжаттарын дайындадым","Багаж шарттарын тексердім","Әуежайға баратын жолды жоспарладым","Талонды сақтап қойдым"];
const airportName = "Нұрсұлтан Назарбаев халықаралық әуежайы";
const airportAddress = "Астана, Қабанбай батыр даңғылы, 119";
const official = "https://www.nn-airport.kz/flights";
const liveMap = "https://www.flightradar24.com/48.5,67.5/5";
const mediaPath = "/static/media/";
const clips = {
  travel: {file:"travel-story", title:"Саяхат картасы", time:"0:19",
    summary:"Әлем картасындағы шағын ұшақ пен түрлі қалалардың көрікті жерлері."},
  flight: {file:"flight-film", title:"Ұшуға дейінгі жұмыс", time:"2:59",
    summary:"Тіркеу, багажды тиеу және ұшақ экипажының рейске дайындалуы."},
  orbit: {file:"orbit", title:"Глобус үстіндегі ұшу", time:"0:03",
    summary:"Көгілдір глобус үстіндегі ұшақ пен бұлттар анимациясы."},
};
const icons = {
  plane:"M22 2 9 15m13-13-7 20-4-9-9-4 20-7Z",
  route:"M2 12h19m-5-5 5 5-5 5M7 7l5 5-5 5",
  bell:"M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-8 12a2 2 0 0 0 4 0",
  board:"M3 5h18v14H3zM3 10h18m-13 4h3m3 0h3",
  trip:"M3 5h18v4a3 3 0 0 0 0 6v4H3v-4a3 3 0 0 0 0-6zM8 7v10m3-7h7m-7 4h5",
  profile:"M12 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8M4 21v-2a5 5 0 0 1 5-5h6a5 5 0 0 1 5 5v2",
  chat:"M3 5h18v12H9l-6 4zm4 4h10M7 13h7",
  saved:"M5 4h14v17l-7-5-7 5z",
  sun:"M12 2v2m7 1-1 2m4 5h-2M2 12h2m1-7 2 2m1 7a4 4 0 1 1 8 0m-12 4h16m-12 3h8",
  map:"M12 22s7-6 7-13a7 7 0 0 0-14 0c0 7 7 13 7 13zm0-16a3 3 0 1 0 0 6 3 3 0 1 0 0-6",
  guide:"M5 6h14v15H5zm4 0V3h6v3m-7 7 3 3 5-5",
  play:"M8 5v14l12-7Z",
  film:"M3 5h18v14H3zM7 5v14m10-14v14M3 10h4m-4 4h4m10-4h4m-4 4h4",
};
let user = null;
let routeVersion = 0;
let dashboard = {};
let watchedView = null;
let repainting = false;
let activeWrites = 0;
let writeRevision = 0;
let toastTimer;
const $ = (selector) => app.querySelector(selector);
const enc = encodeURIComponent;
const esc = (value = "") => String(value ?? "").replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]));
const ice = (name) => '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true"><path d="' + icons[name] + '"/></svg>';
const link = (to, label, style = "") => '<a class="btn ' + style + '" href="#' + esc(to) + '">' + label + '</a>';
const button = (action, label, value = "", style = "") => '<button class="btn ' + style + '" type="button" data-action="' + esc(action) + '" data-value="' + esc(value) + '">' + label + '</button>';
const external = (url, label) => '<a class="btn" target="_blank" rel="noopener" href="' + esc(url) + '">' + label + '</a>';
const liveMapLink = () => external(liveMap, ice("plane") + " Қазір әуеде ↗");
const blank = (text) => '<div class="card neutral">' + esc(text) + '</div>';
const clipRoute = (key) => key === "travel" ? "media" : "media?clip=" + key;
const orbitArt = (alt = "") => '<picture class="orbit-art"><source type="image/webp" srcset="' + mediaPath + 'orbit.webp">'
  + '<img src="' + mediaPath + 'orbit.gif" width="400" height="300" alt="' + esc(alt) + '"></picture>';
const globe = () => '<div class="feature-globe"><div class="globe-frame">' + orbitArt() + '</div></div>';
const mobileDate = (ms, dateOnly = false) => new Date(ms + 18_000_000).toISOString().slice(0, dateOnly ? 10 : 16);
const stamp = (ms) => {const t = mobileDate(ms); return t.slice(8,10) + "." + t.slice(5,7) + " " + t.slice(11);};
const upcoming = row => !row.cancelled && row.status !== 3 && row.departure > Date.now();
const countTasks = (mask) => tasks.filter((_, i) => mask & (1 << i)).length;
const status = (index) => '<span class="badge ' + (index === 1 ? "alert" : index === 3 ? "cancelled" : index === 2 ? "ok" : "") + '">' + flightStates[index] + '</span>';
const href = (path, values) => path + "?" + new URLSearchParams(values).toString();
const title = (name, back = "home") => '<header class="top"><a class="icon-btn" aria-label="Артқа" href="#' + esc(back) + '">‹</a><h1 class="heading">' + esc(name) + '</h1></header>';
const rowLink = (to, inner) => '<a class="item" href="#' + esc(to) + '">' + inner + '</a>';
const rowButton = (action, value, inner) => '<button type="button" class="item" data-action="' + esc(action) + '" data-value="' + esc(value) + '">' + inner + '</button>';

async function api(path, method = "GET", payload) {
  const options = {method, credentials:"same-origin"};
  if (method !== "GET") {
    options.headers = {"Content-Type":"application/json"};
    options.body = JSON.stringify(payload ?? {});
    activeWrites++;
    writeRevision++;
  }
  try {
    const response = await fetch("/api/" + path, options);
    const result = await response.json();
    if (!response.ok) {
      const error = new Error(typeof result.detail === "string" ? result.detail : "Енгізілген деректерді тексеріңіз.");
      error.status = response.status;
      throw error;
    }
    return result;
  } finally {
    if (method !== "GET") activeWrites--;
  }
}

function showMessage(text) {
  const node = document.querySelector("#message");
  window.clearTimeout(toastTimer);
  node.textContent = text;
  toastTimer = window.setTimeout(() => {node.textContent = "";}, 3300);
}

function sessionExpired(error) {
  if (error.status !== 401 || !user) return false;
  user = null;
  dashboard = {};
  go("login");
  return true;
}

function nav(active) {
  const items = [
    ["home","board","Басты бет"],
    [user.role === "passenger" ? "trips" : "flights", "trip", user.role === "passenger" ? "Сапарлар" : "Рейстер"],
    ["tickets","chat","Хабарлар" + (dashboard.unread ? " · " + dashboard.unread : "")],
    ["profile","profile","Профиль"],
  ];
  return '<nav class="nav" aria-label="Бөлімдер">' + items.map(([page, icon, text]) =>
    '<a class="tab" href="#' + page + '"' + (active === page ? ' aria-current="page"' : "") + '>' + ice(icon) + esc(text) + '</a>').join("") + '</nav>';
}

function page(content, active, layout = "") {
  app.className = layout;
  const markup = content + (user ? nav(active) : "");
  if (!repainting) {
    app.innerHTML = markup;
    return;
  }
  const next = document.createElement("template");
  next.innerHTML = markup;
  updateChildren(app, next.content);
}

// Keep the actual controls in the document so a remote change cannot interrupt
// the keyboard, composition, selection, or a review that has not been submitted.
function updateChildren(parent, next) {
  for (let i = 0; i < next.childNodes.length; i++) {
    const before = parent.childNodes[i];
    const after = next.childNodes[i];
    if (!before) {
      parent.append(after.cloneNode(true));
    } else if (before.nodeName !== after.nodeName || before.namespaceURI !== after.namespaceURI) {
      before.replaceWith(after.cloneNode(true));
    } else if (before.nodeType === Node.TEXT_NODE) {
      if (before.data !== after.data) before.data = after.data;
    } else if (before.nodeType === Node.ELEMENT_NODE) {
      for (const name of before.getAttributeNames()) {
        if (!after.hasAttribute(name) && !(name === "open" && before.tagName === "DETAILS")) before.removeAttribute(name);
      }
      for (const {name, value} of after.attributes) {
        if (before.getAttribute(name) !== value) before.setAttribute(name, value);
      }
      if (!before.matches('form[data-form="message"], form[data-form="review"], form[data-form="purchase-review"], form[data-form="search-flights"], form[data-form="filter-accounts"]')) {
        updateChildren(before, after);
      }
    }
  }
  while (parent.childNodes.length > next.childNodes.length) parent.lastChild.remove();
}

async function watchView(version, paths, paint) {
  const hash = location.hash;
  const load = Array.isArray(paths)
    ? () => Promise.all(paths.map(path => api(path)))
    : () => api(paths);
  const data = await load();
  if (version !== routeVersion || hash !== location.hash) return;
  paint(data);
  watchedView = {hash, load, paint, snapshot:JSON.stringify(data)};
}

async function refreshView() {
  const view = watchedView;
  if (!user || !view || activeWrites || view.busy || document.hidden || dialog.open || view.hash !== location.hash) return;
  const revision = writeRevision;
  view.busy = true;
  try {
    const data = await view.load();
    if (watchedView !== view || view.hash !== location.hash || writeRevision !== revision || document.hidden || dialog.open) return;
    const snapshot = JSON.stringify(data);
    if (snapshot === view.snapshot) return;
    repainting = true;
    view.paint(data);
    view.snapshot = snapshot;
  } catch (error) {
    if (watchedView === view && view.hash === location.hash) {
      if (!sessionExpired(error) && (error.status === 403 || error.status === 404)) render();
    }
  } finally {
    repainting = false;
    view.busy = false;
  }
}

async function resumeView() {
  if (document.hidden || !user) return;
  const previous = user;
  try {
    const account = await api("me");
    if (user !== previous) return;
    if (account.id !== previous.id || account.role !== previous.role) {
      user = account;
      dashboard = {};
      go("home");
    } else {
      await refreshView();
    }
  } catch (error) {
    if (user === previous) sessionExpired(error);
  }
}

function go(path) {
  if (location.hash.slice(1) === path) render();
  else location.hash = path;
}

function confirmAction(heading, text, label) {
  dialog.innerHTML = '<form class="stack" method="dialog"><h2>' + esc(heading) + '</h2><p class="sub">' + esc(text)
    + '</p><div class="grid2"><button class="btn" value="cancel">Кері</button><button class="btn primary" value="ok">' + esc(label) + '</button></div></form>';
  dialog.returnValue = "";
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue === "ok"), {once:true}));
}

function formField(label, name, attrs = "") {
  return '<label class="field">' + esc(label) + '<input name="' + name + '" ' + attrs + '></label>';
}
function selectField(label, name, options, current) {
  return '<label class="field">' + esc(label) + '<select name="' + name + '" aria-label="' + esc(label) + '">'
    + options.map(([val,text]) => '<option value="' + esc(val) + '"' + (String(val) === String(current) ? " selected" : "") + '>' + esc(text) + '</option>').join("") + '</select></label>';
}
function textField(label, name) {
  return '<label class="field">' + esc(label) + '<textarea name="' + name + '" required maxlength="2000"></textarea></label>';
}
function submit(label) {
  return '<button type="submit" class="btn primary full">' + esc(label) + '</button><p class="error" data-error hidden></p>';
}

function loginPage(register = false) {
  app.className = "login-page";
  app.innerHTML = '<div class="login-layout"><section class="hero login-hero" aria-labelledby="login-brand"><div>'
    + '<p class="caption">NQZ / АСТАНА</p><h1 id="login-brand">Airport</h1><small>Астана әуежайы</small></div></section>'
    + '<div class="login-controls stack"><section class="card login-form stack"><h2>' + (register ? "Жолаушыны тіркеу" : "Аккаунтқа кіру") + '</h2>'
    + '<form class="fields" data-form="' + (register ? "register" : "login") + '">'
    + (register ? formField("Аты-жөні","name",'autocomplete="name" required maxlength="60"') : "")
    + formField("Email","email",'type="email" autocomplete="username" required maxlength="90"')
    + formField("Құпиясөз","password",'type="password" autocomplete="' + (register ? "new-password" : "current-password") + '" required maxlength="80"')
    + submit(register ? "Аккаунт ашу" : "Кіру") + '</form>'
    + link(register ? "login" : "register", register ? "Кіруге қайту" : "Жаңа жолаушы · Тіркелу", "full") + '</section>'
    + link("media",ice("film") + " Ұшу көріністері","text full")
    + link(register ? "about?from=register" : "about","Біз туралы","text full") + '</div></div>';
}

function flightRoute(flight) {
  const from = [flight.origin_code,flight.origin], to = [flight.destination_code,flight.destination];
  const side = ([code, city], cls) => '<div class="' + cls + '"><b>' + esc(code) + '</b><p class="meta">' + esc(city) + '</p></div>';
  return '<div class="route">' + side(from,"from") + ice("route") + side(to,"to") + '</div>';
}

function flightCard(flight, to = "flight/" + flight.id) {
  return rowLink(to, '<div class="row space"><span class="caption">' + esc(flight.number) + (flight.saved ? " ★" : "") + '</span>' + status(flight.status) + '</div>'
    + flightRoute(flight) + '<hr class="rule"><p class="meta">' + esc(flight.airline) + ' · ' + esc(flight.local_time) + ' · Қақпа ' + esc(flight.gate) + '</p>');
}

function tripCard(trip) {
  const state = trip.cancelled ? "Қайтарылды" : trip.status === 3 ? "Тоқтатылды" : trip.completed ? "Сапар аяқталды" : trip.checked ? "Тіркелді" : upcoming(trip) ? "Расталған бронь" : "Уақыты өтті";
  return rowLink("trip/" + enc(trip.code), '<div class="row space"><span class="caption">' + esc(trip.number) + (trip.connection ? ' · 1 ауысу' : '') + '</span><span class="badge">' + state + '</span></div>'
    + flightRoute(trip) + '<hr class="rule"><p class="meta">' + esc(trip.local_time) + ' · Қақпа ' + esc(trip.gate) + (trip.seat ? ' · Орын ' + esc(trip.seat) : "") + '</p>'
    + '<p class="meta">' + esc(trip.code) + (upcoming(trip) ? ' · Дайындық ' + countTasks(trip.trip_tasks) + '/4' : "") + '</p>');
}

function weatherTile(id, city, label) {
  return '<a class="item weather-tile" id="' + id + '" href="#' + href("weather", {city}) + '">'
    + '<span class="weather-tile-icon">' + ice(id === "weather-astana" ? "sun" : "plane") + '</span>'
    + '<span><span class="weather-tile-label">' + esc(label) + '</span><span class="weather-reading" data-weather-value>'
    + (city ? "Жүктелуде…" : "Қаланы таңдау →") + '</span></span></a>';
}

function weatherName(code) {
  if (code === 0) return "Ашық";
  if (code <= 3) return "Бұлтты";
  if (code <= 48) return "Тұман";
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return "Қар";
  return code >= 95 ? "Найзағай" : "Жауын";
}
const deg = (number) => Number.isFinite(number) ? Math.round(number) + "°" : "—";
const temp = (report) => report?.current ? deg(report.current.temperature_2m) + "C · " + weatherName(report.current.weather_code) + (report.stale ? " · Сақталған" : "") : "Дерек қолжетімсіз";

async function loadWeather(city, apply, version) {
  if (!city) return;
  try {
    const report = await api(href("weather", {city}));
    if (version === routeVersion) apply(report);
  } catch {
    if (version === routeVersion) apply(null);
  }
}
function updateTile(id, city, version) {
  loadWeather(city, report => {
    const tile = $("#" + id);
    if (tile?.hash === "#" + href("weather",{city})) tile.querySelector("[data-weather-value]").textContent = temp(report);
  }, version);
}

function homePage(version) {
  return watchView(version, ["home","me"], ([stats, account]) => {
    user = account;
    dashboard = stats;
    const passenger = user.role === "passenger";
    const operator = user.role === "operator";
    const next = passenger ? groupedTrips(stats.trips.filter(upcoming))[0] : null;
    const tile = (to, number, label, icon) => '<a class="item tile" href="#' + esc(to) + '">' + ice(icon) + '<b>' + number + '</b>' + esc(label) + '</a>';
    const staff = '<section class="stack"><div class="dark"><p class="caption">' + roles[user.role] + ' · ' + esc(user.name) + '</p><h1>'
      + (operator ? esc(stats.airline) : "Әуежай әкімшілігі") + '</h1><small>' + stats.today.split("-").reverse().join(".") + ' · Астана</small></div>'
      + '<div class="grid2">'
      + (operator ? tile(href("flights",{day:stats.today}),stats.flights,"Бүгінгі ұшулар","board") + tile("queue",stats.queue,"Тіркеу кезегі","guide")
        : tile("accounts",stats.active,"Белсенді аккаунттар","profile") + tile("tickets",stats.open_tickets,"Ашық өтінімдер","chat"))
      + '</div><div class="card actions">'
      + (operator ? link(href("flights",{day:stats.today,disrupted:1}),"Кешіккен және тоқтаған · " + stats.disrupted) + link("checkin","Бронь кодымен тіркеу")
        : link("sales-admin","Компаниялар, тарифтер және төлемдер","primary") + link("accounts","Аккаунттарды басқару") + link("reviews",ice("chat") + " Жолаушы пікірлері"))
      + link("log","Әрекеттер журналы") + '</div></section>';

    const header = '<header class="top"><a href="#home" aria-label="Airport басты беті" class="brandmark"><img src="/static/icon.png" alt="" width="41" height="41">'
      + '</a><div class="heading"><p class="brand">Airport</p><p class="caption">АСТАНА · NQZ</p></div>'
      + '<a href="#notices" class="icon-btn" aria-label="Хабарландырулар">' + ice("bell") + (stats.notices ? '<span class="dot">' + stats.notices + '</span>' : "") + '</a>'
      + '<a href="#media" class="icon-btn" aria-label="Ұшу көріністері">' + ice("film") + '</a></header>';

    const hero = '<section class="hero passenger-hero">' + globe() + '<div class="hero-text"><p class="caption">Сәлем, ' + esc(user.name) + '!</p><h1>Қайда ұшамыз?</h1>'
      + '<div class="button-row">' + link("search","Билет іздеу →","hero-primary")
      + link("flights",ice("board") + " Рейстер кестесі","hero-secondary") + '</div></div></section>';
    const nextTrip = '<div class="sectionbar"><h2>Келесі сапар</h2>' + link("flights?saved=1",ice("saved") + " Сақталған","text") + '</div>'
      + (next ? tripCard(next) : rowLink("search",'<h3>Әзірге сапар жоспарланбаған</h3><hr class="rule"><p class="meta">Билет іздеу →</p>'));
    const services = '<h2 class="section">Әуежай қызметтері</h2><div class="card services">'
      + link("directions",ice("map") + " Жол бағыты") + liveMapLink()
      + link("guide",ice("guide") + " Жолға кеңес") + link("tickets",ice("chat") + " Қолдау") + '</div>';
    page(header + '<div class="dashboard-grid"><div class="dashboard-main">' + (passenger ? hero : staff)
      + '<div class="grid2 weather-strip">' + weatherTile("weather-astana","Астана","Астана")
      + weatherTile("weather-dest",next?.destination ?? "",next?.destination ?? "Бағыттағы ауа райы") + '</div>'
      + (passenger ? nextTrip : "") + services
      + (passenger && stats.reviewable ? '<div class="section">' + link("reviews","Аяқталған сапарға пікір қалдыру · " + stats.reviewable,"full") + '</div>' : "")
      + '</div><section class="dashboard-media"><h2 class="section">Ұшу көріністері</h2><div class="media-grid">'
      + mediaCard("travel") + mediaCard("flight") + '</div></section></div>'
      + link("about","Біз туралы","text full"),"home","dashboard-page");
    updateTile("weather-astana","Астана",version);
    updateTile("weather-dest",next?.destination,version);
  });
}

function mediaCard(key, selected = false) {
  const clip = clips[key];
  return '<a class="media-card" href="#' + clipRoute(key) + '"' + (selected ? ' aria-current="true"' : '')
    + '><span class="media-thumb"><img src="' + mediaPath + clip.file + '.jpg" alt="" loading="lazy">'
    + '<span class="media-play">' + ice("play") + '</span><span class="media-duration">' + clip.time + '</span></span>'
    + '<span class="media-card-title">' + clip.title + '</span></a>';
}

function cinemaPage(params) {
  const requested = params.get("clip");
  const key = Object.hasOwn(clips,requested) ? requested : "travel";
  const clip = clips[key];
  const animation = key === "orbit";
  const file = mediaPath + clip.file + (animation ? ".gif" : ".mp4");
  const player = animation ? orbitArt("Көгілдір глобус үстінде айналып ұшқан ұшақ")
    : '<video class="cinema-video" src="' + file + '" poster="' + mediaPath + clip.file + '.jpg"'
    + ' aria-label="' + clip.title + '" controls playsinline preload="none"></video>';
  page(title("Ұшу көріністері",user ? "home" : "login")
    + '<section class="cinema"><div class="cinema-frame' + (animation ? ' cinema-orbit' : '') + '">' + player + '</div>'
    + '<div class="cinema-text"><div><h2>' + clip.title + '</h2><p>' + clip.summary + '</p></div>'
    + '<a class="cinema-open" href="' + file + '" target="_blank" rel="noopener" aria-label="' + (animation ? 'Анимацияны бөлек ашу' : 'Видеоны бөлек ашу') + '">↗</a></div></section>'
    + '<nav class="media-grid media-picker" aria-label="Видеоны таңдау">'
    + Object.keys(clips).map(item => mediaCard(item,item === key)).join("") + '</nav>',"media","media-page");
}

function flightList(params, version) {
  const query = Object.fromEntries(params);
  return watchView(version, "flights?" + params.toString(), results => {
    const arrivals = query.direction === "1";
    const chosen = query.day ?? "";
    const saved = query.saved === "1";
    const disrupted = query.disrupted === "1";
    const operator = user.role !== "passenger";
    const change = changes => href("flights", Object.fromEntries(Object.entries({...query,...changes}).filter(([,v]) => v !== "")));
    const filters = ["direction","saved","disrupted"].filter(key => query[key])
      .map(key => '<input type="hidden" name="' + key + '" value="' + esc(query[key]) + '">').join("");
    const form = '<form class="card stack" data-form="search-flights"><div class="grid2">'
      + link(change({direction:"0"}),"Ұшып шығу",arrivals ? "" : "primary") + link(change({direction:"1"}),"Ұшып келу",arrivals ? "primary" : "")
      + '</div>' + formField("Рейс, қала немесе әуежай коды","q",'type="search" maxlength="80" value="' + esc(query.q) + '"')
      + formField("Ұшатын күн","day",'type="date" value="' + esc(chosen) + '"')
      + '<div class="grid2"><button class="btn primary" type="submit">Іздеу</button>'
      + link(change({q:"",day:""}),"Сүзгіні тазалау") + '</div>' + filters + '</form>';
    const controls = '<div class="button-row">'
      + (user.role === "passenger" ? link(change({saved:saved ? "" : "1"}),ice("saved") + " Сақталған",saved ? "primary" : "") : "")
      + (operator ? link("flight-edit","＋ Рейс қосу","primary") + link(change({disrupted:disrupted ? "" : "1"}),"Кешіккен және тоқтаған",disrupted ? "primary" : "") : "")
      + '</div>';
    page(title("Рейстер") + '<div class="stack">' + form + controls + '<p class="caption">' + results.length + ' рейс · ДЕМО КЕСТЕ</p>'
      + (results.length ? '<div class="cards">' + results.map(f => flightCard(f)).join("") + '</div>' : blank("Бұл сүзгіге сәйкес рейс табылмады.")) + '</div>',"flights");
  });
}

function flightPage(key, version) {
  return watchView(version, "flights/" + key, flight => {
    let controls = "";
    if (user.role !== "passenger") {
      controls = link("flight-edit/" + key,"Рейсті өңдеу","full primary")
        + link("manifest/" + key,"Жолаушылар тізімі","full");
    } else if (user.role === "passenger") {
      controls = button("save-flight",flight.saved ? "Сақтаудан алу" : "Рейсті сақтау",key + ":" + (flight.saved ? 0 : 1),"full");
      if (flight.own_booking) controls += link("trip/" + enc(flight.own_booking),"Талонды ашу","full primary");
      else if (flight.status !== 3 && flight.checkin_closes > Date.now()) controls += link(href("checkout",{legs:key}),"Билет сатып алу","full primary");
    }
    page(title(flight.number,"flights") + '<div class="stack"><section class="card"><div class="row space"><p class="caption">ДЕМО РЕЙС</p>'
      + status(flight.status) + '</div>' + flightInfo(flight) + '<p class="meta">Астана уақыты (UTC+5)</p></section>'
      + (flight.terminal ? link(href("directions",{destination:flight.terminal}),ice("map") + " Терминалға бару","full") : "")
      + link(href("weather",{city:flight.destination}),ice("sun") + ' <span id="flight-weather">' + esc(flight.destination) + ' · Ауа райы</span>',"full")
      + controls + '</div>',"flights");
    loadWeather(flight.destination, report => { $("#flight-weather").textContent = flight.destination + " · " + temp(report); },version);
  });
}

async function flightEditor(key, version) {
  const [catalog, flight] = await Promise.all([api("catalog"), key ? api("flights/" + key) : Promise.resolve({
    number:"", origin:"Астана", destination:"Алматы", gate:"", departure:Date.now()+86400000, duration_min:100,
    status:0, checkin_open_min:180, checkin_close_min:40, base_price:25000, airline_id:user.airline_id
  })]);
  if (version !== routeVersion) return;
  const input = (label,name,value,max,extra="") => formField(label,name,'required maxlength="' + max + '" value="' + esc(value) + '" ' + extra);
  const numeric = (label,name,min,max) => formField(label,name,'type="number" required min="' + min + '" max="' + max + '" value="' + flight[name] + '"');
  const cities = '<datalist id="flight-cities">' + catalog.cities.map(c => '<option value="' + esc(c.name) + '"></option>').join("") + '</datalist>';
  page(title(key ? "Рейсті өңдеу" : "Рейс қосу",key ? "flight/"+key : "flights")
    + '<form class="card fields" data-form="flight-edit" data-key="' + esc(key) + '">' + cities
    + selectField("Әуе компаниясы","airline_id",airlineOptions(catalog),flight.airline_id)
    + input("Рейс нөмірі","number",flight.number,24) + '<div class="grid2">'
    + input("Қайдан","origin",flight.origin,70,'list="flight-cities"') + input("Қайда","destination",flight.destination,70,'list="flight-cities"') + '</div>'
    + formField("Ұшу уақыты · Астана (UTC+5)","departure",'type="datetime-local" required value="' + mobileDate(flight.departure) + '"')
    + numeric("Ұшу ұзақтығы, мин","duration_min",10,1200) + input("Қақпа","gate",flight.gate,12)
    + numeric("Негізгі баға, ₸","base_price",1,5000000) + '<h2>Рейске тіркеу</h2><div class="grid2">'
    + numeric("Ұшуға қанша минут қалғанда ашылады","checkin_open_min",1,2880)
    + numeric("Ұшуға қанша минут қалғанда жабылады","checkin_close_min",0,1440) + '</div>'
    + selectField("Мәртебе","status",flightStates.map((name,i) => [i,name]),flight.status) + submit("Сақтау") + '</form>',"flights");
}

function groupedTrips(trips) {
  const groups = new Map();
  for (const trip of trips) {
    const key = trip.order_id ?? trip.code;
    if (!groups.has(key)) groups.set(key,[]);
    groups.get(key).push(trip);
  }
  return Array.from(groups.values(), legs => {
    legs.sort((a,b) => a.leg_index - b.leg_index);
    const first = legs[0], last = legs.at(-1);
    return {...first, destination:last.destination, destination_code:last.destination_code, connection:legs.length > 1,
      active:legs.some(upcoming), completed:legs.every(l => l.completed), number:legs.map(l => l.number).join(" / ")};
  });
}

function tripsPage(archive, version) {
  return watchView(version, "trips", trips => {
    const selection = groupedTrips(trips).filter(group => group.active !== archive);
    if (archive) selection.reverse();
    page(title("Менің сапарларым") + '<div class="stack"><div class="grid2">' + link("trips","Алдағы",archive ? "" : "primary")
      + link("trips?archive=1","Сапарлар тарихы",archive ? "primary" : "") + '</div>'
      + (selection.length ? selection.map(tripCard).join("") : blank(archive ? "Сапарлар тарихы әзірге бос." : "Алдағы бронь жоқ.")
        + (archive ? "" : link("search","Билет іздеу","full primary"))) + '</div>',"trips");
  });
}

function tripPage(code, version) {
  page(title("Менің билетім","trips") + '<p class="loading">Билет жүктелуде…</p>',"trips");
  return watchView(version, "trips/" + enc(code), trip => {
    const label = trip.cancelled ? "Қайтарылды" : trip.status === 3 ? "Рейс тоқтатылды" : trip.completed ? "Сапар аяқталды"
      : trip.checked ? "Тіркелді" : upcoming(trip) ? "Расталған бронь" : "Уақыты өтті";
    const tabs = trip.legs.length > 1 ? '<nav class="grid2" aria-label="Сапар рейстері">' + trip.legs.map(f =>
      link("trip/" + enc(f.code),esc(f.origin) + ' → ' + esc(f.destination), f.code === trip.code ? "primary" : "")).join("") + '</nav>' : "";
    const receipt = trip.order_id ? '<section class="card stack"><h2>Төлем түбіртегі</h2><div class="receipt-lines">'
      + receiptLine("Билет тарифі",trip.fare_name) + receiptLine("Багаж",trip.baggage)
      + receiptLine("Жеңілдік · " + trip.discount_pct + "%",money(trip.subtotal-trip.total))
      + receiptLine("Төленді · " + trip.legs.length + " рейс",money(trip.total))
      + receiptLine("Төлем тәсілі",paymentMethods[trip.payment_method]) + receiptLine("Операция нөмірі",trip.payment_ref)
      + receiptLine("Төлем күні",stamp(trip.purchased_at))
      + (trip.cancelled_at ? receiptLine("Қайтарылған сома",money(trip.refunded)) : receiptLine("Тариф бойынша қайтарым",trip.refund_pct+"%"))
      + '</div></section>' : "";
    page(title("Менің билетім","trips") + '<div class="stack">'
      + (trip.ask_purchase_review ? purchaseReviewInvite(trip.order_id) : "") + tabs + '<section class="card pass"><div class="row space"><span class="caption">ДЕМО БИЛЕТ</span>'
      + '<span class="badge">' + label + '</span></div>' + flightInfo(trip)
      + '<p class="meta">Астана уақыты (UTC+5)' + (trip.seat ? " · Орын " + esc(trip.seat) : "") + '</p>' + checkinClock(trip) + '<hr class="rule">'
      + (trip.cancelled || trip.status === 3 ? "" : '<img class="qr" src="/api/trips/' + enc(code) + '/qr.svg" alt="Броньның QR коды">')
      + '<strong class="code">' + esc(code) + '</strong></section>' + receipt
      + (trip.cancelled || trip.status === 3 ? "" : '<a class="btn full" href="/api/trips/' + enc(code) + '/ticket.pdf">Билетті PDF ретінде сақтау</a>')
      + (upcoming(trip) ? link("checklist/" + enc(code),"Ұшуға дайындық · " + countTasks(trip.trip_tasks) + "/4","full") : "")
      + (trip.terminal ? link(href("directions",{destination:trip.terminal}),ice("map") + " " + terminalName(trip.terminal),"full") : "")
      + link(href("weather",{city:trip.destination}),ice("sun") + " " + esc(trip.destination) + " · Ауа райы","full")
      + (trip.completed && !trip.reviewed ? link(href("reviews",{code:trip.code}),"Сапарға пікір қалдыру","full primary") : "")
      + button("share","Бронь мәліметін бөлісу",code,"full")
      + (trip.refund.allowed ? button("cancel-trip","Билетті қайтару · " + money(trip.refund.amount),code,"danger full")
        : !trip.cancelled && !trip.completed ? blank(trip.refund.reason) : "") + '</div>',"trips");
    updateCheckinClocks();
  });
}

async function checklistPage(code, version) {
  const trip = await api("trips/" + enc(code));
  if (version !== routeVersion) return;
  const boxes = tasks.map((task,i) => '<label class="check"><input type="checkbox" name="task' + i + '"'
    + (trip.trip_tasks & (1 << i) ? " checked" : "") + '> ' + task + '</label>').join("");
  page(title("Ұшуға дайындық","trip/" + enc(code)) + '<form class="card stack" data-form="checklist" data-key="' + esc(code) + '">'
    + '<p class="caption">' + esc(trip.number) + '</p>' + flightRoute(trip) + boxes + submit("Сақтау") + '</form>',"trips");
}

function weatherCard(label, report) {
  if (!report?.current) return '<h3>' + esc(label) + '</h3><p class="temp">—</p><p class="meta">Дерек қолжетімсіз</p>';
  const c = report.current;
  return '<h3>' + esc(label) + '</h3><p class="meta">' + esc(report.country) + '</p><p class="temp">' + deg(c.temperature_2m) + 'C</p><b>'
    + weatherName(c.weather_code) + '</b><p class="meta">Сезілуі ' + deg(c.apparent_temperature) + '</p><p class="meta">Жел ' + (Number.isFinite(c.wind_speed_10m) ? Math.round(c.wind_speed_10m) : "—")
    + ' км/сағ</p><p class="meta">' + (report.stale ? "Сақталған · " : "") + esc(c.time.replace("T"," ")) + '</p>';
}

async function weatherPage(params, version) {
  const cities = await api("weather/cities");
  if (version !== routeVersion) return;
  const requested = params.get("city");
  const city = cities.includes(requested) ? requested : (cities.find(name => name !== "Астана") ?? "Астана");
  const skeleton = name => '<h3>' + esc(name) + '</h3><p class="temp">—</p><p class="meta">Жүктелуде…</p>';
  page(title("Ауа райы") + '<section class="dark weather-banner"><div><p class="caption">САПАР АЛДЫНДА</p><h2>Астана және бағыттар</h2></div>' + globe() + '</section>'
    + '<div class="section">' + selectField("Бағыт қаласы","weather-city",cities.map(n => [n,n]),city) + '</div><div class="'
    + (city === "Астана" ? "stack" : "grid2") + '"><section class="card weather-card" id="current-astana">' + skeleton("Астана · NQZ") + '</section>'
    + (city === "Астана" ? "" : '<section class="card weather-card" id="current-destination">' + skeleton(city) + '</section>') + '</div>'
    + '<h2 class="section">' + esc(city) + ' · 5 күндік болжам</h2><div id="forecast">' + blank("Болжам жүктелуде…") + '</div>'
    + '<div class="grid2 section">' + button("refresh","Жаңарту") + external("https://open-meteo.com/en/docs","Open‑Meteo ↗") + '</div>',"home");
  function forecast(report) {
    const days = report?.daily;
    $("#forecast").innerHTML = days ? '<div class="card days">' + days.time.map((day,i) =>
      '<div class="day"><span class="caption">' + esc(day.slice(8) + "." + day.slice(5,7)) + '</span><span class="meta">' + weatherName(days.weather_code[i])
      + '</span><b>' + deg(days.temperature_2m_min[i]) + ' / ' + deg(days.temperature_2m_max[i]) + '</b></div>').join("") + '</div>' : blank("Болжам қолжетімсіз.");
  }
  loadWeather("Астана", report => {
    $("#current-astana").innerHTML = weatherCard("Астана · NQZ",report);
    if (city === "Астана") forecast(report);
  },version);
  if (city !== "Астана") loadWeather(city, report => {$("#current-destination").innerHTML = weatherCard(city,report); forecast(report);},version);
}

function profilePage(version) {
  return watchView(version, ["home","me"], ([stats, account]) => {
    user = account;
    dashboard = stats;
    const passenger = user.role === "passenger";
    const initials = user.name.trim().split(/\s+/).slice(0,2).map(word => Array.from(word)[0]).join("").toUpperCase();
    const shortcuts = passenger
      ? link("trips",ice("trip") + " Сапарлар · " + stats.trips.length) + link("flights?saved=1",ice("saved") + " Сақталған · " + stats.saved)
      : (user.role === "admin" ? link("accounts",ice("profile") + " Аккаунттар · " + stats.accounts) : link("flights",ice("board") + " Рейс кестесі"))
        + link("tickets",ice("chat") + " Өтінімдер");
    const preferences = passenger ? '<h2 class="section">Хабарландыру баптаулары</h2><div class="card"><label class="check">'
      + '<input type="checkbox" name="flight-alerts"' + (user.flight_alerts ? " checked" : "") + '>Рейс өзгерістері</label>'
      + '<p class="sub">Қақпа, уақыт және мәртебе өзгергенде қолданба ішінде хабар келеді.</p></div>' : "";
    page(title("Профиль") + '<div class="dark profile-banner"><div class="avatar">' + esc(initials) + '</div><div><p class="caption">' + roles[user.role]
      + '</p><h2>' + esc(user.name) + '</h2><small>' + esc(user.email) + '</small></div></div>'
      + '<div class="grid2 section">' + shortcuts + '</div>'
      + (passenger ? walletCard(stats) : "")
      + (passenger ? rowLink("passport",'<div class="row space"><span class="row">' + ice("guide")
        + '<span><strong>Саяхат паспортым</strong><span class="passport-link-meta">' + stats.passport.trips + ' аяқталған сапар · '
        + stats.passport.cities + ' қала</span></span></span><span aria-hidden="true">›</span></div>') : "")
      + '<h2 class="section">Жеке деректер</h2>'
      + rowLink("profile-edit","Атымды өңдеу") + '<h2 class="section">Қауіпсіздік</h2>'
      + (user.demo ? blank("Құпиясөзді жеке аккаунтта ауыстыруға болады.") : rowLink("password","Құпиясөзді ауыстыру"))
      + preferences
      + (user.role !== "operator" ? '<h2 class="section">Пікірлер</h2>' + rowLink("reviews",passenger ? "Пікір қалдыру және менің пікірлерім" : "Жолаушы пікірлері") : "")
      + '<div class="section">' + button("logout","Шығу","","full") + '</div>',"profile");
    if (passenger) $('[name="flight-alerts"]').checked = Boolean(user.flight_alerts);
  });
}

function passportPage(params, version) {
  return watchView(version, ["passport","me"], ([passport, account]) => {
    user = account;
    const {summary, stamps} = passport;
    const selected = stamps.find(item => item.city === params.get("city")) ?? stamps[0];
    const counts = [[summary.trips,"Сапар"],[summary.cities,"Қала"],[summary.countries,"Ел"]]
      .map(([value,label]) => '<div><b>' + value + '</b><span>' + label + '</span></div>').join("");
    const cityStamps = stamps.map(item => '<a class="passport-stamp' + (item === selected ? ' chosen' : '') + '" href="#'
      + href("passport",{city:item.city}) + '"' + (item === selected ? ' aria-current="true"' : '') + '><span class="passport-seal">'
      + '<span class="passport-seal-label">AIRPORT · САЯХАТ</span><strong>' + esc(item.code_city) + '</strong><span>'
      + item.count + ' сапар</span></span><b>' + esc(item.city) + '</b><small>' + esc(item.country ?? "Ел анықталмаған") + '</small></a>').join("");
    const details = selected ? '<section class="card stack passport-details"><div class="row space"><div><p class="caption">ҚАЛА МӨРІ</p><h2>'
      + esc(selected.city) + '</h2></div><span class="badge ok">' + selected.count + ' сапар</span></div>'
      + selected.trips.map(item => '<article class="passport-trip"><div class="row space wrap"><h3>' + esc(item.flight_number) + '</h3><time class="meta" datetime="'
        + new Date(item.departure).toISOString() + '">' + esc(item.local_time) + '</time></div><p class="sub">' + esc(item.origin) + ' → ' + esc(item.city)
        + (item.seat ? ' · Орын ' + esc(item.seat) : '') + '</p><p class="meta">' + esc(item.code) + '</p></article>').join("") + '</section>' : "";
    const empty = '<section class="card passport-empty"><div class="passport-first-stamp">' + ice("plane") + '</div><h2>Алғашқы мөріңіз алда</h2>'
      + '<p class="sub">Аяқталған сапарды оператор растағанда, барған қалаңыздың мөрі осында пайда болады.</p>'
      + link("search","Сапар таңдау","primary full") + '</section>';
    page(title("Саяхат паспортым","profile") + '<div class="stack"><section class="dark passport-cover"><div class="row space"><p class="caption">AIRPORT / TRAVEL PASSPORT</p>'
      + ice("guide") + '</div><h2>' + esc(user.name) + '</h2><p>Әр сапар — жаңа естелік</p><div class="passport-counts">' + counts
      + '</div></section><div class="sectionbar"><h2>Менің қала мөрлерім</h2><span class="badge">' + summary.cities + '</span></div>'
      + (stamps.length ? '<p class="sub">Мөрді басып, сол қалаға барған сапарларыңызды көріңіз.</p><div class="passport-stamps">' + cityStamps + '</div>' + details : empty)
      + '<p class="meta passport-note">Мөр тіркелген жолаушының сапары аяқталғанын оператор растағаннан кейін беріледі. Бір қалаға бірнеше сапар жасасаңыз, сол мөрдегі сан өседі.</p>'
      + '</div>',"profile");
  });
}

function ratingField(heading) {
  const stars = ratingLabels.slice(1).map((label, index) => {
    const value = index + 1;
    return '<label class="rating-choice"><input type="radio" name="rating" value="' + value + '" required>'
      + '<span class="rating-star" aria-hidden="true">★</span><span class="sr-only">' + value + ' / 5 · ' + label + '</span></label>';
  }).join("");
  return '<fieldset class="rating-field"><legend>' + heading + '</legend><div class="rating-options">' + stars
    + '</div><p class="sub" data-rating-caption role="status">Бағаңызды таңдаңыз</p></fieldset>';
}

function purchaseReviewInvite(orderId) {
  return '<details class="card purchase-invite"><summary><strong>Алғашқы сатып алуыңыз қалай өтті?</strong>'
    + '<span>Билет рәсімдеуді бағалау ›</span></summary><form class="fields" data-form="purchase-review" data-key="' + Number(orderId) + '">'
    + ratingField("Билет сатып алуға баға беріңіз")
    + '<label class="field">Пікіріңіз (міндетті емес)<textarea name="body" maxlength="2000"></textarea></label>'
    + submit("Бағаны жіберу") + '</form></details>';
}

function reviewForm(eligible, selected) {
  if (!eligible.length) return blank("Сапарды оператор растаған соң, рейске пікір қалдыра аласыз.");
  return '<form class="card fields review-form" data-form="review">'
    + selectField("Аяқталған рейс","trip_code",eligible.map(t => [t.code,t.flight_number + ' · ' + t.origin + ' → ' + t.city + ' · ' + stamp(t.departure)]),selected)
    + ratingField("Рейске баға беріңіз")
    + '<label class="field">Пікіріңіз<textarea name="body" required maxlength="2000" placeholder="Не ұнады? Нені жақсартуға болады?"></textarea></label>'
    + '<p class="meta">Пікіріңіз әкімшіге жіберіледі.</p>' + submit("Пікірді жіберу") + '</form>';
}

function reviewsPage(params, version) {
  return watchView(version, user.role === "passenger" ? ["reviews","reviewable","purchase-review"] : ["reviews"], ([items,eligible,first]) => {
    const passenger = user.role === "passenger";
    const cards = items.map(item => '<article class="card stack"><div class="row space wrap"><h3>' + esc(item.name) + '</h3>'
      + '<time class="meta" datetime="' + new Date(item.created).toISOString() + '">' + esc(stamp(item.created)) + '</time></div>'
      + (item.purchase_order ? '<p class="meta">Билет сатып алу</p>' : item.flight_number ? '<p class="meta">' + esc(item.flight_number) + ' · ' + esc(item.city) + '</p>' : "")
      + (passenger ? "" : '<p class="meta">' + esc(item.email) + '</p>')
      + '<div class="row wrap"><span class="review-stars" role="img" aria-label="' + item.rating + ' / 5">'
      + '<span aria-hidden="true">' + '★'.repeat(item.rating) + '<span class="unfilled">' + '★'.repeat(5 - item.rating) + '</span></span></span>'
      + '<span class="badge">' + item.rating + '/5 · ' + ratingLabels[item.rating] + '</span></div><p class="body-text">' + esc(item.body) + '</p></article>');
    const summary = items.length ? '<section class="dark"><h2>' + (items.reduce((sum, item) => sum + item.rating, 0) / items.length).toFixed(1)
      + ' / 5</h2><small>' + items.length + ' пікір · орташа баға</small></section>' : "";
    page(title(passenger ? "Менің пікірлерім" : "Жолаушы пікірлері") + '<div class="stack">'
      + (first ? purchaseReviewInvite(first.id) : "") + (passenger ? reviewForm(eligible,params.get("code")) : summary + button("refresh","Жаңарту"))
      + '<h2 class="section">' + (passenger ? "Жіберілген пікірлерім" : "Барлық пікірлер") + '</h2>'
      + (cards.length ? cards.join("") : blank(passenger ? "Жіберілген пікіріңіз осы жерде көрінеді." : "Жолаушылардың пікірлері осы жерде көрінеді.")) + '</div>',"profile");
  });
}

function profileEditor(password) {
  const fields = password ? formField("Қазіргі құпиясөз","current",'type="password" maxlength="80" required autocomplete="current-password"')
    + formField("Жаңа құпиясөз","password",'type="password" minlength="8" maxlength="80" required autocomplete="new-password"')
    + formField("Жаңа құпиясөзді қайталау","repeat",'type="password" minlength="8" maxlength="80" required autocomplete="new-password"')
    : formField("Аты-жөні","name",'autocomplete="name" maxlength="60" required value="' + esc(user.name) + '"');
  page(title(password ? "Құпиясөзді ауыстыру" : "Атымды өңдеу","profile")
    + '<form class="card fields" data-form="' + (password ? "password" : "profile-edit") + '">' + fields + submit("Сақтау") + '</form>',"profile");
}

function noticesPage(version) {
  return watchView(version, "notices", rows => {
    page(title("Хабарландырулар") + '<div class="stack">' + link("tickets",ice("chat") + " Қолдау хабарлары")
      + '<h2>Рейс өзгерістері</h2>' + (rows.some(item => !item.seen) ? button("notices-read","Барлығын оқылды деп белгілеу") : "")
      + (rows.length ? rows.map(item => rowLink("notice/" + item.id,'<div class="row space"><span class="caption">' + stamp(item.created) + '</span>'
          + (item.seen ? "" : '<span class="badge">Жаңа</span>') + '</div><h3>' + esc(item.title) + '</h3><p class="sub body-text">' + esc(item.body) + '</p>')).join("")
        : blank("Рейс туралы жаңа ақпарат осы жерде шығады.")) + '</div>',"home");
  });
}

async function noticePage(key, version) {
  const rows = await api("notices");
  const item = rows.find(row => row.id === Number(key));
  if (version !== routeVersion) return;
  if (!item) return go("notices");
  if (!item.seen) await api("notices/" + key + "/read","POST");
  if (version !== routeVersion) return;
  page(title("Рейс туралы хабар","notices") + '<div class="stack"><section class="card stack"><p class="caption">' + stamp(item.created)
    + '</p><h2>' + esc(item.title) + '</h2><p class="body-text">' + esc(item.body) + '</p></section>'
    + (item.code ? link("trip/" + enc(item.code),"Сапарды ашу","primary full") : "")
    + button("notice-delete","Өшіру",key,"full") + '</div>',"home");
}

function ticketsPage(version) {
  return watchView(version, "tickets", items => {
    const passenger = user.role === "passenger";
    dashboard.unread = items.reduce((sum,item) => sum + item.unread,0);
    page(title(passenger ? "Менің өтінімдерім" : "Өтінімдер кезегі") + '<div class="stack">'
      + (passenger ? link("ticket-new","＋ Жаңа өтінім","primary") : "")
      + (items.length ? items.map(item => rowLink("ticket/" + item.id,'<div class="row space"><span class="caption">#' + item.id
        + '</span><span class="badge">' + (item.unread ? item.unread + " жаңа" : ticketStates[item.state]) + '</span></div><h3>'
        + esc(item.subject) + '</h3><p class="meta">' + esc(item.airline) + " · " + (passenger ? "" : esc(item.name) + " · ") + stamp(item.updated) + '</p>')).join("")
        : blank("Өтінімдер әзірге жоқ.")) + '</div>',"tickets");
  });
}

async function newTicket(version) {
  const catalog = await api("catalog");
  if (version !== routeVersion) return;
  const topics = ["Рейс және бронь","Багаж","Арнайы көмек","Басқа сұрақ"];
  page(title("Жаңа өтінім","tickets") + '<form class="card fields" data-form="ticket-new">'
    + selectField("Әуе компаниясы","airline_id",airlineOptions(catalog))
    + selectField("Тақырып","subject",topics.map(topic => [topic,topic]),topics[0])
    + textField("Хабар","body") + submit("Жіберу") + '</form>',"tickets");
}

function chatPage(key, version) {
  let marked = 0;
  return watchView(version, "tickets/" + key, ticket => {
    const last = ticket.messages.at(-1)?.id ?? 0;
    if (last > marked) {
      marked = last;
      api("tickets/" + key + "/read","POST",{last}).catch(() => { if (marked === last) marked = 0; });
    }
    const messages = ticket.messages.map(message => '<div class="bubble ' + (message.sender === user.id ? "mine" : "") + '"><p class="caption">'
      + esc(message.name) + ' · ' + stamp(message.sent) + '</p><p class="body-text">' + esc(message.body) + '</p></div>').join("");
    const operator = user.role === "operator";
    const keepDraft = repainting && $('[data-form="message"] textarea')?.value;
    const canCompose = user.role !== "admin" && (ticket.state !== 2 || keepDraft);
    page(title(ticket.subject,"tickets") + '<div class="stack"><div class="card row space"><span class="caption">ӨТІНІМ #' + key
      + '</span><span class="badge">' + ticketStates[ticket.state] + '</span></div>'
      + (operator ? '<div class="card">' + selectField("Мәртебе","ticket-state",ticketStates.map((name,index) => [index,name]),ticket.state) + '</div>' : "")
      + '<div class="stack" id="conversation">' + messages + '</div>'
      + (canCompose ? '<form class="card fields" data-form="message" data-key="' + key + '">' + textField("Хабар","body") + submit("Жіберу") + '</form>' : "")
      + (ticket.state === 2 ? blank("Өтінім шешілді.") : "")
      + button("refresh","Жаңарту") + '</div>',"tickets");
    if (operator) {
      const state = $('[name="ticket-state"]');
      state.dataset.key = key;
      state.value = String(ticket.state);
    }
    const composer = $('[data-form="message"]');
    if (composer) {
      composer.querySelector('[type="submit"]').disabled = ticket.state === 2;
    }
  });
}

function staffPage(kind, key, version) {
  const isLog = kind === "log", isQueue = kind === "queue";
  return watchView(version, isLog ? "staff/log" : isQueue ? "staff/queue" : "flights/" + key + "/manifest", rows => {
    const heading = isLog ? "Әрекеттер журналы" : isQueue ? "Тіркеу кезегі" : "Жолаушылар тізімі";
    const description = isLog ? (user.role === "admin" ? "Қызметкерлердің соңғы әрекеттері" : "Менің соңғы әрекеттерім")
      : isQueue ? "Тіркеуі ашық рейстер · " + rows.length + " жолаушы тіркелмеген"
      : "Тіркелді " + rows.filter(row => row.checked).length + "/" + rows.length;
    const items = rows.map(item => {
      if (isLog) return '<section class="card"><p class="caption">' + stamp(item.created) + ' · ' + esc(item.name)
        + '</p><h3>' + esc(item.action) + '</h3><p class="sub body-text">' + esc(item.detail) + '</p></section>';
      const details = (isQueue ? '<p class="caption">' + esc(item.number) + ' · ' + stamp(item.time) + '</p>' : "")
        + '<div class="row space"><h3>' + esc(item.name) + '</h3><span class="badge' + (item.completed ? ' ok' : '') + '">'
        + (item.completed ? "Сапар аяқталды" : item.checked ? "Тіркелді" : "Күтуде") + '</span></div><p class="sub">'
        + (isQueue ? esc(item.city) + ' · ' : "") + esc(item.code) + (item.seat ? ' · Орын ' + esc(item.seat) : "") + '</p>';
      return '<section class="card stack">' + details + (isQueue || item.can_checkin ? button("check-passenger","Тіркеу",item.code) : "")
        + (item.can_complete ? button("complete-trip","Сапарды аяқтау",item.code,"primary") : "") + '</section>';
    });
    page(title(heading,key ? "flight/" + key : "home") + '<div class="stack"><p class="sub">' + esc(description) + '</p>'
      + (!isLog && !isQueue ? '<p class="meta">Рейс қонған соң тіркелген жолаушының сапарын растаңыз. Оның саяхат паспортына қала мөрі қосылады.</p>' : "")
      + (items.length ? items.join("") : blank(isLog ? "Журналда әзірге жазба жоқ." : "Тізім бос.")) + '</div>',"home");
  });
}

function checkinPage() {
  page(title("Бронь кодымен тіркеу") + '<form class="card fields" data-form="checkin">'
    + formField("Бронь коды","code",'placeholder="NQZ-XXXXXXXX" autocapitalize="characters" maxlength="32" required')
    + submit("Жолаушыны тіркеу") + '</form>',"home");
}

function accountsPage(params, version) {
  return watchView(version, "admin/accounts", accounts => {
    const term = params.get("q") ?? "", role = params.get("role") ?? "";
    const filtered = accounts.filter(item => (role === "blocked" ? !item.active : !role || item.role === role)
      && (item.name.toLowerCase().includes(term.toLowerCase()) || item.email.toLowerCase().includes(term.toLowerCase())));
    const rolesFilter = [["","Барлық рөлдер"],...Object.entries(roles),["blocked","Бұғатталғандар"]];
    const items = filtered.map(item => '<section class="card stack"><div class="row space"><span class="caption">' + roles[item.role] + '</span><span class="badge '
      + (item.active ? "ok" : "cancelled") + '">' + (item.active ? "Белсенді" : "Бұғатталған") + '</span></div><div><h3>' + esc(item.name) + '</h3><p class="sub">'
      + esc(item.email) + (item.airline ? ' · ' + esc(item.airline) : '') + '</p></div>' + (item.role === "admin" ? "" : button("account-active",item.active ? "Бұғаттау" : "Қайта ашу",item.id + ":" + (item.active ? 0 : 1))) + '</section>');
    page(title("Аккаунттар") + '<div class="stack"><div class="dark"><h2>' + accounts.length + ' аккаунт</h2><small>'
      + accounts.filter(item => item.active).length + ' белсенді аккаунт</small></div>' + link("operator-new","＋ Оператор қосу","primary")
      + '<form class="card fields" data-form="filter-accounts">' + formField("Аты немесе email","q",'type="search" value="' + esc(term) + '"')
      + selectField("Аккаунт сүзгісі","role",rolesFilter,role) + '<button class="btn" type="submit">Іздеу</button></form>'
      + (items.length ? items.join("") : blank("Аккаунт табылмады.")) + '</div>',"home");
  });
}

async function newOperator(version) {
  const catalog = await api("catalog");
  if (version !== routeVersion) return;
  page(title("Оператор аккаунты","accounts") + '<form class="card fields" data-form="operator-new">'
    + selectField("Әуе компаниясы","airline_id",airlineOptions(catalog))
    + formField("Аты-жөні","name",'maxlength="60" required')
    + formField("Email","email",'type="email" maxlength="90" required')
    + formField("Бастапқы құпиясөз","password",'type="password" minlength="8" maxlength="80" autocomplete="new-password" required')
    + submit("Қосу") + '</form>',"home");
}

function aboutPage(params) {
  const destination = enc(airportName + ", " + airportAddress);
  const mapUrl = "https://www.google.com/maps/search/?api=1&query=" + destination;
  const back = user ? "home" : (params.get("from") === "register" ? "register" : "login");
  page(title("Біз туралы",back) + '<div class="stack">'
    + '<section class="dark"><p class="caption">NQZ / АСТАНА</p><h2>Airport</h2><small>Әуежай қызметтерінің оқу жобасы</small></section>'
    + '<section class="card stack"><h2>Біздің мекенжай</h2><h3>' + esc(airportName) + '</h3><p>' + esc(airportAddress) + '</p>'
    + '<div class="button-row">' + external(mapUrl,ice("map") + " Мекенжайды көру") + link("directions",ice("route") + " Жол бағыты") + '</div></section>'
    + '<section class="card stack"><h2>Жоба туралы</h2><p>Жолаушы рейс іздеп, орын таңдайды және QR-талон алады. Оператор жолаушыны тіркеп, сапарды растайды. Әкімші аккаунттарды басқарып, пікірлер мен қызметтік журналды қарайды.</p>'
    + '<p class="sub">Рейстер мен броньдар оқу көрсетіліміне арналған. Нақты ұшу мәліметтерін әуежайдың ресми таблосынан тексеріңіз.</p></section>'
    + '<section class="card stack"><h2>Жоба авторлары</h2><p>Айболатқызы Қарлығаш<br>Айтқұл Бейбарс<br>Алпысбайұлы Ерасыл</p>'
    + '<p class="sub">Л.Н. Гумилев атындағы Еуразия ұлттық университеті · ЖИТ-33</p></section></div>',"home");
}

function guidePage() {
  page(title("Жолаушыға көмек") + '<div class="stack">'
    + '<section class="card stack"><h2>Багажды дайындау</h2><p class="sub">Қол жүгі мен багаждың өлшемі мен салмағын әуе компаниясының бронь растауынан тексеріңіз.</p></section>'
    + '<section class="card stack"><h2>Ұшуға дайындық</h2><p class="sub">Құжатыңызды дайындап, терминал мен қақпаны ресми таблодан тексеріңіз.</p>'
    + link("directions","Терминалға бағыт алу") + external(official,"Ресми табло ↗") + '</section>'
    + '<section class="card stack"><h2>Арнайы көмек</h2><p class="sub">Арнайы көмек қажет болса, әуе компаниясына алдын ала хабарласыңыз.</p>'
    + link("tickets","Қолдау хабарлары") + '</section></div>',"home");
}

async function render() {
  const version = ++routeVersion;
  watchedView = null;
  for (const video of app.querySelectorAll("video")) video.pause();
  const [path, search = ""] = (location.hash.slice(1) || "home").split("?");
  const [screen, raw = ""] = path.split("/");
  const params = new URLSearchParams(search);
  window.scrollTo(0,0);
  if (dialog.open) dialog.close("cancel");
  if (screen === "live") {
    // Saved links from the old board also work without an Airport session.
    page(title("Қазір әуеде") + liveMapLink(),"home");
    location.replace(liveMap);
    return;
  }
  if (screen === "about") return aboutPage(params);
  if (screen === "directions") {
    page(title("Жол бағыты",user ? "home" : "about") + '<div id="airport-route"></div>',"home","wayfinding-page");
    mountRouteMap($("#airport-route"),params,selection => history.replaceState(history.state,"","#" + href("directions",selection)));
    return;
  }
  if (screen === "media") {
    cinemaPage(params);
    return;
  }
  if (!user) {
    loginPage(screen === "register");
    return;
  }
  try {
    const key = /^\d+$/.test(raw) ? raw : "";
    const code = /^NQZ-[0-9A-F]{8}$/.test(raw) ? raw : "";
    switch (screen) {
      case "flights": return await flightList(params,version);
      case "search": if (user.role === "passenger") return await finderPage(params,version); break;
      case "checkout": if (user.role === "passenger") return await checkoutPage(params.get("legs"),params.get("back"),version); break;
      case "payment": if (user.role === "passenger" && /^AP-[A-F0-9]{16}$/.test(raw)) return await paymentPage(raw,version); break;
      case "sales-admin": if (user.role === "admin") return await salesAdmin(params,version); break;
      case "airline-new": if (user.role === "admin") return airlineEditor(); break;
      case "fare-new": if (user.role === "admin") return fareEditor(); break;
      case "flight": if (key) return await flightPage(key,version); break;
      case "flight-edit": if (user.role !== "passenger") return await flightEditor(key,version); break;
      case "seats": if (key && user.role === "passenger") return await checkoutPage(key,"",version); break;
      case "trips": if (user.role === "passenger") return await tripsPage(params.get("archive") === "1",version); break;
      case "trip": if (code) return await tripPage(code,version); break;
      case "checklist": if (code) return await checklistPage(code,version); break;
      case "weather": return await weatherPage(params,version);
      case "profile": return await profilePage(version);
      case "passport": if (user.role === "passenger") return await passportPage(params,version); break;
      case "reviews": if (user.role !== "operator") return await reviewsPage(params,version); break;
      case "profile-edit": return profileEditor(false);
      case "password": if (!user.demo) return profileEditor(true); break;
      case "notices": return await noticesPage(version);
      case "notice": if (key) return await noticePage(key,version); break;
      case "tickets": return await ticketsPage(version);
      case "ticket": if (key) return await chatPage(key,version); break;
      case "ticket-new": if (user.role === "passenger") return await newTicket(version); break;
      case "queue": case "log": return await staffPage(screen,"",version);
      case "manifest": if (key) return await staffPage(screen,key,version); break;
      case "checkin": if (user.role === "operator") return checkinPage(); break;
      case "accounts": return await accountsPage(params,version);
      case "operator-new": if (user.role === "admin") return await newOperator(version); break;
      case "guide": return guidePage();
      default: return await homePage(version);
    }
    go("home");
  } catch (error) {
    if (version !== routeVersion) return;
    if (sessionExpired(error)) return;
    page(title("Airport") + blank(error.message) + '<div class="section">' + button("refresh","Қайта жүктеу") + '</div>',"home");
  }
}

app.addEventListener("click", async event => {
  const target = event.target.closest("button[data-action]");
  if (!target) return;
  const {action, value} = target.dataset;
  try {
    switch (action) {
      case "refresh": if (watchedView) await refreshView(); else await render(); break;
      case "cancel-payment": {
        await api("payments/" + enc(value) + "/cancel","POST");
        await render();
        break;
      }
      case "swap-cities": {
        const from = $('[name="origin"]'), to = $('[name="destination"]');
        [from.value,to.value] = [to.value,from.value];
        break;
      }
      case "save-flight": {
        const [key, flag] = value.split(":");
        await api("flights/" + key + "/saved","PUT",{enabled:flag === "1"});
        showMessage(flag === "1" ? "Рейс сақталды." : "Рейс сақталғандардан алынды.");
        await render();
        break;
      }
      case "logout":
        await api("logout","POST");
        user = null;
        dashboard = {};
        go("login");
        break;
      case "cancel-trip": {
        const trip = await api("trips/" + enc(value));
        if (!trip.refund.allowed) throw new Error(trip.refund.reason);
        const affected = trip.legs.filter(f => trip.refund.codes.includes(f.code)).map(f => f.number).join(" / ");
        if (await confirmAction("Билетті қайтару",affected + " · " + trip.refund.reason
            + " Қайтарылатын сома · " + money(trip.refund.amount) + (trip.payment_method ? " · " + paymentMethods[trip.payment_method] : "")
            + ". Көрсетілген рейстердегі орындар босатылады.","Қайтару")) {
          const receipt = await api(href("trips/" + enc(value),{expected:trip.refund.amount}),"DELETE");
          await render();
          showMessage("Билет қайтарылды · " + money(receipt.refunded));
        }
        break;
      }
      case "share": {
        const t = await api("trips/" + enc(value));
        const route = t.origin + " → " + t.destination;
        const details = route + " · " + t.number + " · " + t.local_time + " · Қақпа " + t.gate + (t.seat ? " · Орын " + t.seat : "") + " · " + t.code;
        if (navigator.share) {
          try { await navigator.share({title:"Airport · Демо бронь",text:details}); }
          catch (error) {
            if (error.name !== "AbortError") await confirmAction("Бронь мәліметі",details,"Дайын");
          }
        } else {
          await confirmAction("Бронь мәліметі",details,"Дайын");
        }
        break;
      }
      case "notices-read":
        await api("notices/read","POST");
        await render();
        break;
      case "notice-delete":
        await api("notices/" + value,"DELETE");
        go("notices");
        break;
      case "check-passenger":
        if (await confirmAction("Жолаушыны тіркеу",value,"Тіркеу")) {
          await api("staff/checkin","POST",{code:value});
          showMessage("Жолаушы тіркелді.");
          await render();
        }
        break;
      case "complete-trip":
        if (await confirmAction("Сапар аяқталды ма?",value + " · Жолаушының сапары аяқталғанын растаңыз. Паспортына қала мөрі қосылады.","Растау")) {
          await api("staff/complete","POST",{code:value});
          showMessage("Сапар расталды. Қала мөрі жолаушының паспортына қосылды.");
          await render();
        }
        break;
      case "account-active": {
        const [key,flag] = value.split(":");
        const enabled = flag === "1";
        if (await confirmAction(enabled ? "Аккаунтты қайта ашу" : "Аккаунтты бұғаттау","Аккаунт #" + key,enabled ? "Қайта ашу" : "Бұғаттау")) {
          await api("admin/accounts/" + key + "/active","PUT",{enabled});
          await render();
        }
        break;
      }
    }
  } catch (error) {
    if (!sessionExpired(error)) showMessage(error.message);
  }
});

app.addEventListener("error", event => {
  if (event.target.matches(".cinema-video")) showMessage("Видео жүктелмеді. Қайта ашып көріңіз.");
},true);


app.addEventListener("change", async event => {
  const field = event.target;
  try {
    if (field.name === "rating") {
      const form = field.closest("form");
      for (const option of form.querySelectorAll('[name="rating"]')) {
        option.closest(".rating-choice").classList.toggle("selected", Number(option.value) <= Number(field.value));
      }
      form.querySelector("[data-rating-caption]").textContent = field.value + " / 5 · " + ratingLabels[Number(field.value)];
    } else if (field.name === "fare_id") {
      updatePrice();
    } else if (field.name === "weather-city") {
      go(href("weather",{city:field.value}));
    } else if (field.name === "ticket-state") {
      await api("tickets/" + field.dataset.key + "/state","PUT",{state:Number(field.value)});
      await refreshView();
    } else if (field.name === "flight-alerts") {
      try {
        user = await api("me","PATCH",{flight_alerts:field.checked});
        showMessage("Баптау сақталды.");
      } catch (error) {
        field.checked = Boolean(user.flight_alerts);
        throw error;
      }
    }
  } catch (error) {
    if (!sessionExpired(error)) showMessage(error.message);
  }
});

app.addEventListener("submit", async event => {
  const form = event.target.closest("form[data-form]");
  if (!form) return;
  event.preventDefault();
  const {form:action,key} = form.dataset;
  const values = Object.fromEntries(new FormData(form));
  const submitter = form.querySelector('[type="submit"]');
  const errorNode = form.querySelector("[data-error]");
  if (errorNode) errorNode.hidden = true;
  submitter.disabled = true;
  try {
    switch (action) {
      case "register": case "login":
        user = await api(action,"POST",values);
        go("home");
        break;
      case "search-flights": case "filter-accounts":
        go(href(action === "search-flights" ? "flights" : "accounts",Object.fromEntries(
          Object.entries(values).map(([name,value]) => [name,value.trim()]).filter(([,value]) => value !== ""))));
        break;
      case "flight-edit": {
        const arrival = values.destination.trim() === "Астана";
        values.direction = arrival ? 1 : 0;
        values.base_city = arrival ? values.destination.trim() : values.origin.trim();
        values.city = arrival ? values.origin.trim() : values.destination.trim();
        values.time = Date.parse(values.departure + "+05:00") + (arrival ? Number(values.duration_min)*60000 : 0);
        for (const name of ["status","airline_id","base_price","duration_min","checkin_open_min","checkin_close_min"]) values[name] = Number(values[name]);
        const result = await api("flights" + (key ? "/" + key : ""),key ? "PUT" : "POST",values);
        showMessage("Рейс сақталды.");
        go("flight/" + result.id);
        break;
      }
      case "find-tickets":
        if (values.origin === values.destination) throw new Error("Екі түрлі қаланы таңдаңыз.");
        go(href("search",values));
        break;
      case "checkout": await pay(values); break;
      case "approve-payment": {
        const result = await api("payments/" + enc(key) + "/confirm","POST",{code:values.approval_code || ""});
        showMessage("Төлем қабылданды. Билетіңіз дайын.");
        go("trip/" + result.code);
        break;
      }
      case "airline-new":
        await api("admin/airlines","POST",{name:values.name,code:values.code,operator:{name:values.operator_name,email:values.email,password:values.password}});
        go("sales-admin");
        break;
      case "fare-new":
        await api("admin/fares","POST",{...values,markup_pct:Number(values.markup_pct),refund_pct:Number(values.refund_pct)});
        go("sales-admin");
        break;
      case "checklist": {
        const mask = tasks.reduce((sum,_,i) => sum + (values["task" + i] ? 1 << i : 0),0);
        await api("trips/" + enc(key) + "/checklist","PUT",{mask});
        go("trip/" + key);
        break;
      }
      case "profile-edit":
        user = await api("me","PATCH",{name:values.name});
        go("profile");
        break;
      case "password":
        if (values.password !== values.repeat) throw new Error("Құпиясөздер сәйкес емес.");
        await api("me/password","PUT",{current:values.current,password:values.password});
        showMessage("Құпиясөз ауыстырылды.");
        go("profile");
        break;
      case "ticket-new": {
        const result = await api("tickets","POST",values);
        go("ticket/" + result.id);
        break;
      }
      case "review":
        await api("reviews","POST",{trip_code:values.trip_code,rating:Number(values.rating),body:values.body});
        await render();
        showMessage("Пікіріңіз әкімшіге жіберілді. Рақмет!");
        break;
      case "purchase-review":
        await api("purchase-review","POST",{order_id:Number(key),rating:Number(values.rating),body:values.body});
        await render();
        showMessage("Сатып алу туралы пікіріңіз сақталды. Рақмет!");
        break;
      case "message":
        await api("tickets/" + key + "/messages","POST",values);
        await render();
        break;
      case "checkin":
        await api("staff/checkin","POST",values);
        showMessage("Жолаушы тіркелді.");
        form.reset();
        break;
      case "operator-new":
        await api("admin/operators","POST",values);
        showMessage("Оператор қосылды.");
        go("accounts?role=operator");
        break;
    }
  } catch (error) {
    if (sessionExpired(error)) return;
    if (errorNode) {
      errorNode.textContent = error.message;
      errorNode.hidden = false;
    } else showMessage(error.message);
  } finally {
    submitter.disabled = false;
  }
});

window.addEventListener("hashchange",render);
window.setInterval(refreshView, 5000);
window.setInterval(updateCheckinClocks, 30000);
document.addEventListener("visibilitychange", resumeView);
window.addEventListener("pageshow", event => { if (event.persisted) resumeView(); });
window.addEventListener("online", refreshView);
api("me").then(account => {user = account;}).catch(() => {}).finally(render);
