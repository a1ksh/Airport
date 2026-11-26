"use strict";

// Stops and travel times belong to the airport's fictional teaching map.
const routeStops = {
  city:    {name:"Қала орталығы", short:"Орталық", x:66, y:132},
  station: {name:"Теміржол вокзалы", short:"Вокзал", x:112, y:54},
  bus:     {name:"Қалааралық автовокзал", short:"Автовокзал", x:57, y:224},
  village: {name:"Ақбұлақ кенті", short:"Ақбұлақ", x:80, y:296},
  bridge:  {name:"Өзен көпірі", short:"Көпір", x:175, y:156},
  shuttle: {name:"Әуежай аялдамасы", short:"Аялдама", x:249, y:101},
  parking: {name:"Әуежай тұрағы", short:"Тұрақ", x:260, y:223},
  T1:      {name:"T1 · Халықаралық терминал", short:"T1", x:346, y:137},
  T2:      {name:"T2 · Ішкі рейстер терминалы", short:"T2", x:352, y:231}
};

// Distance in km, followed by minutes on foot, by shuttle and by car.
const routeRoads = [
  {a:"city", b:"station", bend:"66,94", km:2.2, walk:27, bus:8, car:5},
  {a:"city", b:"bus", bend:"57,152", km:2.6, walk:32, bus:9, car:6},
  {a:"bus", b:"village", bend:"57,271", km:7.2, walk:87, bus:15, car:10},
  {a:"city", b:"bridge", bend:"122,132 146,156", km:3.4, walk:41, bus:9, car:6},
  {a:"station", b:"shuttle", bend:"203,54", km:6.1, walk:74, bus:13, car:9},
  {a:"bus", b:"bridge", bend:"107,224", km:3.2, walk:39, bus:8, car:5},
  {a:"bridge", b:"shuttle", bend:"194,156", km:3.0, walk:36, bus:7, car:5},
  {a:"bridge", b:"parking", bend:"216,197 234,197", km:4.0, walk:48, car:6},
  {a:"shuttle", b:"T1", bend:"307,101", km:1.6, walk:19, bus:5, car:4},
  {a:"parking", b:"T2", bend:"286,249 334,249", km:.5, walk:6},
  {a:"T1", b:"T2", bend:"346,206", km:.4, walk:5}
];
const routeModes = {bus:"Автобуспен", car:"Көлікпен", walk:"Жаяу"};

function roadTravel(road, mode) {
  if (road[mode]) return {mode, minutes:road[mode]};
  if (road.bus || road.car) return null;
  return {mode:"walk", minutes:road.walk}; // The terminal and parking links are pedestrian.
}

function findRoute(origin, destination, mode) {
  const remaining = new Set(Object.keys(routeStops)), costs = {[origin]:0}, previous = {};
  while (remaining.size) {
    const next = [...remaining].reduce((best,id) => (costs[id] ?? Infinity) < (costs[best] ?? Infinity) ? id : best);
    if (costs[next] === undefined || next === destination) break;
    remaining.delete(next);
    for (const road of routeRoads) {
      const to = road.a === next ? road.b : road.b === next ? road.a : null;
      if (!remaining.has(to)) continue;
      const travel = roadTravel(road,mode);
      if (!travel) continue;
      const cost = costs[next] + travel.minutes;
      if (cost < (costs[to] ?? Infinity)) {
        costs[to] = cost;
        previous[to] = {from:next,to,road,...travel};
      }
    }
  }
  const steps = [];
  for (let id = destination; previous[id]; id = previous[id].from) steps.unshift(previous[id]);
  return {steps, minutes:costs[destination], km:steps.reduce((sum,step) => sum + step.road.km,0)};
}

function roadPoints(road) {
  const a = routeStops[road.a], b = routeStops[road.b];
  return a.x + "," + a.y + " " + road.bend + " " + b.x + "," + b.y;
}

function routeMap(origin, destination, itinerary) {
  const backdrop = '<rect width="418" height="338" fill="#f5f4ed"/>'
    + '<g fill="#e8ebdf"><rect x="15" y="34" width="65" height="58" rx="9"/><rect x="118" y="77" width="38" height="32" rx="6"/>'
    + '<rect x="10" y="154" width="28" height="43" rx="6"/><rect x="101" y="177" width="46" height="25" rx="6"/>'
    + '<rect x="180" y="231" width="53" height="55" rx="9"/><rect x="273" y="36" width="41" height="30" rx="6"/></g>'
    + '<path d="M163 -14 C119 49 229 73 185 115 S128 167 158 216 S155 287 122 354" fill="none" stroke="#c4dde2" stroke-width="16"/>'
    + '<path d="M13 280 L36 255 L47 282 L32 301 Z M211 10 L233 5 L250 25 L224 36 Z" fill="#d6e6d5"/>'
    + '<rect x="305" y="76" width="92" height="197" rx="20" fill="#e4ece9" stroke="#d4dfdc"/>'
    + '<path d="M382 99 V253" stroke="#c2cecb" stroke-width="9" stroke-linecap="round"/>'
    + '<path d="M382 101 V251" stroke="white" stroke-width="1.4" stroke-dasharray="8 6"/>';
  const roads = routeRoads.map(r => '<polyline class="map-road" points="' + roadPoints(r) + '"/>').join("");
  const segments = itinerary.steps.map(s => '<polyline class="map-path' + (s.mode === "walk" ? ' foot' : '')
    + '" points="' + roadPoints(s.road) + '"/>').join("");
  const stops = Object.entries(routeStops).map(([key,p]) => {
    const mark = key === origin ? "А" : key === destination ? "Б" : "";
    const y = p.y + (["station","village"].includes(key) ? -17 : 26);
    return '<g class="map-stop" role="button" tabindex="0" data-stop="' + key + '" aria-label="' + p.name + '">'
      + '<circle class="map-target" cx="' + p.x + '" cy="' + p.y + '" r="19"/>'
      + '<circle class="map-dot' + (mark === "А" ? ' from' : mark === "Б" ? ' to' : '') + '" cx="' + p.x + '" cy="' + p.y
      + '" r="' + (mark ? 11 : 5) + '"/>' + (mark ? '<text class="map-letter" x="' + p.x + '" y="' + (p.y+4) + '">' + mark + '</text>' : '')
      + '<text class="map-label" x="' + p.x + '" y="' + y + '">' + p.short + '</text></g>';
  }).join("");
  return '<svg viewBox="0 0 418 338" class="route-map" role="group" aria-label="Әуежай мен қала арасындағы оқу картасы">'
    + backdrop + roads + segments + stops + '</svg>';
}

