Albert Heijn is met afstand de grootste, met ongeveer 37% van de markt. Dat is meer dan Jumbo en Lidl samen. Jumbo staat op twee met ruim een vijfde, Lidl op drie met ongeveer een negende. De cijfers zijn afgerond en bij benadering.

```openui-lang
root = Card([header, table, shareTitle, shareChart, note, followUps])
header = CardHeader("Grootste supermarkten", "Nederland, afgerond, rond 2024")
table = Table([Col("Keten", chains), Col("Aandeel", shares, "number"), Col("Winkels", stores, "number")])
chains = ["Albert Heijn", "Jumbo", "Lidl"]
shares = ["37%", "21%", "11%"]
stores = ["1.100", "700", "440"]
shareTitle = InlineHeader("Marktaandeel", "Aandeel van de totale supermarktomzet")
shareChart = SingleStackedBarChart(["Albert Heijn", "Jumbo", "Lidl", "Overige"], [37, 21, 11, 31])
note = Callout("info", "Bij benadering", "Marktaandelen en winkelaantallen veranderen elk jaar iets. Winkels tellen alleen in Nederland, inclusief franchisewinkels.")
followUps = FollowUpBlock([f1, f2, f3])
f1 = FollowUpItem("Waar staan Aldi, Plus en Dirk?")
f2 = FollowUpItem("Hoe is het marktaandeel de laatste jaren veranderd?")
f3 = FollowUpItem("Welke keten is het goedkoopst?")
```
