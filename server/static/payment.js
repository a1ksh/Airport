"use strict";

function paymentOptions(balance) {
  const notes = {wallet:"Қалдық · " + money(balance), card:"Qanat Bank · оқу картасы •• 4242", qr:"Демо банкте растау"};
  return Object.entries(paymentMethods).map(([key,label]) => '<label class="funding-choice"><input type="radio" name="method" value="'
    + key + '"' + (key === "wallet" ? " checked" : "") + '><span><strong>' + label + '</strong><small>' + esc(notes[key])
    + '</small></span></label>').join("");
}

function paymentPage(ref, version) {
  return watchView(version, "payments/" + enc(ref), p => {
    const retry = href("checkout",{legs:p.legs.map(l => l.id).join(",")});
    if (p.state === "paid") return go("trip/" + enc(p.code));
    if (p.state !== "pending") {
      page(title("Төлем","trips") + '<section class="card stack"><h2>'
        + (p.state === "expired" ? "Төлем уақыты аяқталды" : "Төлем тоқтатылды")
        + '</h2><p>Қаражат алынған жоқ.</p>' + link(retry,"Қайта рәсімдеу","primary") + '</section>',"trips");
      return;
    }
    const legs = p.legs.map(l => '<p class="meta">' + esc(l.number) + ' · ' + esc(l.origin) + ' → ' + esc(l.destination)
      + ' · Орын ' + esc(l.seat) + '</p>').join("");
    let approval = "", action = "Әмияннан төлеу";
    if (p.method === "card") {
      approval = '<div class="demo-bank-card"><span>Qanat Bank</span><strong>•••• &nbsp; •••• &nbsp; •••• &nbsp; 4242</strong><small>Оқу картасы</small></div>'
        + '<div class="demo-sms"><span>Демо хабарламадағы код</span><b>' + esc(p.demo_code) + '</b></div>'
        + formField("Растау коды","approval_code",'inputmode="numeric" pattern="[0-9]{4}" maxlength="4" autocomplete="off" placeholder="0000" required');
      action = "Кодпен растау";
    } else if (p.method === "qr") {
      approval = '<div class="qr-approval"><img class="qr" src="/api/payments/' + enc(ref) + '/qr.svg" alt="Оқу төлемінің QR-коды">'
        + '<p class="meta">QR-код осы тапсырысқа арналған. Демо банк атынан төлемді төменде растаңыз.</p></div>';
      action = "QR-төлемді растау";
    } else {
      approval = '<div class="receipt-lines">' + receiptLine("Әмияндағы қалдық",money(p.balance))
        + receiptLine("Төлемнен кейін",money(p.balance-p.amount)) + '</div>';
    }
    page(title("Төлемді растау",retry) + '<div class="stack"><section class="card payment-slip"><div class="row space wrap">'
      + '<h2>Airport</h2><span class="badge">Оқу төлемі</span></div><strong class="payment-amount">' + money(p.amount) + '</strong>'
      + legs + '<hr class="rule"><p class="meta">' + esc(p.ref) + ' · ' + hm(p.expires) + ' дейін (UTC+5)</p></section>'
      + '<form class="card fields" data-form="approve-payment" data-key="' + esc(ref) + '"><h2>' + paymentMethods[p.method] + '</h2>'
      + approval + submit(action) + button("cancel-payment","Төлемнен бас тарту",ref,"text") + '</form></div>',"trips");
  });
}

function checkinClock(flight) {
  if (flight.status === 3 || flight.cancelled || flight.completed) return "";
  return '<p class="checkin-clock" data-opens="' + flight.checkin_opens + '" data-closes="' + flight.checkin_closes
    + '" data-departure="' + flight.departure + '" data-arrival="' + flight.arrival + '"></p>';
}

function updateCheckinClocks() {
  const now = Date.now();
  for (const node of app.querySelectorAll(".checkin-clock")) {
    const {opens,closes,departure,arrival} = node.dataset;
    const left = until => {
      const minutes = Math.max(1, Math.ceil((Number(until) - now)/60000));
      return minutes >= 1440 ? Math.floor(minutes/1440) + " күн " + Math.floor(minutes%1440/60) + " сағ" : duration(minutes);
    };
    const label = now < opens ? "Тіркеудің ашылуына " + left(opens) + " қалды"
      : now < closes ? "Тіркеу ашық · жабылуына " + left(closes) + " қалды"
      : now < departure ? "Тіркеу жабылды · ұшуға " + left(departure) + " қалды"
      : now < arrival ? "Кесте бойынша әуеде · қонуға " + left(arrival) + " қалды" : "Кесте бойынша қону уақыты өтті";
    if (node.textContent !== label) node.textContent = label;
  }
}
