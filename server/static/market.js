"use strict";

const tenge = new Intl.NumberFormat("kk-KZ", {maximumFractionDigits:0});
const money = value => tenge.format(value) + " ₸";
const hm = ms => mobileDate(ms).slice(11);
const duration = minutes => [Math.floor(minutes / 60) ? Math.floor(minutes / 60) + " сағ" : "",
  minutes % 60 ? minutes % 60 + " мин" : ""].filter(Boolean).join(" ");
const airlineOptions = catalog => catalog.airlines.map(a => [a.id, a.name + " · " + a.code]);
const terminalName = id => id === "T1" ? "T1 · Халықаралық терминал" : "T2 · Ішкі рейстер терминалы";

function timing(flight) {
  return '<div class="leg-times"><span><small>Ұшу</small><b>' + stamp(flight.departure) + '</b></span>'
    + '<span class="journey-line">' + ice("plane") + '<small>' + duration(flight.duration_min) + '</small></span>'
    + '<span><small>Қону</small><b>' + stamp(flight.arrival) + '</b></span></div>';
}

function flightInfo(flight) {
  return '<p class="caption">' + esc(flight.airline) + ' · ' + esc(flight.number) + '</p>'
    + flightRoute(flight) + timing(flight)
    + '<p class="meta">Тіркеу ' + stamp(flight.checkin_opens) + ' – ' + stamp(flight.checkin_closes)
    + ' · Қақпа ' + esc(flight.gate) + '</p>'
    + (flight.terminal ? '<p class="meta">Астана · ' + terminalName(flight.terminal) + '</p>' : "");
}

function offerCard(offer, back) {
  const first = offer.legs[0], last = offer.legs.at(-1);
  const transfer = offer.legs.length === 2;
  const company = [...new Set(offer.legs.map(f => f.airline))].map(esc).join(" + ");
  const checkout = href("checkout", {legs:offer.legs.map(f => f.id).join(","), back});
  return '<article class="card offer"><div class="row space wrap"><strong>' + company + '</strong><span class="badge '
    + (transfer ? "" : "ok") + '">' + (transfer ? "1 ауысу" : "Тікелей") + '</span></div>'
    + '<div class="journey-times"><div><b>' + hm(first.departure) + '</b><small>' + esc(first.origin) + '</small></div>'
    + '<div class="journey-line"><small>' + duration(offer.minutes) + '</small><i></i></div><div><b>' + hm(last.arrival)
    + '</b><small>' + esc(last.destination) + (mobileDate(last.arrival,true) !== mobileDate(first.departure,true) ? ' · ' + stamp(last.arrival).slice(0,5) : '') + '</small></div></div>'
    + '<p class="meta">' + (transfer ? esc(first.destination) + ' · ауысуға ' + duration((last.departure-first.arrival)/60000)
      + ' · ' : '') + offer.legs.map(f => esc(f.number)).join(" / ") + '</p>'
    + '<div class="offer-foot"><div><small>Бастапқы баға</small><strong>' + money(offer.price) + '</strong></div>'
    + link(checkout,"Таңдау","primary") + '</div></article>';
}

