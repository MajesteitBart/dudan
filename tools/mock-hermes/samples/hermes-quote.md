De offerte komt uit op € 4.385,00 exclusief btw. De duurste post is de workshop AI-agents: € 2.900,00 voor twee dagdelen.

Ik heb de kolom ‘Prijs’ als prijs per eenheid geïnterpreteerd.

```openui-lang
root = Card([header, items, totals, note])
header = CardHeader("Offerte Studio Noord", "2026-117 · bvdm.ai")
items = Table([Col("Post", ["Workshop AI-agents", "Voorbereiding en intake", "Maatwerk prompts en skills", "Reiskosten"]), Col("Aantal", ["2 dagdelen", "1", "6 uur", "1"]), Col("Per stuk", ["€ 1.450,00", "€ 650,00", "€ 125,00", "€ 85,00"]), Col("Totaal", ["€ 2.900,00", "€ 650,00", "€ 750,00", "€ 85,00"])])
totals = EntityList([{left: "Totaal excl. btw", right: "€ 4.385,00"}, {left: "Btw bij 21%", right: "€ 920,85"}, {left: "Totaal bij 21% btw", right: "€ 5.305,85"}])
note = TextContent("Alle posten zijn exclusief btw. Het btw-tarief staat niet in de offerte; het bedrag inclusief btw is berekend onder aanname van 21%. Geldig tot 31 oktober 2026.", "small")
```