function updateRoute(root, form, onChange) {
  const {origin,destination,mode,pick} = Object.fromEntries(new FormData(form));
  const itinerary = findRoute(origin,destination,mode);
  const map = root.querySelector("#map-canvas");
  map.innerHTML = routeMap(origin,destination,itinerary);
  root.querySelector("#map-pick-hint").textContent = pick === "origin" ? "Картадан бастапқы нүктені таңдаңыз." : "Картадан баратын нүктені таңдаңыз.";
  onChange({origin,destination,mode});
  const result = root.querySelector("#map-result");
  if (origin === destination) {
    result.innerHTML = '<p class="sub">Бағытты көру үшін екі түрлі нүктені таңдаңыз.</p>';
    return;
  }
  const km = new Intl.NumberFormat("kk-KZ",{maximumFractionDigits:1}).format(itinerary.km);
  result.innerHTML = '<div class="row space wrap"><h2>' + itinerary.minutes + " мин" + '</h2><span class="route-distance">' + km + ' км</span></div>'
    + '<p class="sub">' + routeStops[origin].name + ' → ' + routeStops[destination].name + '</p>'
    + '<ol class="route-steps">' + itinerary.steps.map(s => '<li><div><b>' + routeStops[s.to].name
      + '</b><small>' + routeModes[s.mode] + ' · ' + s.minutes + ' мин</small></div></li>').join("") + '</ol>';
}

function routeSelect(label, name, choices, current) {
  // Labels and option values come exclusively from this map's fixed stop/mode lists.
  return '<label class="field">' + label + '<select name="' + name + '">' + choices.map(([key,text]) =>
    '<option value="' + key + '"' + (key === current ? ' selected' : '') + '>' + text + '</option>').join("") + '</select></label>';
}

function mountRouteMap(root, params, onChange = () => {}) {
  const valid = (value, options, fallback) => Object.hasOwn(options,value) ? value : fallback;
  const origin = valid(params.get("origin"),routeStops,"city"), destination = valid(params.get("destination"),routeStops,"T1");
  const mode = valid(params.get("mode"),routeModes,["T1","T2"].includes(origin) ? "walk" : "bus");
  const stops = Object.entries(routeStops).map(([key,p]) => [key,p.name]);
  root.innerHTML = '<div class="wayfinding"><section class="card map-card"><div class="map-heading"><h2>Қала және әуежай</h2><span>Оқу картасы</span></div>'
    + '<div id="map-canvas"></div><p class="meta map-hint" id="map-pick-hint"></p></section>'
    + '<div class="stack"><form class="card fields" data-route-form><div class="map-fields">'
    + routeSelect("А · Қайдан","origin",stops,origin) + '<button type="button" class="btn map-swap" aria-label="Нүктелердің орнын ауыстыру">⇅</button>'
    + routeSelect("Б · Қайда","destination",stops,destination) + '</div>'
    + '<div class="map-settings">' + routeSelect("Қалай барасыз?","mode",Object.entries(routeModes),mode)
    + routeSelect("Картадан таңдау","pick",[["origin","А · Қайдан"],["destination","Б · Қайда"]],"origin") + '</div></form>'
    + '<section class="card map-itinerary" aria-live="polite" id="map-result"></section></div></div>';
  const form = root.querySelector("[data-route-form]");
  const redraw = () => updateRoute(root,form,onChange);
  form.addEventListener("change",redraw);
  form.addEventListener("submit",event => { event.preventDefault(); redraw(); });
  form.querySelector(".map-swap").addEventListener("click",() => {
    const {origin,destination} = form.elements;
    [origin.value,destination.value] = [destination.value,origin.value];
    redraw();
  });
  const chooseStop = (event) => {
    if (event.type === "keydown" && !["Enter"," "].includes(event.key)) return;
    const stop = event.target.closest("[data-stop]");
    if (!stop) return;
    event.preventDefault();
    const {pick} = form.elements, id = stop.dataset.stop;
    form.elements[pick.value].value = id;
    pick.value = pick.value === "origin" ? "destination" : "origin";
    redraw();
    if (event.type === "keydown") root.querySelector('#map-canvas [data-stop="' + id + '"]').focus();
  };
  root.querySelector("#map-canvas").addEventListener("click",chooseStop);
  root.querySelector("#map-canvas").addEventListener("keydown",chooseStop);
  redraw();
}

const localRoute = document.getElementById("local-route");
if (localRoute) mountRouteMap(localRoute,new URLSearchParams(location.search));