async function finderPage(params, version) {
  const origin = params.get("origin") || "Астана", destination = params.get("destination") || "Ыстанбұл";
  const day = params.get("day") || mobileDate(Date.now()+86400000,true);
  const direct = params.get("direct") === "1", sort = params.get("sort") || "price";
  const query = {origin,destination,day,sort,direct:direct ? "1" : "0"};
  const [catalog, offers] = await Promise.all([api("catalog"), api(href("offers",query))]);
  if (version !== routeVersion) return;
  const cities = catalog.cities.map(c => [c.name, c.name]);
  page(title("Әуе билеттері") + '<div class="market-layout"><form class="card fields finder-form" data-form="find-tickets">'
    + '<h2>Қайда ұшасыз?</h2><div class="route-inputs">' + selectField("Қайдан","origin",cities,origin)
    + button("swap-cities","⇄","","swap") + selectField("Қайда","destination",cities,destination) + '</div>'
    + formField("Ұшатын күн","day",'type="date" required min="' + mobileDate(Date.now(),true) + '" value="' + esc(day) + '"')
    + selectField("Сұрыптау","sort",[["price","Ең арзан"],["duration","Ең жылдам"],["departure","Ең ерте"]],sort)
    + '<label class="check"><input type="checkbox" name="direct" value="1"' + (direct ? ' checked' : '') + '>Тек тікелей рейстер</label>'
    + submit("Билет іздеу") + link("flights","Рейстер кестесі","text") + '</form><section class="stack offer-list">'
    + '<div class="sectionbar"><h2>' + esc(origin) + ' → ' + esc(destination) + '</h2><span class="badge">' + offers.length + ' нұсқа</span></div>'
    + '<p class="meta">' + esc(day.split("-").reverse().join(".")) + ' · Астана уақыты (UTC+5) · Демо кесте</p>'
    + (offers.length ? offers.map(offer => offerCard(offer,href("search",query))).join("")
      : blank("Бұл күнге билет табылмады. Күнді немесе бағытты өзгертіп көріңіз.")) + '</section></div>',"home","market-page");
  $('[data-action="swap-cities"]').setAttribute("aria-label","Қалалардың орнын ауыстыру");
}

let checkoutState;
function requestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, n => n.toString(16).padStart(2,"0")).join("");
  return [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join("-");
}

function seatPicker(flight, seats) {
  const occupied = new Set(flight.occupied);
  return '<fieldset class="card stack cabin"><legend>' + esc(flight.number) + ' · Орын таңдау</legend><p class="meta">'
    + esc(flight.origin) + ' → ' + esc(flight.destination) + '</p><div class="seats">'
    + seats.map((label,i) => (i%6 === 3 ? '<span></span>' : '') + '<label class="seat-option"><input type="radio" name="seat-'
      + flight.id + '" value="' + label + '" required' + (occupied.has(label) ? ' disabled' : '') + '><span>' + label + '</span></label>').join("")
    + '</div><p class="meta">' + (occupied.size === seats.length ? "Бос орын қалмады." : "Сұр түспен бос емес орындар көрсетілген.") + '</p></fieldset>';
}

async function checkoutPage(ids, back, version) {
  const quote = await api(href("quote",{flights:ids}));
  if (version !== routeVersion) return;
  const safeBack = back?.startsWith("search?") ? back : "flight/" + quote.legs[0].id;
  checkoutState = {quote, request:requestId()};
  const fares = quote.fares.map((f,i) => '<label class="fare-choice"><input name="fare_id" type="radio" value="' + f.id
    + '"' + (i === 0 ? ' checked' : '') + '><span><strong>' + esc(f.name) + '</strong><small>' + esc(f.baggage)
    + ' · Қайтарым ' + f.refund_pct + '%</small></span><b>' + money(f.total) + '</b></label>').join("");
  page(title("Билетті рәсімдеу",safeBack) + '<form class="stack" data-form="checkout"><section class="card stack">'
    + quote.legs.map(f => '<div class="stack">' + flightInfo(f) + '</div>').join('<hr class="rule">')
    + '<p class="meta">Астана уақыты (UTC+5)</p></section><fieldset class="card stack"><legend>Билет тарифі</legend>'
    + fares + '</fieldset>' + quote.legs.map(f => seatPicker(f, quote.seats)).join("")
    + '<section class="card fields"><h2>Демо әмиянмен төлеу</h2><div class="receipt-lines" id="payment-summary"></div>'
    + '<p class="meta">Оқу төлемі. Банк картасы қажет емес.</p>' + submit("Төлеу")
    + button("refresh","Орындар мен бағаны жаңарту","","text") + '</section></form>',"trips");
  updatePrice();
}

