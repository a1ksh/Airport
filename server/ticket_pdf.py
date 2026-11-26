"""One self-contained passenger ticket per flight, including its check-in QR."""
from datetime import datetime
from io import BytesIO
from xml.sax.saxutils import escape

from reportlab.graphics.barcode import createBarcodeDrawing
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas
from reportlab.platypus import Paragraph, Table, TableStyle

import airport_data as data

FONT = "TicketNotoSans"
pdfmetrics.registerFont(TTFont(FONT, str(data.ROOT / "fonts" / "NotoSans-Regular.ttf")))
NAVY = colors.HexColor("#132f52")
BLUE = colors.HexColor("#1755a7")
MUTED = colors.HexColor("#4d6175")
LINE = colors.HexColor("#ccd9e6")
WIDTH, MARGIN = 360, 20


def text(value, size=13, color=NAVY):
    style = ParagraphStyle("ticket", fontName=FONT, fontSize=size, leading=size * 1.35, textColor=color)
    return Paragraph(escape(" ".join(str(value or "—").split())), style)


def field(label, value):
    return [text(label, 9, MUTED), text(value)]


def local_time(ms):
    return datetime.fromtimestamp(ms / 1000, data.ASTANA).strftime("%d.%m.%Y · %H:%M")


def ticket_pdf(trip, passenger):
    def endpoint(label, code, city, moment):
        lines = [text(label, 9, MUTED)]
        if code != "—":
            lines.append(text(code, 29, BLUE))
        return [*lines, text(city, 14), text(local_time(moment), 10)]

    rows = [
        [endpoint("Ұшу", trip["origin_code"], trip["origin"], trip["departure"]),
         endpoint("Қону", trip["destination_code"], trip["destination"], trip["arrival"])],
        [field("Рейс", trip["number"]), field("Әуе компаниясы", trip["airline"])],
        [field("Жолаушы", passenger), field("Билет тарифі", trip["fare_name"])],
        [field("Қақпа", trip["gate"]), field("Орын", trip["seat"])],
        [field("Тіркеу ашылады", local_time(trip["checkin_opens"])),
         field("Тіркеу жабылады", local_time(trip["checkin_closes"]))],
    ]
    if trip["terminal"]:
        rows.append([field("Астанадағы терминал", trip["terminal"]), ""])
    table = Table(rows, colWidths=[(WIDTH - 2 * MARGIN) / 2] * 2, style=TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10), ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 9), ("LINEBELOW", (0, 0), (-1, -2), 0.45, LINE),
    ]))
    _, table_height = table.wrap(WIDTH - 2 * MARGIN, 2000)
    header, footer = 74, 170
    height = header + table_height + footer
    buffer = BytesIO()
    pdf = Canvas(buffer, pagesize=(WIDTH, height))
    pdf.setTitle("Airport · " + trip["code"])
    pdf.setAuthor("Airport")
    pdf.setFillColor(NAVY)
    pdf.rect(0, height - header, WIDTH, header, stroke=0, fill=1)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 25)
    pdf.drawString(MARGIN, height - 35, "Airport")
    pdf.setFont(FONT, 10)
    pdf.drawRightString(WIDTH - MARGIN, height - 32, "ДЕМО БИЛЕТ")
    pdf.drawString(MARGIN, height - 57, "Астана уақыты (UTC+5)")
    table.drawOn(pdf, MARGIN, footer)
    pdf.setStrokeColor(LINE)
    pdf.setDash(3, 3)
    pdf.line(MARGIN, footer, WIDTH - MARGIN, footer)
    qr = createBarcodeDrawing("QR", value=trip["code"], width=105, height=105, barBorder=4)
    qr.drawOn(pdf, (WIDTH - qr.width) / 2, 56)
    pdf.setFillColor(NAVY)
    pdf.setFont(FONT, 14)
    pdf.drawCentredString(WIDTH / 2, 41, trip["code"])
    pdf.setFillColor(MUTED)
    pdf.setFont(FONT, 8.5)
    pdf.drawCentredString(WIDTH / 2, 19, "Билет сақталған уақыт · " + local_time(data.now_ms()))
    pdf.showPage()
    pdf.save()
    return buffer.getvalue()
