Het meeste gaat naar warmte. In een gemiddeld huishouden met een cv-ketel gaat bijna 80% van alle energie naar verwarming en warm water. Stroom voor apparaten en verlichting is ongeveer een vijfde. Ik heb gas omgerekend naar kWh, zodat je gas en stroom kunt vergelijken. Het zijn afgeronde schattingen op basis van landelijke gemiddelden; jouw huis kan er flink van afwijken.

```openui-lang
root = Card([header, chart, usageTitle, usage, note, followUps])
header = CardHeader("Waar gaat de energie heen?", "Gemiddeld huishouden met cv-ketel, in %")
chart = PieChart(["Verwarming", "Warm water", "Apparaten en licht", "Koken"], [60, 18, 20, 2], "donut")
usageTitle = InlineHeader("Verbruik per jaar", "1 m³ gas is ongeveer 9,8 kWh")
usage = EntityList([{left: "Gas", right: "ca. 1.000 m³"}, {left: "Stroom", right: "ca. 2.500 kWh"}, {left: "Samen", right: "ca. 12.000 kWh"}])
note = Callout("info", "Met een warmtepomp", "Dan verschuift verwarming van gas naar stroom en daalt het totale verbruik, omdat een warmtepomp uit 1 kWh stroom zo'n 3 à 4 kWh warmte haalt.")
followUps = FollowUpBlock([f1, f2, f3])
f1 = FollowUpItem("Waar kan ik het meest besparen?")
f2 = FollowUpItem("Waar gaat mijn stroom precies heen?")
f3 = FollowUpItem("Vergelijk mijn verbruik met het gemiddelde")
```