function updatePrice() {
  const quote = checkoutState.quote;
  const fare = quote.fares.find(f => f.id === Number($('[name="fare_id"]:checked').value));
  $("#payment-summary").innerHTML = receiptLine("Тариф бағасы",money(fare.subtotal))
    + receiptLine("Жолаушы жеңілдігі · " + quote.loyalty.percent + "%","−" + money(fare.subtotal-fare.total))
    + receiptLine("Демо әмияндағы қалдық",money(quote.balance)) + receiptLine("Төлем сомасы",money(fare.total));
  $('[data-form="checkout"] [type="submit"]').textContent = money(fare.total) + " төлеу";
}

function receiptLine(label, value) {
  return '<div><span>' + esc(label) + '</span><b>' + esc(value) + '</b></div>';
}

async function pay(values) {
  const {quote,request} = checkoutState;
  const fare = quote.fares.find(f => f.id === Number(values.fare_id));
  if (!await confirmAction("Төлемді растау",quote.legs.map(f => f.number).join(" / ") + " · " + fare.name
      + " · " + money(fare.total) + " демо әмияннан алынады.", "Төлеу")) return;
  const result = await api("checkout","POST",{legs:quote.legs.map(f => ({flight:f.id,seat:values["seat-"+f.id]})),
    fare_id:fare.id,expected_total:fare.total,request_id:request});
  showMessage("Төлем қабылданды. Билетіңіз дайын.");
  go("trip/" + result.code);
}

function walletCard(stats) {
  const tier = stats.loyalty;
  return '<section class="card stack"><div class="row space wrap"><h2>Демо әмиян</h2><span class="badge ok">'
    + tier.percent + '% жеңілдік</span></div><strong class="wallet-balance">' + money(stats.balance) + '</strong><p class="meta">'
    + tier.completed + ' расталған ұшу · ' + (tier.next_at ? 'Келесі жеңілдікке дейін ' + (tier.next_at-tier.completed) + ' ұшу.'
      : 'Ең жоғары жеңілдік қолданылады.') + '</p></section>';
}

const navigationPlaces = {
  T1:["T1 · Халықаралық терминал","Astana International Airport Terminal 1, Qabanbay Batyr Ave 119/2, Astana, Kazakhstan"],
  T2:["T2 · Ішкі рейстер терминалы","Astana International Airport Terminal 2, Qabanbay Batyr Ave 119, Astana, Kazakhstan"],
  station:["Нұрлы жол вокзалы","Nurly Zhol Railway Station, Astana, Kazakhstan"],
  city:["Бәйтерек · қала орталығы","Baiterek Tower, Astana, Kazakhstan"],
};

function navigationPage(params) {
  const fields = Object.entries(navigationPlaces).map(([id,p]) => [id,p[0]]);
  const origin = params.get("origin") || "current", dest = params.get("destination") || "T1";
  page(title("Жол бағыты") + '<form class="card fields" data-form="directions"><h2>А нүктесінен Б нүктесіне</h2>'
    + selectField("Қайдан","origin",[["current","Қазіргі орным"],...fields,["custom","Басқа мекенжай"]],origin)
    + formField("Бастапқы мекенжай","origin_text",'maxlength="160" placeholder="Қала, көше немесе нысан"')
    + selectField("Қайда","destination",[...fields,["custom","Басқа қала немесе мекенжай"]],dest)
    + formField("Баратын мекенжай","destination_text",'maxlength="160" placeholder="Қала, көше немесе нысан"')
    + selectField("Қалай барасыз?","travelmode",[["driving","Көлікпен"],["transit","Қоғамдық көлікпен"],["walking","Жаяу"]],origin === "T1" || origin === "T2" ? "walking" : "driving")
    + '<p class="meta" id="terminal-hint"></p>' + submit("Маршрутты көрсету") + '</form><div class="section" id="map-result"></div>',"home");
  navigationInputs();
}

function navigationInputs() {
  for (const name of ["origin","destination"]) {
    const custom = $('[name="'+name+'"]').value === "custom", input = $('[name="'+name+'_text"]');
    input.closest("label").hidden = !custom;
    input.disabled = !custom;
    input.required = custom;
  }
  const dest = $('[name="destination"]').value;
  $("#terminal-hint").textContent = dest === "T1" ? "Тіркеу залы · бірінші қабат." : dest === "T2" ? "Тіркеу залы · екінші қабат." : "";
  $("#map-result").replaceChildren();
}

function mapResult(values) {
  const resolve = (key, custom) => key === "current" ? "" : key === "custom" ? custom.trim() : navigationPlaces[key][1];
  const origin = resolve(values.origin,values.origin_text), destination = resolve(values.destination,values.destination_text);
  if (!destination || (values.origin === "custom" && !origin) || origin.toLowerCase() === destination.toLowerCase()) {
    throw new Error("Екі түрлі нүктені таңдаңыз.");
  }
  const params = new URLSearchParams({api:"1",destination,travelmode:values.travelmode});
  if (origin) params.set("origin",origin);
  const label = key => values[key] === "custom" ? values[key+"_text"] : navigationPlaces[values[key]]?.[0] || "Қазіргі орным";
  $("#map-result").innerHTML = '<section class="card stack"><h3>' + esc(label("origin")) + ' → ' + esc(label("destination"))
    + '</h3>' + external("https://www.google.com/maps/dir/?" + params.toString(),ice("map") + " Google Maps-та ашу ↗") + '</section>';
}

async function livePage(params, version) {
  const q = params.get("q") || "", mode = params.get("mode") || "registration";
  const airport = params.get("airport") || "NQZ";
  const airports = [["NQZ","Астана · NQZ"],["ALA","Алматы · ALA"],["IST","Ыстанбұл · IST"],["LHR","Лондон · LHR"]];
  page(title("Қазір әуеде") + '<div class="stack"><form class="card fields" data-form="live-search">'
    + selectField("Нөмір енгізілмесе, осы әуежай маңын көрсету","airport",airports,airport)
    + selectField("Іздеу түрі","mode",[["registration","Борттың тіркеу нөмірі"],["flight","Рейс нөмірі немесе шақыру белгісі"]],mode)
    + formField("Нөмір","q",'type="search" maxlength="16" placeholder="' + (mode === "flight" ? "KC123 немесе KZR123" : "Мысалы, P4-KCA") + '" value="' + esc(q) + '"')
    + submit("Іздеу") + '</form><section class="stack" id="live-results"><p class="sub">Деректер жүктелуде…</p></section>'
    + external(official,"Астана әуежайының ресми таблосы ↗") + '</div>',"home");
  let result;
  try {
    result = await api(href("live",{q,mode,airport}));
  } catch (error) {
    if (version === routeVersion && !sessionExpired(error)) $("#live-results").innerHTML = blank(error.message) + button("refresh","Қайталау");
    return;
  }
  if (version !== routeVersion) return;
  $("#live-results").innerHTML = '<div class="row space wrap"><h2>' + (q ? esc(q) : esc(airport) + " маңындағы борттар") + '</h2>'
    + '<span class="badge">' + result.aircraft.length + ' борт</span></div><p class="meta">adsb.lol · ' + stamp(result.observed)
    + ' (UTC+5) · <a href="https://www.adsb.lol/docs/open-data/api" target="_blank" rel="noopener">ODbL</a></p>'
    + (result.aircraft.map(a => '<article class="card row space"><div><h3>' + esc(a.callsign) + '</h3><p class="meta">'
      + esc(a.registration || "Нөмірі белгісіз") + (a.type ? ' · ' + esc(a.type) : '') + '</p></div><span class="badge ok">Әуеде</span></article>').join("")
      || blank("Қазір осы іздеу бойынша әуедегі борттан сигнал жоқ.")) + button("refresh","Жаңарту");
}

function salesAdmin(params, version) {
  const before = params.get("before");
  return watchView(version, [before ? href("admin/sales",{before}) : "admin/sales","catalog"], ([sales,catalog]) => {
    const companies = sales.companies.map(a => '<article class="card"><div class="row space"><h3>' + esc(a.name)
      + '</h3><span class="badge">' + esc(a.code) + '</span></div><p class="meta">' + a.operators + ' оператор · '
      + a.flights + ' рейс · ' + a.tickets + ' қайтарылмаған билет</p></article>').join("");
    const fares = catalog.fares.map(f => '<article class="card"><div class="row space wrap"><h3>' + esc(f.name)
      + '</h3><span class="badge">+' + f.markup_pct + '%</span></div><p class="meta">' + esc(f.baggage)
      + ' · Қайтарым ' + f.refund_pct + '%</p></article>').join("");
    const payments = sales.payments.map(o => '<article class="card"><div class="row space wrap"><strong>' + esc(o.name)
      + '</strong><b>' + money(o.total) + '</b></div><p class="meta">' + esc(o.code) + ' · ' + esc(o.fare_name) + ' · ' + stamp(o.created)
      + '</p><span class="badge ' + (o.cancelled_at ? 'cancelled' : 'ok') + '">' + (o.cancelled_at ? 'Қайтарылды · ' + money(o.refunded) : 'Төленді')
      + '</span><details><summary>Рейстер мен орындар</summary>' + o.legs.map(l => '<p class="meta">' + esc(l.airline) + ' · ' + esc(l.number)
        + ' · ' + esc(l.origin) + ' → ' + esc(l.destination) + ' · ' + esc(l.seat) + (l.cancelled ? ' · Қайтарылды' : '') + '</p>').join("") + '</details></article>').join("");
    page(title("Компаниялар және сатылымдар") + '<div class="stack"><div class="sectionbar"><h2>Әуе компаниялары</h2>'
      + link("airline-new","＋ Компания") + '</div>' + companies + link("operator-new","Оператор қосу")
      + '<div class="sectionbar"><h2>Билет тарифтері</h2>' + link("fare-new","＋ Тариф") + '</div>' + fares
      + '<h2 class="section">Төлемдер</h2>' + (before ? link("sales-admin","Соңғы төлемдерге оралу") : "")
      + (payments || blank("Әзірге төлем жоқ.")) + (sales.next_before ? link(href("sales-admin",{before:sales.next_before}),"Бұрынғы төлемдер") : "") + '</div>',"home");
  });
}

function airlineEditor() {
  page(title("Әуе компаниясын қосу","sales-admin") + '<form class="card fields" data-form="airline-new">'
    + formField("Компания атауы","name",'required maxlength="60"') + formField("Компания коды","code",'required minlength="2" maxlength="3" pattern="[A-Za-z0-9]{2,3}"')
    + '<h2>Компанияның алғашқы операторы</h2>' + formField("Аты-жөні","operator_name",'required maxlength="60"')
    + formField("Email","email",'type="email" required maxlength="90"') + formField("Бастапқы құпиясөз","password",'type="password" autocomplete="new-password" minlength="8" maxlength="80" required')
    + submit("Компанияны қосу") + '</form>',"home");
}

function fareEditor() {
  page(title("Билет тарифін қосу","sales-admin") + '<form class="card fields" data-form="fare-new">'
    + formField("Тариф атауы","name",'required maxlength="50"') + formField("Багаж шарты","baggage",'required maxlength="100"')
    + formField("Негізгі бағаға үстеме, %","markup_pct",'type="number" min="0" max="500" value="0" required')
    + formField("Қайтарылатын үлес, %","refund_pct",'type="number" min="0" max="100" value="100" required')
    + submit("Тарифті қосу") + '</form>',"home");
}
